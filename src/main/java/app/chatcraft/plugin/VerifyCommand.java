package app.chatcraft.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;

/**
 * {@code /chatcraft verify [host[:port]]} — starts the owner verification.
 * Permission {@value #PERMISSION} (default: op). With no argument it uses the
 * {@code address} from config.yml; when that is not set it tells the operator
 * how to set it. On success it prints the verification link plus a one-line
 * explanation of the next step (open the link on the phone with ChatCraft).
 * The backend call runs async so the main thread never blocks on HTTP.
 */
public class VerifyCommand implements CommandExecutor {

    public static final String PERMISSION = "chatcraft.admin";

    private final Plugin plugin;
    private final PluginConfig config;
    private final VerificationService service;

    public VerifyCommand(Plugin plugin, PluginConfig config, VerificationService service) {
        this.plugin = plugin;
        this.config = config;
        this.service = service;
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Msg.error(sender, "Run /" + label + " verify in-game as the server operator: the verification is tied to your player account.");
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
                Msg.detail(sender, "or run /" + label + " verify host[:port].");
                return true;
            }
        }
        final Player player = (Player) sender;
        Msg.info(sender, "Requesting a verification link...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    final String link = service.start(player.getUniqueId(), address.host, address.port);
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            Msg.ok(sender, "Open this link on your phone with the ChatCraft app to verify this server:");
                            Msg.link(sender, link);
                            Msg.detail(sender, "Once the app confirms it, /" + label + " status shows the server as verified.");
                        }
                    });
                } catch (final IOException e) {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            Msg.error(sender, "Could not start the verification: " + describe(e) + ".");
                        }
                    });
                }
            }
        });
        return true;
    }

    /**
     * A user-facing error description that never includes the raw exception
     * message: for the {@code /seen} route the message embeds the token in the
     * URL path, and the token must never leak into chat or logs.
     */
    private static String describe(IOException e) {
        if (e instanceof BackendClient.HttpException) {
            int status = ((BackendClient.HttpException) e).getStatus();
            if (status == 429) {
                return "ChatCraft is rate-limiting verification requests (HTTP 429) - wait a minute and try again";
            }
            return "ChatCraft answered HTTP " + status;
        }
        return "network error while contacting ChatCraft";
    }
}
