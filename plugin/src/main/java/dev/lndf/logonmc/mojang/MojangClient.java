package dev.lndf.logonmc.mojang;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.lndf.logonmc.util.Uuids;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Name to premium-profile lookups against the Mojang API, cached (including misses) per name.
 * After a 429 response, lookups fail fast until the backoff ends instead of piling up requests.
 */
public final class MojangClient implements ProfileLookup {

    private static final String ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Cache<String, Optional<MojangProfile>> cache;
    private volatile Instant rateLimitedUntil = Instant.MIN;

    public MojangClient(Duration cacheDuration) {
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
        if (Instant.now().isBefore(rateLimitedUntil)) {
            throw new LookupException("Mojang API rate limit, backing off", null);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LookupException("Mojang API unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LookupException("Interrupted", e);
        }
        return switch (response.statusCode()) {
            case 200 -> Optional.of(parse(response.body()));
            case 204, 404 -> Optional.empty();
            case 429 -> {
                rateLimitedUntil = Instant.now().plus(RATE_LIMIT_BACKOFF);
                throw new LookupException("Mojang API rate limit (429)", null);
            }
            default -> throw new LookupException("Mojang API returned HTTP " + response.statusCode(), null);
        };
    }

    static MojangProfile parse(String body) throws LookupException {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return new MojangProfile(Uuids.fromUndashed(json.get("id").getAsString()), json.get("name").getAsString());
        } catch (JsonParseException | IllegalStateException | NullPointerException | IllegalArgumentException e) {
            throw new LookupException("Unexpected Mojang API response: " + body, e);
        }
    }
}
