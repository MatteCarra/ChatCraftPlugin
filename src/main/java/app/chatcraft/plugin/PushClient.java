package app.chatcraft.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sends encrypted staff-alert pushes through the relay's signed route
 * {@code POST /v1/push} (see the relay's ServerPush.kt). One payload is encrypted per
 * device with that device's {@code pushKey}; devices are split into batches of at most
 * 20 items (the relay's per-request cap), each request carrying its own timestamp and
 * signature. The plugin's own budget is 100 pushes per hour across all staff (fan-out
 * counted); excess pushes are dropped with one log line and never throw.
 *
 * <p>The relay base URL defaults to {@code https://relay.chatcraft.app} and can be
 * overridden with the system property {@value #BASE_URL_PROPERTY} (tests).
 */
public class PushClient {

    public static final String BASE_URL_PROPERTY = "chatcraft.relay.base";
    public static final String DEFAULT_BASE_URL = "https://relay.chatcraft.app";

    private static final int MAX_ITEMS_PER_REQUEST = 20; // the relay's cap
    private static final int MAX_PUSHES_PER_HOUR = 100;
    static final int MAX_REPORT_PUSHES_PER_HOUR = 60;   // plugin-side budget, fan-out counted
    private static final long HOUR_MS = 3_600_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /** One enrolled device to push to. */
    public static class PushTarget {
        public final String deviceId;
        public final String platform; // "android" | "ios"
        public final String token;    // FCM registration token / APNs device token
        public final String apnsEnv;  // "production" | "sandbox" | null
        public final String pushKey;  // base64 32 bytes, made by the app at enrolment

        public PushTarget(String deviceId, String platform, String token, String apnsEnv, String pushKey) {
            this.deviceId = deviceId;
            this.platform = platform;
            this.token = token;
            this.apnsEnv = apnsEnv;
            this.pushKey = pushKey;
        }
    }

    public interface PushTransport {
        PushResponse post(String url, String jsonBody) throws IOException;
    }

    public static class PushResponse {
        public final int status;
        public final String body;

        public PushResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    public static class PushResult {
        public final int sent;
        public final int dropped;

        public PushResult(int sent, int dropped) {
            this.sent = sent;
            this.dropped = dropped;
        }
    }

    private final PushTransport transport;
    private final PushKeyStore keys;
    private final Clock clock;
    private final Logger logger;
    private final String baseUrl;

    private long windowStart = -1;
    private int windowCount = 0;
    private int reportCount = 0;
    private boolean sslWarned = false;

    public PushClient(PushTransport transport, PushKeyStore keys, Clock clock) {
        this(transport, keys, clock, Logger.getLogger("ChatCraft"),
                System.getProperty(BASE_URL_PROPERTY, DEFAULT_BASE_URL));
    }

    public PushClient(PushTransport transport, PushKeyStore keys, Clock clock, Logger logger, String baseUrl) {
        this.transport = transport;
        this.keys = keys;
        this.clock = clock;
        this.logger = logger;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Encrypts {@code payload} per device and posts the batches. Never throws for
     * transport/limit failures; returns what was sent and what was dropped.
     */
    public synchronized PushResult send(List<PushTarget> devices, JsonObject payload) {
        return send(devices, payload, false);
    }

    /**
     * [report] pushes (players' /report) may use at most {@link #MAX_REPORT_PUSHES_PER_HOUR} of the hourly
     * budget, so console alerts always have room. A batch that only partly fits is sent in part, not dropped whole.
     */
    public synchronized PushResult send(List<PushTarget> devices, JsonObject payload, boolean report) {
        int sent = 0;
        int dropped = 0;
        for (int from = 0; from < devices.size(); from += MAX_ITEMS_PER_REQUEST) {
            List<PushTarget> batch = devices.subList(from, Math.min(from + MAX_ITEMS_PER_REQUEST, devices.size()));
            int allowed = admit(batch.size(), report);
            if (allowed < batch.size()) {
                dropped += batch.size() - allowed;
                logger.warning("[ChatCraft] push budget of " + MAX_PUSHES_PER_HOUR + "/hour reached, dropping "
                        + (batch.size() - allowed) + " push(es) (in-session delivery is unaffected)");
            }
            if (allowed == 0) continue;
            batch = batch.subList(0, allowed);
            try {
                sent += postBatch(batch, payload);
            } catch (SSLHandshakeException e) {
                // old Java 8 builds may not trust the relay's certificate chain
                if (!sslWarned) {
                    sslWarned = true;
                    logger.warning("[ChatCraft] TLS handshake with the relay failed (" + e.getMessage()
                            + ") - update the server's Java to trust the relay certificate; pushes are skipped until then");
                }
                dropped += batch.size();
            } catch (IOException e) {
                logger.warning("[ChatCraft] push failed: " + e.getMessage());
                dropped += batch.size();
            }
        }
        return new PushResult(sent, dropped);
    }

    /** How many of [n] pushes the hourly budget admits (fixed window); commits that many. */
    private int admit(int n, boolean report) {
        long now = clock.millis();
        if (windowStart < 0 || now - windowStart >= HOUR_MS) {
            windowStart = now;
            windowCount = 0;
            reportCount = 0;
        }
        int room = MAX_PUSHES_PER_HOUR - windowCount;
        if (report) room = Math.min(room, MAX_REPORT_PUSHES_PER_HOUR - reportCount);
        int allowed = Math.max(0, Math.min(n, room));
        windowCount += allowed;
        if (report) reportCount += allowed;
        return allowed;
    }

    private int postBatch(List<PushTarget> batch, JsonObject payload) throws IOException {
        JsonArray items = new JsonArray();
        for (PushTarget d : batch) {
            JsonObject envelope = PushCrypto.encrypt(d.pushKey, d.deviceId, payload);
            JsonObject item = new JsonObject();
            item.addProperty("platform", d.platform);
            item.addProperty("token", d.token);
            if (d.apnsEnv != null) {
                item.addProperty("apnsEnv", d.apnsEnv);
            }
            item.addProperty("payload", envelope.toString());
            items.add(item);
        }
        String itemsJson = items.toString();
        String ts = Long.toString(clock.millis());
        String signed = "push\n" + ts + "\n" + itemsJson;
        String sig = sign(signed);
        JsonObject body = new JsonObject();
        body.addProperty("spki", Base64.getEncoder().encodeToString(keys.spkiDer()));
        body.addProperty("ts", ts);
        body.addProperty("itemsJson", itemsJson);
        body.addProperty("sig", sig);
        PushResponse res = transport.post(baseUrl + "/v1/push", body.toString());
        if (res.status == 200) {
            try {
                return new JsonParser().parse(res.body).getAsJsonObject().get("sent").getAsInt();
            } catch (Exception e) {
                return batch.size();
            }
        }
        if (res.status == 429) {
            logger.warning("[ChatCraft] relay limit reached (HTTP 429); many servers behind one IP can share the budget - pushes dropped");
        }
        throw new IOException("POST /v1/push -> " + res.status);
    }

    private String sign(String data) throws IOException {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(keys.keyPair().getPrivate());
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("signing failed", e);
        }
    }

    /** {@link PushTransport} over {@link HttpURLConnection}, like the backend client. */
    public static class Http implements PushTransport {
        @Override
        public PushResponse post(String url, String jsonBody) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(payload);
                } finally {
                    os.close();
                }
                int status = conn.getResponseCode();
                InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                String text = in == null ? "" : readAll(in);
                return new PushResponse(status, text);
            } finally {
                conn.disconnect();
            }
        }

        private static String readAll(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }
}
