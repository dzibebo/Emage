package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
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
    static final double ANIMATION_TARGET_SECONDS = 0.25;
    static final double ANIMATION_FULL_TARGET_SECONDS = 0.6;
    static final double ANIMATION_FULL_LOW_TARGET_SECONDS = 0.4;

    private record Sample(long rtt, long sentBytes, boolean clean) {}

    record Tuning(int minBytesPerSecond, int maxBytesPerSecond, int maxPendingBytes, int targetDelayMs, boolean latencyProbe) {}

    private static final int PROBE_ID_BASE = 0x4D450000;
    private static final int PROBE_ID_MASK = 0xFFFF0000;

    private static final int MAX_PACKETS_PER_FLUSH = 256;
    private static final double BURST_SECONDS = 0.04;
    private static final double INITIAL_RATE = 32_768;
    private static final double STILL_LOOKAHEAD_SECONDS = 0.35;
    private static final double OPTIMISTIC_CAPACITY = 4_194_304;

    private static final long PROBE_INTERVAL_NANOS = 100_000_000L;
    private static final long IDLE_PROBE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long PROBE_TIMEOUT_NANOS = 4_000_000_000L;
    private static final long PROBE_BACKOFF_MAX_NANOS = 30_000_000_000L;
    private static final long ACTIVE_WINDOW_NANOS = 1_200_000_000L;
    private static final long PROBE_TRUST_NANOS = 3_000_000_000L;
    private static final long PENDING_ADJUST_NANOS = 200_000_000L;
    private static final long IDLE_RESTART_NANOS = 3_000_000_000L;
    private static final long CLEAN_RTT_INTERVAL_NANOS = 30_000_000_000L;
    private static final long CLEAN_RTT_MAX_HOLD_NANOS = 6_000_000_000L;

    @SuppressWarnings("unchecked")
    private final Queue<DeltaFrame>[] tiers = new Queue[TIERS];
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLongArray tierBytes = new AtomicLongArray(TIERS);
    private volatile boolean closed;
    private volatile double animBudget;

    private final double minRate;
    private final double maxRate;
    private final long pendingCap;
    private final boolean probeWanted;

    private final RateController controller;
    private double tokens;
    private long lastRefill;
    private long lastSendNanos;
    private long lastLimitedNanos;
    private long lastPendingAdjust = Long.MIN_VALUE / 4;

    private boolean probeChecked;
    private boolean probeDisabled;
    private boolean everSampled;
    private int probeTimeouts;
    private long probeBackoffUntil;
    private int probeSeq;
    private long lastProbeNanos;
    private long lastSampleNanos;
    private volatile int outstandingProbe = -1;
    private volatile long probeSentNanos;
    private final AtomicReference<Sample> pendingSample = new AtomicReference<>();
    private long totalSentBytes;
    private volatile long probeSentBytes;
    private volatile boolean probeClean;
    private int cleanPhase;
    private long cleanPhaseStart;
    private long lastCleanNanos = Long.MIN_VALUE / 4;
    private long cleanHolds;
    private long totalSentPackets;
    private long flushBytes;
    private double sentRateEwma;
    private volatile long lastPendingSeen;
    private volatile boolean lastWritableSeen = true;

    PlayerSendQueue(@NotNull Tuning tuning) {
        for (int i = 0; i < TIERS; i++) {
            tiers[i] = new ConcurrentLinkedQueue<>();
        }
        this.minRate = Math.max(4096, tuning.minBytesPerSecond());
        this.maxRate = Math.max(this.minRate, tuning.maxBytesPerSecond());
        this.pendingCap = Math.max(MAP_PACKET_BYTES * 2L, tuning.maxPendingBytes());
        this.probeWanted = tuning.latencyProbe();
        this.controller = new RateController(
                this.minRate,
                Math.max(this.minRate, tuning.maxBytesPerSecond()),
                initialRate(tuning),
                Math.max(20, tuning.targetDelayMs()) * 1_000_000L);
        this.tokens = MAP_PACKET_BYTES;
        this.animBudget = ANIMATION_TARGET_SECONDS * capacityEstimate();
    }

    static double initialRate(@NotNull Tuning tuning) {
        double min = Math.max(4096, tuning.minBytesPerSecond());
        double max = Math.max(min, tuning.maxBytesPerSecond());
        return Math.min(max, Math.max(min, INITIAL_RATE));
    }

    static double optimisticCapacity(double maxRate) {
        return Math.min(maxRate, OPTIMISTIC_CAPACITY);
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

    double capacityEstimate() {
        return controller.slowStart()
                ? Math.max(controller.rate(), optimisticCapacity(maxRate))
                : controller.rate();
    }

    double animationFullBudget(boolean lowPriority) {
        double seconds = lowPriority ? ANIMATION_FULL_LOW_TARGET_SECONDS : ANIMATION_FULL_TARGET_SECONDS;
        return Math.max(0.0, seconds * capacityEstimate() - Math.max(0L, tierBytes.get(TIER_ANIMATION)));
    }

    double animationBudget() {
        return animBudget;
    }

    void spendAnimationBudget(long bytes) {
        animBudget -= bytes;
    }

    private void refreshAnimationBudget() {
        animBudget = Math.max(0.0, ANIMATION_TARGET_SECONDS * capacityEstimate() - Math.max(0L, tierBytes.get(TIER_ANIMATION)));
    }

    double urgentDrainSeconds(long extraBytes) {
        double capacity = controller.slowStart()
                ? Math.max(controller.rate(), optimisticCapacity(maxRate))
                : controller.rate();
        return (Math.max(0L, tierBytes.get(TIER_ANIMATION)) + Math.max(0L, extraBytes)) / Math.max(minRate, capacity);
    }

    Set<Integer> purgeAnimation(@NotNull Set<Integer> mapIds) {
        Set<Integer> dropped = new HashSet<>();
        Iterator<DeltaFrame> it = tiers[TIER_ANIMATION].iterator();
        while (it.hasNext()) {
            DeltaFrame part = it.next();
            if (mapIds.contains(part.mapId())) {
                it.remove();
                dropped.add(part.mapId());
                removed(TIER_ANIMATION, part);
                part.freeMemory();
            }
        }
        return dropped;
    }

    Set<Integer> purgeStills(@NotNull Set<Integer> mapIds) {
        Set<Integer> dropped = new HashSet<>();
        for (int t = 1; t < TIERS; t++) {
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

    long stillBytesUpToTier(int tier) {
        long sum = 0;
        for (int t = 1; t <= Math.min(tier, TIERS - 1); t++) {
            sum += Math.max(0L, tierBytes.get(t));
        }
        return sum;
    }

    double currentRateKbForTesting() {
        return controller.rate() / 1024.0;
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
            sb.append(String.format(" rtt=%d ms base=%d ms (clean %d ms)", controller.lastRtt() / 1_000_000L, controller.lastBaseRtt() / 1_000_000L, controller.cleanBase() / 1_000_000L));
        } else {
            sb.append(" probe=waiting(").append(probeTimeouts).append(" missed)");
        }
        sb.append(String.format(" capacity=%.0f KB/s", controller.peakDelivery() / 1024.0));
        if (controller.slowStart()) sb.append(" slow-start");
        return sb.toString();
    }

    java.util.List<String> describeFull(long nowNanos) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(String.format("    send rate limit %.0f KB/s (allowed %.0f..%.0f KB/s)%s, actually sending ~%.0f KB/s, %d MB and %d packets sent in total",
                controller.rate() / 1024.0, minRate / 1024.0, maxRate / 1024.0, controller.slowStart() ? ", ramping up" : "",
                sentRateEwma / 1024.0, totalSentBytes / (1024 * 1024), totalSentPackets));
        lines.add(String.format("    waiting in the server queue: %d packets (animation %d KB, near pictures %d KB, mid %d KB, far %d KB)",
                size.get(),
                Math.max(0L, tierBytes.get(0)) / 1024, Math.max(0L, tierBytes.get(1)) / 1024,
                Math.max(0L, tierBytes.get(2)) / 1024, Math.max(0L, tierBytes.get(3)) / 1024));
        lines.add(String.format("    network buffer: %s, %s",
                lastWritableSeen ? "writable" : "FULL (the connection or proxy is not accepting more data)",
                lastPendingSeen < 0 ? "pending unknown" : (lastPendingSeen / 1024) + " KB pending"));
        if (!probeWanted) {
            lines.add("    ping probe: switched off in the config");
        } else if (probeDisabled) {
            lines.add("    ping probe: not supported by this client, limiting by network buffer only");
        } else if (!everSampled) {
            lines.add("    ping probe: no answer yet (" + probeTimeouts + " missed)");
        } else {
            long lastRtt = controller.lastRtt() / 1_000_000L;
            long base = controller.lastBaseRtt() / 1_000_000L;
            lines.add(String.format("    ping probe: rtt %d ms, baseline %d ms (idle-measured %d ms), so Emage adds ~%d ms; last answer %.1f s ago, probes missed %d, pauses to measure baseline %d%s",
                    lastRtt, base, controller.cleanBase() / 1_000_000L, Math.max(0, lastRtt - base),
                    (nowNanos - lastSampleNanos) / 1e9, probeTimeouts, cleanHolds, cleanPhase != 0 ? " (pausing now)" : ""));
        }
        lines.add(String.format("    measured delivery capacity (last 20 s peak) %.0f KB/s; %s",
                controller.peakDelivery() / 1024.0, controller.stats()));
        lines.add("    ping noise: " + controller.noiseReport());
        return lines;
    }

    int flush(@NotNull Transport transport, @NotNull SendBudget budget, long now) {
        if (lastRefill == 0) lastRefill = now;
        double dt = (now - lastRefill) / 1_000_000_000.0;
        lastRefill = now;
        if (dt < 0) dt = 0;
        if (dt > 0.25) dt = 0.25;
        double rate = controller.rate();
        double burst = Math.max(rate * BURST_SECONDS, MAP_PACKET_BYTES * 2.0);
        tokens = Math.min(tokens + rate * dt, burst);

        if (outstandingProbe != -1) {
            controller.onProbeInFlight(now, now - probeSentNanos);
        }
        consumeSample(now);
        manageCleanProbe(now);

        if (!isEmpty() && now - lastSendNanos > IDLE_RESTART_NANOS && cleanPhase == 0) {
            controller.restartSlowStart();
        }

        flushBytes = 0;
        int sent = (isEmpty() || cleanPhase != 0) ? 0 : sendPending(transport, budget, now);
        double alpha = 1.0 - Math.exp(-Math.max(dt, 0.001) / 2.0);
        sentRateEwma += (flushBytes / Math.max(dt, 0.001) - sentRateEwma) * alpha;
        totalSentPackets += sent;

        serviceProbe(transport, now);
        refreshAnimationBudget();
        return sent;
    }

    private int sendPending(Transport transport, SendBudget budget, long now) {
        boolean writable = transport.isWritable();
        boolean probing = probeWorking(now);
        long cap = probing ? Math.max(pendingCap, (long) (controller.rate() * 0.06)) : pendingCap;
        double measured = controller.peakDelivery();
        if (probing && measured > 0) {
            cap = Math.min(cap, (long) Math.max(MAP_PACKET_BYTES * 2.5, measured * 0.5));
        }
        long pending = writable ? transport.pendingBytes() : Long.MAX_VALUE / 2;
        lastPendingSeen = writable ? pending : -1;
        lastWritableSeen = writable;

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
        boolean stillsWaiting = tierBytes.get(1) + tierBytes.get(2) + tierBytes.get(3) > 0;
        double animationAllowance = stillsWaiting
                ? Math.max(MAP_PACKET_BYTES, 0.6 * Math.min(Math.max(tokens, 0.0), Math.max(0L, cap - pending)))
                : Double.MAX_VALUE;
        double animationSpent = 0;
        outer:
        for (int t = 0; t < TIERS; t++) {
            Queue<DeltaFrame> queue = tiers[t];
            while (true) {
                if (tokens <= 0) {
                    lastLimitedNanos = now;
                    break outer;
                }
                if (t == TIER_ANIMATION && animationSpent >= animationAllowance) break;
                if (!budget.available()) break outer;
                if (sent >= MAX_PACKETS_PER_FLUSH || pending + sentBytes >= cap) break outer;

                DeltaFrame source = queue.poll();
                if (source == null) break;

                int packetBytes;
                boolean written;
                try {
                    removed(t, source);
                    packetBytes = source.packetBuf().readableBytes() + 16;
                    written = transport.write(source);
                } finally {
                    source.freeMemory();
                }
                if (!written) continue;

                tokens -= packetBytes;
                if (t == TIER_ANIMATION) animationSpent += packetBytes;
                totalSentBytes += packetBytes;
                budget.spend(packetBytes);
                sentBytes += packetBytes;
                sent++;
            }
        }

        flushBytes = sentBytes;
        if (sent > 0) {
            transport.flush();
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

        controller.onSample(now, sample.rtt(), now - lastLimitedNanos < 400_000_000L, sample.sentBytes(), sample.clean());
        if (sample.clean()) {
            lastCleanNanos = now;
            cleanPhase = 0;
        }
    }

    private void manageCleanProbe(long now) {
        if (cleanPhase == 0) {
            if (probeWorking(now) && !isEmpty() && now - lastSendNanos < ACTIVE_WINDOW_NANOS
                    && now - lastCleanNanos > CLEAN_RTT_INTERVAL_NANOS) {
                cleanPhase = 1;
                cleanPhaseStart = now;
                cleanHolds++;
            }
        } else if (now - cleanPhaseStart > CLEAN_RTT_MAX_HOLD_NANOS) {
            cleanPhase = 0;
            lastCleanNanos = now - CLEAN_RTT_INTERVAL_NANOS + 5_000_000_000L;
        }
    }

    private boolean cleanGapElapsed(long now) {
        long needed = Math.max(300_000_000L, (long) (controller.lastRtt() * 1.2) + 100_000_000L);
        return now - lastSendNanos >= needed;
    }

    private void serviceProbe(Transport transport, long now) {
        if (!probeWanted || probeDisabled) return;

        if (!probeChecked) {
            probeChecked = true;
            if (!transport.supportsProbe()) {
                probeDisabled = true;
                return;
            }
        }

        if (outstandingProbe != -1) {
            if (now - probeSentNanos <= PROBE_TIMEOUT_NANOS) return;
            outstandingProbe = -1;
            probeTimeouts++;
            if (!everSampled) {
                long backoff = Math.min(PROBE_BACKOFF_MAX_NANOS, 2_000_000_000L << Math.min(probeTimeouts, 4));
                probeBackoffUntil = now + backoff;
            } else {
                controller.decrease(now, 0.5);
            }
        }

        if (now < probeBackoffUntil) return;

        boolean active = !isEmpty() || now - lastSendNanos < ACTIVE_WINDOW_NANOS;
        long interval = active ? PROBE_INTERVAL_NANOS : IDLE_PROBE_INTERVAL_NANOS;
        if (now - lastProbeNanos < interval) return;

        int id = PROBE_ID_BASE | (probeSeq++ & 0xFFFF);
        lastProbeNanos = now;
        probeSentNanos = now;
        probeSentBytes = totalSentBytes;
        probeClean = cleanGapElapsed(now);
        outstandingProbe = id;
        if (!transport.sendProbe(id)) {
            outstandingProbe = -1;
            probeBackoffUntil = now + PROBE_BACKOFF_MAX_NANOS;
        }
    }

    void onPong(int id, long nowNanos) {
        if (id != outstandingProbe) return;
        long rtt = nowNanos - probeSentNanos;
        long sentBytes = probeSentBytes;
        outstandingProbe = -1;
        pendingSample.set(new Sample(Math.max(0L, rtt), sentBytes, probeClean));
    }
}
