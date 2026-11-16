package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class AlertServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MutableClock clock;
    private StaffRegistry registry;
    private RecordingMessenger messenger;
    private FakePerms perms;
    private FakeNames names;
    private RecordingTransport transport;
    private PushClient push;
    private StaffConfig staffConfig;
    private AlertService service;

    private static final Instant T0 = Instant.parse("2026-11-17T12:00:00Z");
    private static final String HOST = "play.example.com";
    private static final int PORT = 25565;

    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static class RecordingMessenger implements AlertService.ChannelMessenger {
        final List<UUID> players = new ArrayList<>();
        final List<JsonObject> messages = new ArrayList<>();
        @Override public void send(UUID player, JsonObject msg) { players.add(player); messages.add(msg); }
    }

    static class FakePerms implements AlertService.PermissionCheck {
        final Map<UUID, Boolean> online = new HashMap<>(); // online + has permission
        @Override public boolean isOnlineWithPermission(UUID player) { return online.getOrDefault(player, false); }
        @Override public boolean isOnline(UUID player) { return online.containsKey(player); }
    }

    static class FakeNames implements AlertService.NameResolver {
        final Map<String, String> exact = new HashMap<>(); // lower -> exact case
        @Override public String exactOnlineName(String name) { return exact.get(name.toLowerCase()); }
    }

    static class RecordingTransport implements PushClient.PushTransport {
        final List<String> bodies = new ArrayList<>();
        @Override public PushClient.PushResponse post(String url, String jsonBody) {
            bodies.add(jsonBody);
            return new PushClient.PushResponse(200, "{\"sent\":0}");
        }
    }

    private static class TestFacts implements ServerFacts {
        @Override public String name() { return "Test Server"; }
        @Override public boolean premium() { return true; }
        @Override public String gameVersion() { return "1.21.4"; }
        @Override public String pluginVersion() { return "1.0.0"; }
    }

    @Before
    public void setUp() {
        clock = new MutableClock(T0);
        registry = new StaffRegistry(new File(tmp.getRoot(), "staff.json"));
        messenger = new RecordingMessenger();
        perms = new FakePerms();
        names = new FakeNames();
        transport = new RecordingTransport();
        push = new PushClient(transport, new PushKeyStore(new File(tmp.getRoot(), "push-key.der"), Logger.getLogger("t")), clock, Logger.getLogger("t"), "https://relay.test");
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("reports", true);
        cfg.set("report-cooldown-seconds", 30);
        staffConfig = new StaffConfig(cfg, Logger.getLogger("t"));
        service = new AlertService(registry, push, messenger, new TestFacts(),
                new Addresses.Address(HOST, PORT), staffConfig, clock, perms, names);
    }

    private UUID enroll(String name, boolean onlineWithPerm) {
        UUID p = UUID.randomUUID();
        registry.add(p, name, new StaffRegistry.DeviceInfo("android", "tok-" + name, null, key64(name.hashCode())));
        perms.online.put(p, onlineWithPerm); // present = online, value = holds chatcraft.staff
        return p;
    }

    private static String key64(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (seed + i);
        return java.util.Base64.getEncoder().encodeToString(k);
    }

    @Test
    public void reportCreatesOneAlertOneInSessionMessagePerOnlineStaffAndOnePushPerDeviceWithSameAid() {
        UUID staff = enroll("Steve", true);
        UUID reporter = UUID.randomUUID();
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(reporter, "Alex", "griefing", "Reporter"));
        // one in-session message to the online staff player
        assertEquals(1, messenger.players.size());
        assertEquals(staff, messenger.players.get(0));
        JsonObject msg = messenger.messages.get(0);
        assertEquals("alert", msg.get("t").getAsString());
        String aid = msg.get("aid").getAsString();
        assertEquals(16, aid.length());
        // one push request containing the device, same aid inside the encrypted envelope is checked via recent()
        assertEquals(1, transport.bodies.size());
        List<Alert> recent = service.recent();
        assertEquals(1, recent.size());
        assertEquals(aid, recent.get(0).aid);
        assertEquals("report", recent.get(0).source);
        assertEquals("Alex", recent.get(0).target);
        assertEquals("griefing", recent.get(0).text);
    }

    @Test
    public void secondReportInsideCooldownCreatesNothing() {
        UUID reporter = UUID.randomUUID();
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(reporter, "Alex", "x", "R"));
        assertEquals(AlertService.ReportResult.COOLDOWN, service.tryReport(reporter, "Alex", "y", "R"));
        assertEquals(1, service.recent().size());
        clock.now = T0.plusSeconds(31);
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(reporter, "Alex", "y", "R"));
        assertEquals(2, service.recent().size());
    }

    @Test
    public void reportCooldownMapIsPrunedToTheCooldownWindow() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(r1, "Alex", "a", "R"));
        clock.now = T0.plusSeconds(10); // both entries inside the 30s cooldown window
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(r2, "Alex", "b", "R"));
        assertEquals(2, service.lastReportAtSize());
        // advance past the cooldown window for both entries, then one more report prunes them
        clock.now = T0.plusSeconds(10 + 31);
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(UUID.randomUUID(), "Alex", "c", "R"));
        assertEquals("old cooldown entries pruned, map stays bounded", 1, service.lastReportAtSize());
        // a pruned reporter is no longer in cooldown
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(r1, "Alex", "d", "R"));
    }

    @Test
    public void reportAtAAndSteveWithArgsCreateNothing() {
        UUID reporter = UUID.randomUUID();
        assertEquals(AlertService.ReportResult.BAD_NAME, service.tryReport(reporter, "@a", "x", "R"));
        assertEquals(AlertService.ReportResult.BAD_NAME, service.tryReport(reporter, "Steve 0 -s", "x", "R"));
        assertTrue(service.recent().isEmpty());
        assertTrue(transport.bodies.isEmpty());
    }

    @Test
    public void reportNamingAnOnlinePlayerStoresTheExactCaseName() {
        UUID reporter = UUID.randomUUID();
        names.exact.put("steve", "Steve");
        assertEquals(AlertService.ReportResult.ACCEPTED, service.tryReport(reporter, "sTeVe", null, "R"));
        assertEquals("Steve", service.recent().get(0).target);
        assertEquals("Reported Steve", service.recent().get(0).text); // default reason text
    }

    @Test
    public void onlineStaffWhoLostPermissionGetsNoPushAndLosesDevices() {
        UUID staff = enroll("Steve", false); // online, but no longer holds chatcraft.staff
        UUID reporter = UUID.randomUUID();
        service.tryReport(reporter, "Alex", "x", "R");
        assertTrue("no in-session message", messenger.players.isEmpty());
        assertTrue("devices removed", registry.devicesOf(staff).isEmpty());
        assertTrue("no push sent", transport.bodies.isEmpty());
    }

    @Test
    public void fanOutBeyondBudgetDropsPushesButKeepsInSessionDelivery() {
        // 101 devices (30-per-server limit caps registry at 30; use registry bypass via many players)
        // registry caps at 30 devices total, so use 30 devices across 30 players, all online with permission
        for (int i = 0; i < 30; i++) enroll("P" + i, true);
        // push budget is 100/hour: 30 devices fit; add a second raise to exceed? 30 < 100. Use 4 raises x 30 = 120 > 100
        UUID reporter = UUID.randomUUID();
        for (int r = 0; r < 4; r++) {
            clock.now = T0.plusSeconds(r * 40); // past cooldown each time
            service.tryReport(reporter, "Alex", "x" + r, "R");
        }
        // in-session: 30 messages per raise = 120, all delivered
        assertEquals(120, messenger.messages.size());
        // pushes: 4 raises x 30 devices = 120 wanted; reports may use 60 of the 100/hour (console alerts keep the rest)
        int pushedItems = 0;
        for (String b : transport.bodies) {
            pushedItems += new com.google.gson.JsonParser().parse(
                    new com.google.gson.JsonParser().parse(b).getAsJsonObject().get("itemsJson").getAsString())
                    .getAsJsonArray().size();
        }
        assertEquals(60, pushedItems);
    }

    @Test
    public void recentDropsOlderThanAnHourAndKeepsNewest20() {
        UUID reporter = UUID.randomUUID();
        for (int i = 0; i < 25; i++) {
            clock.now = T0.plusSeconds(i * 100); // 100s apart; cooldown is 30s so all accepted
            service.tryReport(reporter, "Alex", "m" + i, "R");
        }
        List<Alert> recent = service.recent();
        assertEquals(20, recent.size());
        assertEquals("m24", recent.get(0).text); // newest first
        // one older than an hour is dropped: advance past 1h and add one more
        clock.now = T0.plusSeconds(3700 + 100);
        service.tryReport(reporter, "Alex", "last", "R");
        recent = service.recent();
        assertEquals(20, recent.size());
        assertEquals("last", recent.get(0).text);
        assertFalse(recent.stream().anyMatch(a -> a.text.equals("m0")));
    }

    @Test
    public void controlCharsStrippedAnd500CutTo200() {
        UUID reporter = UUID.randomUUID();
        String big = "ab\u0001cd\n" + new String(new char[500]).replace('\0', 'x');
        service.tryReport(reporter, "Alex", big, "R");
        String text = service.recent().get(0).text;
        assertEquals(200, text.length());
        assertFalse(text.contains("\u0001"));
        assertFalse(text.contains("\n"));
    }

    @Test
    public void reportsFalseDisablesReportButNotConsoleAlert() {
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("reports", false);
        cfg.set("report-cooldown-seconds", 30);
        AlertService s = new AlertService(registry, push, messenger, new TestFacts(),
                new Addresses.Address(HOST, PORT), new StaffConfig(cfg, Logger.getLogger("t")), clock, perms, names);
        UUID reporter = UUID.randomUUID();
        assertEquals(AlertService.ReportResult.DISABLED, s.tryReport(reporter, "Alex", "x", "R"));
        assertTrue(s.recent().isEmpty());
        // console alert still works
        Alert a = s.create("console", "console", "server restarting", "");
        s.raise(a);
        assertEquals(1, s.recent().size());
        assertEquals("console", s.recent().get(0).source);
        assertEquals("", s.recent().get(0).target);
    }

    @Test
    public void pushesRunOnTheExecutorNotInlineAndInSessionMessagesStayInline() {
        final List<Runnable> queued = new ArrayList<>();
        AlertService s = new AlertService(registry, push, messenger, new TestFacts(),
                new Addresses.Address(HOST, PORT), staffConfig, clock, perms, names, queued::add);
        enroll("Steve", true);
        s.raise(s.create("console", "console", "restart", ""));
        assertEquals(1, messenger.players.size()); // in-session delivery already happened
        assertTrue("the HTTP push must not run inline", transport.bodies.isEmpty());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, transport.bodies.size());
    }

    @Test
    public void thePushCarriesTheFullAlertInsideTheCiphertext() throws Exception {
        enroll("Steve", true);
        service.tryReport(UUID.randomUUID(), "Alex", "griefing the spawn", "Reporter");
        com.google.gson.JsonObject req = new com.google.gson.JsonParser().parse(transport.bodies.get(0)).getAsJsonObject();
        com.google.gson.JsonObject item = new com.google.gson.JsonParser().parse(req.get("itemsJson").getAsString()).getAsJsonArray().get(0).getAsJsonObject();
        StaffRegistry.Device dev = registry.all().get(0);
        com.google.gson.JsonObject plain = PushCrypto.decrypt(dev.pushKey, dev.deviceId,
                new com.google.gson.JsonParser().parse(item.get("payload").getAsString()).getAsJsonObject());
        assertEquals("report", plain.get("source").getAsString());
        assertEquals("Reporter", plain.get("from").getAsString());
        assertEquals("Alex", plain.get("target").getAsString());
        assertEquals("griefing the spawn", plain.get("text").getAsString());
    }
}
