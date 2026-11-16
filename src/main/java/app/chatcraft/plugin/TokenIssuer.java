package app.chatcraft.plugin;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory single-use enrolment tokens: random base64url, valid 15 minutes, bound to the
 * player's UUID. A token is consumed only by a successful enrolment; a wrong UUID or an
 * expired token does not consume it (the right player can still use it until it expires).
 */
public class TokenIssuer {

    public static final long TTL_MS = 15 * 60 * 1000L;

    public static class Pending {
        public final UUID player;
        public final long expiresAtMs;

        Pending(UUID player, long expiresAtMs) {
            this.player = player;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new HashMap<>();

    public TokenIssuer(Clock clock) {
        this.clock = clock;
    }

    public synchronized String issue(UUID player) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        pending.put(token, new Pending(player, clock.millis() + TTL_MS));
        return token;
    }

    /** The pending token's data, or null when unknown or expired. Does not consume. */
    public synchronized Pending peek(String token) {
        Pending p = pending.get(token);
        if (p == null) return null;
        if (clock.millis() >= p.expiresAtMs) return null;
        return p;
    }

    /** Consumes the token (single use). */
    public synchronized void consume(String token) {
        pending.remove(token);
    }
}
