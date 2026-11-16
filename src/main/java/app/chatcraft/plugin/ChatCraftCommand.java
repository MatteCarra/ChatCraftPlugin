package app.chatcraft.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The {@code /chatcraft} entry point: help (also the answer to a bare {@code /chatcraft} and to an
 * unknown sub-command), the single permission check for every sub-command (see {@link CommandAuth}),
 * dispatch, and tab completion limited to what the sender may run.
 */
final class ChatCraftCommand implements CommandExecutor, TabCompleter {

    static final String DOCS_URL = "https://api.chatcraft.app/owners";

    private final String version;
    private final BooleanSupplier reportsEnabled;
    private final BooleanSupplier verified;
    private final Supplier<List<String>> staffNames;
    private final CommandExecutor verify;
    private final CommandExecutor status;
    private final CommandExecutor staff;
    private final CommandExecutor alert;

    ChatCraftCommand(String version, BooleanSupplier reportsEnabled, BooleanSupplier verified, Supplier<List<String>> staffNames,
                     CommandExecutor verify, CommandExecutor status, CommandExecutor staff, CommandExecutor alert) {
        this.version = version;
        this.reportsEnabled = reportsEnabled;
        this.verified = verified;
        this.staffNames = staffNames;
        this.verify = verify;
        this.status = status;
        this.staff = staff;
        this.alert = alert;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            help(sender);
            return true;
        }
        CommandExecutor target = target(args[0]);
        if (target == null) {
            Msg.error(sender, "Unknown command '" + args[0] + "'. Type /" + label + " help to see what you can do.");
            return true;
        }
        String perm = CommandAuth.required(args);
        if (!sender.hasPermission(perm)) {
            Msg.error(sender, "You don't have permission to use /" + label + " " + args[0].toLowerCase(Locale.ROOT)
                    + " (needs " + perm + ").");
            return true;
        }
        return target.onCommand(sender, command, label, args);
    }

    private CommandExecutor target(String sub) {
        switch (sub.toLowerCase(Locale.ROOT)) {
            case "verify": return verify;
            case "status": return status;
            case "staff": return staff;
            case "alert": return alert;
            default: return null;
        }
    }

    private void help(CommandSender sender) {
        List<HelpPage.Entry> shown = HelpPage.visible(HelpPage.entries(reportsEnabled.getAsBoolean()), sender::hasPermission);
        Msg.header(sender, "ChatCraft " + version);
        if (shown.isEmpty()) {
            Msg.info(sender, "There is nothing here for you. Ask a server operator if you think that is wrong.");
            return;
        }
        for (HelpPage.Entry e : shown) {
            Msg.command(sender, e.usage, e.suggest, e.description);
        }
        if (sender.hasPermission(CommandAuth.ADMIN) && !verified.getAsBoolean()) {
            Msg.warn(sender, "This server is not verified yet. Run /chatcraft verify to get it listed in the app.");
        }
        Msg.detail(sender, "Click a command to type it. More about the owner program:");
        Msg.link(sender, DOCS_URL);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("help");
            for (String sub : new String[]{"verify", "status", "staff", "alert"}) {
                if (sender.hasPermission(CommandAuth.required(new String[]{sub}))) options.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("staff") && sender.hasPermission(CommandAuth.ADMIN)) {
            options.add("list");
            options.add("remove");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("staff") && args[1].equalsIgnoreCase("remove")
                && sender.hasPermission(CommandAuth.ADMIN)) {
            options.addAll(staffNames.get());
        }
        return startingWith(options, args.length == 0 ? "" : args[args.length - 1]);
    }

    static List<String> startingWith(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p) && !out.contains(o)) out.add(o);
        }
        return out;
    }
}
