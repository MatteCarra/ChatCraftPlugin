package app.chatcraft.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Typed view of config.yml. Every invalid value falls back to its default
 * with a console warning, so a broken config never breaks the plugin.
 */
public class PluginConfig {

    /** Minimal read-only view of the plugin configuration (Bukkit's FileConfiguration in production). */
    public interface ConfigSource {
        Object get(String path);
    }

    public static final int MAX_VOTE_LINKS = 3;

    private final boolean listing;
    private final List<String> languages;
    private final Addresses.Address address;
    private final List<String> voteLinks;

    public PluginConfig(ConfigSource source, Logger logger) {
        this.listing = readListing(source, logger);
        this.languages = readLanguages(source, logger);
        this.address = readAddress(source, logger);
        this.voteLinks = readVoteLinks(source, logger);
    }

    public boolean isListing() {
        return listing;
    }

    /** Languages as lowercase primary subtags (pt-BR -> pt), deduplicated, order preserved. */
    public List<String> getLanguages() {
        return languages;
    }

    /** Configured address, or null when absent/invalid. */
    public Addresses.Address getAddress() {
        return address;
    }

    /** Https vote links, at most {@link #MAX_VOTE_LINKS}. */
    public List<String> getVoteLinks() {
        return voteLinks;
    }

    private static boolean readListing(ConfigSource source, Logger logger) {
        Object v = source.get("listing");
        if (v == null) {
            return true;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        logger.warning("[ChatCraft] config 'listing' must be true or false, got '" + v + "' - using default true");
        return true;
    }

    private static List<String> readLanguages(ConfigSource source, Logger logger) {
        Object v = source.get("languages");
        if (v == null) {
            return Collections.emptyList();
        }
        if (!(v instanceof List)) {
            logger.warning("[ChatCraft] config 'languages' must be a list - using default (empty)");
            return Collections.emptyList();
        }
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (Object item : (List<?>) v) {
            String lang = primarySubtag(item instanceof String ? (String) item : null);
            if (lang == null) {
                logger.warning("[ChatCraft] ignoring invalid language '" + item + "'");
                continue;
            }
            out.add(lang);
        }
        return Collections.unmodifiableList(new ArrayList<String>(out));
    }

    /** Lowercase primary subtag of a language tag (pt-BR -> pt), or null when invalid. */
    static String primarySubtag(String language) {
        if (language == null) {
            return null;
        }
        String s = language.trim();
        int cut = s.length();
        int dash = s.indexOf('-');
        int underscore = s.indexOf('_');
        if (dash >= 0) {
            cut = Math.min(cut, dash);
        }
        if (underscore >= 0) {
            cut = Math.min(cut, underscore);
        }
        String primary = (cut < s.length() ? s.substring(0, cut) : s).toLowerCase(Locale.ROOT);
        return primary.matches("[a-z]{2,8}") ? primary : null;
    }

    private static Addresses.Address readAddress(ConfigSource source, Logger logger) {
        Object v = source.get("address");
        if (v == null) {
            return null;
        }
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            if (v instanceof String) {
                return null; // blank address = not configured
            }
            logger.warning("[ChatCraft] config 'address' must be a string \"host[:port]\" - ignoring");
            return null;
        }
        try {
            return Addresses.parse((String) v);
        } catch (IllegalArgumentException e) {
            logger.warning("[ChatCraft] invalid config 'address' '" + v + "' (" + e.getMessage() + ") - ignoring");
            return null;
        }
    }

    private static List<String> readVoteLinks(ConfigSource source, Logger logger) {
        Object v = source.get("vote-links");
        if (v == null) {
            return Collections.emptyList();
        }
        if (!(v instanceof List)) {
            logger.warning("[ChatCraft] config 'vote-links' must be a list - using default (empty)");
            return Collections.emptyList();
        }
        List<String> valid = new ArrayList<String>();
        for (Object item : (List<?>) v) {
            if (!(item instanceof String) || !isHttpsUrl((String) item)) {
                logger.warning("[ChatCraft] ignoring vote link '" + item + "' (only https URLs are allowed)");
                continue;
            }
            valid.add((String) item);
        }
        if (valid.size() > MAX_VOTE_LINKS) {
            logger.warning("[ChatCraft] config 'vote-links' has " + valid.size()
                    + " valid links - keeping only the first " + MAX_VOTE_LINKS);
            valid = new ArrayList<String>(valid.subList(0, MAX_VOTE_LINKS));
        }
        return Collections.unmodifiableList(valid);
    }

    private static boolean isHttpsUrl(String s) {
        try {
            URL u = new URL(s);
            return "https".equals(u.getProtocol()) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (MalformedURLException e) {
            return false;
        }
    }
}
