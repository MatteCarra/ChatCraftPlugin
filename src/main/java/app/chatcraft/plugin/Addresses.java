package app.chatcraft.plugin;

import java.util.Locale;

/**
 * Address normalisation, mirroring the backend rule (task B1):
 * lowercase host, strip one trailing dot, default port 25565, key = "host:port".
 */
public final class Addresses {

    public static final int DEFAULT_PORT = 25565;

    private Addresses() {
    }

    /** Normalised "host:port" key for the given host and port. */
    public static String key(String host, int port) {
        String h = normaliseHost(host);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return h + ":" + port;
    }

    /** Normalised "host:port" key for an address string of the form "host[:port]". */
    public static String key(String address) {
        Address a = parse(address);
        return key(a.host, a.port);
    }

    /** Parses "host[:port]" (default port 25565) into a normalised host and port. */
    public static Address parse(String address) {
        if (address == null) {
            throw new IllegalArgumentException("address is null");
        }
        String s = address.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("address is blank");
        }
        String host;
        int port;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            host = s.substring(0, colon);
            String p = s.substring(colon + 1);
            try {
                port = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid port in address: " + address);
            }
        } else {
            host = s;
            port = DEFAULT_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range in address: " + address);
        }
        return new Address(normaliseHost(host), port);
    }

    private static String normaliseHost(String host) {
        if (host == null) {
            throw new IllegalArgumentException("host is null");
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty()) {
            throw new IllegalArgumentException("host is blank");
        }
        return h;
    }

    /** A parsed, normalised address. */
    public static final class Address {
        public final String host;
        public final int port;

        public Address(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
