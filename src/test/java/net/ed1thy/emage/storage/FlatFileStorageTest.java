package net.ed1thy.emage.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlatFileStorageTest {

    private static byte[] bytesOf(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    private static Map<Integer, MapFrameUpdate> sample() {
        Random random = new Random(7);
        Map<Integer, MapFrameUpdate> updates = new HashMap<>();
        for (int id : new int[]{5, 77, 100000}) {
            byte[] data = new byte[1000 + id % 500];
            random.nextBytes(data);
            ByteBuf buf = Unpooled.directBuffer(data.length);
            buf.writeBytes(data);
            updates.put(id, new MapFrameUpdate(new DeltaFrame[]{new DeltaFrame(3, id, buf)}));
        }
        return updates;
    }

    @Test
    void savedFramesLoadBackIdentically(@TempDir File dir) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(dir, Executors.newSingleThreadExecutor());
        Map<Integer, MapFrameUpdate> updates = sample();
        storage.saveBundledFrame(9, 3, updates);

        Map<Integer, MapFrameUpdate> loaded = storage.loadBundledFrame(9, 3);
        assertEquals(updates.keySet(), loaded.keySet());
        for (int id : updates.keySet()) {
            assertArrayEquals(bytesOf(updates.get(id).parts()[0].packetBuf()), bytesOf(loaded.get(id).parts()[0].packetBuf()));
            loaded.get(id).freeMemory();
        }
        assertTrue(new File(dir, "9/frame_3.zst").exists());
        assertFalse(new File(dir, "9/frame_3.zst.tmp").exists());
    }

    @Test
    void savedFormatMatchesTheOriginalReaderFormat(@TempDir File dir) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(dir, Executors.newSingleThreadExecutor());
        Map<Integer, MapFrameUpdate> updates = sample();
        storage.saveBundledFrame(1, 0, updates);

        byte[] compressed = Files.readAllBytes(new File(dir, "1/frame_0.zst").toPath());
        long size = com.github.luben.zstd.Zstd.decompressedSize(compressed);
        assertTrue(size > 0, "content size must be stored in the frame header");
        byte[] raw = new byte[(int) size];
        com.github.luben.zstd.Zstd.decompress(raw, compressed);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int count = in.readInt();
        assertEquals(updates.size(), count);
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            int parts = in.readInt();
            assertEquals(1, parts);
            int len = in.readInt();
            byte[] data = new byte[len];
            in.readFully(data);
            assertArrayEquals(bytesOf(updates.get(id).parts()[0].packetBuf()), data);
        }
    }

    @Test
    void filesWrittenByTheOriginalWriterStillLoad(@TempDir File dir) throws Exception {
        Map<Integer, MapFrameUpdate> updates = sample();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(updates.size());
            for (Map.Entry<Integer, MapFrameUpdate> entry : updates.entrySet()) {
                out.writeInt(entry.getKey());
                out.writeInt(1);
                byte[] data = bytesOf(entry.getValue().parts()[0].packetBuf());
                out.writeInt(data.length);
                out.write(data);
            }
        }
        byte[] raw = baos.toByteArray();
        byte[] compressed = new byte[(int) com.github.luben.zstd.Zstd.compressBound(raw.length)];
        long n = com.github.luben.zstd.Zstd.compress(compressed, raw, 3);
        File groupDir = new File(dir, "4");
        groupDir.mkdirs();
        Files.write(new File(groupDir, "frame_2.zst").toPath(), java.util.Arrays.copyOf(compressed, (int) n));

        FlatFileStorage storage = new FlatFileStorage(dir, Executors.newSingleThreadExecutor());
        Map<Integer, MapFrameUpdate> loaded = storage.loadBundledFrame(4, 2);
        assertEquals(updates.keySet(), loaded.keySet());
        for (int id : updates.keySet()) {
            assertArrayEquals(bytesOf(updates.get(id).parts()[0].packetBuf()), bytesOf(loaded.get(id).parts()[0].packetBuf()));
            loaded.get(id).freeMemory();
        }
    }
}
