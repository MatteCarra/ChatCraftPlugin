package app.chatcraft.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * {@code /chatcraft status} — prints whether this server is verified, whether it
 * is currently listed, and the last heartbeat result. Owner-facing diagnostic;
 * never prints the key or any credential. The permission is checked by {@link ChatCraftCommand}.
 */
public class StatusCommand implements CommandExecutor {

    private final HeartbeatTask heartbeat;

    public StatusCommand(HeartbeatTask heartbeat) {
        this.heartbeat = heartbeat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Msg.header(sender, "ChatCraft status");
        boolean verified = heartbeat.isVerified();
        Msg.row(sender, "Verified", verified ? Msg.good("yes") : Msg.bad("no"));
        BackendClient.HeartbeatResult last = heartbeat.getLastResult();
        if (last != null) {
            Msg.row(sender, "Listed in the app", last.listed ? Msg.good("yes") : Msg.bad("no")
                    + (last.disabled ? " (disabled by ChatCraft)" : ""));
        } else {
            Msg.row(sender, "Listed in the app", Msg.unknown("unknown") + " (no heartbeat answer yet)");
        }
        if (heartbeat.getLastRunAtMillis() > 0) {
            String outcome;
            if (heartbeat.getLastError() != null) {
                outcome = Msg.bad("failed") + " (" + heartbeat.getLastError() + ")";
            } else if (last != null) {
                outcome = Msg.good("ok");
            } else {
                outcome = "skipped (not verified)";
            }
            Msg.row(sender, "Last heartbeat", outcome);
        } else {
            Msg.row(sender, "Last heartbeat", Msg.unknown("not sent yet"));
        }
        if (!verified) {
            Msg.warn(sender, "Run /" + label + " verify to get this server listed in the ChatCraft app.");
        }
        return true;
    }
}
