package com.wallet.app;

import android.app.Application;

import com.wallet.core.download.DownloadQueue;

/**
 * Holds the process-wide singletons: the {@link VaultStore} (so its unlocked key
 * survives across screens) and the {@link DownloadQueue} (shared between the
 * browser, the download service worker, and the downloads screen).
 */
public final class App extends Application {

    private VaultStore vault;
    private final DownloadQueue queue = new DownloadQueue();

    @Override
    public void onCreate() {
        super.onCreate();
        vault = new VaultStore(this);
    }

    public VaultStore vault() {
        return vault;
    }

    public DownloadQueue queue() {
        return queue;
    }
}
