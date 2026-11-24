package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationDistributionTest {

    private static final int MAP_A = 10;
    private static final int FRAMES = 8;

    static ByteBuf packetBuffer(int mapId, int x, int z, int width, int height, int value) {
        ByteBuf buf = Unpooled.buffer();
        int v = mapId;
        while ((v & -128) != 0) {
            buf.writeByte(v & 127 | 128);
            v >>>= 7;
        }
        buf.writeByte(v);
        buf.writeByte(0);
        buf.writeBoolean(true);
        buf.writeBoolean(false);
        buf.writeByte(width);
        buf.writeByte(height);
        buf.writeByte(x);
        buf.writeByte(z);
        int length = width * height;
        while ((length & -128) != 0) {
            buf.writeByte(length & 127 | 128);
            length >>>= 7;
        }
        buf.writeByte(length);
        for (int i = 0; i < width * height; i++) buf.writeByte(value);
        return buf;
    }

    static MapFrameUpdate noise(int frame, int mapId, int seed) {
        java.util.Random random = new java.util.Random(seed);
        ByteBuf buf = Unpooled.buffer();
        int v = mapId;
        while ((v & -128) != 0) {
            buf.writeByte(v & 127 | 128);
            v >>>= 7;
        }
        buf.writeByte(v);
        buf.writeByte(0);
        buf.writeBoolean(true);
        buf.writeBoolean(false);
        buf.writeByte(128);
        buf.writeByte(128);
        buf.writeByte(0);
        buf.writeByte(0);
        buf.writeByte(0x80);
        buf.writeByte(0x80);
        buf.writeByte(0x01);
        byte[] data = new byte[16384];
        random.nextBytes(data);
        buf.writeBytes(data);
        return new MapFrameUpdate(new DeltaFrame[]{new DeltaFrame(frame, mapId, buf)});
    }

    static MapFrameUpdate update(int frame, int mapId, int x, int z, int width, int height, int value) {
        return new MapFrameUpdate(new DeltaFrame[]{new DeltaFrame(frame, mapId, packetBuffer(mapId, x, z, width, height, value))});
    }

    static class ClientSink implements FrameSink {
        final Map<Integer, byte[]> client = new HashMap<>();
        final List<int[]> log = new ArrayList<>();
        double capacity = 10_000_000;
        double queued;
        double maxQueued;
        int updates;

        @Override
        public boolean enqueueUpdate(UUID uuid, MapFrameUpdate update, int tier) {
            for (DeltaFrame part : update.parts()) {
                ByteBuf buf = part.packetBuf();
                AnimationCanvas scratch = new AnimationCanvas(1);
                scratch.applyPacket(0, buf, 1);
                int p = buf.readerIndex();
                while ((buf.getByte(p++) & 0x80) != 0) {
                }
                p += 3;
                int width = buf.getUnsignedByte(p++);
                int height = buf.getUnsignedByte(p++);
                int x = buf.getUnsignedByte(p++);
                int z = buf.getUnsignedByte(p++);
                byte[] target = client.computeIfAbsent(part.mapId(), k -> new byte[16384]);
                byte[] parsed = scratch.pixelsOf(0);
                for (int dy = 0; dy < height; dy++) {
                    for (int dx = 0; dx < width; dx++) {
                        target[(z + dy) * 128 + x + dx] = parsed[(z + dy) * 128 + x + dx];
                    }
                }
                queued += buf.readableBytes() + 16;
                maxQueued = Math.max(maxQueued, queued);
                log.add(new int[]{part.mapId(), width * height});
                updates++;
            }
            return true;
        }

        @Override
        public double animationDrainSeconds(UUID uuid, long extraBytes) {
            return (queued + extraBytes) / capacity;
        }

        @Override
        public long stillBudget(UUID uuid, int tier) {
            return 10_000_000L;
        }

        @Override
        public long animationBudget(UUID uuid) {
            return (long) Math.max(0, PlayerSendQueue.ANIMATION_TARGET_SECONDS * capacity - queued);
        }

        @Override
        public long animationFullBudget(UUID uuid, boolean lowPriority) {
            double seconds = lowPriority ? PlayerSendQueue.ANIMATION_FULL_LOW_TARGET_SECONDS : PlayerSendQueue.ANIMATION_FULL_TARGET_SECONDS;
            return (long) Math.max(0, seconds * capacity - queued);
        }

        @Override
        public void spendAnimationBudget(UUID uuid, long bytes) {
        }

        void transmit(double seconds) {
            queued = Math.max(0, queued - capacity * seconds);
        }
    }

    @TempDir
    File dir;

    private ExecutorService executor;
    private Cache<Long, MapFrameUpdate> cache;
    private SyncGroup group;
    private final UUID viewer = UUID.randomUUID();
    private long now = 1_000_000L;

    private SyncGroup buildGroup(int groupId, int maps, int frames, int deltaSize, boolean motion) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(new File(dir, "g" + groupId), executor);
        for (int frame = 0; frame < frames; frame++) {
            Map<Integer, MapFrameUpdate> updates = new HashMap<>();
            if (frame == 0) {
                for (int m = 0; m < maps; m++) updates.put(MAP_A + m, update(0, MAP_A + m, 0, 0, 128, 128, 1 + m));
            } else if (motion) {
                for (int m = 0; m < maps; m++) {
                    if ((frame + m) % 2 == 0) {
                        updates.put(MAP_A + m, update(frame, MAP_A + m, (frame * 9) % 100, (frame * 5) % 100, deltaSize, deltaSize, 10 + frame + m));
                    }
                }
            }
            if (!updates.isEmpty()) storage.saveBundledFrame(groupId, frame, updates);
            for (MapFrameUpdate u : updates.values()) u.freeMemory();
        }
        UUID worldId = UUID.randomUUID();
        List<FrameNode> nodes = new CopyOnWriteArrayList<>();
        for (int m = 0; m < maps; m++) nodes.add(new FrameNode(m, UUID.randomUUID(), worldId, 0, 0, m, 64, 0, MAP_A + m, null));
        MapMetadata meta = new MapMetadata(groupId, UUID.randomUUID(), "http://example/x.gif", maps, 1, frames, 50);
        return new SyncGroup(meta, nodes, storage, cache, executor, null, 96.0);
    }

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        cache = Caffeine.newBuilder().maximumSize(10).build();
        group = buildGroup(1, 2, FRAMES, 6, true);
    }

    @AfterEach
    void tearDown() {
        group.cleanup();
        executor.shutdownNow();
    }

    private void run(ClientSink sink, int iterations) throws Exception {
        for (int i = 0; i < iterations; i++) {
            now += 60;
            if (group.shouldTick(now)) group.tick(now, sink);
            sink.transmit(0.06);
            Thread.sleep(3);
        }
    }

    private void assertClientEqualsServer(ClientSink sink, int maps) {
        for (int m = 0; m < maps; m++) {
            byte[] expected = group.canvasPixelsForTesting(MAP_A + m);
            byte[] actual = sink.client.get(MAP_A + m);
            assertTrue(expected != null && actual != null, "map " + m + " was never delivered");
            assertArrayEquals(expected, actual, "the client's picture of map " + m + " differs from the server's");
        }
    }

    @Test
    void aHealthyViewerSeesTheCurrentPictureAndEveryChangeAfterIt() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);

        run(sink, 200);
        group.freezeClockForTesting();
        run(sink, 20);

        assertTrue(sink.updates > 10, "the animation must keep producing updates: " + sink.updates);
        assertClientEqualsServer(sink, 2);
    }

    @Test
    void theAnimationKeepsLoopingAndOnlyAFewFramesStayInMemory() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);

        Set<Integer> framesSeen = new HashSet<>();
        int wraps = 0;
        int last = -1;
        for (int i = 0; i < 900; i++) {
            now += 60;
            if (group.shouldTick(now)) group.tick(now, sink);
            sink.transmit(0.06);
            Thread.sleep(2);
            int frame = group.canvasFrameForTesting();
            framesSeen.add(frame);
            if (frame < last) wraps++;
            last = frame;
        }
        assertEquals(FRAMES, framesSeen.size(), "every frame must be played: " + framesSeen);
        assertTrue(wraps >= 5, "the animation must loop repeatedly, wraps: " + wraps);
        group.freezeClockForTesting();
        run(sink, 20);
        assertClientEqualsServer(sink, 2);
    }

    @Test
    void aSlowConnectionGetsFewerFramesButNeverAQueueAndEndsUpCorrect() throws Exception {
        ClientSink sink = new ClientSink();
        sink.capacity = 30_000;
        group.setViewerStateForTesting(viewer, true, true);

        run(sink, 300);

        double limit = PlayerSendQueue.ANIMATION_TARGET_SECONDS * sink.capacity + 2 * (16_500 + 16 + 40);
        assertTrue(sink.maxQueued <= limit + 16_600, "queue must stay tiny on a slow link, was " + sink.maxQueued + " limit " + limit);

        sink.capacity = 10_000_000;
        group.freezeClockForTesting();
        run(sink, 30);
        assertClientEqualsServer(sink, 2);
    }

    @Test
    void aViewerOutsideAnimationRangeGetsTheCurrentPictureOnceAndNothingMore() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, false, true);

        run(sink, 150);

        assertEquals(2, sink.updates, "one full picture per map and no animation traffic");
        assertEquals(0, group.canvasFrameForTesting(), "nobody animates, so the clock does not run");
    }

    @Test
    void aViewerLookingAwayGetsTheStillPictureOnly() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, false);

        run(sink, 150);

        assertEquals(2, sink.updates);
    }

    @Test
    void whoeverComesBackIsBroughtUpToDateImmediatelyNotAtTheNextLoop() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);
        run(sink, 120);

        group.setViewerStateForTesting(viewer, false, true);
        run(sink, 40);
        int whileAway = sink.updates;
        run(sink, 40);
        assertEquals(whileAway, sink.updates, "no traffic while the viewer is far away");

        group.setViewerStateForTesting(viewer, true, true);
        group.freezeClockForTesting();
        run(sink, 6);
        assertClientEqualsServer(sink, 2);
    }

    @Test
    void aPictureWithoutAnyMotionIsDeliveredOnceLikeAStillImage() throws Exception {
        group.cleanup();
        group = buildGroup(2, 1, 8, 6, false);

        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);
        run(sink, 80);

        assertEquals(1, sink.updates);
        assertTrue(sink.client.containsKey(MAP_A));
    }

    @Test
    void aGifTooBigForTheConnectionNeverBuildsAHugeQueue() throws Exception {
        group.cleanup();
        group = buildGroup(3, 8, 16, 100, true);

        ClientSink sink = new ClientSink();
        sink.capacity = 400_000;
        group.setViewerStateForTesting(viewer, true, true);
        run(sink, 400);

        double oneRound = 8 * (100 * 100 + 16) + 8 * (16_384 + 16);
        double limit = PlayerSendQueue.ANIMATION_TARGET_SECONDS * sink.capacity + oneRound;
        assertTrue(sink.maxQueued <= limit, "animation queue was " + sink.maxQueued + " bytes, limit " + limit);
        assertTrue(sink.updates > 16, "the animation must still make progress");
    }

    private SyncGroup buildNoisyGroup(int groupId, int maps, int frames, int deltaSize) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(new File(dir, "n" + groupId), executor);
        for (int frame = 0; frame < frames; frame++) {
            Map<Integer, MapFrameUpdate> updates = new HashMap<>();
            for (int m = 0; m < maps; m++) {
                if (frame == 0) {
                    updates.put(MAP_A + m, noise(0, MAP_A + m, 77 + m));
                } else if (m == 0 || deltaSize > 1) {
                    updates.put(MAP_A + m, update(frame, MAP_A + m, frame % 100, (frame * 3) % 100, deltaSize, deltaSize, 20 + frame));
                }
            }
            storage.saveBundledFrame(groupId, frame, updates);
            for (MapFrameUpdate u : updates.values()) u.freeMemory();
        }
        UUID worldId = UUID.randomUUID();
        List<FrameNode> nodes = new CopyOnWriteArrayList<>();
        for (int m = 0; m < maps; m++) nodes.add(new FrameNode(m, UUID.randomUUID(), worldId, 0, 0, m, 64, 0, MAP_A + m, null));
        MapMetadata meta = new MapMetadata(groupId, UUID.randomUUID(), "http://example/noisy.gif", maps, 1, frames, 50);
        SyncGroup built = new SyncGroup(meta, nodes, storage, cache, executor, null, 96.0);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!built.animationInfoLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        return built;
    }

    @Test
    void aGifWhoseFramesBarelyDifferIsTreatedAsAStillPicture() throws Exception {
        group.cleanup();
        group = buildNoisyGroup(5, 4, 10, 1);

        assertTrue(!group.isAnimatedPicture(), "frames that differ by a pixel or two are a still picture");

        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);
        run(sink, 100);

        assertEquals(4, sink.updates, "one picture per map and nothing else");
        assertEquals(0, group.canvasFrameForTesting());
    }

    @Test
    void aGifWithRealMotionIsNotMistakenForAStillPicture() throws Exception {
        group.cleanup();
        group = buildNoisyGroup(6, 4, 10, 30);

        assertTrue(group.isAnimatedPicture(), "a visibly moving gif must stay animated");
    }

    @Test
    void loadingAFrameAgainNeverFreesTheOneThatIsAlreadyInUse() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!group.baseLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        group.loadFrameForTesting(3);
        MapFrameUpdate frameThree = group.frameForTesting(3, MAP_A);
        MapFrameUpdate base = group.frameForTesting(0, MAP_A);
        assertTrue(frameThree != null && base != null);

        group.loadFrameForTesting(3);
        group.loadFrameForTesting(0);

        assertTrue(!frameThree.isFreed(), "a frame being read must not be freed by a second load of the same frame");
        assertTrue(!base.isFreed(), "the base picture being read must not be freed by a second load");
        assertTrue(group.frameForTesting(3, MAP_A) == frameThree, "the frame that is already loaded must stay in place");
        assertTrue(group.frameForTesting(0, MAP_A) == base, "the base picture that is already loaded must stay in place");
    }

    @Test
    void aRemovedGifNeverLoadsFramesIntoMemoryAgain() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!group.baseLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        group.loadFrameForTesting(2);
        assertTrue(group.loadedWindowFramesForTesting() > 0);

        group.cleanup();
        group.loadFrameForTesting(3);
        group.loadFrameForTesting(0);

        assertEquals(0, group.loadedWindowFramesForTesting(), "a removed gif must not keep frames in memory");
        assertTrue(!group.baseLoadedForTesting(), "a removed gif must not keep its base pictures in memory");
    }

    @Test
    void aPausedViewerSendsNothingAndResumesWhenTheGifIsAdmittedAgain() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);
        run(sink, 60);
        assertTrue(sink.updates > 0);

        group.setFrozenFor(viewer, true);
        run(sink, 15);
        int paused = sink.updates;
        run(sink, 60);
        assertEquals(paused, sink.updates, "a paused gif must not send anything");

        group.setFrozenFor(viewer, false);
        run(sink, 60);
        assertTrue(sink.updates > paused, "the gif must start playing again");
    }

    @Test
    void aGifThatWasPausedForALongTimeShowsExactlyTheServerPictureAfterResuming() throws Exception {
        ClientSink sink = new ClientSink();
        group.setViewerStateForTesting(viewer, true, true);
        run(sink, 40);

        for (int round = 0; round < 6; round++) {
            group.setFrozenFor(viewer, true);
            run(sink, 25 + round * 13);
            group.setFrozenFor(viewer, false);
            run(sink, 12 + round * 7);
        }
        group.setFrozenFor(viewer, false);
        run(sink, 60);
        group.freezeClockForTesting();
        run(sink, 40);

        assertClientEqualsServer(sink, 2);
    }
}
