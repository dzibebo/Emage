package net.ed1thy.emage.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MemoryGuard {

    private static final double SAFETY = 0.8;
    private static final long UNLIMITED_THRESHOLD = 1L << 56;

    private MemoryGuard() {}

    public static long liveHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long live = 0;
        boolean measured = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage usage = pool.getCollectionUsage();
            if (usage != null) {
                live += Math.max(0L, usage.getUsed());
                measured = true;
            }
        }
        return measured ? Math.min(live, used) : used;
    }

    public static long heapFreeBytes() {
        return Math.max(0L, Runtime.getRuntime().maxMemory() - liveHeapBytes());
    }

    public static long[] containerMemory() {
        long[] v2 = readCgroup(Path.of("/sys/fs/cgroup/memory.max"), Path.of("/sys/fs/cgroup/memory.current"),
                Path.of("/sys/fs/cgroup/memory.stat"), "inactive_file");
        if (v2 != null) return v2;
        return readCgroup(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"), Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"),
                Path.of("/sys/fs/cgroup/memory/memory.stat"), "total_inactive_file");
    }

    private static long[] readCgroup(Path limitFile, Path usageFile, Path statFile, String inactiveKey) {
        try {
            if (!Files.isReadable(limitFile) || !Files.isReadable(usageFile)) return null;
            String limitText = Files.readString(limitFile).trim();
            if (limitText.isEmpty() || limitText.equals("max")) return null;
            long limit = Long.parseLong(limitText);
            if (limit <= 0 || limit >= UNLIMITED_THRESHOLD) return null;
            long usage = Long.parseLong(Files.readString(usageFile).trim());
            long inactive = 0;
            if (Files.isReadable(statFile)) {
                inactive = statValue(Files.readString(statFile), inactiveKey);
            }
            return new long[]{limit, Math.max(0L, usage - inactive)};
        } catch (Exception e) {
            return null;
        }
    }

    static long statValue(String stat, String key) {
        for (String line : stat.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals(key)) {
                try {
                    return Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    public static boolean fits(long heapBytes, long nativeBytes) {
        Runtime runtime = Runtime.getRuntime();
        long live = liveHeapBytes();
        if (heapBytes > (runtime.maxMemory() - live) * SAFETY) return false;

        long[] container = containerMemory();
        if (container == null) return true;
        long committedFree = Math.max(0L, runtime.totalMemory() - live);
        long containerFree = Math.max(0L, container[0] - container[1]);
        return heapBytes + nativeBytes <= (committedFree + containerFree) * SAFETY;
    }
}
