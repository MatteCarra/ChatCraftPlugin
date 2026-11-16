package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class StaffServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MutableClock clock;
    private StaffRegistry registry;
    private TokenIssuer tokens;
    private StaffService service;

    private static final Instant T0 = Instant.parse("2026-11-17T12:00:00Z");

    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Before
    public void setUp() throws Exception {
        clock = new MutableClock(T0);
        registry = new StaffRegistry(new File(tmp.getRoot(), "staff.json"));
        tokens = new TokenIssuer(clock);
        service = new StaffService(registry, new StaffConfig(new YamlConfiguration(), Logger.getLogger("t")), tokens, clock, Collections::emptyList);
    }

    private static JsonObject enrollMsg(String token, String pushKey) {
        JsonObject m = new JsonObject();
        m.addProperty("t", "enroll");
        m.addProperty("token", token);
        m.addProperty("deviceName", "Pixel 8");
        m.addProperty("platform", "android");
        m.addProperty("pushToken", "fcm-" + pushKey); // one token per phone: the same token re-enrolling replaces the device
        m.add("apnsEnv", null);
        m.addProperty("pushKey", pushKey);
        return m;
    }

    private static String key64(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (seed + i);
        return java.util.Base64.getEncoder().encodeToString(k);
    }

    private String tokenFor(UUID player) {
        String link = service.startEnrolment(player, "play.example.com", 25565);
        assertTrue(link.startsWith("https://api.chatcraft.app/s?h=play.example.com&p=25565&t="));
        return link.substring(link.indexOf("t=") + 2);
    }

    @Test
    public void correctTokenFromRightPlayerWithPermissionEnrols() {
        UUID steve = UUID.randomUUID();
        StaffService.EnrollOutcome ok = service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        assertTrue(ok.isOk());
        assertNotNull(ok.deviceId);
        assertEquals(1, registry.devicesOf(steve).size());
    }

    @Test
    public void wrongTokenFails() {
        UUID steve = UUID.randomUUID();
        assertEquals("token", service.onEnroll(steve, "Steve", true, enrollMsg("not-the-token-aaaaaaaaaaaaaa", key64(1))).failureReason);
        assertTrue(registry.all().isEmpty());
    }

    @Test
    public void expiredTokenFails() {
        UUID steve = UUID.randomUUID();
        String token = tokenFor(steve);
        clock.now = T0.plusSeconds(16 * 60); // past the 15-minute TTL
        assertEquals("token", service.onEnroll(steve, "Steve", true, enrollMsg(token, key64(1))).failureReason);
        assertTrue(registry.all().isEmpty());
    }

    @Test
    public void anotherPlayersTokenFails() {
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        String token = tokenFor(steve);
        assertEquals("token", service.onEnroll(alex, "Alex", true, enrollMsg(token, key64(2))).failureReason);
        assertTrue(registry.devicesOf(alex).isEmpty());
        assertTrue(registry.devicesOf(steve).isEmpty());
    }

    @Test
    public void noPermissionFails() {
        UUID steve = UUID.randomUUID();
        assertEquals("permission", service.onEnroll(steve, "Steve", false, enrollMsg(tokenFor(steve), key64(3))).failureReason);
        assertTrue(registry.all().isEmpty());
    }

    @Test
    public void tokenCannotBeUsedTwice() {
        UUID steve = UUID.randomUUID();
        String token = tokenFor(steve);
        assertTrue(service.onEnroll(steve, "Steve", true, enrollMsg(token, key64(1))).isOk());
        assertEquals("token", service.onEnroll(steve, "Steve", true, enrollMsg(token, key64(2))).failureReason);
        assertEquals(1, registry.devicesOf(steve).size());
    }

    @Test
    public void fourthDeviceGivesLimit() {
        UUID steve = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertTrue(service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(i + 1))).isOk());
        }
        assertEquals("limit", service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(9))).failureReason);
        assertEquals(3, registry.devicesOf(steve).size());
    }

    @Test
    public void onJoinWithoutPermissionRemovesDevices() {
        UUID steve = UUID.randomUUID();
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        assertEquals(1, registry.devicesOf(steve).size());
        service.onJoin(steve, false, clock.instant());
        assertTrue("devices removed on join without permission", registry.devicesOf(steve).isEmpty());
    }

    @Test
    public void joinOnDay29SurvivesPruneOnDay31() {
        UUID steve = UUID.randomUUID();
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        Instant day29 = T0.plusSeconds(29L * 24 * 3600);
        service.onJoin(steve, true, day29); // proves active, via any address
        registry.pruneStale(day29.plusSeconds(2L * 24 * 3600)); // day 31
        assertEquals("join on day 29 keeps the device past the 30-day prune", 1, registry.devicesOf(steve).size());
    }

    @Test
    public void onHelloUpdatesTokenAndLastSeen() {
        UUID steve = UUID.randomUUID();
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        String deviceId = registry.devicesOf(steve).get(0).deviceId;
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "hello");
        hello.addProperty("v", 1);
        hello.addProperty("deviceId", deviceId);
        hello.addProperty("pushToken", "rotated-fcm-token");
        service.onHello(steve, true, hello);
        assertEquals("rotated-fcm-token", registry.devicesOf(steve).get(0).pushToken);
        assertEquals(T0.getEpochSecond(), registry.devicesOf(steve).get(0).lastSeen);
    }

    @Test
    public void helloNamingAnotherPlayersDeviceChangesNothing() {
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        service.onEnroll(alex, "Alex", true, enrollMsg(tokenFor(alex), key64(2)));
        String alexDevice = registry.devicesOf(alex).get(0).deviceId;
        JsonObject evil = new JsonObject();
        evil.addProperty("t", "hello");
        evil.addProperty("v", 1);
        evil.addProperty("deviceId", alexDevice);
        evil.addProperty("pushToken", "evil-token");
        String steveToken = registry.devicesOf(steve).get(0).pushToken;
        String alexToken = registry.devicesOf(alex).get(0).pushToken;
        service.onHello(steve, true, evil);
        assertEquals(steveToken, registry.devicesOf(steve).get(0).pushToken);
        assertEquals(alexToken, registry.devicesOf(alex).get(0).pushToken);
    }

    @Test
    public void helloWithoutPermissionRemovesDevices() {
        UUID steve = UUID.randomUUID();
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "hello");
        hello.addProperty("v", 1);
        hello.addProperty("deviceId", registry.devicesOf(steve).get(0).deviceId);
        hello.addProperty("pushToken", "x");
        service.onHello(steve, false, hello);
        assertTrue(registry.devicesOf(steve).isEmpty());
    }

    @Test
    public void onUnenrollRemovesOnlyTheNamedDevice() {
        UUID steve = UUID.randomUUID();
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(1)));
        service.onEnroll(steve, "Steve", true, enrollMsg(tokenFor(steve), key64(2)));
        String first = registry.devicesOf(steve).get(0).deviceId;
        String second = registry.devicesOf(steve).get(1).deviceId;
        service.onUnenroll(steve, first);
        assertEquals(1, registry.devicesOf(steve).size());
        assertEquals(second, registry.devicesOf(steve).get(0).deviceId);
    }

    @Test
    public void infoForReturnsTemplatesAndRecentAlerts() {
        YamlConfiguration cfg = new YamlConfiguration();
        Map<String, Object> tpl = new HashMap<>();
        tpl.put("id", "kick");
        tpl.put("label", "Kick");
        tpl.put("command", "kick {player}");
        tpl.put("target", "player");
        tpl.put("confirm", true);
        cfg.set("staff-actions", Collections.singletonList(tpl));
        UUID steve = UUID.randomUUID();
        Alert alert = new Alert("r", 1L, "report", "Alex", "griefing", "Steve");
        StaffService s = new StaffService(registry, new StaffConfig(cfg, Logger.getLogger("t")), tokens, clock,
                () -> Collections.singletonList(alert));
        JsonObject info = s.infoFor(steve);
        assertEquals("info", info.get("t").getAsString());
        assertEquals(1, info.getAsJsonArray("templates").size());
        assertEquals("kick", info.getAsJsonArray("templates").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, info.getAsJsonArray("alerts").size());
        assertEquals("r", info.getAsJsonArray("alerts").get(0).getAsJsonObject().get("aid").getAsString());
    }

    @Test
    public void helloWithABlankTokenKeepsTheStoredTokenAndRefreshesLastSeen() {
        UUID p = UUID.randomUUID();
        String d = registry.add(p, "Steve", new StaffRegistry.DeviceInfo("android", "good", null, "k")).get();
        clock.now = T0.plusSeconds(3600);
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "hello");
        hello.addProperty("deviceId", d);
        hello.addProperty("pushToken", "");
        service.onHello(p, true, hello);
        assertEquals("good", registry.devicesOf(p).get(0).pushToken);
        assertEquals(T0.plusSeconds(3600).getEpochSecond(), registry.devicesOf(p).get(0).lastSeen);
    }
}
