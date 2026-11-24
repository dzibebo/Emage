package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.IllegalReferenceCountException;
import net.ed1thy.emage.model.DeltaFrame;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerSendQueue {

    static final int TIER_ANIMATION = 0;
    static final int TIERS = 4;
    static final int MAP_PACKET_BYTES = 16_500;

    private record Sample(long rtt, long sentBytes) {}

    record Tuning(int minBytesPerSecond, int maxBytesPerSecond, int maxPendingBytes, int targetDelayMs, boolean latencyProbe) {}

    private static final int PROBE_ID_BASE = 0x4D450000;
    private static final int PROBE_ID_MASK = 0xFFFF0000;

    private static final int MAX_PACKETS_PER_FLUSH = 256;
    private static final double INITIAL_RATE = 65_536;
    private static final double STILL_LOOKAHEAD_SECONDS = 0.35;

    private static final long PROBE_INTERVAL_NANOS = 100_000_000L;
    private static final long IDLE_PROBE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long PROBE_TIMEOUT_NANOS = 1_500_000_000L;
    private static final long ACTIVE_WINDOW_NANOS = 1_200_000_000L;
    private static final long PROBE_TRUST_NANOS = 3_000_000_000L;
    private static final long PENDING_ADJUST_NANOS = 200_000_000L;

    @SuppressWarnings("unchecked")
    private final Queue<DeltaFrame>[] tiers = new Queue[TIERS];
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLongArray tierBytes = new AtomicLongArray(TIERS);
    private volatile boolean closed;

    private final double minRate;
    private final long pendingCap;
    private final boolean probeWanted;

    private final RateController controller;
    private double tokens;
    private long lastRefill = System.nanoTime();
    private long lastSendNanos;
    private long lastLimitedNanos;
    private long lastPendingAdjust = lastRefill;

    private boolean probeChecked;
    private boolean probeDisabled;
    private boolean everSampled;
    private int probeTimeouts;
    private int probeSeq;
    private long lastProbeNanos;
    private long lastSampleNanos;
    private volatile int outstandingProbe = -1;
    private volatile long probeSentNanos;
    private final AtomicReference<Sample> pendingSample = new AtomicReference<>();
    private long totalSentBytes;
    private volatile long probeSentBytes;

    PlayerSendQueue(@NotNull Tuning tuning) {
        for (int i = 0; i < TIERS; i++) {
            tiers[i] = new ConcurrentLinkedQueue<>();
        }
        this.minRate = Math.max(4096, tuning.minBytesPerSecond());
        this.pendingCap = Math.max(MAP_PACKET_BYTES * 2L, tuning.maxPendingBytes());
        this.probeWanted = tuning.latencyProbe();
        this.controller = new RateController(
                this.minRate,
                Math.max(this.minRate, tuning.maxBytesPerSecond()),
                initialRate(tuning),
                Math.max(20, tuning.targetDelayMs()) * 1_000_000L);
        this.tokens = MAP_PACKET_BYTES;
    }

    static double initialRate(@NotNull Tuning tuning) {
        double min = Math.max(4096, tuning.minBytesPerSecond());
        double max = Math.max(min, tuning.maxBytesPerSecond());
        return Math.min(max, Math.max(min, INITIAL_RATE));
    }

    static double lookaheadFor(double rate) {
        return Math.max(MAP_PACKET_BYTES, rate * STILL_LOOKAHEAD_SECONDS);
    }

    static boolean isProbeId(int id) {
        return (id & PROBE_ID_MASK) == PROBE_ID_BASE;
    }

    boolean add(@NotNull DeltaFrame source, int tier) {
        if (closed) return false;
        ByteBuf buf = source.packetBuf();
        if (buf == null) return false;
        int bytes;
        try {
            bytes = buf.readableBytes();
            if (buf.refCnt() <= 0 || bytes <= 0) return false;
            buf.retain();
        } catch (IllegalReferenceCountException e) {
            return false;
        }
        int t = Math.max(0, Math.min(TIERS - 1, tier));
        tiers[t].add(source);
        size.incrementAndGet();
        tierBytes.addAndGet(t, bytes);
        return true;
    }

    int size() {
        return size.get();
    }

    boolean isEmpty() {
        return size.get() <= 0;
    }

    void close() {
        closed = true;
    }

    boolean isClosed() {
        return closed;
    }

    void drain() {
        closed = true;
        for (int t = 0; t < TIERS; t++) {
            DeltaFrame dropped;
            while ((dropped = tiers[t].poll()) != null) {
                removed(t, dropped);
                dropped.freeMemory();
            }
        }
    }

    private void removed(int tier, DeltaFrame part) {
        size.decrementAndGet();
        ByteBuf buf = part.packetBuf();
        if (buf != null && buf.refCnt() > 0) {
            tierBytes.addAndGet(tier, -buf.readableBytes());
        }
    }

    Set<Integer> purge(@NotNull Set<Integer> mapIds) {
        Set<Integer> dropped = new HashSet<>();
        for (int t = 0; t < TIERS; t++) {
            Iterator<DeltaFrame> it = tiers[t].iterator();
            while (it.hasNext()) {
                DeltaFrame part = it.next();
                if (mapIds.contains(part.mapId())) {
                    it.remove();
                    dropped.add(part.mapId());
                    removed(t, part);
                    part.freeMemory();
                }
            }
        }
        return dropped;
    }

    double urgentDrainSeconds(long extraBytes) {
        return (Math.max(0L, tierBytes.get(TIER_ANIMATION)) + Math.max(0L, extraBytes)) / Math.max(minRate, controller.rate());
    }

    long stillBytesUpToTier(int tier) {
        long sum = 0;
        for (int t = 1; t <= Math.min(tier, TIERS - 1); t++) {
            sum += Math.max(0L, tierBytes.get(t));
        }
        return sum;
    }

    double lookaheadBytes() {
        return lookaheadFor(controller.rate());
    }

    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("rate=%.0f KB/s", controller.rate() / 1024.0));
        sb.append(String.format(" queued=%d (anim %d KB, near %d KB, mid %d KB, far %d KB)",
                size.get(),
                Math.max(0L, tierBytes.get(0)) / 1024,
                Math.max(0L, tierBytes.get(1)) / 1024,
                Math.max(0L, tierBytes.get(2)) / 1024,
                Math.max(0L, tierBytes.get(3)) / 1024));
        if (!probeWanted) {
            sb.append(" probe=off");
        } else if (probeDisabled) {
            sb.append(" probe=unsupported");
        } else if (everSampled) {
            sb.append(String.format(" rtt=%d ms base=%d ms", controller.lastRtt() / 1_000_000L, controller.lastBaseRtt() / 1_000_000L));
        } else {
            sb.append(" probe=waiting");
        }
        if (controller.slowStart()) sb.append(" slow-start");
        return sb.toString();
    }

    int flush(@NotNull User user, @NotNull Channel channel, @NotNull PacketSender sender, @NotNull SendBudget budget, long now) {
        double dt = (now - lastRefill) / 1_000_000_000.0;
        lastRefill = now;
        if (dt < 0) dt = 0;
        if (dt > 0.25) dt = 0.25;
        double rate = controller.rate();
        double burst = Math.max(rate * 0.25, MAP_PACKET_BYTES * 2.0);
        tokens = Math.min(tokens + rate * dt, burst);

        if (outstandingProbe != -1) {
            controller.onProbeInFlight(now, now - probeSentNanos);
        }
        consumeSample(now);

        int sent = isEmpty() ? 0 : sendPending(user, channel, sender, budget, now);

        serviceProbe(user, now);
        return sent;
    }

    private int sendPending(User user, Channel channel, PacketSender sender, SendBudget budget, long now) {
        boolean writable = channel.isWritable();
        boolean probing = probeWorking(now);
        long cap = probing ? Math.max(pendingCap, (long) (controller.rate() * 0.06)) : pendingCap;
        long pending = writable ? pendingBytes(channel) : Long.MAX_VALUE / 2;

        if (probing) {
            if (!writable && now - lastPendingAdjust >= PENDING_ADJUST_NANOS) {
                lastPendingAdjust = now;
                controller.decrease(now, 0.7);
            }
        } else {
            adaptFromBuffer(now, writable, pending);
        }

        if (!writable || pending >= cap) return 0;

        int sent = 0;
        long sentBytes = 0;
        outer:
        for (int t = 0; t < TIERS; t++) {
            Queue<DeltaFrame> queue = tiers[t];
            while (true) {
                if (tokens <= 0) {
                    lastLimitedNanos = now;
                    break outer;
                }
                if (!budget.available()) break outer;
                if (sent >= MAX_PACKETS_PER_FLUSH || pending + sentBytes >= cap) break outer;

                DeltaFrame source = queue.poll();
                if (source == null) break;

                int packetBytes;
                PacketWrapper<?> packet;
                try {
                    removed(t, source);
                    packet = sender.createMapPacket(source);
                    packetBytes = source.packetBuf().readableBytes() + 16;
                } finally {
                    source.freeMemory();
                }
                if (packet == null) continue;

                try {
                    user.writePacketSilently(packet);
                } catch (Exception e) {
                    if (packet instanceof ZeroCopyMapWrapper map) {
                        map.getDelta().freeMemory();
                    }
                }
                tokens -= packetBytes;
                totalSentBytes += packetBytes;
                budget.spend(packetBytes);
                sentBytes += packetBytes;
                sent++;
            }
        }

        if (sent > 0) {
            user.flushPackets();
            lastSendNanos = now;
        }
        return sent;
    }

    private boolean probeWorking(long now) {
        return probeWanted && !probeDisabled && everSampled && now - lastSampleNanos < PROBE_TRUST_NANOS;
    }

    private void adaptFromBuffer(long now, boolean writable, long pending) {
        if (now - lastPendingAdjust < PENDING_ADJUST_NANOS) return;
        if (!writable || pending >= pendingCap) {
            lastPendingAdjust = now;
            controller.cut(0.6);
        } else if (pending <= pendingCap / 4 && now - lastLimitedNanos < 400_000_000L) {
            lastPendingAdjust = now;
            controller.increase();
        }
    }

    private void consumeSample(long now) {
        Sample sample = pendingSample.getAndSet(null);
        if (sample == null) return;

        everSampled = true;
        probeTimeouts = 0;
        lastSampleNanos = now;

        controller.onSample(now, sample.rtt(), now - lastLimitedNanos < 400_000_000L, sample.sentBytes());
    }

    private void serviceProbe(User user, long now) {
        if (!probeWanted || probeDisabled) return;

        if (!probeChecked) {
            probeChecked = true;
            if (!user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_17)) {
                probeDisabled = true;
                return;
            }
        }

        if (outstandingProbe != -1) {
            if (now - probeSentNanos <= PROBE_TIMEOUT_NANOS) return;
            outstandingProbe = -1;
            probeTimeouts++;
            if (!everSampled) {
                if (probeTimeouts >= 3) probeDisabled = true;
            } else {
                controller.decrease(now, 0.5);
            }
        }

        boolean active = !isEmpty() || now - lastSendNanos < ACTIVE_WINDOW_NANOS;
        long interval = active ? PROBE_INTERVAL_NANOS : IDLE_PROBE_INTERVAL_NANOS;
        if (now - lastProbeNanos < interval) return;

        int id = PROBE_ID_BASE | (probeSeq++ & 0xFFFF);
        lastProbeNanos = now;
        probeSentNanos = now;
        probeSentBytes = totalSentBytes;
        outstandingProbe = id;
        try {
            user.sendPacketSilently(new WrapperPlayServerPing(id));
        } catch (Exception e) {
            outstandingProbe = -1;
            probeDisabled = true;
        }
    }

    void onPong(int id) {
        if (id != outstandingProbe) return;
        long rtt = System.nanoTime() - probeSentNanos;
        long sentBytes = probeSentBytes;
        outstandingProbe = -1;
        pendingSample.set(new Sample(Math.max(0L, rtt), sentBytes));
    }

    private static long pendingBytes(Channel channel) {
        long high = channel.config().getWriteBufferHighWaterMark();
        return Math.max(0L, high - channel.bytesBeforeUnwritable());
    }
}
