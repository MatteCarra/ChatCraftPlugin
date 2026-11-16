package app.chatcraft.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The enrolled staff devices, persisted as JSON in {@code plugins/ChatCraft/staff.json}.
 * Each entry keeps the player UUID, the last known name, deviceId, platform, push token,
 * push key and lastSeen (epoch seconds). Limits: 3 devices per player, 30 per server.
 * Devices not seen for 30 days are pruned; a join (with the staff permission) refreshes
 * lastSeen, so an active staff member connecting by any address is never pruned.
 */
public class StaffRegistry {

    public static final int MAX_DEVICES_PER_PLAYER = 3;
    public static final int MAX_DEVICES_TOTAL = 30;
    public static final long STALE_AFTER_SECONDS = 30L * 24 * 3600;

    /** The device data sent in the enroll message. */
    public static class DeviceInfo {
        public final String platform;
        public final String pushToken;
        public final String apnsEnv;
        public final String pushKey; // base64, 32 bytes

        public DeviceInfo(String platform, String pushToken, String apnsEnv, String pushKey) {
            this.platform = platform;
            this.pushToken = pushToken;
            this.apnsEnv = apnsEnv;
            this.pushKey = pushKey;
        }
    }

    public static class Device {
        public UUID player;
        public String name;
        public String deviceId;
        public String platform;
        public String pushToken;
        public String apnsEnv;
        public String pushKey;
        public long lastSeen; // epoch seconds
    }

    private final File file;
    private final Map<UUID, List<Device>> byPlayer = new LinkedHashMap<>();

    public StaffRegistry(File file) {
        this.file = file;
        try {
            load();
        } catch (Exception e) {
            System.err.println("[ChatCraft] could not read " + file + " (" + e.getMessage() + "), starting with an empty staff registry");
            byPlayer.clear();
            preserveCorruptFile(); // a later save() must never overwrite the unreadable enrolments
        }
    }

    /** Renames an unreadable staff.json aside (staff.json.corrupt-<ts>) so it is not lost to the next save(). */
    private void preserveCorruptFile() {
        if (!file.exists()) return;
        File aside = new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), aside.toPath());
        } catch (IOException e) {
            System.err.println("[ChatCraft] could not preserve corrupt " + file + " as " + aside.getName() + ": " + e.getMessage());
        }
    }

    /** Adds a device; returns the new deviceId, or empty when a limit is hit. */
    public synchronized Optional<String> add(UUID player, String name, DeviceInfo d) {
        List<Device> mine = byPlayer.computeIfAbsent(player, k -> new ArrayList<>());
        // the same phone enrolling again (same push token) replaces its old entry instead of using up the limit
        if (d.pushToken != null && !d.pushToken.isEmpty()) {
            mine.removeIf(old -> d.pushToken.equals(old.pushToken));
        }
        if (mine.size() >= MAX_DEVICES_PER_PLAYER) return Optional.empty();
        if (all().size() >= MAX_DEVICES_TOTAL) return Optional.empty();
        Device dev = new Device();
        dev.player = player;
        dev.name = name;
        dev.deviceId = UUID.randomUUID().toString();
        dev.platform = d.platform;
        dev.pushToken = d.pushToken;
        dev.apnsEnv = d.apnsEnv;
        dev.pushKey = d.pushKey;
        dev.lastSeen = Instant.now().getEpochSecond();
        mine.add(dev);
        save();
        return Optional.of(dev.deviceId);
    }

    public synchronized void remove(UUID player) {
        if (byPlayer.remove(player) != null) save();
    }

    public synchronized void removeDevice(UUID player, String deviceId) {
        List<Device> mine = byPlayer.get(player);
        if (mine == null) return;
        if (mine.removeIf(d -> d.deviceId.equals(deviceId)) ) save();
    }

    public synchronized void updateToken(UUID player, String deviceId, String pushToken, Instant now) {
        Device d = find(byPlayer.get(player), deviceId);
        if (d == null) return;
        if (pushToken != null && !pushToken.isEmpty()) d.pushToken = pushToken; // never wipe a good token with a blank
        d.lastSeen = now.getEpochSecond();
        save();
    }

    /** Removes all devices of the player with this stored name (case-insensitive; never a Mojang lookup). */
    public synchronized int removeByName(String name) {
        int removed = 0;
        for (Map.Entry<UUID, List<Device>> e : byPlayer.entrySet()) {
            int before = e.getValue().size();
            e.getValue().removeIf(d -> d.name != null && d.name.equalsIgnoreCase(name));
            removed += before - e.getValue().size();
        }
        if (removed > 0) save();
        return removed;
    }

    /** Drops devices not seen for [STALE_AFTER_SECONDS]. */
    public synchronized void pruneStale(Instant now) {
        long cutoff = now.getEpochSecond() - STALE_AFTER_SECONDS;
        boolean changed = false;
        for (List<Device> mine : byPlayer.values()) {
            if (mine.removeIf(d -> d.lastSeen < cutoff)) changed = true;
        }
        if (changed) save();
    }

    /** Refreshes lastSeen of the player's devices (a join with the permission proves activity). */
    public synchronized void touch(UUID player, Instant now) {
        List<Device> mine = byPlayer.get(player);
        if (mine == null || mine.isEmpty()) return;
        for (Device d : mine) d.lastSeen = now.getEpochSecond();
        save();
    }

    public synchronized List<Device> all() {
        List<Device> out = new ArrayList<>();
        for (List<Device> mine : byPlayer.values()) out.addAll(mine);
        return out;
    }

    public synchronized List<Device> devicesOf(UUID player) {
        return new ArrayList<>(byPlayer.getOrDefault(player, new ArrayList<>()));
    }

    private static Device find(List<Device> mine, String deviceId) {
        if (mine == null) return null;
        for (Device d : mine) if (d.deviceId.equals(deviceId)) return d;
        return null;
    }

    private void load() throws IOException {
        if (!file.exists()) return;
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) return;
        JsonArray arr = new JsonParser().parse(text).getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            Device d = new Device();
            d.player = UUID.fromString(o.get("player").getAsString());
            d.name = o.get("name").getAsString();
            d.deviceId = o.get("deviceId").getAsString();
            d.platform = o.get("platform").getAsString();
            d.pushToken = o.get("pushToken").getAsString();
            d.apnsEnv = o.has("apnsEnv") && !o.get("apnsEnv").isJsonNull() ? o.get("apnsEnv").getAsString() : null;
            d.pushKey = o.get("pushKey").getAsString();
            d.lastSeen = o.get("lastSeen").getAsLong();
            byPlayer.computeIfAbsent(d.player, k -> new ArrayList<>()).add(d);
        }
    }

    private void save() {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            JsonArray arr = new JsonArray();
            for (Device d : all()) {
                JsonObject o = new JsonObject();
                o.addProperty("player", d.player.toString());
                o.addProperty("name", d.name);
                o.addProperty("deviceId", d.deviceId);
                o.addProperty("platform", d.platform);
                o.addProperty("pushToken", d.pushToken);
                if (d.apnsEnv != null) o.addProperty("apnsEnv", d.apnsEnv);
                o.addProperty("pushKey", d.pushKey);
                o.addProperty("lastSeen", d.lastSeen);
                arr.add(o);
            }
            // staff.json holds push keys and FCM/APNs tokens: write it atomically and owner-only,
            // exactly like the other secret files (PushKeyStore / FileCredentialStore)
            PushKeyStore.writeAtomic(file, arr.toString().getBytes(StandardCharsets.UTF_8), Logger.getLogger(StaffRegistry.class.getName()));
        } catch (IOException e) {
            // registry persistence failure: log, keep running in memory
            System.err.println("[ChatCraft] could not write " + file + ": " + e.getMessage());
        }
    }
}
