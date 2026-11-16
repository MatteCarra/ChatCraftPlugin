package app.chatcraft.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Clock;

/**
 * {@code /chatcraft staff [host[:port]]} (permission {@code chatcraft.staff}) prints the
 * enrolment link and the next step; {@code /chatcraft staff remove <name>} (permission
 * {@code chatcraft.admin}) removes a staff member's devices by their last known name.
 * The address comes from the argument, else from the config {@code address}; with neither
 * the command refuses with a clear message.
 */
public class StaffCommand implements CommandExecutor {

    private final Plugin plugin;
    private final PluginConfig config;
    private final StaffService service;
    private final Clock clock;

    public StaffCommand(Plugin plugin, PluginConfig config, StaffService service, Clock clock) {
        this.plugin = plugin;
        this.config = config;
        this.service = service;
        this.clock = clock;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
            if (args.length < 3) {
                Msg.warn(sender, "Usage: /" + label + " staff remove <name>");
                return true;
            }
            int removed = service.registry().removeByName(args[2]);
            if (removed == 0) {
                Msg.warn(sender, "No enrolled phone found for '" + args[2] + "'. See /" + label + " staff list.");
            } else {
                Msg.ok(sender, "Removed " + removed + (removed == 1 ? " phone" : " phones") + " of " + args[2] + ".");
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            Msg.error(sender, "Run /" + label + " staff in-game: the enrolment is tied to your player account.");
            return true;
        }
        final Addresses.Address address;
        if (args.length >= 2) {
            try {
                address = Addresses.parse(args[1]);
            } catch (IllegalArgumentException e) {
                Msg.error(sender, "'" + args[1] + "' is not a valid address (" + e.getMessage() + "). Use host or host:port.");
                return true;
            }
        } else {
            address = config.getAddress();
            if (address == null) {
                Msg.warn(sender, "This server's address is not set. Add 'address: host[:port]' to plugins/ChatCraft/config.yml,");
                Msg.detail(sender, "or run /" + label + " staff host[:port].");
                return true;
            }
        }
        final Player player = (Player) sender;
        final String link = service.startEnrolment(player.getUniqueId(), address.host, address.port);
        Msg.ok(sender, "Open this link on the phone you want to enrol for staff alerts:");
        Msg.link(sender, link);
        Msg.detail(sender, "The phone needs the ChatCraft app. The link works once and expires in 15 minutes.");
        return true;
    }

    private void list(CommandSender sender) {
        java.util.Map<String, java.util.List<StaffRegistry.Device>> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (StaffRegistry.Device d : service.registry().all()) {
            String name = d.name == null || d.name.isEmpty() ? "(unknown)" : d.name;
            byName.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(d);
        }
        Msg.header(sender, "Staff phones");
        if (byName.isEmpty()) {
            Msg.info(sender, "No phone is enrolled yet. Staff run /chatcraft staff to enrol one.");
            return;
        }
        long now = clock.instant().getEpochSecond();
        for (java.util.Map.Entry<String, java.util.List<StaffRegistry.Device>> e : byName.entrySet()) {
            StringBuilder phones = new StringBuilder();
            for (StaffRegistry.Device d : e.getValue()) {
                if (phones.length() > 0) phones.append(", ");
                phones.append(d.platform == null ? "phone" : d.platform).append(" (seen ").append(ago(now - d.lastSeen)).append(")");
            }
            Msg.row(sender, e.getKey(), org.bukkit.ChatColor.WHITE.toString() + phones);
        }
        Msg.detail(sender, "Remove one with /chatcraft staff remove <name>.");
    }

    static String ago(long seconds) {
        if (seconds < 120) return "just now";
        if (seconds < 2 * 3600) return (seconds / 60) + " min ago";
        if (seconds < 2 * 86400) return (seconds / 3600) + " h ago";
        return (seconds / 86400) + " days ago";
    }
}
