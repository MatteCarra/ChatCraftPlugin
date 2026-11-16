package app.chatcraft.plugin;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The staff section of config.yml: `reports` (default true), `report-cooldown-seconds`
 * (default 30) and `staff-actions` (at most 10 templates of {id, label, command, target,
 * confirm}; confirm defaults true). A template whose command contains a newline, starts
 * with `/`, or is longer than 100 characters, or whose id repeats, is dropped with a
 * console warning at load time (the app refuses them again at display time).
 */
public class StaffConfig {

    public static final int MAX_TEMPLATES = 10;
    public static final int MAX_COMMAND_LENGTH = 100;

    public static class Template {
        public final String id;
        public final String label;
        public final String command;
        public final String target; // "player" | "alert"
        public final boolean confirm;

        Template(String id, String label, String command, String target, boolean confirm) {
            this.id = id;
            this.label = label;
            this.command = command;
            this.target = target;
            this.confirm = confirm;
        }
    }

    private final boolean reports;
    private final int reportCooldownSeconds;
    private final List<Template> templates;

    public StaffConfig(ConfigurationSection section, Logger logger) {
        this.reports = section == null || section.getBoolean("reports", true);
        this.reportCooldownSeconds = section == null ? 30 : Math.max(0, section.getInt("report-cooldown-seconds", 30));
        this.templates = parse(section, logger);
    }

    public boolean reportsEnabled() {
        return reports;
    }

    public int reportCooldownSeconds() {
        return reportCooldownSeconds;
    }

    public List<Template> getTemplates() {
        return templates;
    }

    public static List<Template> parse(ConfigurationSection section, Logger logger) {
        List<Template> out = new ArrayList<>();
        if (section == null) return out;
        Set<String> seenIds = new HashSet<>();
        for (Map<?, ?> raw : section.getMapList("staff-actions")) {
            if (out.size() >= MAX_TEMPLATES) {
                logger.warning("[ChatCraft] more than " + MAX_TEMPLATES + " staff-actions configured, the rest are ignored");
                break;
            }
            Object idO = raw.get("id");
            Object cmdO = raw.get("command");
            if (!(idO instanceof String) || ((String) idO).isEmpty() || !(cmdO instanceof String)) {
                logger.warning("[ChatCraft] staff-action without id or command dropped");
                continue;
            }
            String id = (String) idO;
            String command = (String) cmdO;
            if (!seenIds.add(id)) {
                logger.warning("[ChatCraft] duplicate staff-action id '" + id + "' dropped");
                continue;
            }
            if (command.contains("\n") || command.contains("\r")) {
                logger.warning("[ChatCraft] staff-action '" + id + "' has a newline in its command, dropped");
                continue;
            }
            if (command.startsWith("/")) {
                logger.warning("[ChatCraft] staff-action '" + id + "' starts with '/', dropped");
                continue;
            }
            if (command.length() > MAX_COMMAND_LENGTH) {
                logger.warning("[ChatCraft] staff-action '" + id + "' command is longer than " + MAX_COMMAND_LENGTH + " characters, dropped");
                continue;
            }
            String label = raw.get("label") instanceof String ? (String) raw.get("label") : id;
            Object targetO = raw.get("target");
            String target = targetO instanceof String ? (String) targetO : "player";
            if (!target.equals("player") && !target.equals("alert")) {
                logger.warning("[ChatCraft] staff-action '" + id + "' has unknown target '" + target + "', dropped");
                continue;
            }
            boolean confirm = !(raw.get("confirm") instanceof Boolean) || (Boolean) raw.get("confirm");
            out.add(new Template(id, label, command, target, confirm));
        }
        return out;
    }
}
