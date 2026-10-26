package dev.lndf.logonmc.mojang;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.util.Uuids;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Name to premium-profile lookups, cached (including misses) per name.
 *
 * <p>Requests go to the Mojang API from each configured local address in rotation, so the
 * per-IP rate limit applies to each address separately. When every address is rate limited or
 * failing, the configured proxy URLs are tried in order. An endpoint that was rate limited or
 * unreachable is skipped until its backoff ends instead of piling up requests.
 */
public final class MojangClient implements ProfileLookup {

    static final String MOJANG_URL = "https://api.mojang.com/users/profiles/minecraft/{name}";
    private static final Duration BACKOFF = Duration.ofSeconds(30);

    /** One place to send a lookup: a URL template and the client (local address) to send it with. */
    private static final class Endpoint {
        final String urlTemplate;
        final String description;
        final HttpClient http;
        volatile Instant backoffUntil = Instant.MIN;

        Endpoint(String urlTemplate, @Nullable InetAddress localAddress, String description) {
            this.urlTemplate = urlTemplate;
            this.description = description;
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (localAddress != null) {
                builder.localAddress(localAddress);
            }
            this.http = builder.build();
        }
    }

    /** Endpoints built from the settings they came from, rebuilt when a reload changes those. */
    private record Endpoints(PluginConfig.LookupSources sources, List<Endpoint> mojang, List<Endpoint> proxies) {
    }

    private final String mojangUrl;
    private final Supplier<PluginConfig.LookupSources> sources;
    private final Cache<String, Optional<MojangProfile>> cache;
    private final AtomicInteger nextMojang = new AtomicInteger();
    private volatile @Nullable Endpoints endpoints;

    public MojangClient(Duration cacheDuration, Supplier<PluginConfig.LookupSources> sources) {
        this(cacheDuration, sources, MOJANG_URL);
    }

    MojangClient(Duration cacheDuration, Supplier<PluginConfig.LookupSources> sources, String mojangUrl) {
        this.mojangUrl = mojangUrl;
        this.sources = sources;
        this.cache = Caffeine.newBuilder().expireAfterWrite(cacheDuration).maximumSize(10_000).build();
    }

    @Override
    public Optional<MojangProfile> lookup(String name) throws LookupException {
        String key = name.toLowerCase(Locale.ROOT);
        Optional<MojangProfile> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Optional<MojangProfile> result = fetch(name);
        cache.put(key, result);
        return result;
    }

    @Override
    public boolean isCached(String name) {
        return cache.getIfPresent(name.toLowerCase(Locale.ROOT)) != null;
    }

    private Optional<MojangProfile> fetch(String name) throws LookupException {
        Endpoints current = endpoints();
        List<Endpoint> order = new ArrayList<>(current.mojang().size() + current.proxies().size());
        int start = Math.floorMod(nextMojang.getAndIncrement(), current.mojang().size());
        for (int i = 0; i < current.mojang().size(); i++) {
            order.add(current.mojang().get((start + i) % current.mojang().size()));
        }
        order.addAll(current.proxies());

        Instant now = Instant.now();
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : order) {
            if (now.isBefore(endpoint.backoffUntil)) {
                failures.add(endpoint.description + ": backing off");
                continue;
            }
            try {
                return fetch(endpoint, name);
            } catch (LookupException e) {
                failures.add(endpoint.description + ": " + e.getMessage());
            }
        }
        throw new LookupException("Every lookup endpoint failed (" + String.join("; ", failures) + ")", null);
    }

    private static Optional<MojangProfile> fetch(Endpoint endpoint, String name) throws LookupException {
        URI uri = URI.create(endpoint.urlTemplate.replace("{name}", URLEncoder.encode(name, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = endpoint.http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            endpoint.backoffUntil = Instant.now().plus(BACKOFF);
            throw new LookupException("unreachable (" + e + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LookupException("interrupted", e);
        }
        return switch (response.statusCode()) {
            case 200 -> Optional.of(parse(response.body()));
            case 204, 404 -> Optional.empty();
            case 429 -> {
                endpoint.backoffUntil = Instant.now().plus(BACKOFF);
                throw new LookupException("rate limited (429)", null);
            }
            default -> throw new LookupException("HTTP " + response.statusCode(), null);
        };
    }

    private Endpoints endpoints() {
        PluginConfig.LookupSources wanted = sources.get();
        Endpoints current = endpoints;
        if (current != null && current.sources().equals(wanted)) {
            return current;
        }
        List<Endpoint> mojang = new ArrayList<>();
        if (wanted.addresses().isEmpty()) {
            mojang.add(new Endpoint(mojangUrl, null, "Mojang"));
        }
        for (InetAddress address : wanted.addresses()) {
            mojang.add(new Endpoint(mojangUrl, address, "Mojang from " + address.getHostAddress()));
        }
        List<Endpoint> proxies = wanted.proxies().stream().map(url -> new Endpoint(url, null, url)).toList();
        Endpoints built = new Endpoints(wanted, List.copyOf(mojang), proxies);
        endpoints = built;
        return built;
    }

    static MojangProfile parse(String body) throws LookupException {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return new MojangProfile(Uuids.fromUndashed(json.get("id").getAsString()), json.get("name").getAsString());
        } catch (JsonParseException | IllegalStateException | NullPointerException | IllegalArgumentException e) {
            throw new LookupException("unexpected response: " + body, e);
        }
    }
}
