package com.wallet.core.download;

import java.io.IOException;

/** Thrown by the download engine when its {@link CancelSignal} is tripped. */
public final class CancelledException extends IOException {
    public CancelledException() {
        super("download cancelled");
    }
}
