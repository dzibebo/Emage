package net.ed1thy.emage.render;

final class SendBudget {

    private final double rate;
    private final double burst;
    private double tokens;
    private long last = System.nanoTime();

    SendBudget(double bytesPerSecond) {
        this.rate = Math.max(1_048_576.0, bytesPerSecond);
        this.burst = this.rate * 0.1;
        this.tokens = this.burst;
    }

    void refill(long now) {
        double dt = (now - last) / 1_000_000_000.0;
        last = now;
        if (dt < 0) dt = 0;
        if (dt > 0.25) dt = 0.25;
        tokens = Math.min(tokens + rate * dt, burst);
    }

    boolean available() {
        return tokens > 0;
    }

    void spend(double bytes) {
        tokens -= bytes;
    }

    double rate() {
        return rate;
    }
}
