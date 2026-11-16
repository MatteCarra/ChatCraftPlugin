package app.chatcraft.plugin;

import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Alerts and fan-out (owner program stage 2). {@link #raise} delivers an alert (1) in-session
 * to every online enrolled staff player ({@code {"t":"alert",...}}) and (2) as an encrypted
 * push to every enrolled device, always — the app dedupes by {@code aid}. At send time an
 * online enrolled player who no longer holds {@code chatcraft.staff} is skipped and their
 * devices are removed. Text is trimmed to 200 characters with control characters removed;
 * {@code target} is set only to a name matching the placeholder pattern.
 */
public class AlertService {

    /** Placeholder values the app may substitute: Java and Floodgate names. */
    public static final Pattern PLACEHOLDER_NAME = Pattern.compile("^\\.?[A-Za-z0-9_]{1,16}$");

    private static final int MAX_TEXT = 200;
    private static final int MAX_BODY = 100;
    private static final int MAX_RECENT = 20;
    private static final long RECENT_WINDOW_MS = 3_600_000L;

    public interface ChannelMessenger {
        void send(UUID player, JsonObject msg);
    }

    public interface PermissionCheck {
        /** The player is online and currently holds chatcraft.staff. */
        boolean isOnlineWithPermission(UUID player);
        /** The player is online at all. */
        boolean isOnline(UUID player);
    }

    public interface NameResolver {
        /** The exact-case name of the online player called [name], or null when offline. */
        String exactOnlineName(String name);
    }

    public enum ReportResult { ACCEPTED, DISABLED, COOLDOWN, BAD_NAME }

    private final StaffRegistry registry;
    private final PushClient push;
    private final ChannelMessenger messenger;
    private final ServerFacts facts;
    private final Addresses.Address address;
    private final StaffConfig staffConfig;
    private final Clock clock;
    private final PermissionCheck perms;
    private final NameResolver names;
    private final Executor pushExecutor;
    private final SecureRandom random = new SecureRandom();

    private final List<Alert> alerts = new ArrayList<>();
    private final Map<UUID, Long> lastReportAt = new HashMap<>();

    /** Pushes are sent inline (tests, or a caller that already runs off the server thread). */
    public AlertService(StaffRegistry registry, PushClient push, ChannelMessenger messenger, ServerFacts facts,
                        Addresses.Address address, StaffConfig staffConfig, Clock clock,
                        PermissionCheck perms, NameResolver names) {
        this(registry, push, messenger, facts, address, staffConfig, clock, perms, names, Runnable::run);
    }

    /**
     * [pushExecutor] runs the push HTTP calls: they block for seconds when the relay is slow, so the
     * plugin passes a single background thread (one thread keeps the alerts ordered). Everything that
     * touches Bukkit (permissions, in-session messages, the target snapshot) stays on the caller's thread.
     */
    public AlertService(StaffRegistry registry, PushClient push, ChannelMessenger messenger, ServerFacts facts,
                        Addresses.Address address, StaffConfig staffConfig, Clock clock,
                        PermissionCheck perms, NameResolver names, Executor pushExecutor) {
        this.pushExecutor = pushExecutor;
        this.registry = registry;
        this.push = push;
        this.messenger = messenger;
        this.facts = facts;
        this.address = address;
        this.staffConfig = staffConfig;
        this.clock = clock;
        this.perms = perms;
        this.names = names;
    }

    /** Creates an alert: random 8-byte hex aid, ts in seconds, text sanitised, target validated. */
    public Alert create(String source, String from, String text, String target) {
        byte[] aidBytes = new byte[8];
        random.nextBytes(aidBytes);
        StringBuilder hex = new StringBuilder(16);
        for (byte b : aidBytes) hex.append(String.format("%02x", b));
        String clean = sanitize(text);
        String t = target != null && PLACEHOLDER_NAME.matcher(target).matches() ? target : "";
        return new Alert(hex.toString(), clock.instant().getEpochSecond(), source, from, clean, t);
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String s = sb.toString().trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** Alerts of the last hour, newest first, at most 20. */
    public synchronized List<Alert> recent() {
        long cutoff = clock.millis() - RECENT_WINDOW_MS;
        List<Alert> out = new ArrayList<>();
        for (int i = alerts.size() - 1; i >= 0 && out.size() < MAX_RECENT; i--) {
            if (alerts.get(i).ts * 1000L >= cutoff) out.add(alerts.get(i));
        }
        return out;
    }

    /** Delivers the alert in-session to online staff and by encrypted push to every enrolled device. Never throws. */
    public void raise(Alert a) {
        synchronized (this) {
            alerts.add(a);
        }
        // (1) in-session to online enrolled staff; drop devices of players who lost the permission
        for (StaffRegistry.Device d : registry.all()) {
            UUID player = d.player;
            if (!perms.isOnline(player)) continue;
            if (!perms.isOnlineWithPermission(player)) {
                registry.remove(player);
                continue;
            }
            JsonObject msg = a.toJson();
            msg.addProperty("t", "alert");
            try {
                messenger.send(player, msg);
            } catch (Exception ignored) {
            }
        }
        // (2) push to every enrolled device, always (the app dedupes by aid)
        JsonObject plaintext = new JsonObject();
        plaintext.addProperty("title", facts.name());
        String body = a.text.length() > MAX_BODY ? a.text.substring(0, MAX_BODY) : a.text;
        plaintext.addProperty("body", body);
        plaintext.addProperty("kind", "staffAlert");
        plaintext.addProperty("aid", a.aid);
        plaintext.addProperty("host", address.host);
        plaintext.addProperty("port", address.port);
        plaintext.addProperty("ts", a.ts);
        // the whole alert travels inside the ciphertext so the app's stored copy is complete (actions need from/target)
        plaintext.addProperty("source", a.source);
        plaintext.addProperty("from", a.from);
        plaintext.addProperty("target", a.target);
        plaintext.addProperty("text", a.text);
        List<PushClient.PushTarget> targets = new ArrayList<>();
        for (StaffRegistry.Device d : registry.all()) {
            targets.add(new PushClient.PushTarget(d.deviceId, d.platform, d.pushToken, d.apnsEnv, d.pushKey));
        }
        final boolean report = "report".equals(a.source);
        try {
            pushExecutor.execute(() -> {
                try {
                    push.send(targets, plaintext, report);
                } catch (Exception e) {
                    // fan-out must never throw or block; in-session delivery already happened
                    System.err.println("[ChatCraft] push fan-out failed: " + e.getMessage());
                }
            });
        } catch (RuntimeException e) { // executor shut down (plugin disabling)
            System.err.println("[ChatCraft] push fan-out skipped: " + e.getMessage());
        }
    }

    /**
     * A player's /report. The target name must match [PLACEHOLDER_NAME] (so a reporter cannot make
     * a staff tap run {@code kick @a}); when the target is online their exact-case name is stored.
     */
    public ReportResult tryReport(UUID reporter, String targetName, String reason, String reporterName) {
        if (!staffConfig.reportsEnabled()) return ReportResult.DISABLED;
        if (targetName == null || !PLACEHOLDER_NAME.matcher(targetName).matches()) return ReportResult.BAD_NAME;
        long now = clock.millis();
        synchronized (this) {
            long cooldownMs = staffConfig.reportCooldownSeconds() * 1000L;
            // prune entries older than the cooldown window: they can never trigger COOLDOWN again,
            // so the bookkeeping map stays bounded by reporters inside one window
            Iterator<Map.Entry<UUID, Long>> it = lastReportAt.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() <= now - cooldownMs) it.remove();
            }
            Long last = lastReportAt.get(reporter);
            if (last != null && now - last < cooldownMs) return ReportResult.COOLDOWN;
            lastReportAt.put(reporter, now);
        }
        String exact = names.exactOnlineName(targetName);
        String target = exact != null ? exact : targetName;
        String text = (reason == null || reason.trim().isEmpty()) ? "Reported " + target : reason;
        raise(create("report", reporterName, text, target));
        return ReportResult.ACCEPTED;
    }

    /** Test hook: size of the report-cooldown bookkeeping map (pruned to the cooldown window in tryReport). */
    synchronized int lastReportAtSize() {
        return lastReportAt.size();
    }
}
