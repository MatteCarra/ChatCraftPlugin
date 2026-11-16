package app.chatcraft.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client for the ChatCraft backend owner endpoints (/owner/v1/...).
 * Sends only the fields of the Contracts table — no player data.
 */
public interface BackendClient {

    /** POST /owner/v1/verify with {host, port, operatorUuid}. */
    RegisterResult register(String host, int port, UUID op) throws IOException;

    /** POST /owner/v1/verify/:token/seen with {operatorUuid}. */
    SeenResult seen(String token, UUID op) throws IOException;

    /** POST /owner/v1/heartbeat with Authorization: Bearer id:key. */
    HeartbeatResult heartbeat(int id, String key, HeartbeatBody body) throws IOException;

    class RegisterResult {
        public String token;
        public long expiresAt;
        public String link;
    }

    class SeenResult {
        public int affiliationId;
        public String key;
    }

    class HeartbeatResult {
        public boolean listed;
        public boolean disabled;
    }

    /** Body of POST /owner/v1/heartbeat — exactly the allowed fields, nothing else. */
    class HeartbeatBody {
        public String name;
        public List<String> languages = new ArrayList<String>();
        public boolean listing;
        public boolean premium;
        public String gameVersion;
        public String pluginVersion;
        public List<String> voteLinks = new ArrayList<String>();

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            JsonArray langs = new JsonArray();
            for (String l : languages) {
                langs.add(new JsonPrimitive(l));
            }
            o.add("languages", langs);
            o.addProperty("listing", listing);
            o.addProperty("premium", premium);
            o.addProperty("gameVersion", gameVersion);
            o.addProperty("pluginVersion", pluginVersion);
            JsonArray links = new JsonArray();
            for (String v : voteLinks) {
                links.add(new JsonPrimitive(v));
            }
            o.add("voteLinks", links);
            return o;
        }
    }

    /** A non-2xx response from the backend. */
    class HttpException extends IOException {
        private final int status;

        public HttpException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * HTTPS implementation over HttpURLConnection, JSON via the Gson that
     * Spigot ships (com.google.gson, bundled in the 1.8.8 server jar).
     * Base URL defaults to https://api.chatcraft.app and can be overridden
     * with the system property {@value #BASE_URL_PROPERTY} (used by tests).
     */
    class Https implements BackendClient {
        public static final String BASE_URL_PROPERTY = "chatcraft.api.base";
        public static final String DEFAULT_BASE_URL = "https://api.chatcraft.app";

        private static final int CONNECT_TIMEOUT_MS = 10000;
        private static final int READ_TIMEOUT_MS = 10000;

        private final String baseUrl;

        public Https() {
            this(System.getProperty(BASE_URL_PROPERTY, DEFAULT_BASE_URL));
        }

        public Https(String baseUrl) {
            if (baseUrl == null || baseUrl.isEmpty()) {
                throw new IllegalArgumentException("baseUrl is empty");
            }
            this.baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
        }

        @Override
        public RegisterResult register(String host, int port, UUID op) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("host", host);
            body.addProperty("port", port);
            body.addProperty("operatorUuid", op.toString());
            JsonObject res = postJson("/owner/v1/verify", body, null);
            RegisterResult r = new RegisterResult();
            r.token = res.get("token").getAsString();
            r.expiresAt = res.get("expiresAt").getAsLong();
            r.link = res.get("link").getAsString();
            return r;
        }

        @Override
        public SeenResult seen(String token, UUID op) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("operatorUuid", op.toString());
            JsonObject res = postJson("/owner/v1/verify/" + urlEncode(token) + "/seen", body, null);
            SeenResult r = new SeenResult();
            r.affiliationId = res.get("affiliationId").getAsInt();
            r.key = res.get("key").getAsString();
            return r;
        }

        @Override
        public HeartbeatResult heartbeat(int id, String key, HeartbeatBody body) throws IOException {
            String authorization = "Bearer " + id + ":" + key;
            JsonObject res = postJson("/owner/v1/heartbeat", body.toJson(), authorization);
            HeartbeatResult r = new HeartbeatResult();
            r.listed = res.get("listed").getAsBoolean();
            r.disabled = res.get("disabled").getAsBoolean();
            return r;
        }

        private JsonObject postJson(String path, JsonObject body, String authorization) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            try {
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (authorization != null) {
                    conn.setRequestProperty("Authorization", authorization);
                }
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
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
                if (status < 200 || status >= 300) {
                    throw new HttpException(status, "POST " + path + " -> " + status + ": " + abbreviate(text));
                }
                return new JsonParser().parse(text).getAsJsonObject();
            } finally {
                conn.disconnect();
            }
        }

        private static String urlEncode(String s) throws IOException {
            // URLEncoder is form encoding; base64url tokens only contain
            // alphanumerics, '-', '_' and '.', which it leaves untouched.
            return URLEncoder.encode(s, "UTF-8");
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

        private static String abbreviate(String s) {
            return s.length() <= 200 ? s : s.substring(0, 200) + "...";
        }
    }
}
