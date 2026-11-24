package net.ed1thy.emage.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

public final class NetworkEnvironment {

    public record Info(int compressionThreshold, @Nullable String proxy) {

        public boolean behindProxy() {
            return proxy != null;
        }

        public boolean compressionEnabled() {
            return compressionThreshold >= 0;
        }
    }

    private NetworkEnvironment() {}

    @NotNull
    public static Info detect(@NotNull File serverRoot) {
        String properties = read(new File(serverRoot, "server.properties"));
        String paperGlobal = read(new File(new File(serverRoot, "config"), "paper-global.yml"));
        String spigot = read(new File(serverRoot, "spigot.yml"));
        return parse(properties, paperGlobal, spigot);
    }

    @NotNull
    static Info parse(@Nullable String serverProperties, @Nullable String paperGlobalYml, @Nullable String spigotYml) {
        int threshold = 256;
        if (serverProperties != null) {
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(serverProperties));
                String value = properties.getProperty("network-compression-threshold");
                if (value != null) threshold = Integer.parseInt(value.trim());
            } catch (Exception ignored) {
            }
        }

        String proxy = null;
        if (paperGlobalYml != null) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(paperGlobalYml);
                if (yaml.getBoolean("proxies.velocity.enabled", false)) proxy = "Velocity";
            } catch (Exception ignored) {
            }
        }
        if (proxy == null && spigotYml != null) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(spigotYml);
                if (yaml.getBoolean("settings.bungeecord", false)) proxy = "BungeeCord";
            } catch (Exception ignored) {
            }
        }
        return new Info(threshold, proxy);
    }

    @Nullable
    public static String advice(@NotNull Info info) {
        if (!info.behindProxy() || !info.compressionEnabled()) return null;
        return "This server is behind " + info.proxy() + " and compresses packets itself (network-compression-threshold="
                + info.compressionThreshold() + "). Every map packet is compressed here, then decompressed and compressed again by the proxy, "
                + "which costs a lot of CPU on both when many maps are streamed and can add ping. Set network-compression-threshold=-1 in "
                + "server.properties and restart, as the Velocity FAQ recommends, so only the proxy compresses.";
    }

    @NotNull
    public static String summary(@NotNull Info info) {
        String compression = info.compressionEnabled() ? "enabled above " + info.compressionThreshold() + " bytes" : "disabled";
        return "Network: backend compression " + compression + ", proxy " + (info.behindProxy() ? info.proxy() : "not detected");
    }

    @Nullable
    private static String read(File file) {
        try {
            if (!file.isFile()) return null;
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
