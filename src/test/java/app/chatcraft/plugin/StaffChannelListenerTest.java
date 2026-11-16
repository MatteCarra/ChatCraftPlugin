package app.chatcraft.plugin;

import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Clock;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class StaffChannelListenerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StaffService service() throws Exception {
        StaffRegistry registry = new StaffRegistry(new File(tmp.getRoot(), "staff.json"));
        return new StaffService(registry, new StaffConfig(new YamlConfiguration(), Logger.getLogger("t")),
                new TokenIssuer(Clock.systemUTC()), Clock.systemUTC(), Collections::emptyList);
    }

    private static JsonObject hello(String deviceId, String token) {
        JsonObject m = new JsonObject();
        m.addProperty("t", "hello");
        m.addProperty("deviceId", deviceId);
        if (token != null) m.addProperty("pushToken", token);
        return m;
    }

    @Test
    public void helloWithoutTheStaffPermissionGetsNoInfoAndLosesItsDevices() throws Exception {
        StaffService service = service();
        UUID p = UUID.randomUUID();
        String d = service.registry().add(p, "Steve", new StaffRegistry.DeviceInfo("android", "tok", null, "k")).get();
        assertNull("templates and recent reports must not reach a non-staff client",
                StaffChannelListener.handle(service, p, "Steve", false, hello(d, "tok")));
        assertTrue(service.registry().devicesOf(p).isEmpty());
    }

    @Test
    public void helloWithPermissionAnswersInfoAndABlankTokenChangesNothing() throws Exception {
        StaffService service = service();
        UUID p = UUID.randomUUID();
        String d = service.registry().add(p, "Steve", new StaffRegistry.DeviceInfo("android", "tok", null, "k")).get();
        JsonObject reply = StaffChannelListener.handle(service, p, "Steve", true, hello(d, ""));
        assertEquals("info", reply.get("t").getAsString());
        assertEquals("tok", service.registry().devicesOf(p).get(0).pushToken);
    }

    @Test
    public void unknownAndMalformedMessagesGetNoReply() throws Exception {
        StaffService service = service();
        JsonObject m = new JsonObject();
        m.addProperty("t", "nope");
        assertNull(StaffChannelListener.handle(service, UUID.randomUUID(), "x", true, m));
        assertNull(StaffChannelListener.handle(service, UUID.randomUUID(), "x", true, new JsonObject()));
    }
}
