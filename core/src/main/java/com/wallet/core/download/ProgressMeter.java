package com.wallet.core.download;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Computes download speed and ETA from progress samples over a sliding time
 * window. Timestamps are passed in (not read from a clock) so it's deterministic
 * and unit-testable; the app feeds it {@code System.currentTimeMillis()}.
 */
public final class ProgressMeter {

    private final long windowMs;
    private final Deque<long[]> samples = new ArrayDeque<>();   // {timeMs, bytesDone}

    public ProgressMeter(long windowMs) {
        this.windowMs = windowMs;
    }

    public void sample(long timeMs, long bytesDone) {
        samples.addLast(new long[]{timeMs, bytesDone});
        while (samples.size() > 2 && timeMs - samples.peekFirst()[0] > windowMs) {
            samples.pollFirst();
        }
    }

    /** Bytes/second across the window, or 0 if not enough samples. */
    public double bytesPerSecond() {
        if (samples.size() < 2) return 0;
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long dt = last[0] - first[0];
        long db = last[1] - first[1];
        return dt > 0 ? db * 1000.0 / dt : 0;
    }

    /** Seconds remaining to reach {@code total}, or -1 if unknown. */
    public long etaSeconds(long total) {
        double bps = bytesPerSecond();
        if (bps <= 0 || total <= 0) return -1;
        long remaining = total - samples.peekLast()[1];
        if (remaining <= 0) return 0;
        return (long) Math.ceil(remaining / bps);
    }
}
