package net.ed1thy.emage.render;

import java.util.Arrays;

final class RateController {

    private static final double ABSOLUTE_MIN_RATE = 16_384;
    private static final int RTT_BUCKETS = 10;
    private static final long RTT_BUCKET_NANOS = 1_000_000_000L;
    private static final long DELIVERY_WINDOW_NANOS = 400_000_000L;
    private static final long PEAK_MEMORY_NANOS = 20_000_000_000L;
    private static final int PEAK_ENTRIES = 64;
    private static final long IN_FLIGHT_BACKSTOP_NANOS = 160_000_000L;
    private static final long ABSOLUTE_RTT_LIMIT_NANOS = 2_000_000_000L;
    private static final int FILTER = 2;
    private static final double INCREASE_BELOW = 0.75;
    private static final double DECREASE_ABOVE = 1.5;
    private static final double NEAR_PEAK_GROWTH = 1.03;
    private static final double SEVERE_ABOVE = 3.0;

    private final double minRate;
    private final double maxRate;
    private final long targetDelayNanos;

    private volatile double rate;
    private boolean slowStart = true;
    private long lastDecreaseNanos;
    private volatile long lastRtt;
    private volatile long lastBaseRtt;

    private final long[] lastDelays = new long[3];
    private int delayCount;
    private int delayIndex;

    private boolean hasAnchor;
    private long anchorNanos;
    private long anchorBytes;
    private volatile double peakDelivery;
    private final long[] peakTimes = new long[PEAK_ENTRIES];
    private final double[] peakValues = new double[PEAK_ENTRIES];
    private int peakCount;

    private final long[] cleanRing = {Long.MAX_VALUE, Long.MAX_VALUE};
    private int cleanIndex;
    private volatile long cleanBase;
    private double recoverTo;
    private volatile long congestionEvents;
    private volatile long severeEvents;
    private volatile long increaseEvents;
    private volatile long lastCongestionNanos;
    private volatile String lastCongestionReason = "none";

    private final long[] rttBuckets = new long[RTT_BUCKETS];
    private int rttBucketIndex;
    private long rttBucketStart;

    RateController(double minRate, double maxRate, double initialRate, long targetDelayNanos) {
        this.minRate = minRate;
        this.maxRate = Math.max(minRate, maxRate);
        this.targetDelayNanos = targetDelayNanos;
        this.rate = Math.min(this.maxRate, Math.max(minRate, initialRate));
    }

    double rate() {
        return rate;
    }

    boolean slowStart() {
        return slowStart;
    }

    long lastRtt() {
        return lastRtt;
    }

    long lastBaseRtt() {
        return lastBaseRtt;
    }

    double peakDelivery() {
        return peakDelivery;
    }

    void restartSlowStart() {
        slowStart = true;
    }

    void onSample(long now, long rtt, boolean rateLimited, long sentBytesAtProbe) {
        onSample(now, rtt, rateLimited, sentBytesAtProbe, false);
    }

    void onSample(long now, long rtt, boolean rateLimited, long sentBytesAtProbe, boolean clean) {
        lastRtt = rtt;
        if (clean) recordClean(rtt);
        noteDelayForStats(now, rtt);
        long base = recordRtt(now, rtt);
        long clear = cleanBase;
        if (clear > 0 && clear < base) base = clear;
        lastBaseRtt = base;

        long delay = Math.max(0L, rtt - base);
        lastDelays[delayIndex] = delay;
        delayIndex = (delayIndex + 1) % lastDelays.length;
        if (delayCount < lastDelays.length) delayCount++;
        int window = Math.min(delayCount, FILTER);
        long smoothedDelay = delay;
        for (int i = 1; i < window; i++) {
            smoothedDelay = Math.min(smoothedDelay, lastDelays[(delayIndex - 1 - i + 2 * lastDelays.length) % lastDelays.length]);
        }

        long target = targetDelayNanos;

        updateDelivery(now, sentBytesAtProbe);

        if (slowStart && delayCount >= 2) {
            long fast = Math.min(delay, lastDelays[(delayIndex - 2 + lastDelays.length) % lastDelays.length]);
            if (fast > target) slowStart = false;
        }

        if (smoothedDelay <= target * DECREASE_ABOVE && rtt > ABSOLUTE_RTT_LIMIT_NANOS) {
            lastCongestionReason = "absolute rtt";
            congestion(now, true, rateLimited);
        } else if (smoothedDelay > target * SEVERE_ABOVE) {
            lastCongestionReason = "delay>severe";
            congestion(now, true, rateLimited);
        } else if (smoothedDelay > target * DECREASE_ABOVE) {
            lastCongestionReason = "delay>target";
            congestion(now, false, rateLimited);
        } else if (smoothedDelay < target * INCREASE_BELOW && rateLimited) {
            increase();
        }
    }

    void onProbeInFlight(long now, long ageNanos) {
        if (lastBaseRtt <= 0) return;
        long threshold = Math.max((long) (targetDelayNanos * SEVERE_ABOVE), IN_FLIGHT_BACKSTOP_NANOS);
        if (ageNanos - lastBaseRtt > threshold) {
            lastCongestionReason = "probe overdue";
            congestion(now, true, true);
        }
    }

    private void updateDelivery(long now, long sentBytes) {
        if (!hasAnchor) {
            hasAnchor = true;
            anchorNanos = now;
            anchorBytes = sentBytes;
            return;
        }
        long elapsed = now - anchorNanos;
        if (elapsed < DELIVERY_WINDOW_NANOS) return;
        long bytes = sentBytes - anchorBytes;
        anchorNanos = now;
        anchorBytes = sentBytes;
        if (bytes <= 0) return;

        double delivered = bytes / (elapsed / 1_000_000_000.0);
        if (peakCount == PEAK_ENTRIES) {
            System.arraycopy(peakTimes, 1, peakTimes, 0, PEAK_ENTRIES - 1);
            System.arraycopy(peakValues, 1, peakValues, 0, PEAK_ENTRIES - 1);
            peakCount--;
        }
        peakTimes[peakCount] = now;
        peakValues[peakCount] = delivered;
        peakCount++;

        double peak = 0;
        int keep = 0;
        for (int i = 0; i < peakCount; i++) {
            if (now - peakTimes[i] <= PEAK_MEMORY_NANOS) {
                peakTimes[keep] = peakTimes[i];
                peakValues[keep] = peakValues[i];
                peak = Math.max(peak, peakValues[i]);
                keep++;
            }
        }
        peakCount = keep;
        peakDelivery = peak;
    }

    double minimumRate() {
        double peak = peakDelivery;
        if (peak > 0 && peak < minRate) {
            return Math.max(ABSOLUTE_MIN_RATE, peak * 0.7);
        }
        return minRate;
    }

    private double floorRate() {
        return minimumRate();
    }

    private static long median(long[] values, int count) {
        long[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return copy[count / 2];
    }

    private void congestion(long now, boolean severe, boolean deliveryRateValid) {
        long cooldown = Math.max(250_000_000L, lastBaseRtt);
        if (now - lastDecreaseNanos < cooldown) return;
        lastDecreaseNanos = now;
        congestionEvents++;
        if (severe) severeEvents++;
        lastCongestionNanos = now;

        double before = rate;
        double target = rate * (severe ? 0.5 : 0.8);
        if (deliveryRateValid && peakDelivery > 0 && severe && rate > peakDelivery * 2.0) {
            target = Math.min(target, peakDelivery * 0.7);
        }
        target = Math.max(target, rate * 0.3);
        target = Math.max(target, floorRate());
        rate = Math.min(rate, Math.max(minimumRate(), target));
        recoverTo = before * 0.85;
        slowStart = false;
    }

    void decrease(long now, double factor) {
        long cooldown = Math.max(250_000_000L, lastBaseRtt);
        if (now - lastDecreaseNanos < cooldown) return;
        lastDecreaseNanos = now;
        rate = Math.max(floorRate(), rate * factor);
        slowStart = false;
    }

    void increase() {
        increaseEvents++;
        double next;
        if (slowStart) {
            next = rate * slowStartGrowth();
        } else if (recoverTo > 0 && rate < recoverTo) {
            next = rate * 1.12 + 8192;
        } else if (rate < peakDelivery * 0.4) {
            next = rate * 1.25 + 8192;
        } else if (rate < peakDelivery * 0.9) {
            next = rate * 1.08 + 8192;
        } else {
            next = rate * NEAR_PEAK_GROWTH + 4096;
        }
        rate = Math.min(maxRate, next);
        if (recoverTo > 0 && rate >= recoverTo) recoverTo = 0;
    }

    private double slowStartGrowth() {
        long base = lastBaseRtt;
        if (base <= 0) return 1.5;
        double perSample = Math.pow(2.0, 100_000_000.0 / base);
        return Math.max(1.12, Math.min(1.5, perSample));
    }

    void cut(double factor) {
        rate = Math.max(minimumRate(), rate * factor);
        slowStart = false;
    }

    private final long[] statDelays = new long[64];
    private int statCount;
    private int statIndex;
    private long statBase = Long.MAX_VALUE;

    private void noteDelayForStats(long now, long rtt) {
        statBase = Math.min(statBase, rtt);
        statDelays[statIndex] = rtt;
        statIndex = (statIndex + 1) % statDelays.length;
        if (statCount < statDelays.length) statCount++;
    }

    String noiseReport() {
        int count = statCount;
        if (count < 8) return "not enough samples yet";
        long[] copy = Arrays.copyOf(statDelays, count);
        Arrays.sort(copy);
        long base = lastBaseRtt > 0 ? lastBaseRtt : copy[0];
        long median = Math.max(0, copy[count / 2] - base) / 1_000_000L;
        long p90 = Math.max(0, copy[(int) (count * 0.9)] - base) / 1_000_000L;
        long worst = Math.max(0, copy[count - 1] - base) / 1_000_000L;
        return "recent delay above baseline: median " + median + " ms, 90% under " + p90 + " ms, worst " + worst + " ms (last " + count + " samples)";
    }

    private void recordClean(long rtt) {
        cleanRing[cleanIndex] = rtt;
        cleanIndex = (cleanIndex + 1) % cleanRing.length;
        long min = Long.MAX_VALUE;
        for (long value : cleanRing) min = Math.min(min, value);
        cleanBase = min == Long.MAX_VALUE ? 0 : min;
    }

    long cleanBase() {
        return cleanBase;
    }

    String stats() {
        return String.format("cuts=%d (severe %d) raises=%d lastCut=%s", congestionEvents, severeEvents, increaseEvents, lastCongestionReason);
    }

    private long recordRtt(long now, long rtt) {
        if (rttBucketStart == 0) {
            rttBucketStart = now;
            Arrays.fill(rttBuckets, Long.MAX_VALUE);
        }
        long elapsed = now - rttBucketStart;
        if (elapsed >= RTT_BUCKETS * RTT_BUCKET_NANOS) {
            Arrays.fill(rttBuckets, Long.MAX_VALUE);
            rttBucketStart = now;
        } else {
            while (now - rttBucketStart >= RTT_BUCKET_NANOS) {
                rttBucketIndex = (rttBucketIndex + 1) % RTT_BUCKETS;
                rttBuckets[rttBucketIndex] = Long.MAX_VALUE;
                rttBucketStart += RTT_BUCKET_NANOS;
            }
        }
        rttBuckets[rttBucketIndex] = Math.min(rttBuckets[rttBucketIndex], rtt);

        long base = Long.MAX_VALUE;
        for (long bucket : rttBuckets) {
            base = Math.min(base, bucket);
        }
        return base;
    }
}
