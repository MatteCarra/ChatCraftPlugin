package app.chatcraft.plugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.logging.Logger;

/**
 * Receives the app-to-plugin verification message. The app sends the pending
 * token as UTF-8 bytes on the channel {@code chatcraft:verify} on Minecraft
 * 1.13+ and on {@code CC|Verify} below (pre-1.13 channel names may not contain
 * a colon). The payload is the token — a bearer secret — so it is never
 * logged; only the boolean outcome is.
 */
public class VerifyChannelListener implements PluginMessageListener {

    public static final String MODERN_CHANNEL = "chatcraft:verify";
    public static final String LEGACY_CHANNEL = "CC|Verify";

    private final VerificationService service;
    private final Logger logger;
    private final String channel;
    private final Runnable onVerified;

    public VerifyChannelListener(VerificationService service, Logger logger, String channel) {
        this(service, logger, channel, null);
    }

    /**
     * @param onVerified optional callback run after a successful verification
     *                   (used to trigger a heartbeat right away); may be null
     */
    public VerifyChannelListener(VerificationService service, Logger logger, String channel, Runnable onVerified) {
        this.service = service;
        this.logger = logger;
        this.channel = channel;
        this.onVerified = onVerified;
    }

    /**
     * Picks the channel name for a {@code Bukkit.getBukkitVersion()} value such
     * as {@code "1.8.8-R0.1-SNAPSHOT"} or {@code "1.21.4-R0.1-SNAPSHOT"}:
     * namespaced {@code chatcraft:verify} from 1.13 up, legacy {@code CC|Verify}
     * below. Unparseable versions are assumed modern.
     */
    public static String channelNameFor(String bukkitVersion) {
        return isModernChannel(bukkitVersion) ? MODERN_CHANNEL : LEGACY_CHANNEL;
    }

    /** True when namespaced plugin channels (with a colon) exist: Minecraft 1.13+. Unparseable versions are assumed modern. */
    public static boolean isModernChannel(String bukkitVersion) {
        if (bukkitVersion != null) {
            String v = bukkitVersion.split("-", 2)[0].trim();
            String[] parts = v.split("\\.");
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return major > 1 || (major == 1 && minor >= 13);
            } catch (NumberFormatException ignored) {
                // fall through to the modern default
            }
        }
        return true;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!this.channel.equals(channel)) {
            return;
        }
        boolean ok = service.onChannelMessage(player.getUniqueId(), player.isOp(), message);
        if (ok) {
            logger.info("[ChatCraft] Verification completed - this server is now affiliated. Credentials stored in plugins/ChatCraft/credentials.yml.");
            if (onVerified != null) {
                onVerified.run();
            }
        } else {
            logger.info("[ChatCraft] Ignored a verify channel message (no pending token, token expired, or sender is not the operator).");
        }
    }
}
