package com.wallet.core.download;

/** A flip-once cancellation token (thread-safe), handed to a running download. */
public final class Cancellation implements CancelSignal {

    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    @Override
    public boolean cancelled() { return cancelled; }
}
