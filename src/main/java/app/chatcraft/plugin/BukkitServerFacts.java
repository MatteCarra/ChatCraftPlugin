package app.chatcraft.plugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * {@link ServerFacts} backed by the running Bukkit server:
 * name from config.yml {@code name} or the {@code server.properties} motd,
 * premium from {@code Bukkit.getOnlineMode()}, gameVersion from
 * {@code Bukkit.getBukkitVersion()} (prefix before {@code '-'}), pluginVersion
 * from the plugin description.
 */
public class BukkitServerFacts implements ServerFacts {

    private static final String FALLBACK_NAME = "Minecraft Server";

    private final Plugin plugin;
    private volatile String cachedMotd;
    private volatile boolean motdRead;

    public BukkitServerFacts(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        String configured = plugin.getConfig().getString("name");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String motd = motd();
        return motd != null && !motd.isEmpty() ? motd : FALLBACK_NAME;
    }

    @Override
    public boolean premium() {
        return Bukkit.getOnlineMode();
    }

    @Override
    public String gameVersion() {
        String v = Bukkit.getBukkitVersion();
        if (v == null) {
            return "unknown";
        }
        String prefix = v.split("-", 2)[0].trim();
        return prefix.isEmpty() ? v : prefix;
    }

    @Override
    public String pluginVersion() {
        String v = plugin.getDescription().getVersion();
        return v == null ? "unknown" : v;
    }

    /** The {@code motd} line of {@code server.properties} in the server root, colour codes stripped. */
    private String motd() {
        if (!motdRead) {
            cachedMotd = readMotd(new File("server.properties"));
            motdRead = true;
        }
        return cachedMotd;
    }

    private static String readMotd(File file) {
        try {
            if (!file.isFile()) {
                return null;
            }
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("motd=")) {
                    return stripColorCodes(line.substring("motd=".length()).trim());
                }
            }
        } catch (IOException ignored) {
            // fall through: no motd available
        }
        return null;
    }

    /** Removes Minecraft colour/format codes ({@code &} and {@code §} followed by a code char). */
    private static String stripColorCodes(String s) {
        return s.replaceAll("(?i)[&\u00A7][0-9A-FK-OR]", "");
    }
}
