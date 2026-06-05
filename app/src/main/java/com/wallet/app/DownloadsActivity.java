package com.wallet.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import com.wallet.core.download.DownloadQueue;
import com.wallet.core.download.DownloadState;
import com.wallet.core.download.DownloadTask;

/**
 * Shows the download queue with per-item state + progress, and lets the user
 * pause / resume / cancel / retry. It observes the shared {@link DownloadQueue}
 * and refreshes on the UI thread whenever the queue changes.
 */
public final class DownloadsActivity extends Activity implements DownloadQueue.Listener {

    private DownloadQueue queue;
    private ArrayAdapter<DownloadTask> adapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        queue = ((App) getApplication()).queue();

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<DownloadTask>(this, 0) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = convertView instanceof TextView
                        ? (TextView) convertView : new TextView(DownloadsActivity.this);
                int p = (int) (12 * getResources().getDisplayMetrics().density);
                tv.setPadding(p, p, p, p);
                tv.setText(describe(getItem(position)));
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, idd) -> showActions(adapter.getItem(pos)));
        setContentView(list);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        queue.addListener(this);
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        queue.removeListener(this);
    }

    @Override public void onQueueChanged() {
        main.post(this::refresh);
    }

    private void refresh() {
        adapter.clear();
        adapter.addAll(queue.tasks());
        adapter.notifyDataSetChanged();
    }

    private static String describe(DownloadTask t) {
        String pct = t.percent() >= 0 ? t.percent() + "%" : "…";
        String tail = t.message().isEmpty() ? "" : "  •  " + t.message();
        return t.name + "\n" + t.state() + "  •  " + pct + tail;
    }

    private void showActions(DownloadTask t) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        switch (t.state()) {
            case QUEUED:
            case RUNNING:
                labels.add("Pause");  actions.add(() -> queue.pause(t.id));
                labels.add("Cancel"); actions.add(() -> queue.cancel(t.id));
                break;
            case PAUSED:
                labels.add("Resume"); actions.add(() -> { queue.resume(t.id); kickService(); });
                labels.add("Cancel"); actions.add(() -> queue.cancel(t.id));
                break;
            case FAILED:
            case CANCELED:
                labels.add("Retry");  actions.add(() -> { queue.retry(t.id); kickService(); });
                labels.add("Remove"); actions.add(() -> queue.remove(t.id));
                break;
            case COMPLETED:
                labels.add("Remove from list"); actions.add(() -> queue.remove(t.id));
                break;
        }
        new AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void kickService() {
        Intent i = new Intent(this, DownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }
}
