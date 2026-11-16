package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeartbeatTaskTest {

    /** BackendClient fake: records heartbeat calls, returns programmed result/error. */
    private static class FakeBackend implements BackendClient {
        HeartbeatResult heartbeatResult;
        IOException heartbeatError;

        int heartbeatCalls;
        int lastId = -1;
        String lastKey;
        HeartbeatBody lastBody;

        @Override
        public RegisterResult register(String host, int port, UUID op) {
            throw new UnsupportedOperationException("not used by HeartbeatTask");
        }

        @Override
        public SeenResult seen(String token, UUID op) {
            throw new UnsupportedOperationException("not used by HeartbeatTask");
        }

        @Override
        public HeartbeatResult heartbeat(int id, String key, HeartbeatBody body) throws IOException {
            heartbeatCalls++;
            lastId = id;
            lastKey = key;
            lastBody = body;
            if (heartbeatError != null) {
                throw heartbeatError;
            }
            return heartbeatResult;
        }
    }

    /** In-memory CredentialStore fake. */
    private static class MemStore implements CredentialStore {
        Credentials saved;

        @Override
        public void save(int affiliationId, String key) {
            saved = new Credentials(affiliationId, key);
        }

        @Override
        public Credentials load() {
            return saved;
        }
    }

    /** Fixed ServerFacts for the tests. */
    private static class FakeFacts implements ServerFacts {
        @Override
        public String name() {
            return "Test Server";
        }

        @Override
        public boolean premium() {
            return false;
        }

        @Override
        public String gameVersion() {
            return "1.8.8";
        }

        @Override
        public String pluginVersion() {
            return "1.0.0";
        }
    }

    /** Captures log records so tests can assert on what was (not) logged. */
    private static class LogCapture extends Handler {
        final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean anyMessageContains(String fragment) {
            for (LogRecord r : records) {
                if (r.getMessage() != null && r.getMessage().contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static PluginConfig config(final boolean listing, final List<String> languages,
                                       final List<String> voteLinks) {
        PluginConfig.ConfigSource source = new PluginConfig.ConfigSource() {
            @Override
            public Object get(String path) {
                if ("listing".equals(path)) {
                    return listing;
                }
                if ("languages".equals(path)) {
                    return languages;
                }
                if ("vote-links".equals(path)) {
                    return voteLinks;
                }
                return null;
            }
        };
        return new PluginConfig(source, Logger.getLogger("HeartbeatTaskTest"));
    }

    private static Set<String> keysOf(JsonObject o) {
        Set<String> keys = new HashSet<String>();
        for (Map.Entry<String, ?> e : o.entrySet()) {
            keys.add(e.getKey());
        }
        return keys;
    }

    private FakeBackend backend;
    private MemStore store;
    private LogCapture logCapture;
    private Logger logger;
    private HeartbeatTask task;

    @Before
    public void setUp() {
        backend = new FakeBackend();
        store = new MemStore();
        logCapture = new LogCapture();
        logger = Logger.getLogger("HeartbeatTaskTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(logCapture);

        BackendClient.HeartbeatResult r = new BackendClient.HeartbeatResult();
        r.listed = true;
        r.disabled = false;
        backend.heartbeatResult = r;

        store.saved = new CredentialStore.Credentials(7, "secret-key");
    }

    private HeartbeatTask newTask(PluginConfig config) {
        return new HeartbeatTask(backend, store, config, new FakeFacts(), logger);
    }

    @Test
    public void sendsBodyWithExactlyTheAllowedFields() {
        PluginConfig config = config(true, Arrays.asList("en", "pt"),
                Arrays.asList("https://vote.example.com"));
        HeartbeatTask task = newTask(config);
        task.run();

        assertEquals(1, backend.heartbeatCalls);
        assertEquals(7, backend.lastId);
        assertEquals("secret-key", backend.lastKey);
        assertNotNull(backend.lastBody);

        JsonObject sent = backend.lastBody.toJson();
        // Exactly the allowed fields of the Contracts table - nothing else may
        // ever be sent, so player data cannot leak in later.
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
    }

    @Test
    public void noCredentialsSendsNothing() {
        store.saved = null;
        HeartbeatTask task = newTask(config(true, Arrays.asList("en"), new ArrayList<String>()));
        task.run(); // must not throw either
        assertEquals(0, backend.heartbeatCalls);
    }

    @Test
    public void http401IsLoggedAndDoesNotThrow() {
        backend.heartbeatError = new BackendClient.HttpException(401, "POST /owner/v1/heartbeat -> 401: bad key");
        HeartbeatTask task = newTask(config(true, new ArrayList<String>(), new ArrayList<String>()));
        task.run(); // must not throw
        assertEquals(1, backend.heartbeatCalls);
        assertTrue("expected the 'key rejected' warning in the log",
                logCapture.anyMessageContains("key rejected, run /chatcraft verify again"));
        assertNotNull(task.getLastError());
        assertNull(task.getLastResult());
    }

    @Test
    public void otherHttpErrorIsLoggedAndDoesNotThrow() {
        backend.heartbeatError = new BackendClient.HttpException(500, "boom");
        HeartbeatTask task = newTask(config(true, new ArrayList<String>(), new ArrayList<String>()));
        task.run(); // must not throw
        assertEquals(1, backend.heartbeatCalls);
        assertTrue(logCapture.anyMessageContains("HTTP 500"));
        assertNull(task.getLastResult());
    }

    @Test
    public void networkErrorIsLoggedAndDoesNotThrow() {
        backend.heartbeatError = new IOException("connection refused");
        HeartbeatTask task = newTask(config(true, new ArrayList<String>(), new ArrayList<String>()));
        task.run(); // must not throw
        assertEquals(1, backend.heartbeatCalls);
        assertNotNull(task.getLastError());
        assertNull(task.getLastResult());
    }

    @Test
    public void listingFalseIsSentAsFalse() {
        PluginConfig config = config(false, new ArrayList<String>(), new ArrayList<String>());
        HeartbeatTask task = newTask(config);
        task.run();

        assertEquals(1, backend.heartbeatCalls);
        assertFalse(backend.lastBody.listing);
        JsonObject sent = backend.lastBody.toJson();
        assertTrue("'listing' must be present even when false", sent.has("listing"));
        assertFalse(sent.get("listing").getAsBoolean());
    }

    @Test
    public void successfulHeartbeatRecordsLastResult() {
        HeartbeatTask task = newTask(config(true, new ArrayList<String>(), new ArrayList<String>()));
        assertNull(task.getLastResult());
        task.run();
        assertNotNull(task.getLastResult());
        assertTrue(task.getLastResult().listed);
        assertFalse(task.getLastResult().disabled);
        assertNull(task.getLastError());
        assertTrue(task.isVerified());
    }

    @Test
    public void neverLogsTheKey() {
        backend.heartbeatError = new BackendClient.HttpException(401, "POST /owner/v1/heartbeat -> 401: bad key");
        HeartbeatTask task = newTask(config(true, new ArrayList<String>(), new ArrayList<String>()));
        task.run();
        for (LogRecord r : logCapture.records) {
            assertFalse("log must never contain the key", r.getMessage() != null && r.getMessage().contains("secret-key"));
        }
    }
}
