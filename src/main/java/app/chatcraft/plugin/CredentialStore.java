package app.chatcraft.plugin;

import java.io.IOException;

/**
 * Persistence for the owner-program credentials (affiliation id + key) that
 * the backend hands out after a successful verification. The plugin writes
 * them to {@code plugins/ChatCraft/credentials.yml}, readable by the server
 * user only — the key is a bearer secret.
 */
public interface CredentialStore {

    /** Stores the credentials, replacing any previous ones. */
    void save(int affiliationId, String key) throws IOException;

    /** Returns the stored credentials, or null when none were stored yet. */
    Credentials load() throws IOException;

    /** The bearer credentials for {@code POST /owner/v1/heartbeat}. */
    final class Credentials {
        public final int affiliationId;
        public final String key;

        public Credentials(int affiliationId, String key) {
            this.affiliationId = affiliationId;
            this.key = key;
        }
    }
}
