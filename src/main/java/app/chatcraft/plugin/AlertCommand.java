package app.chatcraft.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * {@code /chatcraft alert <text>} — console/admin command (permission {@code chatcraft.admin},
 * checked via [CommandAuth]) raising an alert to all enrolled staff devices. Runs even when
 * {@code reports: false} (that flag only disables the players' /report).
 */
public class AlertCommand implements CommandExecutor {

    private final AlertService alerts;

    public AlertCommand(AlertService alerts) {
        this.alerts = alerts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            Msg.warn(sender, "Usage: /" + label + " alert <text>");
            Msg.detail(sender, "Sends the text to every phone enrolled with /" + label + " staff.");
            return true;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (text.length() > 0) text.append(' ');
            text.append(args[i]);
        }
        Alert a = alerts.create("console", sender.getName(), text.toString(), "");
        alerts.raise(a);
        Msg.ok(sender, "Alert sent to the enrolled staff phones.");
        return true;
    }
}
