package net.ed1thy.emage.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class FlatFileStorage {

    private final File dataFolder;
    private final ExecutorService ioExecutor;

    public FlatFileStorage(@NotNull Emage plugin, @NotNull ExecutorService ioExecutor) {
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }
        this.ioExecutor = ioExecutor;
    }

    public FlatFileStorage(@NotNull File dataFolder, @NotNull ExecutorService ioExecutor) {
        this.dataFolder = dataFolder;
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }
        this.ioExecutor = ioExecutor;
    }

    public CompletableFuture<Void> saveBundledFrameAsync(int syncGroupId, int frameIndex, Map<Integer, MapFrameUpdate> updates) {
        return CompletableFuture.runAsync(() -> {
            try {
                saveBundledFrame(syncGroupId, frameIndex, updates);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save frame data to disk", e);
            }
        }, ioExecutor);
    }

    public void saveBundledFrame(int syncGroupId, int frameIndex, Map<Integer, MapFrameUpdate> updates) throws IOException {
        if (updates.isEmpty()) return;

        int total = 4;
        for (MapFrameUpdate update : updates.values()) {
            total += 8;
            for (DeltaFrame df : update.parts()) {
                total += 4 + df.packetBuf().readableBytes();
            }
        }

        byte[] uncompressed = new byte[total];
        java.nio.ByteBuffer out = java.nio.ByteBuffer.wrap(uncompressed);
        out.putInt(updates.size());
        for (Map.Entry<Integer, MapFrameUpdate> entry : updates.entrySet()) {
            out.putInt(entry.getKey());
            MapFrameUpdate update = entry.getValue();
            out.putInt(update.parts().length);
            for (DeltaFrame df : update.parts()) {
                ByteBuf buf = df.packetBuf();
                int len = buf.readableBytes();
                out.putInt(len);
                buf.getBytes(buf.readerIndex(), uncompressed, out.position(), len);
                out.position(out.position() + len);
            }
        }

        byte[] compressed = new byte[(int) com.github.luben.zstd.Zstd.compressBound(uncompressed.length)];
        long compressedSize = com.github.luben.zstd.Zstd.compress(compressed, uncompressed, 3);
        if (com.github.luben.zstd.Zstd.isError(compressedSize)) {
            throw new IOException("Compression failed: " + com.github.luben.zstd.Zstd.getErrorName(compressedSize));
        }
        uncompressed = null;

        File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
        if (!groupDir.exists()) groupDir.mkdirs();

        File frameFile = new File(groupDir, "frame_" + frameIndex + ".zst");
        File tempFile = new File(groupDir, "frame_" + frameIndex + ".zst.tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(compressed, 0, (int) compressedSize);
        }
        try {
            Files.move(tempFile.toPath(), frameFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile.toPath(), frameFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @NotNull
    public Map<Integer, MapFrameUpdate> loadBundledFrame(int syncGroupId, int frameIndex) throws IOException {
        File frameFile = new File(dataFolder, syncGroupId + File.separator + "frame_" + frameIndex + ".zst");
        if (!frameFile.exists()) return java.util.Collections.emptyMap();

        byte[] compressed = Files.readAllBytes(frameFile.toPath());
        long decompressedSize = com.github.luben.zstd.Zstd.decompressedSize(compressed);
        if (decompressedSize <= 0) return java.util.Collections.emptyMap();

        byte[] uncompressed = new byte[(int) decompressedSize];
        com.github.luben.zstd.Zstd.decompress(uncompressed, compressed);

        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(uncompressed);
        int numUpdates = in.getInt();
        Map<Integer, MapFrameUpdate> resultMap = new java.util.HashMap<>();

        try {
            for (int i = 0; i < numUpdates; i++) {
                int mapId = in.getInt();
                int numParts = in.getInt();
                DeltaFrame[] parts = new DeltaFrame[numParts];
                for (int p = 0; p < numParts; p++) {
                    int len = in.getInt();
                    if (len < 0 || len > in.remaining()) {
                        for (int q = 0; q < p; q++) parts[q].freeMemory();
                        throw new IllegalStateException("bad part length " + len);
                    }
                    ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(len);
                    buf.writeBytes(uncompressed, in.position(), len);
                    in.position(in.position() + len);
                    parts[p] = new DeltaFrame(frameIndex, mapId, buf);
                }
                resultMap.put(mapId, new MapFrameUpdate(parts));
            }
        } catch (RuntimeException e) {
            for (MapFrameUpdate update : resultMap.values()) {
                update.freeMemory();
            }
            throw new IOException("Corrupt frame data: " + frameFile.getName(), e);
        }
        return resultMap;
    }

    public CompletableFuture<Void> saveFrameDelaysAsync(int syncGroupId, int[] delays) {
        return CompletableFuture.runAsync(() -> {
            File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
            if (!groupDir.exists()) groupDir.mkdirs();
            File delayFile = new File(groupDir, "delays.dat");
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(delayFile))) {
                dos.writeInt(delays.length);
                for (int d : delays) dos.writeInt(d);
            } catch (IOException ignored) {}
        }, ioExecutor);
    }

    public int[] loadFrameDelays(int syncGroupId) {
        File delayFile = new File(dataFolder, syncGroupId + File.separator + "delays.dat");
        if (!delayFile.exists()) return null;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(delayFile))) {
            int len = dis.readInt();
            int[] delays = new int[len];
            for (int i = 0; i < len; i++) delays[i] = dis.readInt();
            return delays;
        } catch (IOException e) {
            return null;
        }
    }

    public long frameDataLength(int syncGroupId, int frameIndex) {
        return new File(dataFolder, syncGroupId + File.separator + "frame_" + frameIndex + ".zst").length();
    }

    public boolean hasFrameData(int syncGroupId, int frameIndex) {
        return new File(dataFolder, syncGroupId + File.separator + "frame_" + frameIndex + ".zst").exists();
    }

    public boolean groupExists(int syncGroupId) {
        File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
        return groupDir.exists() && groupDir.isDirectory();
    }

    public void deleteSyncGroup(int syncGroupId) {
        File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
        deleteDirectory(groupDir);
    }

    public int cleanupOrphanedFrameData(Set<Integer> activeIds) {
        if (!dataFolder.exists()) return 0;
        File[] dirs = dataFolder.listFiles();
        if (dirs == null) return 0;
        int deleted = 0;
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                try {
                    int id = Integer.parseInt(dir.getName());
                    if (!activeIds.contains(id)) {
                        deleteDirectory(dir);
                        deleted++;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return deleted;
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
    }

    public void shutdown() {}
}
