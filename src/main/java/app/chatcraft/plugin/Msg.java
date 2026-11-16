package app.chatcraft.plugin;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The one place that decides how the plugin talks to a sender: a coloured {@code [ChatCraft]}
 * prefix, green for success, red for errors, yellow for hints, gray for secondary detail, and
 * clickable links/commands for players (plain text for the console and on servers without the
 * Spigot chat API). Nothing here knows about the plugin's features.
 */
final class Msg {

    static final String PREFIX = ChatColor.GOLD + "[" + ChatColor.AQUA + "ChatCraft" + ChatColor.GOLD + "] " + ChatColor.RESET;

    private Msg() {
    }

    static void ok(CommandSender to, String text) {
        to.sendMessage(PREFIX + ChatColor.GREEN + text);
    }

    static void error(CommandSender to, String text) {
        to.sendMessage(PREFIX + ChatColor.RED + text);
    }

    static void warn(CommandSender to, String text) {
        to.sendMessage(PREFIX + ChatColor.YELLOW + text);
    }

    static void info(CommandSender to, String text) {
        to.sendMessage(PREFIX + ChatColor.WHITE + text);
    }

    /** A secondary line under a message (no prefix, indented, gray). */
    static void detail(CommandSender to, String text) {
        to.sendMessage("  " + ChatColor.GRAY + text);
    }

    /** A section title: {@code ---- Title ----} with a gold rule. */
    static void header(CommandSender to, String title) {
        to.sendMessage(ChatColor.GOLD + "------- " + ChatColor.AQUA + ChatColor.BOLD + title + ChatColor.RESET + ChatColor.GOLD + " -------");
    }

    /** A "label: value" row of a status list. */
    static void row(CommandSender to, String label, String value) {
        to.sendMessage("  " + ChatColor.GRAY + label + ": " + value);
    }

    static String good(String text) {
        return ChatColor.GREEN + text + ChatColor.GRAY;
    }

    static String bad(String text) {
        return ChatColor.RED + text + ChatColor.GRAY;
    }

    static String unknown(String text) {
        return ChatColor.YELLOW + text + ChatColor.GRAY;
    }

    /** A URL on its own line: underlined, and clickable for players (opens the browser). */
    static void link(CommandSender to, String url) {
        String shown = "  " + ChatColor.AQUA + ChatColor.UNDERLINE + url;
        if (!sendClickable(to, shown, ClickEvent.Action.OPEN_URL, url, "Click to open")) {
            to.sendMessage(shown);
        }
    }

    /** A help row: the command is highlighted and clicking it types it into the chat bar. */
    static void command(CommandSender to, String usage, String suggest, String description) {
        String shown = " " + ChatColor.YELLOW + usage + ChatColor.GRAY + " - " + ChatColor.WHITE + description;
        if (!sendClickable(to, shown, ClickEvent.Action.SUGGEST_COMMAND, suggest, "Click to type " + suggest.trim())) {
            to.sendMessage(shown);
        }
    }

    private static boolean sendClickable(CommandSender to, String legacyText, ClickEvent.Action action, String value, String hover) {
        if (!(to instanceof Player)) return false;
        try {
            BaseComponent[] parts = TextComponent.fromLegacyText(legacyText);
            for (BaseComponent part : parts) {
                part.setClickEvent(new ClickEvent(action, value));
                part.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(ChatColor.GRAY + hover)));
            }
            ((Player) to).spigot().sendMessage(parts);
            return true;
        } catch (Throwable unsupported) { // CraftBukkit without the Spigot chat API: fall back to plain text
            return false;
        }
    }
}
