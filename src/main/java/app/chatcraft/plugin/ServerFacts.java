package app.chatcraft.plugin;

/**
 * Facts about this server that are sent in the heartbeat body. Kept behind an
 * interface so {@link HeartbeatTask} stays unit-testable without Bukkit.
 */
public interface ServerFacts {

    /** Display name: the {@code name} from config.yml when set, else the server.properties {@code motd}. */
    String name();

    /** Whether the server runs in online (premium) mode: {@code Bukkit.getOnlineMode()}. */
    boolean premium();

    /** Game version without the build suffix: {@code Bukkit.getBukkitVersion()} prefix before {@code '-'}. */
    String gameVersion();

    /** Version of this plugin, e.g. {@code "1.0.0"}. */
    String pluginVersion();
}
