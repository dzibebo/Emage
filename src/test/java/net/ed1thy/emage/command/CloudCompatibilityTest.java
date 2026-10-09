package net.ed1thy.emage.command;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CloudCompatibilityTest {

    @Test
    void testCloudPaperVersionAndPatch() throws Exception {
        // Verify that ItemStackParser$ModernParser class file contains asBukkitMirror
        String classResource = "org/incendo/cloud/bukkit/parser/ItemStackParser$ModernParser.class";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classResource)) {
            assertNotNull(is, "ItemStackParser$ModernParser.class should be present in the classpath");
            byte[] bytes = is.readAllBytes();
            String content = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue(content.contains("asBukkitMirror"), 
                    "Cloud-bukkit MUST contain asBukkitMirror lookup for Paper 26.3+ compatibility");
            assertTrue(content.contains("asBukkitCopy, asCraftMirror or asBukkitMirror"), 
                    "Cloud-bukkit error message should verify support for asBukkitMirror");
        }
    }
}
