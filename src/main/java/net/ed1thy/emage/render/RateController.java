package net.ed1thy.emage.render;

import java.util.Arrays;

final class RateController {

    private static final int RTT_BUCKETS = 10;
    private static final long RTT_BUCKET_NANOS = 1_000_000_000L;
    private static final long MIN_DELIVERY_WINDOW_NANOS = 40_000_000L;

    private final double minRate;
    private final double maxRate;
    private final long targetDelayNanos;

    private volatile double rate;
    private boolean slowStart = true;
    private long lastDecreaseNanos;
    private volatile long lastRtt;
    private volatile long lastBaseRtt;

    private boolean hasPreviousSample;
    private long previousReturnNanos;
    private long previousSentBytes;
    private double deliveryRate;

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

    void onSample(long now, long rtt, boolean rateLimited, long sentBytesAtProbe) {
        lastRtt = rtt;
        long base = recordRtt(now, rtt);
        lastBaseRtt = base;
        long delay = Math.max(0L, rtt - base);

        if (hasPreviousSample && now - previousReturnNanos >= MIN_DELIVERY_WINDOW_NANOS) {
            long bytes = sentBytesAtProbe - previousSentBytes;
            if (bytes > 0) {
                deliveryRate = bytes / ((now - previousReturnNanos) / 1_000_000_000.0);
            }
        }
        hasPreviousSample = true;
        previousReturnNanos = now;
        previousSentBytes = sentBytesAtProbe;

        if (delay > targetDelayNanos * 2) {
            congestion(now, true, rateLimited);
        } else if (delay > targetDelayNanos) {
            congestion(now, false, rateLimited);
        } else if (delay < targetDelayNanos / 2 && rateLimited) {
            increase();
        }
    }

    void onProbeInFlight(long now, long ageNanos) {
        if (lastBaseRtt > 0 && ageNanos - lastBaseRtt > targetDelayNanos * 2) {
            congestion(now, true, true);
        }
    }

    private void congestion(long now, boolean severe, boolean deliveryRateValid) {
        long cooldown = Math.max(250_000_000L, lastBaseRtt);
        if (now - lastDecreaseNanos < cooldown) return;
        lastDecreaseNanos = now;

        double target = rate * (severe ? 0.5 : 0.8);
        if (deliveryRateValid && deliveryRate > 0) {
            target = Math.min(target, deliveryRate * (severe ? 0.7 : 0.85));
        }
        target = Math.max(target, rate * 0.3);
        rate = Math.max(minRate, target);
        slowStart = false;
    }

    void decrease(long now, double factor) {
        long cooldown = Math.max(250_000_000L, lastBaseRtt);
        if (now - lastDecreaseNanos < cooldown) return;
        lastDecreaseNanos = now;
        rate = Math.max(minRate, rate * factor);
        slowStart = false;
    }

    void increase() {
        rate = slowStart
                ? Math.min(maxRate, rate * 1.5)
                : Math.min(maxRate, rate * 1.08 + 8192);
    }

    void cut(double factor) {
        rate = Math.max(minRate, rate * factor);
        slowStart = false;
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
