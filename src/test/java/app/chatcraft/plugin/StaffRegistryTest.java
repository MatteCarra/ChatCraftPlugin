package app.chatcraft.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

public class StaffRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StaffRegistry registry() throws Exception {
        return new StaffRegistry(new File(tmp.getRoot(), "staff.json"));
    }

    private static StaffRegistry.DeviceInfo dev(String pushKey) {
        return new StaffRegistry.DeviceInfo("android", "token-" + pushKey, null, pushKey);
    }

    @Test
    public void addReturnsDeviceIdAndEnforcesLimits() throws Exception {
        StaffRegistry r = registry();
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertTrue(r.add(p, "Steve", dev("k" + i)).isPresent());
        }
        assertFalse("4th device for one player is refused", r.add(p, "Steve", dev("k3")).isPresent());
        // 30 per server
        for (int i = 0; i < 27; i++) {
            assertTrue(r.add(UUID.randomUUID(), "P" + i, dev("x" + i)).isPresent());
        }
        assertFalse("31st device overall is refused", r.add(UUID.randomUUID(), "P31", dev("x31")).isPresent());
    }

    @Test
    public void removeDeviceRemovesOnlyTheNamedDeviceOfThatPlayer() throws Exception {
        StaffRegistry r = registry();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String d1 = r.add(a, "Steve", dev("k1")).get();
        String d2 = r.add(a, "Steve", dev("k2")).get();
        r.add(b, "Alex", dev("k3")).get();
        r.removeDevice(a, d1);
        assertEquals(1, r.devicesOf(a).size());
        assertEquals(d2, r.devicesOf(a).get(0).deviceId);
        assertEquals(1, r.devicesOf(b).size());
        // a device id of another player changes nothing
        r.removeDevice(a, r.devicesOf(b).get(0).deviceId);
        assertEquals(1, r.devicesOf(b).size());
    }

    @Test
    public void updateTokenUpdatesTokenAndLastSeen() throws Exception {
        StaffRegistry r = registry();
        UUID p = UUID.randomUUID();
        String d = r.add(p, "Steve", dev("k1")).get();
        Instant now = Instant.now();
        r.updateToken(p, d, "new-token", now);
        StaffRegistry.Device dev = r.devicesOf(p).get(0);
        assertEquals("new-token", dev.pushToken);
        assertEquals(now.getEpochSecond(), dev.lastSeen);
        // another player's device id: no-op
        r.updateToken(UUID.randomUUID(), d, "evil", now);
        assertEquals("new-token", r.devicesOf(p).get(0).pushToken);
    }

    @Test
    public void removeByNameIsCaseInsensitiveAndWorksForOfflinePlayers() throws Exception {
        StaffRegistry r = registry();
        UUID p = UUID.randomUUID();
        r.add(p, "Steve", dev("k1")).get();
        r.removeByName("sTeVe");
        assertTrue(r.devicesOf(p).isEmpty());
        r.removeByName("nobody"); // no throw
    }

    @Test
    public void pruneStaleDrops31DaysKeeps29Days() throws Exception {
        StaffRegistry r = registry();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        r.add(p1, "Old", dev("k1")).get();
        r.add(p2, "New", dev("k2")).get();
        Instant now = Instant.now();
        r.updateToken(p1, r.devicesOf(p1).get(0).deviceId, "t", now.minusSeconds(31L * 24 * 3600));
        r.updateToken(p2, r.devicesOf(p2).get(0).deviceId, "t", now.minusSeconds(29L * 24 * 3600));
        r.pruneStale(now);
        assertTrue(r.devicesOf(p1).isEmpty());
        assertEquals(1, r.devicesOf(p2).size());
    }

    @Test
    public void savedFileIsOwnerOnlyAndWrittenViaTempPlusRename() throws Exception {
        File f = new File(tmp.getRoot(), "staff.json");
        StaffRegistry r = new StaffRegistry(f);
        r.add(UUID.randomUUID(), "Steve", dev("k1")).get();
        assertTrue(f.exists());
        assertEquals("secret staff.json must be rw------- like the other secret files",
                "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())));
        assertFalse("no temp file left behind after the atomic move",
                new File(tmp.getRoot(), "staff.json.tmp").exists());
        // and the atomically-written file reloads fine
        assertEquals(1, new StaffRegistry(f).all().size());
    }

    @Test
    public void corruptFileIsRenamedAsideOnLoadAndNeverOverwrittenBySave() throws Exception {
        File f = new File(tmp.getRoot(), "staff.json");
        Files.write(f.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        StaffRegistry r = new StaffRegistry(f); // load fails: registry starts empty, file preserved aside
        assertTrue(r.all().isEmpty());
        File[] asides = tmp.getRoot().listFiles((d, n) -> n.startsWith("staff.json.corrupt-"));
        assertNotNull("corrupt file renamed aside", asides);
        assertEquals(1, asides.length);
        assertEquals("{broken", new String(Files.readAllBytes(asides[0].toPath()), StandardCharsets.UTF_8));
        // a later mutation saves a fresh staff.json and does NOT overwrite the corrupt one
        r.add(UUID.randomUUID(), "Steve", dev("k1")).get();
        assertEquals(1, new StaffRegistry(f).all().size());
        assertEquals("corrupt file still intact after a save()", "{broken",
                new String(Files.readAllBytes(asides[0].toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void survivesReloadFromDisk() throws Exception {
        File f = new File(tmp.getRoot(), "staff.json");
        StaffRegistry r = new StaffRegistry(f);
        UUID p = UUID.randomUUID();
        String d = r.add(p, "Steve", dev("k1")).get();
        StaffRegistry r2 = new StaffRegistry(f);
        List<StaffRegistry.Device> all = r2.all();
        assertEquals(1, all.size());
        assertEquals(d, all.get(0).deviceId);
        assertEquals(p, all.get(0).player);
        assertEquals("Steve", all.get(0).name);
        assertEquals("k1", all.get(0).pushKey);
    }

    @Test
    public void reEnrollingTheSamePhoneReplacesItsDeviceInsteadOfUsingTheLimit() throws Exception {
        StaffRegistry r = registry();
        UUID p = UUID.randomUUID();
        String first = r.add(p, "Steve", new StaffRegistry.DeviceInfo("android", "same-token", null, dev("k1").pushKey)).get();
        for (int i = 0; i < 5; i++) {
            assertTrue(r.add(p, "Steve", new StaffRegistry.DeviceInfo("android", "same-token", null, dev("k" + i).pushKey)).isPresent());
        }
        assertEquals(1, r.devicesOf(p).size());
        assertNotEquals(first, r.devicesOf(p).get(0).deviceId);
    }

    @Test
    public void aBlankTokenNeverOverwritesTheStoredOneButStillRefreshesLastSeen() throws Exception {
        StaffRegistry r = registry();
        UUID p = UUID.randomUUID();
        String d = r.add(p, "Steve", new StaffRegistry.DeviceInfo("android", "good", null, dev("k1").pushKey)).get();
        r.updateToken(p, d, "", Instant.ofEpochSecond(4_000_000_000L));
        r.updateToken(p, d, null, Instant.ofEpochSecond(4_000_000_000L));
        assertEquals("good", r.devicesOf(p).get(0).pushToken);
        assertEquals(4_000_000_000L, r.devicesOf(p).get(0).lastSeen);
    }
}
