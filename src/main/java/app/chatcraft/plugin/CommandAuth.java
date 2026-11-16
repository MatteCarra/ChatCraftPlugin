package app.chatcraft.plugin;

/**
 * Which permission a /chatcraft sub-command needs. Stage 1 put a command-level permission
 * in plugin.yml (Bukkit checks it before any executor runs); stage 2 removes that and each
 * sub-command checks its own permission here.
 */
public final class CommandAuth {

    public static final String STAFF = "chatcraft.staff";
    public static final String ADMIN = "chatcraft.admin";

    private CommandAuth() {
    }

    /** The permission the sub-command described by [args] needs. */
    public static String required(String[] args) {
        if (args.length == 0) return STAFF;
        String sub = args[0].toLowerCase();
        if (sub.equals("staff")) {
            return (args.length >= 2 && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("list"))) ? ADMIN : STAFF;
        }
        // verify, status, alert and anything unknown: admin
        return ADMIN;
    }
}
