package net.ed1thy.emage.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationTest {

    private static YamlConfiguration parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test
    void oldDefaultLimitsAreRaisedOnce() throws Exception {
        YamlConfiguration config = parse("""
                performance:
                  max-bytes-per-second-per-player: 2097152
                  min-bytes-per-second-per-player: 262144
                  max-total-bytes-per-second: 16777216
                  target-queue-delay-ms: 80
                  animated-view-distance-blocks: 32
                quality:
                  max-gif-grid-size: 5
                """);

        assertTrue(ConfigManager.migrate(config));

        assertEquals(20, config.getInt("performance.target-queue-delay-ms"));
        assertEquals(48, config.getInt("performance.animated-view-distance-blocks"));
        assertEquals(67108864, config.getInt("performance.max-bytes-per-second-per-player"));
        assertEquals(67108864, config.getInt("performance.max-total-bytes-per-second"));
        assertEquals(10, config.getInt("quality.max-gif-grid-size"));
        assertEquals(32768, config.getInt("performance.min-bytes-per-second-per-player"));
        assertEquals(ConfigManager.CONFIG_VERSION, config.getInt("config-version"));
        assertFalse(ConfigManager.migrate(config), "a migrated config must not be touched again");
    }

    @Test
    void valuesTheAdminChangedAreKept() throws Exception {
        YamlConfiguration config = parse("""
                performance:
                  max-bytes-per-second-per-player: 1048576
                  max-total-bytes-per-second: 8388608
                quality:
                  max-gif-grid-size: 8
                """);

        assertTrue(ConfigManager.migrate(config));

        assertEquals(1048576, config.getInt("performance.max-bytes-per-second-per-player"));
        assertEquals(8388608, config.getInt("performance.max-total-bytes-per-second"));
        assertEquals(8, config.getInt("quality.max-gif-grid-size"));
    }

    @Test
    void missingKeysAreLeftToTheBuiltInDefaults() throws Exception {
        YamlConfiguration config = parse("performance:\n  view-distance-blocks: 96\n");

        assertTrue(ConfigManager.migrate(config));

        assertFalse(config.contains("performance.max-bytes-per-second-per-player"));
        assertEquals(ConfigManager.CONFIG_VERSION, config.getInt("config-version"));
    }
}
