package net.ed1thy.emage.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RateControllerTest {

    private record Result(double p95DelayMs, double maxDelayMs, double throughputRatio, double secondsToHalfLink) {}

    private interface ClientLag {
        double millis(double t, java.util.Random random);
    }

    private static final ClientLag NO_LAG = (t, r) -> 0.0;

    private static final ClientLag BUSY_CLIENT = (t, r) -> {
        double lag = r.nextDouble() < 0.9 ? r.nextDouble() * 40 : 60 + r.nextDouble() * 140;
        if (t >= 3 && t < 12) lag += 30 + r.nextDouble() * 70;
        return lag;
    };

    private static Result simulate(double linkBytesPerSecond, double baseRttMs, double maxRate, int seconds) {
        return simulate(linkBytesPerSecond, baseRttMs, maxRate, seconds, NO_LAG);
    }

    private static Result simulate(double linkBytesPerSecond, double baseRttMs, double maxRate, int seconds, ClientLag lag) {
        java.util.Random random = new java.util.Random(11);
        PlayerSendQueue.Tuning tuning = new PlayerSendQueue.Tuning(16_384, (int) maxRate, 98_304, 20, true);
        RateController controller = new RateController(16_384, maxRate, PlayerSendQueue.initialRate(tuning), 80_000_000L);

        double dt = 0.005;
        double sendStart = 1.0;
        double rampEnd = sendStart + 6.0;
        double backlog = 0;
        double totalSent = 0;
        double deliveredAfterRamp = 0;
        double nextProbeAt = 0;
        double probeReturnAt = -1;
        double probeRttMs = 0;
        double probeSentAt = 0;
        double probeSentBytes = 0;
        double secondsToHalf = -1;
        double maxDelay = 0;
        List<Double> delays = new ArrayList<>();

        double t = 0;
        while (t < seconds) {
            long now = 1_000_000_000L + (long) (t * 1_000_000_000.0);
            if (t >= sendStart) {
                double sent = controller.rate() * dt;
                backlog += sent;
                totalSent += sent;
            }
            double drained = Math.min(backlog, linkBytesPerSecond * dt);
            backlog -= drained;
            if (t >= rampEnd) deliveredAfterRamp += drained;

            double delayMs = backlog / linkBytesPerSecond * 1000.0;
            if (t >= rampEnd) {
                delays.add(delayMs);
                maxDelay = Math.max(maxDelay, delayMs);
            }
            if (t >= sendStart && secondsToHalf < 0 && controller.rate() >= Math.min(linkBytesPerSecond, maxRate) * 0.5) {
                secondsToHalf = t - sendStart;
            }

            if (probeReturnAt >= 0) {
                controller.onProbeInFlight(now, (long) ((t - probeSentAt) * 1_000_000_000.0));
            }
            if (probeReturnAt < 0 && t >= nextProbeAt) {
                probeRttMs = baseRttMs + delayMs + lag.millis(t, random);
                probeSentAt = t;
                probeSentBytes = totalSent;
                probeReturnAt = t + probeRttMs / 1000.0;
                nextProbeAt = t + (t >= sendStart ? 0.1 : 1.0);
            }
            if (probeReturnAt >= 0 && t >= probeReturnAt) {
                controller.onSample(now, (long) (probeRttMs * 1_000_000.0), t >= sendStart, (long) probeSentBytes);
                probeReturnAt = -1;
            }

            t += dt;
        }

        Collections.sort(delays);
        double p95 = delays.get((int) (delays.size() * 0.95));
        double ideal = Math.min(linkBytesPerSecond, maxRate);
        double throughput = deliveredAfterRamp / (seconds - rampEnd) / ideal;
        return new Result(p95, maxDelay, throughput, secondsToHalf);
    }

    @Test
    void aBusyClientWhosePingAnswersAreLateIsNotMistakenForANetworkJam() {
        double[] links = {500_000, 2_000_000, 12_500_000};
        for (double link : links) {
            Result r = simulate(link, 20, 16_777_216, 40, BUSY_CLIENT);
            System.out.printf("busy client: link=%9.0f B/s -> throughput %.2f, p95 delay %.0f ms%n", link, r.throughputRatio(), r.p95DelayMs());
            assertTrue(r.throughputRatio() > 0.5, "client-side lag must not throttle a healthy link: link " + link + " got " + r.throughputRatio());
        }
    }

    @Test
    void delayStaysLowAndLinkIsUsedAcrossVeryDifferentConnections() {
        double[] links = {40_000, 120_000, 500_000, 2_000_000, 12_500_000, 125_000_000};
        double[] rtts = {15, 60, 250};
        for (double link : links) {
            for (double rtt : rtts) {
                Result r = simulate(link, rtt, 16_777_216, 40);
                System.out.printf("link=%9.0f B/s rtt=%3.0f ms -> p95 delay %6.1f ms, max delay %6.1f ms, throughput %.2f, half-link after %.2f s%n",
                        link, rtt, r.p95DelayMs(), r.maxDelayMs(), r.throughputRatio(), r.secondsToHalfLink());
                assertTrue(r.p95DelayMs() < (link < 100_000 ? 800 : 400), "p95 queue delay too high for link " + link + " rtt " + rtt + ": " + r.p95DelayMs());
                assertTrue(r.throughputRatio() > 0.5, "link badly underused for " + link + " rtt " + rtt + ": " + r.throughputRatio());
            }
        }
    }

    @Test
    void fastConnectionsReachFullSpeedWithinAFewSeconds() {
        Result r = simulate(50_000_000, 15, 16_777_216, 20);
        System.out.printf("fast link: half speed after %.2f s%n", r.secondsToHalfLink());
        assertTrue(r.secondsToHalfLink() >= 0 && r.secondsToHalfLink() < 4.0, "ramp too slow: " + r.secondsToHalfLink());
    }

    @Test
    void aStandingQueueThatLastsForMinutesNeverBecomesTheBaseline() {
        RateController controller = new RateController(16_384, 16_777_216, 3_000_000, 20_000_000L);
        long now = 1_000_000_000L;
        for (int i = 0; i < 20; i++) {
            controller.onSample(now, 100_000_000L, false, 0, true);
            now += 200_000_000L;
        }
        assertTrue(controller.lastBaseRtt() <= 100_000_000L);

        double lowestRate = controller.rate();
        for (int i = 0; i < 400; i++) {
            controller.onSample(now, 1_900_000_000L, true, 0, false);
            lowestRate = Math.min(lowestRate, controller.rate());
            now += 250_000_000L;
        }

        assertTrue(controller.lastBaseRtt() <= 110_000_000L,
                "the baseline must not absorb a standing queue: " + controller.lastBaseRtt() / 1_000_000L + " ms");
        assertTrue(controller.rate() <= 300_000, "the rate must stay at the floor while the queue stands: " + controller.rate());
    }
}
