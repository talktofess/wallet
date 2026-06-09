package com.wallet.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;

/**
 * Plays a saved video <b>inside the vault</b>. The caller ({@link VaultActivity})
 * decrypts the item to a private cache file and passes its path here; we hand that
 * local path to a {@link VideoView} so the plaintext is never exposed to another
 * app. The temp file is deleted when this screen goes away, so the cleartext copy
 * does not outlive the viewing.
 */
public final class PlayerActivity extends Activity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";

    private File temp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        if (path == null) { finish(); return; }
        temp = new File(path);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        VideoView video = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        video.setLayoutParams(lp);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoPath(path);
        video.setOnPreparedListener(mp -> video.start());
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Can't play this file", Toast.LENGTH_LONG).show();
            finish();
            return true;
        });

        root.addView(video);
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (temp != null) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();          // don't let the decrypted copy outlive playback
        }
    }
}
