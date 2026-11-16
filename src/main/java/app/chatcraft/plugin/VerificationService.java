package app.chatcraft.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

/**
 * Owner-program verification flow (plugin side):
 *
 * <ol>
 *   <li>{@link #start(UUID, String, int)} asks the backend for a verification
 *       token for this server address and remembers token -&gt; operator.</li>
 *   <li>The operator opens the returned link on the phone with the ChatCraft
 *       app; the app relays the token to this server over the plugin message
 *       channel ({@code chatcraft:verify} on 1.13+, {@code CC|Verify} below),
 *       which arrives as {@link #onChannelMessage(UUID, boolean, byte[])}.</li>
 *   <li>When the token is known, unexpired, and comes from the same operator
 *       (who must be op), the plugin calls {@code seen} once; the backend
 *       answers with the affiliation credentials, which are stored in
 *       {@code plugins/ChatCraft/credentials.yml}.</li>
 * </ol>
 *
 * The token is a bearer secret while pending: it is never logged.
 */
public class VerificationService {

    private final BackendClient backend;
    private final Clock clock;
    private final CredentialStore credentials;

    private Pending pending;

    public VerificationService(BackendClient backend, Clock clock, CredentialStore credentials) {
        if (backend == null || clock == null || credentials == null) {
            throw new IllegalArgumentException("backend, clock and credentials are required");
        }
        this.backend = backend;
        this.clock = clock;
        this.credentials = credentials;
    }

    /**
     * Registers this server address with the backend and returns the
     * verification link to show the operator. Remembers token -&gt; operator
     * for {@link #onChannelMessage}.
     */
    public synchronized String start(UUID operator, String host, int port) throws IOException {
        BackendClient.RegisterResult r = backend.register(host, port, operator);
        if (r == null || r.token == null || r.token.isEmpty()) {
            throw new IOException("backend returned no verification token");
        }
        pending = new Pending(r.token, operator, r.expiresAt);
        return r.link;
    }

    /**
     * Handles an app-to-plugin channel message whose payload is the token as
     * UTF-8 bytes. Returns true only when the token is the pending one, it has
     * not expired, the sender is the remembered operator and is op, and the
     * backend's {@code seen} call succeeded — in which case the affiliation
     * credentials are stored. Never calls {@code seen} for a wrong sender,
     * non-op, unknown or expired token.
     */
    public synchronized boolean onChannelMessage(UUID sender, boolean senderIsOp, byte[] payload) {
        if (sender == null || payload == null || payload.length == 0) {
            return false;
        }
        String token = new String(payload, StandardCharsets.UTF_8);
        Pending p = pending;
        if (p == null || !p.token.equals(token)) {
            return false; // unknown token (or no verification running)
        }
        if (!senderIsOp || !sender.equals(p.operator)) {
            return false; // only the operator who started the verification, and only as op
        }
        if (clock.millis() / 1000L >= p.expiresAt) {
            pending = null; // token is dead server-side too (409); drop it
            return false;
        }
        try {
            BackendClient.SeenResult r = backend.seen(token, sender);
            if (r == null || r.key == null) {
                return false;
            }
            credentials.save(r.affiliationId, r.key);
            pending = null;
            return true;
        } catch (IOException e) {
            // 409 used/expired, 403 UUID mismatch, or a network error.
            // The exception message is deliberately NOT logged here: for the
            // /seen route it contains the token in the URL path.
            return false;
        }
    }

    private static final class Pending {
        final String token;
        final UUID operator;
        final long expiresAt; // epoch seconds, as returned by the backend

        Pending(String token, UUID operator, long expiresAt) {
            this.token = token;
            this.operator = operator;
            this.expiresAt = expiresAt;
        }
    }
}
