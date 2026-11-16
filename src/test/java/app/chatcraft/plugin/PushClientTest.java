package app.chatcraft.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class PushClientTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static class RecordingTransport implements PushClient.PushTransport {
        final List<String> bodies = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        int status = 200;
        String responseBody = "{\"sent\":0}";
        IOException toThrow;

        @Override
        public PushClient.PushResponse post(String url, String jsonBody) throws IOException {
            urls.add(url);
            bodies.add(jsonBody);
            if (toThrow != null) throw toThrow;
            if (status == 200) {
                JsonObject req = new JsonParser().parse(jsonBody).getAsJsonObject();
                int n = new JsonParser().parse(req.get("itemsJson").getAsString()).getAsJsonArray().size();
                return new PushClient.PushResponse(200, "{\"sent\":" + n + "}");
            }
            return new PushClient.PushResponse(status, responseBody);
        }
    }

    private static List<PushClient.PushTarget> devices(int n) {
        List<PushClient.PushTarget> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new PushClient.PushTarget("dev" + i, "android", "token" + i, null, randomKey()));
        }
        return out;
    }

    private static String randomKey() {
        byte[] k = new byte[32];
        new java.security.SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private PushKeyStore keyStore() throws IOException {
        return new PushKeyStore(new File(tmp.getRoot(), "push-key.der"), Logger.getLogger("test"));
    }

    private static void verifySignature(PushKeyStore ks, String body) throws Exception {
        JsonObject req = new JsonParser().parse(body).getAsJsonObject();
        assertTrue(req.has("spki"));
        assertTrue(req.has("ts"));
        assertTrue(req.has("itemsJson"));
        assertTrue(req.has("sig"));
        String signed = "push\n" + req.get("ts").getAsString() + "\n" + req.get("itemsJson").getAsString();
        java.security.PublicKey pub = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(req.get("spki").getAsString())));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(pub);
        sig.update(signed.getBytes(StandardCharsets.UTF_8));
        assertTrue("signature must verify", sig.verify(Base64.getDecoder().decode(req.get("sig").getAsString())));
    }

    @Test
    public void twentyFiveDevicesGiveTwoRequestsOf20And5AndBothSignaturesVerify() throws Exception {
        RecordingTransport t = new RecordingTransport();
        PushKeyStore ks = keyStore();
        PushClient client = new PushClient(t, ks, Clock.systemUTC());
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "staffAlert");
        PushClient.PushResult r = client.send(devices(25), payload);
        assertEquals(2, t.bodies.size());
        JsonArray first = new JsonParser().parse(new JsonParser().parse(t.bodies.get(0)).getAsJsonObject().get("itemsJson").getAsString()).getAsJsonArray();
        JsonArray second = new JsonParser().parse(new JsonParser().parse(t.bodies.get(1)).getAsJsonObject().get("itemsJson").getAsString()).getAsJsonArray();
        assertEquals(20, first.size());
        assertEquals(5, second.size());
        for (int i = 0; i < t.bodies.size(); i++) {
            verifySignature(ks, t.bodies.get(i));
            assertEquals("https://relay.chatcraft.app/v1/push", t.urls.get(i));
        }
        assertEquals(25, r.sent);
        assertEquals(0, r.dropped);
    }

    @Test
    public void hundredPerHourBudgetDropsThe101st() throws Exception {
        RecordingTransport t = new RecordingTransport();
        PushClient client = new PushClient(t, keyStore(), Clock.systemUTC());
        JsonObject payload = new JsonObject();
        PushClient.PushResult r = client.send(devices(101), payload);
        assertEquals(100, r.sent);
        assertEquals(1, r.dropped);
        assertEquals(5, t.bodies.size()); // 5 batches of 20, no 6th request
    }

    @Test
    public void transportIOExceptionIsSwallowedWithOneLogLine() throws Exception {
        RecordingTransport t = new RecordingTransport();
        t.toThrow = new IOException("boom");
        PushClient client = new PushClient(t, keyStore(), Clock.systemUTC());
        PushClient.PushResult r = client.send(devices(2), new JsonObject());
        assertEquals(0, r.sent);
        assertEquals(2, r.dropped);
    }

    @Test
    public void http429IsLoggedWithItsOwnLineAndNotThrown() throws Exception {
        RecordingTransport t = new RecordingTransport();
        t.status = 429;
        t.responseBody = "{\"error\":\"rate limited\"}";
        PushClient client = new PushClient(t, keyStore(), Clock.systemUTC());
        PushClient.PushResult r = client.send(devices(2), new JsonObject()); // must not throw
        assertEquals(0, r.sent);
        assertEquals(2, r.dropped);
    }

    @Test
    public void sslHandshakeExceptionIsLoggedOnceAndNeverThrown() throws Exception {
        RecordingTransport t = new RecordingTransport();
        t.toThrow = new javax.net.ssl.SSLHandshakeException("PKIX path building failed");
        PushClient client = new PushClient(t, keyStore(), Clock.systemUTC());
        client.send(devices(2), new JsonObject());
        client.send(devices(2), new JsonObject());
        assertEquals(2, t.bodies.size()); // both attempts made, nothing thrown
    }

    @Test
    public void aBatchThatOnlyPartlyFitsIsSentInPartNotDroppedWhole() throws Exception {
        RecordingTransport t = new RecordingTransport();
        PushClient client = new PushClient(t, keyStore(), Clock.systemUTC());
        assertEquals(85, client.send(devices(85), new JsonObject()).sent);
        PushClient.PushResult r = client.send(devices(20), new JsonObject()); // 15 left
        assertEquals(15, r.sent);
        assertEquals(5, r.dropped);
    }

    @Test
    public void reportPushesStopAtSixtyAndConsoleAlertsKeepTheRest() throws Exception {
        RecordingTransport t = new RecordingTransport();
        PushClient client = new PushClient(t, keyStore(), Clock.systemUTC());
        PushClient.PushResult flood = client.send(devices(80), new JsonObject(), true);
        assertEquals(60, flood.sent);
        assertEquals(20, flood.dropped);
        assertEquals(0, client.send(devices(1), new JsonObject(), true).sent); // reports stay capped
        assertEquals(40, client.send(devices(40), new JsonObject(), false).sent); // console still has its 40
        assertEquals(0, client.send(devices(1), new JsonObject(), false).sent); // the 101st is dropped
    }
}
