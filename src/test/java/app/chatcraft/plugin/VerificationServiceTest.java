package app.chatcraft.plugin;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VerificationServiceTest {

    /** BackendClient fake: records calls, returns programmed results/errors. */
    private static class FakeBackend implements BackendClient {
        RegisterResult registerResult;
        IOException registerError;
        SeenResult seenResult;
        IOException seenError;

        String regHost;
        int regPort = -1;
        UUID regOp;
        int registerCalls;
        String seenToken;
        UUID seenOp;
        int seenCalls;

        @Override
        public RegisterResult register(String host, int port, UUID op) throws IOException {
            registerCalls++;
            regHost = host;
            regPort = port;
            regOp = op;
            if (registerError != null) {
                throw registerError;
            }
            return registerResult;
        }

        @Override
        public SeenResult seen(String token, UUID op) throws IOException {
            seenCalls++;
            seenToken = token;
            seenOp = op;
            if (seenError != null) {
                throw seenError;
            }
            return seenResult;
        }

        @Override
        public HeartbeatResult heartbeat(int id, String key, HeartbeatBody body) {
            throw new UnsupportedOperationException("not used by VerificationService");
        }
    }

    /** Clock with a settable instant. */
    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** In-memory CredentialStore fake. */
    private static class MemStore implements CredentialStore {
        CredentialStore.Credentials saved;

        @Override
        public void save(int affiliationId, String key) {
            saved = new CredentialStore.Credentials(affiliationId, key);
        }

        @Override
        public CredentialStore.Credentials load() {
            return saved;
        }
    }

    private static final Instant T0 = Instant.ofEpochSecond(1_700_000_000L);
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final String TOKEN = "tok-abc123";
    private static final String LINK = "https://chatcraft.app/v/" + TOKEN;

    private FakeBackend backend;
    private MutableClock clock;
    private MemStore store;
    private VerificationService service;

    @Before
    public void setUp() {
        backend = new FakeBackend();
        clock = new MutableClock(T0);
        store = new MemStore();
        service = new VerificationService(backend, clock, store);

        BackendClient.RegisterResult r = new BackendClient.RegisterResult();
        r.token = TOKEN;
        r.expiresAt = T0.getEpochSecond() + 900; // valid for 15 minutes
        r.link = LINK;
        backend.registerResult = r;

        BackendClient.SeenResult s = new BackendClient.SeenResult();
        s.affiliationId = 7;
        s.key = "secret-key";
        backend.seenResult = s;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void startRegistersAndReturnsLink() throws Exception {
        String link = service.start(OPERATOR, "play.example.com", 25565);
        assertEquals(LINK, link);
        assertEquals(1, backend.registerCalls);
        assertEquals("play.example.com", backend.regHost);
        assertEquals(25565, backend.regPort);
        assertEquals(OPERATOR, backend.regOp);
    }

    @Test
    public void correctMessageFromOperatorCompletesAndStoresCredentials() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        boolean ok = service.onChannelMessage(OPERATOR, true, utf8(TOKEN));
        assertTrue(ok);
        assertEquals(1, backend.seenCalls);
        assertEquals(TOKEN, backend.seenToken);
        assertEquals(OPERATOR, backend.seenOp);
        assertEquals(7, store.saved.affiliationId);
        assertEquals("secret-key", store.saved.key);
    }

    @Test
    public void messageFromAnotherUuidReturnsFalseAndNeverCallsSeen() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        boolean ok = service.onChannelMessage(UUID.randomUUID(), true, utf8(TOKEN));
        assertFalse(ok);
        assertEquals(0, backend.seenCalls);
        assertNull(store.saved);
    }

    @Test
    public void nonOpSenderReturnsFalseAndNeverCallsSeen() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        boolean ok = service.onChannelMessage(OPERATOR, false, utf8(TOKEN));
        assertFalse(ok);
        assertEquals(0, backend.seenCalls);
        assertNull(store.saved);
    }

    @Test
    public void payloadAfterExpiryReturnsFalseAndNeverCallsSeen() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        clock.set(Instant.ofEpochSecond(T0.getEpochSecond() + 901)); // past expiresAt
        boolean ok = service.onChannelMessage(OPERATOR, true, utf8(TOKEN));
        assertFalse(ok);
        assertEquals(0, backend.seenCalls);
        assertNull(store.saved);
    }

    @Test
    public void unknownTokenReturnsFalseAndNeverCallsSeen() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        boolean ok = service.onChannelMessage(OPERATOR, true, utf8("some-other-token"));
        assertFalse(ok);
        assertEquals(0, backend.seenCalls);
        assertNull(store.saved);
    }

    @Test
    public void messageWithoutStartReturnsFalse() {
        boolean ok = service.onChannelMessage(OPERATOR, true, utf8(TOKEN));
        assertFalse(ok);
        assertEquals(0, backend.seenCalls);
    }

    @Test
    public void seenFailureReturnsFalseAndStoresNothing() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        backend.seenError = new BackendClient.HttpException(409, "used or expired");
        boolean ok = service.onChannelMessage(OPERATOR, true, utf8(TOKEN));
        assertFalse(ok);
        assertEquals(1, backend.seenCalls);
        assertNull(store.saved);
    }

    @Test
    public void emptyPayloadReturnsFalse() throws Exception {
        service.start(OPERATOR, "play.example.com", 25565);
        assertFalse(service.onChannelMessage(OPERATOR, true, new byte[0]));
        assertFalse(service.onChannelMessage(OPERATOR, true, null));
        assertEquals(0, backend.seenCalls);
    }
}
