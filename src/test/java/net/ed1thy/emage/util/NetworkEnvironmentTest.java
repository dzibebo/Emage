package net.ed1thy.emage.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkEnvironmentTest {

    private static final String VELOCITY_ON = "proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n";

    @Test
    void aVelocityBackendThatCompressesIsToldToSwitchCompressionOff() {
        NetworkEnvironment.Info info = NetworkEnvironment.parse("motd=x\nnetwork-compression-threshold=256\n", VELOCITY_ON, null);

        assertEquals(256, info.compressionThreshold());
        assertEquals("Velocity", info.proxy());
        assertNotNull(NetworkEnvironment.advice(info));
        assertTrue(NetworkEnvironment.advice(info).contains("network-compression-threshold=-1"));
    }

    @Test
    void aBackendWithCompressionAlreadyOffGetsNoAdvice() {
        NetworkEnvironment.Info info = NetworkEnvironment.parse("network-compression-threshold=-1\n", VELOCITY_ON, null);

        assertFalse(info.compressionEnabled());
        assertNull(NetworkEnvironment.advice(info));
    }

    @Test
    void aServerWithoutAProxyGetsNoAdvice() {
        NetworkEnvironment.Info info = NetworkEnvironment.parse("network-compression-threshold=256\n", "proxies:\n  velocity:\n    enabled: false\n", "settings:\n  bungeecord: false\n");

        assertFalse(info.behindProxy());
        assertNull(NetworkEnvironment.advice(info));
    }

    @Test
    void bungeecordIsRecognisedAndMissingFilesFallBackToTheVanillaDefault() {
        NetworkEnvironment.Info bungee = NetworkEnvironment.parse(null, null, "settings:\n  bungeecord: true\n");
        assertEquals("BungeeCord", bungee.proxy());
        assertEquals(256, bungee.compressionThreshold());
        assertNotNull(NetworkEnvironment.advice(bungee));
    }
}
