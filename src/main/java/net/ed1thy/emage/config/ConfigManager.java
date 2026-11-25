package net.ed1thy.emage.config;

import net.ed1thy.emage.Emage;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

public class ConfigManager {

    private final Emage plugin;
    private FileConfiguration config;

    public int maxPacketsPerTick;
    public int viewDistanceBlocks;
    public int animatedViewDistanceBlocks;
    public int maxBytesPerSecondPerPlayer;
    public int minBytesPerSecondPerPlayer;
    public int maxPendingBytesPerPlayer;
    public int maxTotalBytesPerSecond;
    public int targetQueueDelayMs;
    public boolean latencyProbe;
    public boolean limitAnimatedGifs;
    public boolean scatterMapUpdates;

    public String dbFileName;
    public int dbPoolSize;

    public int maxGifFrames;
    public int maxGifGridSize;
    public int maxImageGridSize;

    public int maxFileSizeMb;
    public int connectTimeoutSeconds;
    public int readTimeoutSeconds;
    public int maxRedirects;
    public boolean blockInternalUrls;

    public int cacheMaxMemoryMb;
    public int cacheExpireMinutes;
    public int idleReleaseSeconds;

    public int cooldownSeconds;
    public int maxConcurrentTasks;

    public boolean useActionBar;
    public boolean enableSounds;
    public String soundSuccess;
    public String soundError;
    public String soundStart;

    public ConfigManager(@NotNull Emage plugin) {
        this.plugin = plugin;
    }

    public static final int CONFIG_VERSION = 7;

    public static boolean migrate(@NotNull FileConfiguration config) {
        if (config.getInt("config-version", 1) >= CONFIG_VERSION) return false;
        upgradeDefault(config, "performance.max-bytes-per-second-per-player", 2097152, 33554432);
        upgradeDefault(config, "performance.max-total-bytes-per-second", 16777216, 67108864);
        upgradeDefault(config, "performance.target-queue-delay-ms", 80, 20);
        upgradeDefault(config, "performance.target-queue-delay-ms", 30, 20);
        upgradeDefault(config, "performance.max-bytes-per-second-per-player", 33554432, 8388608);
        upgradeDefault(config, "performance.max-bytes-per-second-per-player", 8388608, 16777216);
        upgradeDefault(config, "performance.min-bytes-per-second-per-player", 262144, 32768);
        upgradeDefault(config, "performance.animated-view-distance-blocks", 32, 48);
        upgradeDefault(config, "quality.max-gif-grid-size", 5, 10);
        upgradeDefault(config, "quality.max-gif-grid-size", 16, 10);
        config.set("config-version", CONFIG_VERSION);
        return true;
    }

    private static void upgradeDefault(FileConfiguration config, String path, int oldDefault, int newDefault) {
        if (config.contains(path) && config.getInt(path) == oldDefault) {
            config.set(path, newDefault);
        }
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        if (migrate(config)) {
            plugin.saveConfig();
        }

        this.maxPacketsPerTick = config.getInt("performance.max-packets-per-tick", 64);
        this.viewDistanceBlocks = config.getInt("performance.view-distance-blocks", 96);
        this.animatedViewDistanceBlocks = config.getInt("performance.animated-view-distance-blocks", 48);
        this.maxBytesPerSecondPerPlayer = Math.max(16384, config.getInt("performance.max-bytes-per-second-per-player", 16777216));
        this.maxTotalBytesPerSecond = Math.max(1048576, config.getInt("performance.max-total-bytes-per-second", 67108864));
        this.minBytesPerSecondPerPlayer = Math.max(4096, config.getInt("performance.min-bytes-per-second-per-player", 32768));
        this.maxPendingBytesPerPlayer = Math.max(33000, config.getInt("performance.max-pending-bytes-per-player", 98304));
        this.targetQueueDelayMs = Math.max(20, config.getInt("performance.target-queue-delay-ms", 20));
        this.latencyProbe = config.getBoolean("performance.latency-probe", true);
        this.limitAnimatedGifs = config.getBoolean("performance.limit-animated-gifs", true);
        this.scatterMapUpdates = config.getBoolean("performance.scatter-map-updates", true);

        this.dbFileName = config.getString("storage.database.file-name", "emage_meta.db");
        this.dbPoolSize = config.getInt("storage.database.pool-size", 2);

        this.maxGifFrames = config.getInt("quality.max-gif-frames", 240);
        this.maxGifGridSize = config.getInt("quality.max-gif-grid-size", 10);
        this.maxImageGridSize = config.getInt("quality.max-image-grid-size", 16);

        this.maxFileSizeMb = config.getInt("downloads.max-file-size-mb", 50);
        this.connectTimeoutSeconds = config.getInt("downloads.connect-timeout-seconds", 10);
        this.readTimeoutSeconds = config.getInt("downloads.read-timeout-seconds", 30);
        this.maxRedirects = config.getInt("downloads.max-redirects", 5);
        this.blockInternalUrls = config.getBoolean("downloads.block-internal-urls", true);

        this.cacheMaxMemoryMb = config.getInt("cache.max-memory-mb", 100);
        this.cacheExpireMinutes = config.getInt("cache.expire-minutes", 30);
        this.idleReleaseSeconds = config.getInt("cache.idle-release-seconds", 300);

        this.cooldownSeconds = config.getInt("rate-limits.cooldown-seconds", 5);
        this.maxConcurrentTasks = config.getInt("rate-limits.max-concurrent-tasks", 3);

        this.useActionBar = config.getBoolean("feedback.use-action-bar", true);
        this.enableSounds = config.getBoolean("feedback.sounds.enabled", true);
        this.soundSuccess = config.getString("feedback.sounds.success", "entity.player.levelup");
        this.soundError = config.getString("feedback.sounds.error", "entity.villager.no");
        this.soundStart = config.getString("feedback.sounds.start", "block.note_block.chime");
    }

    @NotNull
    public FileConfiguration getRawConfig() {
        return config;
    }
}
