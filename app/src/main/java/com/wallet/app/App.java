package com.wallet.app;

import android.app.Application;

/** Holds the single {@link VaultStore} so its unlocked key survives across screens. */
public final class App extends Application {

    private VaultStore vault;

    @Override
    public void onCreate() {
        super.onCreate();
        vault = new VaultStore(this);
    }

    public VaultStore vault() {
        return vault;
    }
}
