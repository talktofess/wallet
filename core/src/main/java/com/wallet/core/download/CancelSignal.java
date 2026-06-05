package com.wallet.core.download;

/**
 * Cooperative cancellation: the download engine polls {@link #cancelled()} in its
 * copy loops and between segments, and bails out with a {@link CancelledException}
 * when it flips true. Kept as an interface so tests can drive it with a lambda.
 */
public interface CancelSignal {

    boolean cancelled();

    /** A signal that is never cancelled. */
    CancelSignal NONE = () -> false;
}
