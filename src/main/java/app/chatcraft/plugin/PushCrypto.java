package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Push payload encryption, byte-compatible with the app's
 * {@code mattecarra.chatcraft.remote.PushCrypto} (spec section 10): AES-256-GCM with the
 * device's 32-byte {@code pushKey}, random 96-bit nonce, AAD = {@code deviceId || v},
 * plaintext = 2-byte length + JSON padded to 256-byte buckets. Providers only ever see
 * the {@code {v, n, c}} envelope. (Interop is pinned by push-interop.json, decrypted by
 * the app-side PluginPushInteropTest.)
 */
public final class PushCrypto {

    public static final int VERSION = 1;
    private static final int BUCKET = 256;
    private static final int MAX_PLAINTEXT = 2048;
    private static final SecureRandom random = new SecureRandom();

    private PushCrypto() {
    }

    private static byte[] aad(String deviceId) {
        return (deviceId + VERSION).getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the {@code {v, n, c}} envelope to send through the push provider. */
    public static JsonObject encrypt(String keyB64, String deviceId, JsonObject payload) {
        byte[] json = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("push payload too large");
        }
        int paddedLen = ((2 + json.length + BUCKET - 1) / BUCKET) * BUCKET;
        byte[] padded = ByteBuffer.allocate(paddedLen).putShort((short) json.length).put(json).array();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(deviceId));
            JsonObject out = new JsonObject();
            out.addProperty("v", VERSION);
            out.addProperty("n", Base64.getEncoder().encodeToString(nonce));
            out.addProperty("c", Base64.getEncoder().encodeToString(cipher.doFinal(padded)));
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM not available", e);
        }
    }

    /** Throws if the envelope was tampered with, was made for another device, or has an unknown version. */
    public static JsonObject decrypt(String keyB64, String deviceId, JsonObject envelope) {
        if (envelope.get("v").getAsInt() != VERSION) {
            throw new IllegalArgumentException("unknown push version");
        }
        byte[] nonce = Base64.getDecoder().decode(envelope.get("n").getAsString());
        if (nonce.length != 12) {
            throw new IllegalArgumentException("bad nonce");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(deviceId));
            byte[] padded = cipher.doFinal(Base64.getDecoder().decode(envelope.get("c").getAsString()));
            int len = ByteBuffer.wrap(padded).getShort() & 0xffff;
            if (len > padded.length - 2) {
                throw new IllegalArgumentException("bad length");
            }
            return new JsonParser().parse(new String(padded, 2, len, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("decryption failed", e);
        }
    }
}
