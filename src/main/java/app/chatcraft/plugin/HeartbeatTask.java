package app.chatcraft.plugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Sends the hourly heartbeat to {@code POST /owner/v1/heartbeat} with the
 * bearer credentials from the {@link CredentialStore}. The body contains
 * exactly the fields of the Contracts table ({@link BackendClient.HeartbeatBody})
 * — no player data is ever sent.
 *
 * <p>{@link #run()} performs one heartbeat and never throws: without stored
 * credentials it does nothing; a 401 is logged as "key rejected, run
 * /chatcraft verify again"; other HTTP/network errors are logged as warnings.
 * The key is never logged.</p>
 *
 * <p>{@link #start(Plugin)} schedules it async once shortly after enable and
 * then every {@value #PERIOD_MINUTES} minutes; the plugin also calls
 * {@link #run()} (async) right after a successful verification.</p>
 */
public class HeartbeatTask implements Runnable {

    /** Heartbeat period: 60 minutes, in server ticks (20 ticks = 1 s). */
    public static final long PERIOD_TICKS = 60L * 60L * 20L;
    /** First heartbeat one second after enable, so the server is listed promptly. */
    public static final long INITIAL_DELAY_TICKS = 20L;
    public static final int PERIOD_MINUTES = 60;

    private final BackendClient backend;
    private final CredentialStore credentials;
    private final PluginConfig config;
    private final ServerFacts facts;
    private final Logger logger;

    private volatile BackendClient.HeartbeatResult lastResult;
    private volatile String lastError;
    private volatile long lastRunAtMillis = -1L;

    public HeartbeatTask(BackendClient backend, CredentialStore credentials, PluginConfig config, ServerFacts facts) {
        this(backend, credentials, config, facts, Logger.getLogger("ChatCraft"));
    }

    public HeartbeatTask(BackendClient backend, CredentialStore credentials, PluginConfig config,
                         ServerFacts facts, Logger logger) {
        if (backend == null || credentials == null || config == null || facts == null || logger == null) {
            throw new IllegalArgumentException("backend, credentials, config, facts and logger are required");
        }
        this.backend = backend;
        this.credentials = credentials;
        this.config = config;
        this.facts = facts;
        this.logger = logger;
    }

    /** Schedules {@link #run()} async: once after {@link #INITIAL_DELAY_TICKS}, then every {@link #PERIOD_TICKS}. */
    public void start(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, INITIAL_DELAY_TICKS, PERIOD_TICKS);
    }

    /** Runs one heartbeat right now on the calling thread; schedule async via {@link #start(Plugin)} or the Bukkit scheduler. */
    @Override
    public void run() {
        CredentialStore.Credentials creds;
        try {
            creds = credentials.load();
        } catch (IOException e) {
            logger.warning("[ChatCraft] could not read credentials.yml (" + e.getMessage() + ") - heartbeat skipped");
            return;
        }
        lastRunAtMillis = System.currentTimeMillis();
        if (creds == null) {
            // Not verified yet: send nothing at all (no credentials, no request).
            logger.info("[ChatCraft] not verified yet - no heartbeat sent (run /chatcraft verify)");
            return;
        }
        BackendClient.HeartbeatBody body = new BackendClient.HeartbeatBody();
        body.name = facts.name();
        body.languages = config.getLanguages();
        body.listing = config.isListing();
        body.premium = facts.premium();
        body.gameVersion = facts.gameVersion();
        body.pluginVersion = facts.pluginVersion();
        body.voteLinks = config.getVoteLinks();
        try {
            BackendClient.HeartbeatResult r = backend.heartbeat(creds.affiliationId, creds.key, body);
            lastResult = r;
            lastError = null;
            logger.info("[ChatCraft] heartbeat sent - listed=" + r.listed + ", disabled=" + r.disabled);
        } catch (BackendClient.HttpException e) {
            // Never log e.getMessage() here in a way that could echo the key: the
            // key travels in the Authorization header, not the URL, but we only
            // log the status code to be safe.
            lastResult = null;
            lastError = "HTTP " + e.getStatus();
            if (e.getStatus() == 401) {
                logger.warning("[ChatCraft] key rejected, run /chatcraft verify again");
            } else {
                logger.warning("[ChatCraft] heartbeat failed with HTTP " + e.getStatus());
            }
        } catch (IOException e) {
            lastResult = null;
            lastError = "network error";
            logger.warning("[ChatCraft] heartbeat failed: network error while contacting ChatCraft");
        }
    }

    /** True when credentials are stored, i.e. this server completed verification. */
    public boolean isVerified() {
        try {
            return credentials.load() != null;
        } catch (IOException e) {
            logger.warning("[ChatCraft] could not read credentials.yml (" + e.getMessage() + ")");
            return false;
        }
    }

    /** Result of the last successful heartbeat, or null when none succeeded yet. */
    public BackendClient.HeartbeatResult getLastResult() {
        return lastResult;
    }

    /** "HTTP &lt;status&gt;" or "network error" when the last heartbeat failed, null when it succeeded or never ran. */
    public String getLastError() {
        return lastError;
    }

    /** Epoch millis of the last heartbeat attempt, or -1 when none ran yet. */
    public long getLastRunAtMillis() {
        return lastRunAtMillis;
    }
}
