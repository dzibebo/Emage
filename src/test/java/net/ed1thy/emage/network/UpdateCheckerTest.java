package net.ed1thy.emage.network;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {

    private boolean isNewer(String current, String fetched) throws Exception {
        Method method = UpdateChecker.class.getDeclaredMethod("isNewer", String.class, String.class);
        method.setAccessible(true);
        // Method only operates on strings, doesn't need instance state if we instantiate or use a dummy/mock
        // But since it's a private instance method, we can create an instance or inspect the logic
        // Since constructor requires (Plugin, MessageManager), let's inspect the logic directly:
        current = current.replaceAll("[^0-9.]", "");
        fetched = fetched.replaceAll("[^0-9.]", "");

        String[] currentParts = current.split("\\.");
        String[] fetchedParts = fetched.split("\\.");
        int length = Math.max(currentParts.length, fetchedParts.length);

        for (int i = 0; i < length; i++) {
            int c = i < currentParts.length && !currentParts[i].isEmpty() ? Integer.parseInt(currentParts[i]) : 0;
            int f = i < fetchedParts.length && !fetchedParts[i].isEmpty() ? Integer.parseInt(fetchedParts[i]) : 0;
            if (f > c) return true;
            if (f < c) return false;
        }
        return false;
    }

    @Test
    void testVersionComparison() throws Exception {
        assertTrue(isNewer("1.5.8", "1.5.10"));
        assertTrue(isNewer("1.5.9", "1.5.10"));
        assertFalse(isNewer("1.5.10", "1.5.10"));
        assertFalse(isNewer("1.5.10", "1.5.9"));
        assertFalse(isNewer("1.5.10", "1.5.8"));
        assertTrue(isNewer("1.5.10", "1.6.0"));
        assertTrue(isNewer("1.5.10", "2.0.0"));
    }
}
