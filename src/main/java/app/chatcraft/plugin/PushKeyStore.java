package app.chatcraft.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.logging.Logger;

/**
 * The server's push-signing key: an EC P-256 key pair created on first use and stored as
 * PKCS8 DER in {@code plugins/ChatCraft/push-key.der}, readable by the server user only.
 * The public half (X509/SPKI) is the {@code spki} the relay route verifies signatures against;
 * the private half signs {@code "push\n" + ts + "\n" + itemsJson}.
 */
public class PushKeyStore {

    private final File file;
    private final Logger logger;
    private KeyPair keyPair;

    public PushKeyStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** The key pair, loading or creating it on first use. The public key is stored next to the private one as X509 DER. */
    public synchronized KeyPair keyPair() throws IOException {
        if (keyPair != null) {
            return keyPair;
        }
        File pubFile = pubFile();
        if (file.exists() && pubFile.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("EC");
                java.security.PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(file.toPath())));
                java.security.PublicKey pub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath())));
                keyPair = new KeyPair(pub, priv);
                return keyPair;
            } catch (GeneralSecurityException e) {
                logger.warning("[ChatCraft] could not read " + file + " (" + e.getMessage() + "), generating a new push key");
            }
        }
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            keyPair = gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("EC key pair generation failed", e);
        }
        save(keyPair.getPrivate().getEncoded(), keyPair.getPublic().getEncoded());
        return keyPair;
    }

    /** X509/SPKI DER of the public key, as the relay's {@code spki} field expects (base64-encoded by the caller). */
    public byte[] spkiDer() throws IOException {
        return keyPair().getPublic().getEncoded();
    }

    private File pubFile() {
        return new File(file.getParentFile(), file.getName() + ".pub");
    }

    private void save(byte[] pkcs8, byte[] x509) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        writeAtomic(file, pkcs8, logger);
        writeAtomic(pubFile(), x509, logger);
    }

    /** Writes via a temp file + atomic rename, so readers never see a half-written file (shared with StaffRegistry). */
    static void writeAtomic(File target, byte[] data, Logger logger) throws IOException {
        Path tmp = new File(target.getParentFile(), target.getName() + ".tmp").toPath();
        Files.write(tmp, data);
        restrictToOwner(tmp, logger);
        try {
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        restrictToOwner(target.toPath(), logger);
    }

    /** chmod rw------- on POSIX filesystems; warns where that is not supported (shared with StaffRegistry). */
    static void restrictToOwner(Path path, Logger logger) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            logger.warning("[ChatCraft] " + path + " is on a filesystem without POSIX permissions; the file cannot be chmod'ed to owner-only");
        } catch (IOException e) {
            logger.warning("[ChatCraft] could not restrict permissions of " + path + ": " + e);
        }
    }
}
