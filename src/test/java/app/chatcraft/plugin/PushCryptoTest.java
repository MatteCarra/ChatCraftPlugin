package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.Assert.*;

public class PushCryptoTest {

    private static String randomKeyB64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static JsonObject payload(String body) {
        JsonObject p = new JsonObject();
        p.addProperty("body", body);
        return p;
    }

    private static int cipherLen(JsonObject envelope) {
        return Base64.getDecoder().decode(envelope.get("c").getAsString()).length;
    }

    @Test
    public void roundTrip() {
        String key = randomKeyB64();
        JsonObject env = PushCrypto.encrypt(key, "dev1", payload("hello"));
        assertEquals(PushCrypto.VERSION, env.get("v").getAsInt());
        JsonObject dec = PushCrypto.decrypt(key, "dev1", env);
        assertEquals("hello", dec.get("body").getAsString());
    }

    @Test
    public void shortPayloadCiphertextIsOneBucketPlusTag() {
        String key = randomKeyB64();
        // {"body":"hi"} is 12 bytes: 2-byte length + 12 = 14 -> one 256-byte bucket, + 16 tag
        assertEquals(256 + 16, cipherLen(PushCrypto.encrypt(key, "dev1", payload("hi"))));
    }

    @Test
    public void payloadOverOneBucketCrossesToTwoBuckets() {
        String key = randomKeyB64();
        String big = new String(new char[300]).replace('\0', 'x'); // 300 bytes
        // 2 + 300 = 302 -> two 256-byte buckets = 512, + 16 tag
        assertEquals(512 + 16, cipherLen(PushCrypto.encrypt(key, "dev1", payload(big))));
    }

    @Test
    public void tamperedCiphertextFails() {
        String key = randomKeyB64();
        JsonObject env = PushCrypto.encrypt(key, "dev1", payload("hi"));
        byte[] c = Base64.getDecoder().decode(env.get("c").getAsString());
        c[0] ^= 1;
        env.addProperty("c", Base64.getEncoder().encodeToString(c));
        try {
            PushCrypto.decrypt(key, "dev1", env);
            fail("tampered ciphertext must not decrypt");
        } catch (Exception expected) {
        }
    }

    @Test
    public void wrongDeviceIdFails() {
        String key = randomKeyB64();
        JsonObject env = PushCrypto.encrypt(key, "dev1", payload("hi"));
        try {
            PushCrypto.decrypt(key, "dev2", env);
            fail("wrong device id must fail (AAD)");
        } catch (Exception expected) {
        }
    }

    @Test
    public void wrongKeyFails() {
        JsonObject env = PushCrypto.encrypt(randomKeyB64(), "dev1", payload("hi"));
        try {
            PushCrypto.decrypt(randomKeyB64(), "dev1", env);
            fail("wrong key must fail");
        } catch (Exception expected) {
        }
    }

    @Test
    public void nonceDiffersEachCall() {
        String key = randomKeyB64();
        JsonObject a = PushCrypto.encrypt(key, "dev1", payload("hi"));
        JsonObject b = PushCrypto.encrypt(key, "dev1", payload("hi"));
        assertNotEquals(a.get("n").getAsString(), b.get("n").getAsString());
    }

    /** Writes the interop fixture the app-side test (remote/PluginPushInteropTest) decrypts with the real PushCrypto. */
    @Test
    public void writesInteropFixture() throws Exception {
        String key = randomKeyB64();
        String deviceId = "interop-device-1";
        JsonObject payload = new JsonObject();
        payload.addProperty("title", "Test server");
        payload.addProperty("body", "Player Steve was reported");
        payload.addProperty("kind", "staffAlert");
        payload.addProperty("aid", "0123456789abcdef");
        payload.addProperty("host", "play.example.com");
        payload.addProperty("port", 25565);
        payload.addProperty("ts", 1700000000L);
        JsonObject envelope = PushCrypto.encrypt(key, deviceId, payload);
        JsonObject fixture = new JsonObject();
        fixture.addProperty("key", key);
        fixture.addProperty("deviceId", deviceId);
        fixture.add("envelope", envelope);
        fixture.add("payload", payload);
        File out = new File("src/test/resources/push-interop.json");
        out.getParentFile().mkdirs();
        FileWriter w = new FileWriter(out);
        try {
            w.write(fixture.toString());
        } finally {
            w.close();
        }
        assertTrue(out.length() > 0);
        // and it round-trips
        assertEquals("staffAlert", PushCrypto.decrypt(key, deviceId, envelope).get("kind").getAsString());
    }
}
