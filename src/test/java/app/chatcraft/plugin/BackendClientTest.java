package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackendClientTest {

    private HttpServer server;
    private BackendClient.Https client;

    private final AtomicReference<String> lastMethod = new AtomicReference<String>();
    private final AtomicReference<String> lastPath = new AtomicReference<String>();
    private final AtomicReference<String> lastBody = new AtomicReference<String>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<String>();
    private final AtomicReference<String> lastAccept = new AtomicReference<String>();
    private volatile int responseStatus;
    private volatile String responseBody;

    @Before
    public void setUp() throws Exception {
        responseStatus = 200;
        responseBody = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            lastBody.set(readAll(exchange.getRequestBody()));
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, resp.length);
            OutputStream os = exchange.getResponseBody();
            os.write(resp);
            os.close();
            exchange.close();
        });
        server.start();
        client = new BackendClient.Https("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Set<String> keysOf(JsonObject o) {
        Set<String> keys = new HashSet<String>();
        for (Map.Entry<String, ?> e : o.entrySet()) {
            keys.add(e.getKey());
        }
        return keys;
    }

    @Test
    public void registerSendsHostPortAndOperatorUuid() throws Exception {
        responseBody = "{\"token\":\"tok123\",\"expiresAt\":1700000000,\"link\":\"https://api.chatcraft.app/v/tok123\"}";
        UUID op = UUID.randomUUID();
        BackendClient.RegisterResult r = client.register("play.example.com", 25565, op);
        assertEquals("POST", lastMethod.get());
        assertEquals("/owner/v1/verify", lastPath.get());
        assertTrue(lastAccept.get().contains("application/json"));
        JsonObject body = new JsonParser().parse(lastBody.get()).getAsJsonObject();
        assertEquals("play.example.com", body.get("host").getAsString());
        assertEquals(25565, body.get("port").getAsInt());
        assertEquals(op.toString(), body.get("operatorUuid").getAsString());
        assertEquals("tok123", r.token);
        assertEquals(1700000000L, r.expiresAt);
        assertEquals("https://api.chatcraft.app/v/tok123", r.link);
    }

    @Test
    public void seenPostsOperatorUuidToTokenSeen() throws Exception {
        responseBody = "{\"affiliationId\":7,\"key\":\"secretkey\"}";
        UUID op = UUID.randomUUID();
        BackendClient.SeenResult r = client.seen("tok123", op);
        assertEquals("POST", lastMethod.get());
        assertEquals("/owner/v1/verify/tok123/seen", lastPath.get());
        JsonObject body = new JsonParser().parse(lastBody.get()).getAsJsonObject();
        assertEquals(op.toString(), body.get("operatorUuid").getAsString());
        assertEquals(7, r.affiliationId);
        assertEquals("secretkey", r.key);
    }

    @Test
    public void heartbeatSendsAuthorizationHeaderAndContractBody() throws Exception {
        responseBody = "{\"listed\":true,\"disabled\":false}";
        BackendClient.HeartbeatBody body = new BackendClient.HeartbeatBody();
        body.name = "Test Server";
        body.languages = Arrays.asList("en", "pt");
        body.listing = true;
        body.premium = false;
        body.gameVersion = "1.8.8";
        body.pluginVersion = "1.0.0";
        body.voteLinks = Arrays.asList("https://vote.example.com");
        BackendClient.HeartbeatResult r = client.heartbeat(7, "secretkey", body);
        assertEquals("POST", lastMethod.get());
        assertEquals("/owner/v1/heartbeat", lastPath.get());
        assertEquals("Bearer 7:secretkey", lastAuthorization.get());
        assertTrue(lastAccept.get().contains("application/json"));
        JsonObject sent = new JsonParser().parse(lastBody.get()).getAsJsonObject();
        // Exactly the allowed fields of the Contracts table — nothing else may ever be sent.
        assertEquals(new HashSet<String>(Arrays.asList(
                        "name", "languages", "listing", "premium", "gameVersion", "pluginVersion", "voteLinks")),
                keysOf(sent));
        assertEquals("Test Server", sent.get("name").getAsString());
        assertEquals("en", sent.getAsJsonArray("languages").get(0).getAsString());
        assertEquals("pt", sent.getAsJsonArray("languages").get(1).getAsString());
        assertTrue(sent.get("listing").getAsBoolean());
        assertFalse(sent.get("premium").getAsBoolean());
        assertEquals("1.8.8", sent.get("gameVersion").getAsString());
        assertEquals("1.0.0", sent.get("pluginVersion").getAsString());
        assertEquals("https://vote.example.com", sent.getAsJsonArray("voteLinks").get(0).getAsString());
        assertTrue(r.listed);
        assertFalse(r.disabled);
    }

    @Test
    public void non2xxResponseThrowsHttpExceptionWithStatus() throws Exception {
        responseStatus = 401;
        responseBody = "{\"error\":\"bad key\"}";
        BackendClient.HeartbeatBody body = new BackendClient.HeartbeatBody();
        try {
            client.heartbeat(7, "wrong", body);
            fail("expected HttpException");
        } catch (BackendClient.HttpException e) {
            assertEquals(401, e.getStatus());
        }
    }

    @Test
    public void baseUrlIsOverridableBySystemProperty() throws Exception {
        String old = System.getProperty(BackendClient.Https.BASE_URL_PROPERTY);
        System.setProperty(BackendClient.Https.BASE_URL_PROPERTY,
                "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            responseBody = "{\"token\":\"t\",\"expiresAt\":1,\"link\":\"l\"}";
            BackendClient.Https fromProperty = new BackendClient.Https();
            BackendClient.RegisterResult r = fromProperty.register("a.b", 25565, UUID.randomUUID());
            assertEquals("t", r.token);
            assertEquals("/owner/v1/verify", lastPath.get());
        } finally {
            if (old == null) {
                System.clearProperty(BackendClient.Https.BASE_URL_PROPERTY);
            } else {
                System.setProperty(BackendClient.Https.BASE_URL_PROPERTY, old);
            }
        }
    }
}
