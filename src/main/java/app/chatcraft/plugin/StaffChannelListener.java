package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Receives app-to-plugin messages on the staff channel ({@code chatcraft:staff} on 1.13+,
 * {@code CC|Staff} below) and answers on the same channel. Payloads are UTF-8 JSON
 * ({"t": ...}), at most 32,000 bytes. The enrolment token is a bearer secret while
 * pending: it is never logged.
 */
public class StaffChannelListener implements PluginMessageListener {

    public static final String MODERN_CHANNEL = "chatcraft:staff";
    public static final String LEGACY_CHANNEL = "CC|Staff";
    private static final int MAX_BYTES = 32_000;

    private final Plugin plugin;
    private final StaffService service;
    private final Logger logger;
    private final String channel;

    public StaffChannelListener(Plugin plugin, StaffService service, Logger logger, String channel) {
        this.plugin = plugin;
        this.service = service;
        this.logger = logger;
        this.channel = channel;
    }

    /** Namespaced channel from Minecraft 1.13 up, legacy below (same boundary as the verify channel). */
    public static String channelNameFor(String bukkitVersion) {
        return VerifyChannelListener.isModernChannel(bukkitVersion) ? MODERN_CHANNEL : LEGACY_CHANNEL;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!this.channel.equals(channel)) return;
        if (message == null || message.length == 0 || message.length > MAX_BYTES) return;
        JsonObject msg;
        try {
            msg = new JsonParser().parse(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        JsonObject reply = handle(service, player.getUniqueId(), player.getName(), player.hasPermission(CommandAuth.STAFF), msg);
        if (reply == null) return;
        if ("enrolled".equals(reply.get("t").getAsString())) {
            logger.info("[ChatCraft] a device of " + player.getName() + " enrolled for staff alerts");
        }
        send(player, reply);
    }

    /**
     * The answer to one app message, or null for none. Pure (no Bukkit types) so it is unit-tested:
     * a {@code hello} from someone without {@code chatcraft.staff} removes their devices and gets no
     * reply, so the templates and recent reports in {@code info} are never handed to a plain player.
     */
    static JsonObject handle(StaffService service, UUID sender, String senderName, boolean hasStaff, JsonObject msg) {
        String t = msg.has("t") && msg.get("t").isJsonPrimitive() ? msg.get("t").getAsString() : null;
        if (t == null) return null;
        switch (t) {
            case "hello":
                service.onHello(sender, hasStaff, msg);
                return hasStaff ? service.infoFor(sender) : null;
            case "enroll":
                return service.onEnroll(sender, senderName, hasStaff, msg).toJson();
            case "unenroll": {
                String deviceId = msg.has("deviceId") && msg.get("deviceId").isJsonPrimitive()
                        ? msg.get("deviceId").getAsString() : null;
                if (deviceId != null) service.onUnenroll(sender, deviceId);
                return null;
            }
            default:
                return null;
        }
    }

    private void send(Player player, JsonObject msg) {
        player.sendPluginMessage(plugin, channel, msg.toString().getBytes(StandardCharsets.UTF_8));
    }
}
