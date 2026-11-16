package app.chatcraft.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The staff enrolment and session logic (owner program stage 2): the plugin issues a
 * single-use 15-minute token bound to the player's UUID and prints the
 * {@code https://api.chatcraft.app/s?h=&p=&t=} link; the app opens it, connects, registers
 * the staff channel and sends {@code enroll}; the plugin answers {@code enrolled} or
 * {@code enroll_failed}. {@code hello} refreshes a device's push token (that is how a
 * rotated FCM token reaches the plugin) or, without the staff permission, removes the
 * player's devices; {@code onJoin} does the same on every join.
 */
public class StaffService {

    public static class EnrollOutcome {
        public final String deviceId;      // when ok
        public final String failureReason; // "token" | "permission" | "limit"

        private EnrollOutcome(String deviceId, String failureReason) {
            this.deviceId = deviceId;
            this.failureReason = failureReason;
        }

        public boolean isOk() {
            return deviceId != null;
        }

        static EnrollOutcome ok(String deviceId) {
            return new EnrollOutcome(deviceId, null);
        }

        static EnrollOutcome fail(String reason) {
            return new EnrollOutcome(null, reason);
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (isOk()) {
                o.addProperty("t", "enrolled");
                o.addProperty("deviceId", deviceId);
            } else {
                o.addProperty("t", "enroll_failed");
                o.addProperty("reason", failureReason);
            }
            return o;
        }
    }

    private final StaffRegistry registry;
    private final StaffConfig config;
    private final TokenIssuer tokens;
    private final Clock clock;
    private Supplier<List<Alert>> recentAlerts;

    public StaffService(StaffRegistry registry, StaffConfig config, TokenIssuer tokens, Clock clock, Supplier<List<Alert>> recentAlerts) {
        this.registry = registry;
        this.config = config;
        this.tokens = tokens;
        this.clock = clock;
        this.recentAlerts = recentAlerts;
    }

    /** Starts an enrolment for [player] at the given address and returns the link to print. */
    public String startEnrolment(UUID player, String host, int port) {
        String token = tokens.issue(player);
        return "https://api.chatcraft.app/s?h=" + host + "&p=" + port + "&t=" + token;
    }

    /**
     * Handles an {@code enroll} message. Enrols only when the sender holds
     * {@code chatcraft.staff}, the token is the pending one, unexpired, and was issued to
     * this sender. Consumes the token only on success.
     */
    public EnrollOutcome onEnroll(UUID sender, String senderName, boolean hasStaffPermission, JsonObject message) {
        if (!hasStaffPermission) {
            return EnrollOutcome.fail("permission");
        }
        String token = message.has("token") && message.get("token").isJsonPrimitive()
                ? message.get("token").getAsString() : null;
        TokenIssuer.Pending p = token == null ? null : tokens.peek(token);
        if (p == null || !p.player.equals(sender)) {
            return EnrollOutcome.fail("token");
        }
        String pushKey = message.has("pushKey") && message.get("pushKey").isJsonPrimitive()
                ? message.get("pushKey").getAsString() : null;
        String pushToken = message.has("pushToken") && message.get("pushToken").isJsonPrimitive()
                ? message.get("pushToken").getAsString() : "";
        String platform = message.has("platform") && message.get("platform").isJsonPrimitive()
                ? message.get("platform").getAsString() : "android";
        String apnsEnv = message.has("apnsEnv") && !message.get("apnsEnv").isJsonNull()
                ? message.get("apnsEnv").getAsString() : null;
        if (pushKey == null || decodeKey(pushKey) == null) {
            return EnrollOutcome.fail("token");
        }
        java.util.Optional<String> deviceId = registry.add(sender, senderName,
                new StaffRegistry.DeviceInfo(platform, pushToken, apnsEnv, pushKey));
        if (!deviceId.isPresent()) {
            return EnrollOutcome.fail("limit");
        }
        tokens.consume(token);
        return EnrollOutcome.ok(deviceId.get());
    }

    private static byte[] decodeKey(String b64) {
        try {
            byte[] k = Base64.getDecoder().decode(b64);
            return k.length == 32 ? k : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code info} answer: the configured templates plus the alerts of the last hour. */
    public JsonObject infoFor(UUID player) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "info");
        JsonArray templates = new JsonArray();
        for (StaffConfig.Template t : config.getTemplates()) {
            JsonObject tpl = new JsonObject();
            tpl.addProperty("id", t.id);
            tpl.addProperty("label", t.label);
            tpl.addProperty("command", t.command);
            tpl.addProperty("target", t.target);
            tpl.addProperty("confirm", t.confirm);
            templates.add(tpl);
        }
        o.add("templates", templates);
        JsonArray alerts = new JsonArray();
        for (Alert a : recentAlerts.get()) {
            alerts.add(a.toJson());
        }
        o.add("alerts", alerts);
        return o;
    }

    /**
     * Handles {@code hello}: refreshes lastSeen and, when a non-empty token is given, the push token; or removes the
     * player's devices when the permission is gone. A hello naming another player's
     * deviceId changes nothing.
     */
    public void onHello(UUID sender, boolean hasStaffPermission, JsonObject message) {
        if (!hasStaffPermission) {
            registry.remove(sender);
            return;
        }
        String deviceId = message.has("deviceId") && message.get("deviceId").isJsonPrimitive()
                ? message.get("deviceId").getAsString() : null;
        String pushToken = message.has("pushToken") && message.get("pushToken").isJsonPrimitive()
                ? message.get("pushToken").getAsString() : null;
        if (deviceId == null) return;
        registry.updateToken(sender, deviceId, pushToken, clock.instant());
    }

    /**
     * On join: without the staff permission the player's devices are removed; with it,
     * their lastSeen is refreshed (a join proves activity whatever address was used).
     */
    public void onJoin(UUID player, boolean hasStaffPermission, Instant now) {
        if (!hasStaffPermission) {
            registry.remove(player);
        } else {
            registry.touch(player, now);
        }
    }

    public void onUnenroll(UUID player, String deviceId) {
        registry.removeDevice(player, deviceId);
    }

    /** Replaces the recent-alerts supplier (AlertService wires itself in after construction). */
    public void setRecentAlerts(Supplier<List<Alert>> recentAlerts) {
        this.recentAlerts = recentAlerts;
    }

    public StaffRegistry registry() {
        return registry;
    }
}
