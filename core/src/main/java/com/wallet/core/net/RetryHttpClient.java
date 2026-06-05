package com.wallet.core.net;

import java.io.IOException;
import java.util.Map;

/**
 * Wraps another {@link HttpClient} with retry + exponential backoff. Flaky CDNs
 * and transient 5xx are the common reason a long download dies; this gives the
 * pipeline a few attempts before giving up. The {@link Sleeper} is injectable so
 * tests verify the retry behaviour without actually waiting.
 */
public final class RetryHttpClient implements HttpClient {

    public interface Sleeper {
        void sleep(long ms) throws IOException;
    }

    private final HttpClient delegate;
    private final int maxAttempts;
    private final long baseDelayMs;
    private final Sleeper sleeper;

    public RetryHttpClient(HttpClient delegate) {
        this(delegate, 3, 500, ms -> {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    public RetryHttpClient(HttpClient delegate, int maxAttempts, long baseDelayMs, Sleeper sleeper) {
        this.delegate = delegate;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = baseDelayMs;
        this.sleeper = sleeper;
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) sleeper.sleep(baseDelayMs * (1L << (attempt - 1)));
            try {
                Response r = delegate.get(url, headers);
                if (r.status() >= 500 && attempt < maxAttempts - 1) {
                    r.close();          // transient server error — back off and retry
                    continue;
                }
                return r;
            } catch (IOException e) {
                lastError = e;
                if (attempt == maxAttempts - 1) throw e;
            }
        }
        throw lastError != null ? lastError : new IOException("retries exhausted for " + url);
    }
}
