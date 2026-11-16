package app.chatcraft.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@link CredentialStore} backed by {@code plugins/ChatCraft/credentials.yml}.
 * The file holds exactly two scalar fields ({@code affiliationId}, {@code key});
 * the key is written as a double-quoted YAML scalar so special characters
 * survive the round trip. After every write the file is chmod'ed to
 * {@code rw-------} (owner = the server user) on POSIX filesystems; on
 * filesystems without POSIX permissions the write still happens and a warning
 * is logged once per save.
 */
public class FileCredentialStore implements CredentialStore {

    private final File file;
    private final Logger logger;

    public FileCredentialStore(File file) {
        this(file, Logger.getLogger("ChatCraft"));
    }

    public FileCredentialStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public File getFile() {
        return file;
    }

    @Override
    public synchronized void save(int affiliationId, String key) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ChatCraft owner-program credentials - bearer secret, server user only.\n");
        sb.append("affiliationId: ").append(affiliationId).append('\n');
        sb.append("key: ").append(yamlQuote(key)).append('\n');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        // Write to a temp file, restrict it, then move atomically so a crash
        // can never leave a world-readable or half-written credentials file.
        File tmp = new File(parent, file.getName() + ".tmp");
        Files.write(tmp.toPath(), bytes);
        restrictToOwner(tmp.toPath());
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        restrictToOwner(file.toPath());
    }

    @Override
    public synchronized Credentials load() throws IOException {
        if (!file.exists()) {
            return null;
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        Integer id = null;
        String key = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int colon = t.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String k = t.substring(0, colon).trim();
            String v = t.substring(colon + 1).trim();
            if ("affiliationId".equals(k)) {
                try {
                    id = Integer.valueOf(v);
                } catch (NumberFormatException ignored) {
                    // fall through: treated as missing below
                }
            } else if ("key".equals(k)) {
                key = v.startsWith("\"") ? yamlUnquote(v) : v;
            }
        }
        if (id == null || key == null) {
            return null;
        }
        return new Credentials(id, key);
    }

    private void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            logger.warning("[ChatCraft] " + path + " is on a filesystem without POSIX permissions; "
                    + "credentials.yml cannot be chmod'ed to owner-only");
        } catch (IOException e) {
            logger.warning("[ChatCraft] could not restrict permissions of " + path + ": " + e);
        }
    }

    /** Double-quoted YAML scalar. */
    private static String yamlQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** Inverse of {@link #yamlQuote}; tolerates a trailing quote or junk after the closing quote. */
    private static String yamlUnquote(String v) {
        if (v.length() < 2 || v.charAt(0) != '"') {
            return v;
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (c == '"') {
                break; // end of scalar
            }
            if (c == '\\' && i + 1 < v.length()) {
                char e = v.charAt(i + 1);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        i += 2;
                        continue;
                    case 'r':
                        sb.append('\r');
                        i += 2;
                        continue;
                    case 't':
                        sb.append('\t');
                        i += 2;
                        continue;
                    case 'u':
                        if (i + 5 < v.length()) {
                            sb.append((char) Integer.parseInt(v.substring(i + 2, i + 6), 16));
                            i += 6;
                            continue;
                        }
                        break;
                    default:
                        sb.append(e);
                        i += 2;
                        continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
