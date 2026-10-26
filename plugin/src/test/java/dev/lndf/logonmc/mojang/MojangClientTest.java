package dev.lndf.logonmc.mojang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.lndf.logonmc.TestSupport;
import dev.lndf.logonmc.config.PluginConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MojangClientTest {

    private static final String NOTCH = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";

    private HttpServer server;
    private String base;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Set<String> sources = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        respond("/ok/", 200, NOTCH);
        respond("/limited/", 429, "");
        respond("/missing/", 404, "");
        respond("/broken/", 500, "");
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            sources.add(exchange.getRemoteAddress().getAddress().getHostAddress());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private MojangClient client(String mojangPath, List<String> addresses, List<String> proxyPaths) throws Exception {
        List<InetAddress> parsed = addresses.stream().map(InetAddress::ofLiteral).toList();
        List<String> proxies = proxyPaths.stream().map(p -> base + p + "{name}").toList();
        PluginConfig.LookupSources settings = new PluginConfig.LookupSources(parsed, proxies);
        return new MojangClient(Duration.ofMinutes(10), () -> settings, base + mojangPath + "{name}");
    }

    private int hits(String path) {
        return hits.getOrDefault(path, new AtomicInteger()).get();
    }

    @Test
    void parsesProfile() throws Exception {
        MojangProfile profile = MojangClient.parse(NOTCH);
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profile.id());
        assertEquals("Notch", profile.name());
    }

    @Test
    void rejectsUnexpectedBody() {
        assertThrows(ProfileLookup.LookupException.class, () -> MojangClient.parse("{\"error\":\"x\"}"));
        assertThrows(ProfileLookup.LookupException.class, () -> MojangClient.parse("not json"));
    }

    @Test
    void rotatesSourceAddresses() throws Exception {
        MojangClient client = client("/ok/", List.of("127.0.0.1", "127.0.0.2"), List.of());
        client.lookup("one");
        client.lookup("two");
        assertEquals(Set.of("127.0.0.1", "127.0.0.2"), sources);
    }

    @Test
    void cachesResults() throws Exception {
        MojangClient client = client("/ok/", List.of(), List.of());
        client.lookup("Notch");
        assertTrue(client.isCached("notch"));
        client.lookup("NOTCH");
        assertEquals(1, hits("/ok/"));
    }

    @Test
    void fallsBackToProxiesAndBacksOff() throws Exception {
        MojangClient client = client("/limited/", List.of(), List.of("/broken/", "/ok/"));
        assertEquals("Notch", client.lookup("one").orElseThrow().name());
        assertEquals("Notch", client.lookup("two").orElseThrow().name());
        // Mojang is skipped after its 429; a 500 is retried, it may be a one-off.
        assertEquals(1, hits("/limited/"));
        assertEquals(2, hits("/broken/"));
    }

    @Test
    void proxyMissIsAnAnswer() throws Exception {
        MojangClient client = client("/limited/", List.of(), List.of("/missing/", "/ok/"));
        assertTrue(client.lookup("one").isEmpty());
        assertEquals(0, hits("/ok/"));
    }

    @Test
    void failsWhenEveryEndpointFails() throws Exception {
        MojangClient client = client("/limited/", List.of(), List.of("/broken/"));
        assertThrows(ProfileLookup.LookupException.class, () -> client.lookup("one"));
    }

    @Test
    void validatesSettings() {
        assertThrows(IllegalStateException.class, () -> TestSupport.config(root -> set(root, List.of("192.0.2.1"), "lookup-addresses")));
        assertThrows(IllegalStateException.class, () -> TestSupport.config(root -> set(root, List.of("example.com"), "lookup-addresses")));
        assertThrows(IllegalStateException.class, () -> TestSupport.config(root -> set(root, List.of("https://a.example/x"), "lookup-proxies")));
        assertThrows(IllegalStateException.class, () -> TestSupport.config(root -> set(root, List.of("ftp://a.example/{name}"), "lookup-proxies")));
        PluginConfig config = TestSupport.config(root -> {
            set(root, List.of("127.0.0.1"), "lookup-addresses");
            set(root, List.of("https://a.example/{name}"), "lookup-proxies");
        });
        assertEquals(List.of(InetAddress.ofLiteral("127.0.0.1")), config.premium().lookupSources().addresses());
    }

    private static void set(org.spongepowered.configurate.ConfigurationNode root, List<String> value, String key) {
        try {
            root.node("premium", key).setList(String.class, value);
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            throw new IllegalStateException(e);
        }
    }
}
