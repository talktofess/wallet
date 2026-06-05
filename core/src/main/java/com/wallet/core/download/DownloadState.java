package com.wallet.core.download;

/** Lifecycle of a queued download. */
public enum DownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == FAILED;
    }
}
