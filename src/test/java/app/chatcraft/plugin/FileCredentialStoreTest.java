package app.chatcraft.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileCredentialStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void saveThenLoadRoundTrips() throws Exception {
        File file = new File(tmp.getRoot(), "ChatCraft/credentials.yml");
        FileCredentialStore store = new FileCredentialStore(file);
        assertNull(store.load()); // absent before first save
        store.save(42, "key-with-\"quotes\"-and-\\-backslash");
        CredentialStore.Credentials c = store.load();
        assertEquals(42, c.affiliationId);
        assertEquals("key-with-\"quotes\"-and-\\-backslash", c.key);
    }

    @Test
    public void fileIsReadableByOwnerOnly() throws Exception {
        File file = new File(tmp.getRoot(), "credentials.yml");
        FileCredentialStore store = new FileCredentialStore(file);
        store.save(7, "secret");
        try {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(file.toPath()));
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem (e.g. Windows): permission bits do not apply
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        String text = join(lines);
        assertTrue(text.contains("affiliationId: 7"));
        assertTrue(text.contains("key:"));
        // the plain key must not sit unquoted in a way YAML parsers would mangle:
        // it is written as a double-quoted scalar
        assertTrue(text.contains("\"secret\""));
    }

    @Test
    public void loadIgnoresCommentsAndUnknownKeys() throws Exception {
        File file = new File(tmp.getRoot(), "credentials.yml");
        Files.write(file.toPath(), (
                "# comment\n" +
                "affiliationId: 9\n" +
                "somethingElse: x\n" +
                "key: \"abc\"\n").getBytes(StandardCharsets.UTF_8));
        CredentialStore.Credentials c = new FileCredentialStore(file).load();
        assertEquals(9, c.affiliationId);
        assertEquals("abc", c.key);
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
