package com.wallet.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.wallet.core.download.DownloadQueue;
import com.wallet.core.util.ByteFormat;
import com.wallet.core.vault.VaultIndex;

/**
 * The home screen, reachable only once the vault is open. (Unlocking happens on
 * the {@link ChessActivity} disguise — there is no passphrase box here.) It lets
 * the user open the in-app browser to find and save videos, watch the videos they
 * have already saved, manage downloads, and lock back to the chess board.
 *
 * <p>Saved videos are played <b>inside</b> the app ({@link PlayerActivity}): the
 * file is decrypted to private cache and handed to a {@code VideoView}, never to
 * an external player that could cache or index it.
 */
public final class VaultActivity extends Activity {

    private VaultStore vault;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = ((App) getApplication()).vault();
        if (!vault.isUnlocked()) {       // came here locked — bounce to the front door
            startActivity(new Intent(this, ChessActivity.class));
            finish();
            return;
        }
        restoreQueue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!vault.isUnlocked()) {
            startActivity(new Intent(this, ChessActivity.class));
            finish();
            return;
        }
        showHome();                      // rebuild so a newly-saved item appears
    }

    private void showHome() {
        LinearLayout root = column();

        Button browse = new Button(this);
        browse.setText(R.string.open_browser);
        browse.setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class)));

        Button downloads = new Button(this);
        downloads.setText(R.string.downloads);
        downloads.setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));

        Button lock = new Button(this);
        lock.setText(R.string.lock_vault);
        lock.setOnClickListener(v -> {
            vault.lock();
            startActivity(new Intent(this, ChessActivity.class));
            finish();
        });

        TextView header = new TextView(this);
        header.setText(R.string.saved_items);
        header.setTextSize(20);
        header.setPadding(0, dp(12), 0, dp(4));

        final List<VaultIndex.Item> items = vault.items();

        root.addView(browse);
        root.addView(downloads);
        root.addView(lock);
        root.addView(header);

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.nothing_saved);
            empty.setPadding(0, dp(8), 0, 0);
            root.addView(empty);
            setContentView(root);
            return;
        }

        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        ArrayAdapter<VaultIndex.Item> adapter = new ArrayAdapter<VaultIndex.Item>(this, 0, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = convertView instanceof TextView
                        ? (TextView) convertView : new TextView(VaultActivity.this);
                int p = dp(12);
                tv.setPadding(p, p, p, p);
                VaultIndex.Item it = getItem(position);
                tv.setText(it.name + "\n" + ByteFormat.bytes(it.size)
                        + (it.mime != null && !it.mime.isEmpty() ? "  •  " + it.mime : ""));
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> play(items.get(pos)));

        root.addView(list);
        setContentView(root);
    }

    /** Decrypt a saved item to private cache off the UI thread, then play it in-app. */
    private void play(VaultIndex.Item item) {
        Toast.makeText(this, getString(R.string.preparing, item.name), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File out = new File(getCacheDir(), "play");
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                File temp = new File(out, "v-" + System.nanoTime() + suffix(item));
                try (OutputStream os = new FileOutputStream(temp)) {
                    vault.getTo(item.name, os);
                }
                main.post(() -> {
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_PATH, temp.getAbsolutePath());
                    i.putExtra(PlayerActivity.EXTRA_TITLE, item.name);
                    startActivity(i);
                });
            } catch (Exception e) {
                main.post(() -> toast("Couldn't open: " + e.getMessage()));
            }
        }, "decrypt-play").start();
    }

    private static String suffix(VaultIndex.Item item) {
        int dot = item.name.lastIndexOf('.');
        return dot > 0 ? item.name.substring(dot) : ".mp4";
    }

    /** Restore the encrypted download queue saved last session, and resume pending work. */
    private void restoreQueue() {
        try {
            byte[] blob = vault.loadBlobOrNull("queue");
            if (blob == null) return;
            DownloadQueue queue = ((App) getApplication()).queue();
            queue.loadSerialized(blob);
            if (queue.hasWork()) {
                Intent i = new Intent(this, DownloadService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                else startService(i);
            }
        } catch (Exception ignored) { /* no saved queue / cannot decrypt */ }
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        l.setPadding(p, p, p, p);
        l.setGravity(Gravity.TOP);
        l.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return l;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
