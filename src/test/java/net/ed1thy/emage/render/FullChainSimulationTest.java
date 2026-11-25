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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FullChainSimulationTest {

    private static final int MIN_RATE = 32_768;
    private static final int MAX_RATE = 33_554_432;
    private static final long BASE_NANOS = 5_000_000_000L;
    private static final int TARGET_DELAY_MS = 30;

    private static final class Chunk {
        double left;
        final int mapId;
        final int probeId;
        final boolean probe;
        boolean animation;

        Chunk(double left, int mapId, int probeId, boolean probe) {
            this.left = left;
            this.mapId = mapId;
            this.probeId = probeId;
            this.probe = probe;
        }
    }

    private static final class SimulatedLink implements Transport {
        final double bandwidth;
        final double rtt;
        final double highWater;
        final boolean probes;
        final ArrayDeque<Chunk> pipe = new ArrayDeque<>();
        final List<double[]> pongs = new ArrayList<>();
        final Map<Integer, Double> deliveredAt = new HashMap<>();
        double pending;
        double nowSec;
        double animationBytes;
        final Map<Integer, byte[]> client = new HashMap<>();
        final Map<Integer, Integer> animationPacketsPerMap = new HashMap<>();
        final Map<Integer, List<Double>> animationTimes = new HashMap<>();
        double jitter;
        final java.util.Random random = new java.util.Random(42);

        SimulatedLink(double bandwidth, double rtt, double highWater, boolean probes) {
            this.bandwidth = bandwidth;
            this.rtt = rtt;
            this.highWater = highWater;
            this.probes = probes;
        }

        SimulatedLink withJitter(double jitter) {
            this.jitter = jitter;
            return this;
        }

        double hiccupEvery;
        double hiccupLen;
        double extraFrom = Double.MAX_VALUE;
        double extraDelay;

        SimulatedLink withExtraDelay(double from, double delay) {
            this.extraFrom = from;
            this.extraDelay = delay;
            return this;
        }

        SimulatedLink withHiccups(double every, double length) {
            this.hiccupEvery = every;
            this.hiccupLen = length;
            return this;
        }

        private double effectiveDue(double due) {
            if (due >= extraFrom) due += extraDelay;
            if (hiccupEvery <= 0) return due;
            double phase = due % hiccupEvery;
            return phase < hiccupLen ? due - phase + hiccupLen : due;
        }

        private double jitterSample() {
            if (jitter <= 0) return 0;
            double value = jitter * random.nextDouble();
            if (random.nextDouble() < 0.03) value += jitter * 3 * random.nextDouble();
            return value;
        }

        @Override
        public boolean isWritable() {
            return pending < highWater;
        }

        @Override
        public long pendingBytes() {
            return (long) pending;
        }

        @Override
        public boolean write(DeltaFrame source) {
            int bytes = source.packetBuf().readableBytes() + 16;
            Chunk chunk = new Chunk(bytes, source.mapId(), 0, false);
            chunk.animation = source.frameIndex() > 0;
            pipe.add(chunk);
            pending += bytes;
            if (source.frameIndex() > 0) {
                animationBytes += bytes;
                animationPacketsPerMap.merge(source.mapId(), 1, Integer::sum);
            }
            decode(source);
            return true;
        }

        private final AnimationCanvas scratch = new AnimationCanvas(1);

        private void decode(DeltaFrame source) {
            ByteBuf buf = source.packetBuf();
            if (!scratch.applyPacket(0, buf, 1)) return;
            int p = buf.readerIndex();
            while ((buf.getByte(p++) & 0x80) != 0) {
            }
            p += 3;
            int width = buf.getUnsignedByte(p++);
            int height = buf.getUnsignedByte(p++);
            int x = buf.getUnsignedByte(p++);
            int z = buf.getUnsignedByte(p++);
            byte[] target = client.computeIfAbsent(source.mapId(), k -> new byte[16384]);
            byte[] parsed = scratch.pixelsOf(0);
            for (int dy = 0; dy < height; dy++) {
                for (int dx = 0; dx < width; dx++) {
                    target[(z + dy) * 128 + x + dx] = parsed[(z + dy) * 128 + x + dx];
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean supportsProbe() {
            return probes;
        }

        @Override
        public boolean sendProbe(int id) {
            pipe.add(new Chunk(0, 0, id, true));
            return true;
        }

        void advance(double dt) {
            double capacity = bandwidth * dt;
            while (capacity > 0 && !pipe.isEmpty()) {
                Chunk c = pipe.peek();
                if (c.probe) {
                    pipe.poll();
                    pongs.add(new double[]{nowSec + rtt + jitterSample(), c.probeId});
                    continue;
                }
                double take = Math.min(capacity, c.left);
                c.left -= take;
                capacity -= take;
                pending -= take;
                if (c.left <= 1e-9) {
                    pipe.poll();
                    deliveredAt.putIfAbsent(c.mapId, nowSec + rtt / 2);
                    if (c.animation) animationTimes.computeIfAbsent(c.mapId, k -> new ArrayList<>()).add(nowSec + rtt / 2);
                }
            }
        }

        void deliverPongs(PlayerSendQueue queue) {
            for (int i = 0; i < pongs.size(); ) {
                double[] pong = pongs.get(i);
                double due = effectiveDue(pong[0]);
                if (due <= nowSec) {
                    queue.onPong((int) pong[1], BASE_NANOS + (long) (due * 1e9));
                    pongs.remove(i);
                } else {
                    i++;
                }
            }
        }
    }

    private static final class QueueSink implements FrameSink {
        final PlayerSendQueue queue;

        QueueSink(PlayerSendQueue queue) {
            this.queue = queue;
        }

        @Override
        public boolean enqueueUpdate(UUID uuid, MapFrameUpdate update, int tier) {
            boolean all = true;
            for (DeltaFrame part : update.parts()) {
                if (!queue.add(part, tier)) all = false;
            }
            return all;
        }

        @Override
        public double animationDrainSeconds(UUID uuid, long extraBytes) {
            return queue.urgentDrainSeconds(extraBytes);
        }

        @Override
        public long stillBudget(UUID uuid, int tier) {
            return (long) (queue.lookaheadBytes() - queue.stillBytesUpToTier(tier));
        }

        @Override
        public long animationBudget(UUID uuid) {
            return (long) queue.animationBudget();
        }

        @Override
        public long animationFullBudget(UUID uuid, boolean lowPriority) {
            return (long) queue.animationFullBudget(lowPriority);
        }

        @Override
        public void spendAnimationBudget(UUID uuid, long bytes) {
            queue.spendAnimationBudget(bytes);
        }
    }

    @TempDir
    File dir;

    private ExecutorService executor;
    private Cache<Long, MapFrameUpdate> cache;
    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(3);
        cache = Caffeine.newBuilder().maximumSize(10).build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static MapFrameUpdate part(int frame, int mapId, int bytes) {
        ByteBuf buf = Unpooled.directBuffer(bytes);
        buf.writeZero(bytes);
        return new MapFrameUpdate(new DeltaFrame[]{new DeltaFrame(frame, mapId, buf)});
    }

    private SyncGroup buildStill(int groupId, int mapBase, int maps) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(new File(dir, "g" + groupId), executor);
        Map<Integer, MapFrameUpdate> updates = new HashMap<>();
        for (int m = 0; m < maps; m++) updates.put(mapBase + m, part(0, mapBase + m, 16_500));
        storage.saveBundledFrame(groupId, 0, updates);
        for (MapFrameUpdate update : updates.values()) update.freeMemory();
        UUID worldId = UUID.randomUUID();
        List<FrameNode> nodes = new CopyOnWriteArrayList<>();
        for (int m = 0; m < maps; m++) nodes.add(new FrameNode(m, UUID.randomUUID(), worldId, 0, 0, m, 64, 0, mapBase + m, null));
        MapMetadata meta = new MapMetadata(groupId, UUID.randomUUID(), "http://x/still.png", maps, 1, 1, 100);
        SyncGroup group = new SyncGroup(meta, nodes, storage, cache, executor, null, 96.0);
        group.setViewerStateForTesting(viewer, true, true);
        long deadline = System.currentTimeMillis() + 15_000;
        while (!group.baseLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        return group;
    }

    private SyncGroup buildGif(int groupId, int mapBase, int maps, int frames, int deltaSize, int delayMs) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(new File(dir, "g" + groupId), executor);
        for (int frame = 0; frame < frames; frame++) {
            Map<Integer, MapFrameUpdate> updates = new HashMap<>();
            for (int m = 0; m < maps; m++) {
                if (frame == 0) {
                    updates.put(mapBase + m, AnimationDistributionTest.update(0, mapBase + m, 0, 0, 128, 128, 50 + (m % 50)));
                } else {
                    int span = 128 - deltaSize;
                    updates.put(mapBase + m, AnimationDistributionTest.update(frame, mapBase + m,
                            span == 0 ? 0 : (frame * 7 + m * 3) % span, span == 0 ? 0 : (frame * 11) % span, deltaSize, deltaSize, 10 + frame));
                }
            }
            storage.saveBundledFrame(groupId, frame, updates);
            for (MapFrameUpdate update : updates.values()) update.freeMemory();
        }
        UUID worldId = UUID.randomUUID();
        List<FrameNode> nodes = new CopyOnWriteArrayList<>();
        for (int m = 0; m < maps; m++) nodes.add(new FrameNode(m, UUID.randomUUID(), worldId, 0, 0, m, 64, 0, mapBase + m, null));
        MapMetadata meta = new MapMetadata(groupId, UUID.randomUUID(), "http://x/anim.gif", maps, 1, frames, delayMs);
        SyncGroup group = new SyncGroup(meta, nodes, storage, cache, executor, null, 96.0);
        group.setViewerStateForTesting(viewer, true, true);
        long deadline = System.currentTimeMillis() + 15_000;
        while (!group.baseLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        return group;
    }

    private record Outcome(double completionSeconds, double p95DelaySeconds, double maxDelaySeconds, int delivered,
                           double animationBytesPerSecond, double finalRateKb, boolean clientMatches) {}

    private Outcome simulate(List<SyncGroup> groups, int expectedMaps, double bandwidth, double rtt, double highWater,
                             boolean probes, double seconds, boolean realTimePacing, SyncGroup watched) throws Exception {
        PlayerSendQueue queue = new PlayerSendQueue(new PlayerSendQueue.Tuning(MIN_RATE, MAX_RATE, 98_304, TARGET_DELAY_MS, probes));
        QueueSink sink = new QueueSink(queue);
        SendBudget budget = new SendBudget(16_777_216);
        SimulatedLink link = new SimulatedLink(bandwidth, rtt, highWater, probes);

        double dt = 0.02;
        List<Double> delays = new ArrayList<>();
        List<Double> clusterLate = new ArrayList<>();
        double completion = -1;
        boolean frozen = false;
        double end = watched == null ? seconds : seconds + 8;
        for (double t = dt; t < end; t += dt) {
            link.nowSec = t;
            long nanos = BASE_NANOS + (long) (t * 1e9);
            long millis = 1_000_000L + (long) (t * 1000);
            if (watched != null && t >= seconds && !frozen) {
                watched.freezeClockForTesting();
                frozen = true;
            }
            budget.refill(nanos);
            link.deliverPongs(queue);
            for (SyncGroup group : groups) {
                if (group.shouldTick(millis)) group.tick(millis, sink);
            }
            queue.flush(link, budget, nanos);
            link.advance(dt);
            if (t > 2.0 && t < seconds) delays.add(link.pending / bandwidth);
            if (completion < 0 && link.deliveredAt.size() >= expectedMaps) {
                completion = t;
                if (watched == null) break;
            }
            if (realTimePacing) Thread.sleep(1);
        }
        Collections.sort(delays);
        double p95 = delays.isEmpty() ? 0 : delays.get((int) (delays.size() * 0.95));
        double max = delays.isEmpty() ? 0 : delays.get(delays.size() - 1);
        boolean matches = true;
        if (watched != null) {
            for (int mapId : watched.canvasMapIdsForTesting()) {
                byte[] expected = watched.canvasPixelsForTesting(mapId);
                byte[] actual = link.client.get(mapId);
                if (expected == null || actual == null || !java.util.Arrays.equals(expected, actual)) matches = false;
            }
        }
        queue.drain();
        return new Outcome(completion, p95, max, link.deliveredAt.size(),
                link.animationBytes / Math.max(1.0, seconds), queue.currentRateKbForTesting(), matches);
    }

    @Test
    void aBigWallOfStillPicturesLoadsQuicklyOnEveryKindOfConnection() throws Exception {
        int maps = 512;
        double[][] connections = {
                {60_000, 0.08}, {130_000, 0.06}, {500_000, 0.05}, {2_000_000, 0.03}, {12_500_000, 0.02}, {100_000_000, 0.015}
        };
        int groupId = 1;
        for (double[] c : connections) {
            double bandwidth = c[0];
            SyncGroup wall = buildStill(groupId, 1000 * groupId, maps);
            Outcome o = simulate(List.of(wall), maps, bandwidth, c[1], 2_000_000, true, 1500, false, null);
            double ideal = maps * 16_516.0 / Math.min(bandwidth, MAX_RATE);
            System.out.printf("wall %d maps, link %9.0f B/s rtt %3.0f ms: loaded in %6.1f s (ideal %6.1f s), delivered %d, p95 delay %5.0f ms, max %5.0f ms%n",
                    maps, bandwidth, c[1] * 1000, o.completionSeconds(), ideal, o.delivered(), o.p95DelaySeconds() * 1000, o.maxDelaySeconds() * 1000);
            assertTrue(o.completionSeconds() > 0, "the wall never finished loading on a " + bandwidth + " B/s link, delivered " + o.delivered());
            assertTrue(o.completionSeconds() <= ideal * 1.6 + 6.0,
                    "loading took " + o.completionSeconds() + " s but ideal is " + ideal + " s on a " + bandwidth + " B/s link");
            assertTrue(o.p95DelaySeconds() < (bandwidth < 150_000 ? 0.95 : 0.5),
                    "ping inflation too high: " + o.p95DelaySeconds() + " s on a " + bandwidth + " B/s link");
            wall.cleanup();
            groupId++;
        }
    }

    @Test
    void aGifPlaysOnEveryConnectionWithoutHurtingIt() throws Exception {
        double[][] connections = {{200_000, 0.06}, {600_000, 0.05}, {10_000_000, 0.03}};
        int groupId = 20;
        for (double[] c : connections) {
            SyncGroup gif = buildGif(groupId, 5000 * (groupId - 19), 16, 40, 60, 50);
            Outcome o = simulate(List.of(gif), 16, c[0], c[1], 2_000_000, true, 30, true, gif);
            System.out.printf("gif 4x4, 40 frames, link %9.0f B/s rtt %3.0f ms: animation traffic %5.0f KB/s, p95 delay %5.0f ms, max %5.0f ms, final rate %.0f KB/s, client correct: %b%n",
                    c[0], c[1] * 1000, o.animationBytesPerSecond() / 1024, o.p95DelaySeconds() * 1000, o.maxDelaySeconds() * 1000, o.finalRateKb(), o.clientMatches());
            assertTrue(o.delivered() >= 16, "the picture must always arrive");
            assertTrue(o.p95DelaySeconds() < 0.8, "ping inflation too high: " + o.p95DelaySeconds());
            assertTrue(o.clientMatches(), "the client must end up showing exactly what the server shows");
            assertTrue(o.animationBytesPerSecond() > 20_000, "the animation must really be streamed: " + o.animationBytesPerSecond());
            gif.cleanup();
            groupId++;
        }
    }

    @Test
    void stillPicturesAreNotStarvedByAnAnimationNextToThem() throws Exception {
        SyncGroup wall = buildStill(40, 40_000, 256);
        SyncGroup gif = buildGif(41, 50_000, 16, 40, 60, 50);
        Outcome o = simulate(List.of(wall, gif), 256 + 16, 5_000_000, 0.03, 2_000_000, true, 60, true, gif);
        double ideal = (256 * 16_516.0) / MAX_RATE;
        System.out.printf("wall 256 maps next to a playing gif, link 5 MB/s: all loaded in %.1f s (ideal %.1f s), p95 delay %.0f ms, gif correct: %b%n",
                o.completionSeconds(), ideal, o.p95DelaySeconds() * 1000, o.clientMatches());
        assertTrue(o.completionSeconds() > 0 && o.completionSeconds() < ideal * 2.5 + 8, "the wall was starved: " + o.completionSeconds());
        assertTrue(o.p95DelaySeconds() < 0.6);
        assertTrue(o.clientMatches());
        wall.cleanup();
        gif.cleanup();
    }

    @Test
    void manyGifsInOnePlaceShareTheConnectionFairlyAndNeverFloodIt() throws Exception {
        List<SyncGroup> gifs = new ArrayList<>();
        for (int g = 0; g < 12; g++) {
            gifs.add(buildGif(200 + g, 200_000 + g * 100, 9, 30, 80, 50));
        }
        PlayerSendQueue queue = new PlayerSendQueue(new PlayerSendQueue.Tuning(MIN_RATE, MAX_RATE, 98_304, TARGET_DELAY_MS, true));
        QueueSink sink = new QueueSink(queue);
        SendBudget budget = new SendBudget(16_777_216);
        SimulatedLink link = new SimulatedLink(8_000_000, 0.03, 2_000_000, true);
        List<Double> delays = new ArrayList<>();
        double dt = 0.02;
        for (double t = dt; t < 40; t += dt) {
            link.nowSec = t;
            long nanos = BASE_NANOS + (long) (t * 1e9);
            long millis = 1_000_000L + (long) (t * 1000);
            budget.refill(nanos);
            link.deliverPongs(queue);
            gifs.sort(Comparator.comparingDouble(g -> g.servedScore(millis)));
            for (SyncGroup group : gifs) {
                if (group.shouldTick(millis)) group.tick(millis, sink);
            }
            queue.flush(link, budget, nanos);
            link.advance(dt);
            if (t > 3) delays.add(link.pending / 8_000_000.0);
            Thread.sleep(1);
        }
        Collections.sort(delays);
        double p95 = delays.get((int) (delays.size() * 0.95));
        int groupsWithPicture = 0;
        for (int g = 0; g < 12; g++) {
            int base = 200_000 + g * 100;
            boolean any = false;
            for (int m = 0; m < 9; m++) {
                if (link.deliveredAt.containsKey(base + m)) any = true;
            }
            if (any) groupsWithPicture++;
        }
        System.out.printf("12 gifs (108 maps) in one place, link 8 MB/s: p95 delay %.0f ms, pictures delivered for %d groups, animation traffic %.0f KB/s%n",
                p95 * 1000, groupsWithPicture, link.animationBytes / 40 / 1024);
        assertTrue(p95 < 0.4, "ping inflation with many gifs: " + p95);
        assertTrue(groupsWithPicture == 12, "every gif must at least show its picture: " + groupsWithPicture);
        assertTrue(link.animationBytes / 40 <= MAX_RATE * 1.2, "total animation traffic must respect the per-player limit");
        queue.drain();
        for (SyncGroup group : gifs) group.cleanup();
    }

    @Test
    void clientsThatCannotAnswerPingsStillLoadFastWithoutFloodingTheirConnection() throws Exception {
        double[] links = {250_000, 1_000_000, 8_000_000};
        int groupId = 60;
        for (double bandwidth : links) {
            SyncGroup wall = buildStill(groupId, 1000 * groupId, 256);
            Outcome o = simulate(List.of(wall), 256, bandwidth, 0.04, 2_000_000, false, 1500, false, null);
            double ideal = 256 * 16_516.0 / Math.min(bandwidth, MAX_RATE);
            System.out.printf("no ping support, wall 256 maps, link %9.0f B/s: loaded in %6.1f s (ideal %5.1f s), p95 delay %5.0f ms, max %5.0f ms%n",
                    bandwidth, o.completionSeconds(), ideal, o.p95DelaySeconds() * 1000, o.maxDelaySeconds() * 1000);
            assertTrue(o.completionSeconds() > 0 && o.completionSeconds() <= ideal * 2.0 + 8.0, "too slow: " + o.completionSeconds());
            assertTrue(o.p95DelaySeconds() < 1.2, "too much ping inflation without probes: " + o.p95DelaySeconds());
            wall.cleanup();
            groupId++;
        }
    }

    @Test
    void manyPicturesAroundASpawnAreLoadedTogetherAtFullSpeed() throws Exception {
        List<SyncGroup> groups = new ArrayList<>();
        int total = 0;
        for (int g = 0; g < 60; g++) {
            int maps = 12 + (g % 5) * 6;
            groups.add(buildStill(100 + g, 100_000 + g * 100, maps));
            total += maps;
        }
        double bandwidth = 5_000_000;
        Outcome o = simulate(groups, total, bandwidth, 0.03, 2_000_000, true, 300, false, null);
        double ideal = total * 16_516.0 / Math.min(bandwidth, MAX_RATE);
        System.out.printf("60 pictures (%d maps) around a spawn, link 5 MB/s: loaded in %.1f s (ideal %.1f s), p95 delay %.0f ms%n",
                total, o.completionSeconds(), ideal, o.p95DelaySeconds() * 1000);
        assertTrue(o.completionSeconds() > 0 && o.completionSeconds() <= ideal * 1.8 + 5.0, "too slow: " + o.completionSeconds());
        assertTrue(o.p95DelaySeconds() < 0.4);
        for (SyncGroup group : groups) group.cleanup();
    }

    @Test
    void aClusterOfManyGifsAndPicturesStaysSmoothAndFair() throws Exception {
        List<SyncGroup> groups = new ArrayList<>();
        List<Integer> animatedBases = new ArrayList<>();
        List<Integer> stillBases = new ArrayList<>();
        int expectedMaps = 0;
        for (int g = 0; g < 30; g++) {
            int base = 300_000 + g * 100;
            groups.add(buildGif(300 + g, base, 16, 40, 70, 50));
            animatedBases.add(base);
            expectedMaps += 16;
        }
        for (int g = 0; g < 10; g++) {
            int base = 400_000 + g * 100;
            groups.add(buildNearlyStillGif(400 + g, base, 16, 40));
            stillBases.add(base);
            expectedMaps += 16;
        }
        groups.add(buildStill(500, 500_000, 256));
        expectedMaps += 256;
        AnimationDistributionTest.ClientSink warmup = new AnimationDistributionTest.ClientSink();
        warmup.capacity = 1;
        for (SyncGroup group : groups) {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!group.animationInfoLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(2);
        }
        long warmDeadline = System.currentTimeMillis() + 30_000;
        boolean warm = false;
        while (!warm && System.currentTimeMillis() < warmDeadline) {
            warm = true;
            for (SyncGroup group : groups) {
                if (!group.isAnimatedPicture()) continue;
                group.tick(1_000_000L, warmup);
                if (group.loadedWindowFramesForTesting() < 39) warm = false;
            }
            Thread.sleep(5);
        }

        PlayerSendQueue queue = new PlayerSendQueue(new PlayerSendQueue.Tuning(MIN_RATE, MAX_RATE, 98_304, TARGET_DELAY_MS, true));
        QueueSink sink = new QueueSink(queue);
        SendBudget budget = new SendBudget(16_777_216);
        SimulatedLink link = new SimulatedLink(10_000_000, 0.03, 2_000_000, true);
        List<Double> delays = new ArrayList<>();
        List<Double> clusterLate = new ArrayList<>();
        double completion = -1;
        long tickNanos = 0;
        int steps = 0;
        double dt = 0.02;
        for (double t = dt; t < 45; t += dt) {
            link.nowSec = t;
            long nanos = BASE_NANOS + (long) (t * 1e9);
            long millis = 1_000_000L + (long) (t * 1000);
            budget.refill(nanos);
            link.deliverPongs(queue);
            long started = System.nanoTime();
            groups.sort(Comparator.comparingDouble(g -> g.servedScore(millis)));
            for (SyncGroup group : groups) {
                if (group.shouldTick(millis)) group.tick(millis, sink);
            }
            tickNanos += System.nanoTime() - started;
            steps++;
            queue.flush(link, budget, nanos);
            link.advance(dt);
            if (t > 3) delays.add(link.pending / 10_000_000.0);
            if (t > 10) clusterLate.add(link.pending / 10_000_000.0);
            if (completion < 0 && link.deliveredAt.size() >= expectedMaps) completion = t;
            Thread.sleep(1);
        }
        Collections.sort(delays);
        double p95 = delays.get((int) (delays.size() * 0.95));
        Collections.sort(clusterLate);
        System.out.printf("CLUSTER late p50 %.0f p95 %.0f p99 %.0f max %.0f ms%n", clusterLate.get(clusterLate.size() / 2) * 1000, clusterLate.get((int) (clusterLate.size() * 0.95)) * 1000, clusterLate.get((int) (clusterLate.size() * 0.99)) * 1000, clusterLate.get(clusterLate.size() - 1) * 1000);

        double total = 0;
        double minimum = Double.MAX_VALUE;
        for (int base : animatedBases) {
            int packets = 0;
            for (int m = 0; m < 16; m++) packets += link.animationPacketsPerMap.getOrDefault(base + m, 0);
            total += packets;
            minimum = Math.min(minimum, packets);
        }
        double average = total / animatedBases.size();
        int stillGifPackets = 0;
        for (int base : stillBases) {
            for (int m = 0; m < 16; m++) stillGifPackets += link.animationPacketsPerMap.getOrDefault(base + m, 0);
        }
        double ideal = expectedMaps * 16_516.0 / MAX_RATE;
        double averageTickMillis = tickNanos / 1e6 / steps;
        System.out.printf("cluster of 30 gifs + 10 nearly still gifs + 256-map wall (%d maps), link 10 MB/s: everything shown after %.1f s (ideal %.1f s), p95 delay %.0f ms, "
                        + "least-served gif got %.0f updates (average %.0f), nearly-still gifs animated: %d packets, %.2f ms per tick%n",
                expectedMaps, completion, ideal, p95 * 1000, minimum, average, stillGifPackets, averageTickMillis);
        assertTrue(completion > 0 && completion < ideal * 2.2 + 8, "the cluster took too long to show up: " + completion);
        assertTrue(p95 < 0.4, "ping inflation in the cluster: " + p95);
        assertTrue(minimum >= average * 0.3, "some gifs were starved: " + minimum + " vs " + average);
        assertTrue(stillGifPackets == 0, "gifs without motion must not be streamed as animations");
        assertTrue(averageTickMillis < 8.0, "a tick must stay cheap even with 41 pictures: " + averageTickMillis + " ms");
        queue.drain();
        for (SyncGroup group : groups) group.cleanup();
    }

    private static final double MOSAIC_LIMIT = 0.2;
    private int lastMaxDistinct;
    private double lastMosaicFraction;
    private int lastMostBigFramesInFlight;
    private boolean testAdmission;
    private boolean testSparse;
    private int testMaxRate = MAX_RATE;
    private int lastFrozenAtEnd;
    private double testExtraFrom = Double.MAX_VALUE;
    private double testExtraDelay;
    private double testHiccupEvery;
    private double testHiccupLen;
    private double lastLateP95;
    private double lastLateP99;

    private record ClusterResult(double p50, double p95, double p99, double maxDelay, double averageFps, double worstFps, double longestGap, double kbPerSecond) {}

    private ClusterResult runGifCluster(int gifCount, int mapsPerGif, int deltaSize, int frames, double bandwidth, double rtt, double seconds, int idBase, int maxRate) throws Exception {
        return runGifCluster(gifCount, mapsPerGif, deltaSize, frames, bandwidth, rtt, 0, TARGET_DELAY_MS, seconds, idBase, maxRate);
    }

    private ClusterResult runGifCluster(int gifCount, int mapsPerGif, int deltaSize, int frames, double bandwidth, double rtt, double jitter,
                                        int targetMs, double seconds, int idBase, int maxRate) throws Exception {
        List<SyncGroup> gifs = new ArrayList<>();
        List<Integer> firstMaps = new ArrayList<>();
        for (int g = 0; g < gifCount; g++) {
            int base = (idBase + g) * 100;
            gifs.add(testSparse ? buildSparseGif(idBase + g, base, mapsPerGif, frames, 50) : buildGif(idBase + g, base, mapsPerGif, frames, deltaSize, 50));
            firstMaps.add(base);
        }
        PlayerSendQueue queue = new PlayerSendQueue(new PlayerSendQueue.Tuning(MIN_RATE, maxRate, 98_304, targetMs, true));
        QueueSink sink = new QueueSink(queue);
        SendBudget budget = new SendBudget(Math.max(16_777_216, maxRate * 2));
        SimulatedLink link = new SimulatedLink(bandwidth, rtt, 2_000_000, true).withJitter(jitter).withHiccups(testHiccupEvery, testHiccupLen).withExtraDelay(testExtraFrom, testExtraDelay);
        List<Double> delays = new ArrayList<>();
        List<Double> lateDelays = new ArrayList<>();
        double dt = 0.02;
        for (double t = dt; t < seconds; t += dt) {
            link.nowSec = t;
            long nanos = BASE_NANOS + (long) (t * 1e9);
            long millis = 1_000_000L + (long) (t * 1000);
            budget.refill(nanos);
            link.deliverPongs(queue);
            gifs.sort(Comparator.comparingDouble(g -> g.servedScore(millis)));
            for (SyncGroup group : gifs) {
                if (group.shouldTick(millis)) group.tick(millis, sink);
            }
            queue.flush(link, budget, nanos);
            link.advance(dt);
            if (t > 3) delays.add(link.pending / bandwidth);
            if (t > 10) lateDelays.add(link.pending / bandwidth);
            Thread.sleep(1);
        }
        Collections.sort(lateDelays);
        lastLateP95 = lateDelays.isEmpty() ? 0 : lateDelays.get((int) (lateDelays.size() * 0.95));
        lastLateP99 = lateDelays.isEmpty() ? 0 : lateDelays.get((int) (lateDelays.size() * 0.99));
        Collections.sort(delays);
        double p50 = delays.get(delays.size() / 2);
        double p95 = delays.get((int) (delays.size() * 0.95));
        double p99 = delays.get((int) (delays.size() * 0.99));
        double maxDelay = delays.get(delays.size() - 1);

        double warmup = Math.min(12, seconds / 3);
        double window = seconds - warmup;
        double totalFps = 0;
        double worstFps = Double.MAX_VALUE;
        double longestGap = 0;
        for (int base : firstMaps) {
            List<Double> times = link.animationTimes.getOrDefault(base, List.of());
            double previous = warmup;
            int count = 0;
            for (double time : times) {
                if (time < warmup) continue;
                count++;
                longestGap = Math.max(longestGap, time - previous);
                previous = time;
            }
            longestGap = Math.max(longestGap, seconds - previous);
            double fps = count / window;
            totalFps += fps;
            worstFps = Math.min(worstFps, fps);
        }
        queue.drain();
        for (SyncGroup group : gifs) group.cleanup();
        return new ClusterResult(p50, p95, p99, maxDelay, totalFps / gifCount, worstFps, longestGap, link.animationBytes / seconds / 1024);
    }

    @Test
    void aHugeClusterOfHeavyGifsSlowsDownGentlyAndNeverFloodsTheConnection() throws Exception {
        double[][] scenarios = {
                {12, 4, 64, 12_500_000, 0.02, MAX_RATE, 18},
                {6, 16, 128, 100_000_000, 0.01, MAX_RATE, 18},
                {1, 256, 128, 12_500_000, 0.02, MAX_RATE, 2.5},
                {1, 256, 128, 100_000_000, 0.01, MAX_RATE, 6},
                {1, 256, 128, 800_000, 0.06, MAX_RATE, 0.1},
                {24, 16, 128, 800_000, 0.06, MAX_RATE, 0.05},
        };
        int idBase = 700;
        for (double[] s : scenarios) {
            int gifCount = (int) s[0];
            int maps = (int) s[1];
            int deltaSize = (int) s[2];
            int maxRate = (int) s[5];
            double expectedFps = s[6];
            ClusterResult r = runGifCluster(gifCount, maps, deltaSize, 12, s[3], s[4], 20, idBase, maxRate);
            idBase += 100;
            System.out.printf("%2d gifs of %2d maps (%3d maps), %3d px changes per map per frame, link %5.1f MB/s, limit %.0f MB/s: ping p95 %4.0f ms, max %4.0f ms; refresh average %4.1f/s (original 20/s), slowest gif %4.1f/s, longest freeze %4.1f s, traffic %5.0f KB/s%n",
                    gifCount, maps, gifCount * maps, deltaSize, s[3] / 1e6, maxRate / 1048576.0,
                    r.p95() * 1000, r.maxDelay() * 1000, r.averageFps(), r.worstFps(), r.longestGap(), r.kbPerSecond());
            assertTrue(r.p95() < 0.4, "ping inflation with a huge cluster of heavy gifs: " + r.p95());
            // When a whole gif only gets a frame every few seconds the counts per window are 0-3 and the per-gif
            // comparison is pure quantisation noise, so it is checked only where the frame counts are meaningful.
            if (expectedFps >= 1) {
                assertTrue(r.worstFps() >= r.averageFps() * 0.5, "a gif was starved: " + r.worstFps() + " vs " + r.averageFps());
                assertTrue(r.worstFps() > 0.05, "a gif froze for good: " + r.worstFps());
            }
            assertTrue(r.kbPerSecond() * 1024 <= maxRate * 1.2, "traffic above the per-player limit: " + r.kbPerSecond());
            assertTrue(r.averageFps() >= expectedFps, "gifs run slower than the connection allows: " + r.averageFps() + " < " + expectedFps);
        }
    }

    @Test
    void routesWithHighLatencyAndJitterKeepTheAddedPingLow() throws Exception {
        double[][] routes = {
                {5_000_000, 0.150, 0.015, 16, 0.06},
                {3_000_000, 0.300, 0.040, 3, 0.06},
                {1_500_000, 0.500, 0.060, 1, 0.06},
                {500_000, 0.250, 0.030, 0.35, 0.10},
        };
        int idBase = 900;
        for (double[] route : routes) {
            ClusterResult r = runGifCluster(12, 4, 64, 12, route[0], route[1], route[2], TARGET_DELAY_MS, 26, idBase, MAX_RATE);
            idBase += 100;
            System.out.printf("route: link %4.1f MB/s, ping to server %3.0f ms (+/- %2.0f ms jitter): added ping p50 %3.0f, p95 %3.0f ms, after 10 s p95 %3.0f, p99 %3.0f ms; gifs %4.1f/s of 20, traffic %5.0f KB/s%n",
                    route[0] / 1e6, route[1] * 1000, route[2] * 1000,
                    r.p50() * 1000, r.p95() * 1000, lastLateP95 * 1000, lastLateP99 * 1000, r.averageFps(), r.kbPerSecond());
            assertTrue(lastLateP95 < route[4], "added ping too high on a " + route[0] + " B/s route: " + lastLateP95);
            assertTrue(r.averageFps() >= route[3], "gifs too slow on a " + route[0] + " B/s route: " + r.averageFps());
        }
    }

    private double[] runMixed(int[][] specs, double bandwidth, double rtt, double seconds, int idBase) throws Exception {
        List<SyncGroup> all = new ArrayList<>();
        List<List<Integer>> classMaps = new ArrayList<>();
        List<int[]> wholeMapGifs = new ArrayList<>();
        lastMaxDistinct = 0;
        long mosaicSamples = 0;
        lastMostBigFramesInFlight = 0;
        long samplesTaken = 0;
        int groupId = idBase;
        for (int[] spec : specs) {
            List<Integer> firsts = new ArrayList<>();
            for (int g = 0; g < spec[0]; g++) {
                int base = groupId * 1000;
                all.add(buildGif(groupId, base, spec[1], spec[3], spec[2], 50));
                firsts.add(base);
                if (spec[2] == 128) wholeMapGifs.add(new int[]{base, spec[1]});
                groupId++;
            }
            classMaps.add(firsts);
        }
        PlayerSendQueue queue = new PlayerSendQueue(new PlayerSendQueue.Tuning(MIN_RATE, testMaxRate, 98_304, TARGET_DELAY_MS, true));
        QueueSink sink = new QueueSink(queue);
        SendBudget budget = new SendBudget(Math.max(16_777_216, testMaxRate * 2));
        SimulatedLink link = new SimulatedLink(bandwidth, rtt, 2_000_000, true);
        List<Double> late = new ArrayList<>();
        double dt = 0.02;
        for (double t = dt; t < seconds; t += dt) {
            link.nowSec = t;
            long nanos = BASE_NANOS + (long) (t * 1e9);
            long millis = 1_000_000L + (long) (t * 1000);
            budget.refill(nanos);
            link.deliverPongs(queue);
            if (testAdmission && ((int) Math.round(t / dt)) % 12 == 0) {
                List<AdmissionControl.Candidate> candidates = new ArrayList<>();
                for (SyncGroup group : all) {
                    candidates.add(new AdmissionControl.Candidate(group.groupId(), group.priorityFor(viewer), group.demandBytesPerSecond(), !group.isFrozenFor(viewer)));
                }
                Map<Integer, Double> admitted = AdmissionControl.select(candidates, Math.min(queue.capacityEstimate(), testMaxRate));
                lastFrozenAtEnd = 0;
                for (SyncGroup group : all) {
                    Double allowance = admitted.get(group.groupId());
                    group.setFrozenFor(viewer, allowance == null);
                    group.setAllowanceFor(viewer, allowance == null ? 0.0 : allowance);
                    if (allowance == null) lastFrozenAtEnd++;
                }
            }
            all.sort(Comparator.comparingDouble(g -> g.servedScore(millis)));
            for (SyncGroup group : all) {
                if (group.shouldTick(millis)) group.tick(millis, sink);
            }
            int bigInFlight = 0;
            for (SyncGroup group : all) {
                if (group.openBatchBytesForTesting(viewer) > SyncGroup.smallBatchBytesForTesting()) bigInFlight++;
            }
            lastMostBigFramesInFlight = Math.max(lastMostBigFramesInFlight, bigInFlight);
            queue.flush(link, budget, nanos);
            link.advance(dt);
            if (t > 10) late.add(link.pending / bandwidth);
            if (t > 10 && ((int) Math.round(t / dt)) % 5 == 0) {
                for (int[] gif : wholeMapGifs) {
                    java.util.Set<Integer> values = new java.util.HashSet<>();
                    for (int m = 0; m < gif[1]; m++) {
                        byte[] pixels = link.client.get(gif[0] + m);
                        if (pixels != null && pixels[0] >= 10 && pixels[0] <= 30) values.add((int) pixels[0]);
                    }
                    lastMaxDistinct = Math.max(lastMaxDistinct, values.size());
                    samplesTaken++;
                    if (values.size() > 1) mosaicSamples++;
                }
            }
            Thread.sleep(1);
        }
        lastMosaicFraction = samplesTaken == 0 ? 0 : mosaicSamples / (double) samplesTaken;
        Collections.sort(late);
        double[] result = new double[specs.length + 1];
        double warmup = 10;
        for (int c = 0; c < specs.length; c++) {
            double total = 0;
            for (int base : classMaps.get(c)) {
                int count = 0;
                for (double time : link.animationTimes.getOrDefault(base, List.of())) if (time >= warmup) count++;
                total += count / (seconds - warmup);
            }
            result[c] = total / classMaps.get(c).size();
        }
        result[specs.length] = late.get((int) (late.size() * 0.95));
        queue.drain();
        for (SyncGroup group : all) group.cleanup();
        return result;
    }

    @Test
    void smallGifsKeepPlayingWhileHugeOnesShareWhatIsLeft() throws Exception {
        int[][] specs = {{4, 256, 128, 8}, {3, 60, 128, 12}, {3, 4, 100, 20}};
        double[][] links = {{20_000_000, 0.10}, {3_000_000, 0.10}};
        int idBase = 1200;
        for (double[] l : links) {
            double[] r = runMixed(specs, l[0], l[1], 24, idBase);
            idBase += 20;
            System.out.printf("MIXED link %4.1f MB/s: 4 huge 16x16 gifs %4.1f/s, 3 big 6x10 gifs %4.1f/s, 3 small 2x2 gifs %4.1f/s (original 20/s); added ping p95 %3.0f ms%n",
                    l[0] / 1e6, r[0], r[1], r[2], r[3] * 1000);
            System.out.printf("MIXED coherence: no gif ever showed more than %d different frames at once%n", lastMaxDistinct);
            assertTrue(lastMaxDistinct <= 3, "a gif was torn into pieces of " + lastMaxDistinct + " different frames");
            assertTrue(r[2] >= 14, "small gifs must keep playing next to huge ones: " + r[2]);
            assertTrue(r[3] < 0.06, "added ping too high: " + r[3]);
        }
    }

    @Test
    void afterRejoiningEveryPictureLoadsFastWhileTheGifsKeepPlaying() throws Exception {
        List<SyncGroup> all = new ArrayList<>();
        int groupId = 1700;
        int stillMaps = 0;
        List<Integer> stillBases = new ArrayList<>();
        for (int g = 0; g < 150; g++) {
            int base = 5_000_000 + g * 100;
            all.add(buildStill(groupId++, base, 20));
            stillBases.add(base);
            stillMaps += 20;
        }
        List<Integer> smallBases = new ArrayList<>();
        int[][] specs = {{4, 256, 128, 6}, {3, 60, 128, 10}, {3, 4, 100, 20}};
        for (int[] spec : specs) {
            for (int g = 0; g < spec[0]; g++) {
                int base = groupId * 1000;
                all.add(buildGif(groupId, base, spec[1], spec[3], spec[2], 50));
                if (spec[1] == 4) smallBases.add(base);
                groupId++;
            }
        }
        PlayerSendQueue queue = new PlayerSendQueue(new PlayerSendQueue.Tuning(MIN_RATE, 16_777_216, 98_304, TARGET_DELAY_MS, true));
        QueueSink sink = new QueueSink(queue);
        SendBudget budget = new SendBudget(41_943_040);
        SimulatedLink link = new SimulatedLink(18_000_000, 0.10, 2_000_000, true).withJitter(0.010);
        double dt = 0.02;
        double completion = -1;
        List<Double> late = new ArrayList<>();
        double seconds = 40;
        for (double t = dt; t < seconds; t += dt) {
            link.nowSec = t;
            long nanos = BASE_NANOS + (long) (t * 1e9);
            long millis = 1_000_000L + (long) (t * 1000);
            budget.refill(nanos);
            link.deliverPongs(queue);
            all.sort(Comparator.comparingDouble(g -> g.servedScore(millis)));
            for (SyncGroup group : all) {
                if (group.shouldTick(millis)) group.tick(millis, sink);
            }
            queue.flush(link, budget, nanos);
            link.advance(dt);
            if (t > 10) late.add(link.pending / 18_000_000.0);
            if (completion < 0) {
                boolean done = true;
                for (int base : stillBases) {
                    for (int m = 0; m < 20 && done; m++) if (!link.deliveredAt.containsKey(base + m)) done = false;
                    if (!done) break;
                }
                if (done) completion = t;
            }
            Thread.sleep(1);
        }
        Collections.sort(late);
        double smallFps = 0;
        for (int base : smallBases) {
            int count = 0;
            for (double time : link.animationTimes.getOrDefault(base, List.of())) if (time >= 12) count++;
            smallFps += count / (seconds - 12);
        }
        smallFps /= smallBases.size();
        double p95 = late.get((int) (late.size() * 0.95));
        System.out.printf("REJOIN link 18 MB/s, ping 100 ms: all %d still maps (%d pictures) delivered after %.1f s while 10 gifs play; small gifs %.1f/s; added ping p95 %.0f ms%n",
                stillMaps, stillBases.size(), completion, smallFps, p95 * 1000);
        assertTrue(completion > 0 && completion < 20, "the pictures took too long to load: " + completion);
        assertTrue(smallFps >= 14, "small gifs stopped playing: " + smallFps);
        assertTrue(p95 < 0.06, "added ping too high: " + p95);
        queue.drain();
        for (SyncGroup group : all) group.cleanup();
    }

    @Test
    void aNoisyRealPathWithClientHiccupsDoesNotCollapseTheSendRate() throws Exception {
        testHiccupEvery = 3.7;
        testHiccupLen = 0.25;
        try {
            ClusterResult r = runGifCluster(6, 16, 128, 6, 18_000_000, 0.09, 0.040, TARGET_DELAY_MS, 40, 2300, 33_554_432);
            System.out.printf("NOISY link 18 MB/s, ping 90 ms +/- 40 ms noise, client hiccup of 250 ms every 3.7 s: traffic %.0f KB/s, added ping p50 %.0f, p95 %.0f, p99 %.0f ms (after 10 s: p95 %.0f)%n",
                    r.kbPerSecond(), r.p50() * 1000, r.p95() * 1000, r.p99() * 1000, lastLateP95 * 1000);
            assertTrue(r.kbPerSecond() >= 5000, "the send rate collapsed on a noisy path: " + r.kbPerSecond() + " KB/s");
            assertTrue(lastLateP95 < 0.08, "added ping too high: " + lastLateP95);
        } finally {
            testHiccupEvery = 0;
            testHiccupLen = 0;
        }
    }

    @Test
    void limitingTheAnimatedGifsKeepsTheCheapOnesSmoothAndThePingLow() throws Exception {
        int[][] specs = {{4, 256, 128, 8}, {3, 60, 128, 12}, {3, 4, 100, 20}};
        testAdmission = true;
        testMaxRate = 8_388_608;
        try {
            double[] r = runMixed(specs, 20_000_000, 0.10, 24, 1400);
            System.out.printf("ADMISSION link 20 MB/s, limit 8 MB/s: 4 huge gifs %.1f/s, 3 big gifs %.1f/s, 3 small gifs %.1f/s; added ping p95 %.0f ms; paused gifs: %d of 10; no gif shows more than %d frames at once; the big gifs are a mosaic of several frames %.0f%% of the time%n",
                    r[0], r[1], r[2], r[3] * 1000, lastFrozenAtEnd, lastMaxDistinct, lastMosaicFraction * 100);
            assertTrue(r[2] >= 17, "the cheap gifs must run at nearly full speed: " + r[2]);
            assertTrue(r[3] < 0.04, "added ping with the limit: " + r[3]);
            assertTrue(lastFrozenAtEnd >= 4, "gifs that do not fit the budget must be paused: " + lastFrozenAtEnd);
        } finally {
            testAdmission = false;
            testMaxRate = MAX_RATE;
        }
    }

    @Test
    void whenManyHugeGifsCompeteEachFrameArrivesAsOneQuickBurstNotAConstantWipe() throws Exception {
        int[][] specs = {{3, 176, 128, 10}, {2, 120, 128, 10}, {3, 36, 128, 12}, {3, 9, 64, 20}};
        testAdmission = true;
        testMaxRate = 8_388_608;
        try {
            double[] r = runMixed(specs, 20_000_000, 0.04, 40, 2900);
            System.out.printf("USERLIKE link 20 MB/s, limit 8 MB/s, 8 big gifs + 3 small: 176-map gifs %.1f/s, 120-map %.1f/s, 36-map %.1f/s, 9-map %.1f/s; added ping p95 %.0f ms; paused %d of 11; at most %d frames at once; mosaic of several frames %.0f%% of the time%n",
                    r[0], r[1], r[2], r[3], r[4] * 1000, lastFrozenAtEnd, lastMaxDistinct, lastMosaicFraction * 100);
            assertTrue(r[4] < 0.04, "added ping with the limit: " + r[4]);
            assertTrue(lastMosaicFraction < MOSAIC_LIMIT, "the big gifs are a patchwork of frames too much of the time: " + lastMosaicFraction);
            assertTrue(lastMostBigFramesInFlight <= 1, "several big frames were half sent at the same time: " + lastMostBigFramesInFlight);
        } finally {
            testAdmission = false;
            testMaxRate = MAX_RATE;
        }
    }

    @Test
    void theWorstConnectionsStillGetTheGifsThatFitSmoothlyAndALowPing() throws Exception {
        int[][] specs = {{4, 256, 128, 8}, {3, 60, 128, 12}, {3, 4, 100, 20}};
        double[][] links = {{150_000, 0.40}, {500_000, 0.25}, {2_000_000, 0.15}};
        int idBase = 1600;
        testAdmission = true;
        testMaxRate = 8_388_608;
        try {
            for (double[] link : links) {
                double[] r = runMixed(specs, link[0], link[1], 26, idBase);
                idBase += 20;
                System.out.printf("WORST link %4.2f MB/s, ping %3.0f ms: small gifs %4.1f/s of 20, big %4.1f/s, huge %4.1f/s, paused %d of 10, added ping p95 %3.0f ms%n",
                        link[0] / 1e6, link[1] * 1000, r[2], r[1], r[0], lastFrozenAtEnd, r[3] * 1000);
                assertTrue(r[3] < 0.30, "added ping on a weak connection: " + r[3]);
            }
        } finally {
            testAdmission = false;
            testMaxRate = MAX_RATE;
        }
    }

    @Test
    void aWeakPcWhoseGameAnswersPingsLateForeverStillGetsItsPictures() throws Exception {
        testExtraFrom = 8.0;
        testExtraDelay = 0.14;
        try {
            ClusterResult r = runGifCluster(12, 4, 64, 12, 12_500_000, 0.04, 0.004, TARGET_DELAY_MS, 26, 2600, MAX_RATE);
            System.out.printf("WEAKPC link 12.5 MB/s, the game answers every ping 140 ms late from 8 s on: traffic %.0f KB/s, gifs %.1f/s of 20, added ping p95 %.0f ms%n",
                    r.kbPerSecond(), r.averageFps(), lastLateP95 * 1000);
            assertTrue(r.kbPerSecond() >= 3000, "the sender starved because the game itself is slow to answer: " + r.kbPerSecond() + " KB/s");
        } finally {
            testExtraFrom = Double.MAX_VALUE;
            testExtraDelay = 0;
        }
    }

    @Test
    void aLinkSlowerThanTheRateFloorNeverStallsTheConnection() throws Exception {
        // Below the configured floor (32 KB/s) the sender is overdriving the link by design, so the delay is only
        // bounded by the queue limits; the point of this test is that pictures keep arriving and the delay never grows without bound.
        double[][] links = {{20_000, 0.30}, {10_000, 0.50}};
        int groupId = 2700;
        for (double[] link : links) {
            SyncGroup wall = buildStill(groupId, 1000 * groupId, 64);
            Outcome o = simulate(List.of(wall), 64, link[0], link[1], 2_000_000, true, 300, false, null);
            System.out.printf("SLOW link %5.0f B/s rtt %3.0f ms: first %d of 64 maps delivered in 300 s, p95 delay %5.0f ms, max delay %5.0f ms%n",
                    link[0], link[1] * 1000, o.delivered(), o.p95DelaySeconds() * 1000, o.maxDelaySeconds() * 1000);
            double bound = link[0] >= 20_000 ? 5.0 : 14.0;
            assertTrue(o.maxDelaySeconds() < bound, "a link of " + link[0] + " B/s is bounded by the queue limits, not unbounded: " + o.maxDelaySeconds());
            assertTrue(o.delivered() >= 8, "pictures must keep arriving on a very slow link: " + o.delivered());
            wall.cleanup();
            groupId++;
        }
    }

    /**
     * A GIF the way real ones are stored: per frame one bounding box around the few things that moved (here three blobs),
     * the rest of the box is unchanged picture.
     */
    private SyncGroup buildSparseGif(int groupId, int mapBase, int maps, int frames, int delayMs) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(new File(dir, "g" + groupId), executor);
        byte[][] state = new byte[maps][16384];
        byte[][] background = new byte[maps][];
        for (int m = 0; m < maps; m++) {
            java.util.Arrays.fill(state[m], (byte) (50 + (m % 50)));
            background[m] = state[m].clone();
        }
        for (int frame = 0; frame < frames; frame++) {
            Map<Integer, MapFrameUpdate> updates = new HashMap<>();
            for (int m = 0; m < maps; m++) {
                if (frame == 0) {
                    updates.put(mapBase + m, AnimationDistributionTest.update(0, mapBase + m, 0, 0, 128, 128, 50 + (m % 50)));
                    continue;
                }
                byte[] before = state[m].clone();
                state[m] = background[m].clone();
                int[][] blobs = {{6 + (frame * 5 + m * 3) % 36, 8, 18, 14}, {72, 60 + (frame * 3 + m) % 40, 20, 16}, {30 + (frame * 7) % 50, 100, 14, 14}};
                for (int[] blob : blobs) {
                    for (int yy = blob[1]; yy < Math.min(128, blob[1] + blob[3]); yy++) {
                        for (int xx = blob[0]; xx < Math.min(128, blob[0] + blob[2]); xx++) state[m][yy * 128 + xx] = (byte) (10 + frame);
                    }
                }
                int minX = 128, minY = 128, maxX = -1, maxY = -1;
                for (int i = 0; i < 16384; i++) {
                    if (before[i] == state[m][i]) continue;
                    minX = Math.min(minX, i % 128);
                    maxX = Math.max(maxX, i % 128);
                    minY = Math.min(minY, i / 128);
                    maxY = Math.max(maxY, i / 128);
                }
                if (maxX < 0) continue;
                int w = maxX - minX + 1;
                int h = maxY - minY + 1;
                byte[] data = new byte[w * h];
                for (int yy = 0; yy < h; yy++) System.arraycopy(state[m], (minY + yy) * 128 + minX, data, yy * w, w);
                updates.put(mapBase + m, AnimationDistributionTest.updateWithData(frame, mapBase + m, minX, minY, w, h, data));
            }
            storage.saveBundledFrame(groupId, frame, updates);
            for (MapFrameUpdate update : updates.values()) update.freeMemory();
        }
        UUID worldId = UUID.randomUUID();
        List<FrameNode> nodes = new CopyOnWriteArrayList<>();
        for (int m = 0; m < maps; m++) nodes.add(new FrameNode(m, UUID.randomUUID(), worldId, 0, 0, m, 64, 0, mapBase + m, null));
        MapMetadata meta = new MapMetadata(groupId, UUID.randomUUID(), "http://x/sparse.gif", maps, 1, frames, delayMs);
        SyncGroup group = new SyncGroup(meta, nodes, storage, cache, executor, null, 96.0);
        group.setViewerStateForTesting(viewer, true, true);
        long deadline = System.currentTimeMillis() + 15_000;
        while (!group.baseLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        return group;
    }

    @Test
    void sparseGifsSentAsSmallRectanglesPlayFasterOnTheSameLinkWithoutMorePing() throws Exception {
        double[][] links = {{500_000, 0.25}, {3_000_000, 0.10}, {12_500_000, 0.04}};
        int idBase = 3100;
        testSparse = true;
        try {
            for (double[] link : links) {
                MapRects.enabled = false;
                ClusterResult off = runGifCluster(6, 16, 0, 12, link[0], link[1], 0.01, TARGET_DELAY_MS, 18, idBase, MAX_RATE);
                double offPing = lastLateP95;
                idBase += 20;
                MapRects.enabled = true;
                ClusterResult on = runGifCluster(6, 16, 0, 12, link[0], link[1], 0.01, TARGET_DELAY_MS, 18, idBase, MAX_RATE);
                double onPing = lastLateP95;
                idBase += 20;
                System.out.printf("SPARSE link %5.1f MB/s: bounding boxes %.1f gifs frames/s at %.0f KB/s, ping p95 %.0f ms; small rectangles %.1f frames/s at %.0f KB/s, ping p95 %.0f ms%n",
                        link[0] / 1e6, off.averageFps(), off.kbPerSecond(), offPing * 1000, on.averageFps(), on.kbPerSecond(), onPing * 1000);
                if (link[0] <= 3_000_000) {
                    assertTrue(on.averageFps() >= off.averageFps() * 1.5, "small rectangles must make a bandwidth-limited link carry clearly more frames: " + on.averageFps() + " vs " + off.averageFps());
                }
                assertTrue(onPing <= Math.max(offPing + 0.03, 0.13), "small rectangles must not raise the added ping (a fully used slow link sits near 100 ms): " + onPing + " vs " + offPing);
            }
        } finally {
            testSparse = false;
            MapRects.enabled = true;
        }
    }

    private SyncGroup buildNearlyStillGif(int groupId, int mapBase, int maps, int frames) throws Exception {
        FlatFileStorage storage = new FlatFileStorage(new File(dir, "g" + groupId), executor);
        for (int frame = 0; frame < frames; frame++) {
            Map<Integer, MapFrameUpdate> updates = new HashMap<>();
            for (int m = 0; m < maps; m++) {
                if (frame == 0) {
                    updates.put(mapBase + m, AnimationDistributionTest.noise(0, mapBase + m, groupId * 31 + m));
                } else if (m == 0) {
                    updates.put(mapBase + m, AnimationDistributionTest.update(frame, mapBase + m, frame % 100, 5, 1, 1, 1 + frame));
                }
            }
            storage.saveBundledFrame(groupId, frame, updates);
            for (MapFrameUpdate update : updates.values()) update.freeMemory();
        }
        UUID worldId = UUID.randomUUID();
        List<FrameNode> nodes = new CopyOnWriteArrayList<>();
        for (int m = 0; m < maps; m++) nodes.add(new FrameNode(m, UUID.randomUUID(), worldId, 0, 0, m, 64, 0, mapBase + m, null));
        MapMetadata meta = new MapMetadata(groupId, UUID.randomUUID(), "http://x/almost-still.gif", maps, 1, frames, 50);
        SyncGroup group = new SyncGroup(meta, nodes, storage, cache, executor, null, 96.0);
        group.setViewerStateForTesting(viewer, true, true);
        long deadline = System.currentTimeMillis() + 15_000;
        while (!group.baseLoadedForTesting() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        return group;
    }
}
