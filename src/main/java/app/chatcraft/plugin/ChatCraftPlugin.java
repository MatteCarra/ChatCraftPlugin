package app.chatcraft.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * ChatCraft owner-program plugin. Stage 1: loads the config, prepares the
 * backend client, and wires the verification command plus the app-to-plugin
 * verify channel (P2); the heartbeat task and /chatcraft status are wired
 * here (P3).
 */
public class ChatCraftPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private BackendClient backendClient;
    private VerificationService verificationService;
    private HeartbeatTask heartbeatTask;
    private String verifyChannel;
    private StaffService staffService;
    private String staffChannel;
    private java.util.concurrent.ExecutorService pushExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginConfig = new PluginConfig(new BukkitConfigSource(getConfig()), getLogger());
        backendClient = new BackendClient.Https();
        CredentialStore credentials = new FileCredentialStore(
                new File(getDataFolder(), "credentials.yml"), getLogger());
        verificationService = new VerificationService(backendClient, Clock.systemUTC(), credentials);

        // P3: heartbeat - async once shortly after enable, then hourly; also
        // right after a successful verification (see the listener below).
        heartbeatTask = new HeartbeatTask(backendClient, credentials, pluginConfig,
                new BukkitServerFacts(this), getLogger());
        heartbeatTask.start(this);

        // Stage 2: staff toolkit - registry, enrolment service, staff channel, /chatcraft staff
        StaffConfig staffConfig = new StaffConfig(getConfig(), getLogger());
        StaffRegistry staffRegistry = new StaffRegistry(new File(getDataFolder(), "staff.json"));
        staffRegistry.pruneStale(Instant.now());
        TokenIssuer tokenIssuer = new TokenIssuer(Clock.systemUTC());
        staffService = new StaffService(staffRegistry, staffConfig, tokenIssuer, Clock.systemUTC(),
                () -> java.util.Collections.emptyList()); // AlertService (P6) replaces this supplier
        final StaffCommand staffCommand = new StaffCommand(this, pluginConfig, staffService, Clock.systemUTC());

        // Stage 2 alerts: push client (signed relay route) + alert service, wired into the staff service
        PushKeyStore pushKeys = new PushKeyStore(new File(getDataFolder(), "push-key.der"), getLogger());
        PushClient pushClient = new PushClient(new PushClient.Http(), pushKeys, Clock.systemUTC(), getLogger(),
                System.getProperty(PushClient.BASE_URL_PROPERTY, PushClient.DEFAULT_BASE_URL));
        Addresses.Address pushAddress = pluginConfig.getAddress() != null ? pluginConfig.getAddress()
                : new Addresses.Address(Bukkit.getIp() == null || Bukkit.getIp().isEmpty() ? "localhost" : Bukkit.getIp(), Bukkit.getPort());
        final String staffChannelName = StaffChannelListener.channelNameFor(Bukkit.getBukkitVersion());
        pushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "ChatCraft-push");
            th.setDaemon(true);
            return th;
        });
        AlertService alertService = new AlertService(staffRegistry, pushClient,
                (player, msg) -> {
                    Player pl = Bukkit.getPlayer(player);
                    if (pl != null) {
                        pl.sendPluginMessage(ChatCraftPlugin.this, staffChannelName,
                                msg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                },
                new BukkitServerFacts(this), pushAddress, staffConfig, Clock.systemUTC(),
                new AlertService.PermissionCheck() {
                    @Override public boolean isOnlineWithPermission(UUID player) {
                        Player pl = Bukkit.getPlayer(player);
                        return pl != null && pl.hasPermission(CommandAuth.STAFF);
                    }
                    @Override public boolean isOnline(UUID player) {
                        return Bukkit.getPlayer(player) != null;
                    }
                },
                name -> {
                    Player pl = Bukkit.getPlayerExact(name);
                    return pl != null ? pl.getName() : null;
                },
                pushExecutor);
        staffService.setRecentAlerts(alertService::recent);
        final AlertCommand alertCommand = new AlertCommand(alertService);
        if (getCommand("report") != null) {
            getCommand("report").setExecutor(new ReportCommand(alertService));
        }

        final VerifyCommand verifyCommand = new VerifyCommand(this, pluginConfig, verificationService);
        final StatusCommand statusCommand = new StatusCommand(heartbeatTask);
        if (getCommand("chatcraft") != null) {
            ChatCraftCommand router = new ChatCraftCommand(getDescription().getVersion(),
                    staffConfig::reportsEnabled, heartbeatTask::isVerified,
                    () -> {
                        java.util.List<String> names = new java.util.ArrayList<>();
                        for (StaffRegistry.Device d : staffRegistry.all()) {
                            if (d.name != null && !names.contains(d.name)) names.add(d.name);
                        }
                        return names;
                    },
                    verifyCommand, statusCommand, staffCommand, alertCommand);
            getCommand("chatcraft").setExecutor(router);
            getCommand("chatcraft").setTabCompleter(router);
        } else {
            getLogger().severe("command 'chatcraft' missing from plugin.yml - /chatcraft will not work");
        }

        verifyChannel = VerifyChannelListener.channelNameFor(Bukkit.getBukkitVersion());
        getServer().getMessenger().registerIncomingPluginChannel(
                this, verifyChannel, new VerifyChannelListener(verificationService, getLogger(), verifyChannel,
                        new Runnable() {
                            @Override
                            public void run() {
                                // Heartbeat right after a successful verification, async.
                                Bukkit.getScheduler().runTaskAsynchronously(ChatCraftPlugin.this, heartbeatTask);
                            }
                        }));

        staffChannel = StaffChannelListener.channelNameFor(Bukkit.getBukkitVersion());
        StaffChannelListener staffListener = new StaffChannelListener(this, staffService, getLogger(), staffChannel);
        getServer().getMessenger().registerIncomingPluginChannel(this, staffChannel, staffListener);
        getServer().getMessenger().registerOutgoingPluginChannel(this, staffChannel);

        // A join without the staff permission removes the player's devices; with it, refreshes lastSeen.
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                Player p = e.getPlayer();
                staffService.onJoin(p.getUniqueId(), p.hasPermission(CommandAuth.STAFF), Instant.now());
            }
        }, this);

        getLogger().info("ChatCraft enabled (listing=" + pluginConfig.isListing()
                + ", languages=" + pluginConfig.getLanguages()
                + ", verify channel '" + verifyChannel + "')");
    }

    @Override
    public void onDisable() {
        if (pushExecutor != null) pushExecutor.shutdown();
        if (verifyChannel != null) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, verifyChannel);
        }
        if (staffChannel != null) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, staffChannel);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, staffChannel);
        }
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public BackendClient getBackendClient() {
        return backendClient;
    }

    public VerificationService getVerificationService() {
        return verificationService;
    }

    public HeartbeatTask getHeartbeatTask() {
        return heartbeatTask;
    }

    public StaffService getStaffService() {
        return staffService;
    }

    /** Adapts Bukkit's FileConfiguration to {@link PluginConfig.ConfigSource}. */
    private static class BukkitConfigSource implements PluginConfig.ConfigSource {
        private final FileConfiguration config;

        BukkitConfigSource(FileConfiguration config) {
            this.config = config;
        }

        @Override
        public Object get(String path) {
            return config.get(path);
        }
    }
}
