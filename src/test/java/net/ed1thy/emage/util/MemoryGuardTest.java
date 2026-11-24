package net.ed1thy.emage.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryGuardTest {

    @Test
    void readsInactiveFileFromCgroupStat() {
        String stat = "anon 1000\nfile 5000\ninactive_file 1234\nactive_file 10\n";
        assertEquals(1234, MemoryGuard.statValue(stat, "inactive_file"));
        assertEquals(0, MemoryGuard.statValue(stat, "missing"));
    }

    @Test
    void smallRequestsFitAndAbsurdRequestsDoNot() {
        assertTrue(MemoryGuard.fits(1024 * 1024, 1024 * 1024));
        assertFalse(MemoryGuard.fits(Long.MAX_VALUE / 4, 0));
    }
}
