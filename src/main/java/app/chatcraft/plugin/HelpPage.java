package app.chatcraft.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What {@code /chatcraft help} lists. Pure data so it is unit-tested: every entry carries the
 * permission it needs (null = everyone), and a sender only sees the entries they may run.
 */
final class HelpPage {

    static final class Entry {
        final String usage;       // shown: "/chatcraft verify [host[:port]]"
        final String suggest;     // put in the chat bar when clicked
        final String description;
        final String permission;  // null = everyone

        Entry(String usage, String suggest, String description, String permission) {
            this.usage = usage;
            this.suggest = suggest;
            this.description = description;
            this.permission = permission;
        }
    }

    private HelpPage() {
    }

    static List<Entry> entries(boolean reportsEnabled) {
        List<Entry> all = new ArrayList<>();
        all.add(new Entry("/chatcraft verify [host[:port]]", "/chatcraft verify ",
                "Prove you own this server to get it listed in the ChatCraft app", CommandAuth.ADMIN));
        all.add(new Entry("/chatcraft status", "/chatcraft status",
                "Show whether the server is verified and listed", CommandAuth.ADMIN));
        all.add(new Entry("/chatcraft staff [host[:port]]", "/chatcraft staff",
                "Get a link to enrol your phone for staff alerts", CommandAuth.STAFF));
        all.add(new Entry("/chatcraft staff list", "/chatcraft staff list",
                "List the phones enrolled for staff alerts", CommandAuth.ADMIN));
        all.add(new Entry("/chatcraft staff remove <name>", "/chatcraft staff remove ",
                "Remove a staff member's enrolled phones", CommandAuth.ADMIN));
        all.add(new Entry("/chatcraft alert <text>", "/chatcraft alert ",
                "Send an alert to every enrolled staff phone", CommandAuth.ADMIN));
        if (reportsEnabled) {
            all.add(new Entry("/report <player> [reason]", "/report ",
                    "Report a player to the server staff", null));
        }
        return all;
    }

    static List<Entry> visible(List<Entry> all, Predicate<String> hasPermission) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.permission == null || hasPermission.test(e.permission)) out.add(e);
        }
        return out;
    }
}
