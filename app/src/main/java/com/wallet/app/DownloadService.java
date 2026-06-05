package com.wallet.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import com.wallet.core.download.Cancellation;
import com.wallet.core.download.DownloadQueue;
import com.wallet.core.download.DownloadState;
import com.wallet.core.download.DownloadTask;
import com.wallet.core.download.ProgressMeter;
import com.wallet.core.util.ByteFormat;

/**
 * Foreground service that drains the shared {@link DownloadQueue} one task at a
 * time on a worker thread, showing a progress notification. It:
 * <ul>
 *   <li>runs each task through {@link DownloadJob} with a {@link Cancellation};</li>
 *   <li>listens to the queue, so a pause/cancel of the <em>running</em> task trips
 *       its cancellation and the download stops promptly;</li>
 *   <li>annotates progress with speed/ETA via {@link ProgressMeter};</li>
 *   <li>persists the (encrypted) queue at each task boundary so it survives a
 *       restart.</li>
 * </ul>
 */
public final class DownloadService extends Service implements DownloadQueue.Listener {

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 1001;

    private DownloadQueue queue;
    private VaultStore vault;
    private volatile boolean running;
    private volatile String currentId;
    private volatile Cancellation current;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        App app = (App) getApplication();
        queue = app.queue();
        vault = app.vault();
        queue.addListener(this);
        createChannel();
    }

    @Override public void onDestroy() {
        queue.removeListener(this);
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification("Downloading", "Starting…", -1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        ensureWorker();
        return START_NOT_STICKY;
    }

    /** When the running task leaves RUNNING (user paused/cancelled), stop its IO. */
    @Override public void onQueueChanged() {
        String id = currentId;
        Cancellation c = current;
        if (id != null && c != null) {
            DownloadTask t = queue.byId(id);
            if (t == null || t.state() != DownloadState.RUNNING) c.cancel();
        }
    }

    private synchronized void ensureWorker() {
        if (running) return;
        running = true;
        new Thread(this::drain, "download-worker").start();
    }

    private void drain() {
        try {
            DownloadTask task;
            while ((task = queue.nextQueued()) != null) {
                final String id = task.id;
                queue.start(id);
                current = new Cancellation();
                currentId = id;
                try {
                    runOne(id, task, current);
                } finally {
                    current = null;
                    currentId = null;
                    persist();
                }
            }
        } finally {
            running = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private void runOne(String id, DownloadTask task, Cancellation cancel) {
        final ProgressMeter meter = new ProgressMeter(5_000);
        new DownloadJob(vault, getCacheDir()).run(task.url, task.contentType, task.name, true, cancel,
                new DownloadJob.Listener() {
                    @Override public void onProgress(long done, long total, String label) {
                        queue.progress(id, done, total);
                        String note = label;
                        if (total > 4096) {                       // bytes (not segment counts)
                            meter.sample(SystemClock.elapsedRealtime(), done);
                            double bps = meter.bytesPerSecond();
                            if (bps > 0) {
                                long eta = meter.etaSeconds(total);
                                note = ByteFormat.rate(bps) + (eta >= 0 ? "  •  ETA " + eta + "s" : "");
                            }
                        }
                        queue.note(id, note);
                        DownloadTask t = queue.byId(id);
                        updateNotification(task.name, note, t != null ? t.percent() : -1);
                    }
                    @Override public void onComplete(String vaultName) {
                        if (isRunning(id)) queue.complete(id);
                    }
                    @Override public void onError(Exception error) {
                        if (isRunning(id)) queue.fail(id, String.valueOf(error.getMessage()));
                    }
                });
    }

    private boolean isRunning(String id) {
        DownloadTask t = queue.byId(id);
        return t != null && t.state() == DownloadState.RUNNING;
    }

    private void persist() {
        try {
            if (vault.isUnlocked()) vault.saveBlob("queue", queue.serialize());
        } catch (Exception ignored) { /* best-effort */ }
    }

    // --- notifications -------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNotification(String title, String text, int percent) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(title)
         .setContentText(text)
         .setSmallIcon(android.R.drawable.stat_sys_download)
         .setOngoing(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void updateNotification(String name, String text, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(name, text, percent));
    }
}
