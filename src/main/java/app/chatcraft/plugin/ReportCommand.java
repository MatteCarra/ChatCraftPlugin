package app.chatcraft.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /report <player> [reason]} — any player, only when {@code reports: true}, one per
 * {@code report-cooldown-seconds} per player. The target name must match the placeholder
 * pattern (a reporter cannot make a staff tap run {@code kick @a} or {@code kick Steve 0 -s}).
 */
public class ReportCommand implements CommandExecutor {

    private final AlertService alerts;

    public ReportCommand(AlertService alerts) {
        this.alerts = alerts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Msg.error(sender, "Only players can use /" + label + ".");
            return true;
        }
        if (args.length < 1) {
            Msg.warn(sender, "Usage: /" + label + " <player> [reason]");
            return true;
        }
        String target = args[0];
        StringBuilder reason = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (reason.length() > 0) reason.append(' ');
            reason.append(args[i]);
        }
        Player p = (Player) sender;
        switch (alerts.tryReport(p.getUniqueId(), target, reason.toString(), p.getName())) {
            case ACCEPTED:
                Msg.ok(sender, "Thanks, " + target + " has been reported. The server staff have been alerted.");
                break;
            case DISABLED:
                Msg.error(sender, "Reports are turned off on this server.");
                break;
            case COOLDOWN:
                Msg.warn(sender, "Please wait a moment before sending another report.");
                break;
            case BAD_NAME:
                Msg.error(sender, "'" + target + "' is not a valid player name.");
                break;
        }
        return true;
    }
}
