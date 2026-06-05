package com.wallet.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.wallet.core.download.DownloadState;
import com.wallet.core.download.DownloadQueue;
import com.wallet.core.download.DownloadTask;

/**
 * Foreground service that drains the shared {@link DownloadQueue} on a worker
 * thread, one task at a time, and shows a progress notification. It runs each
 * task through {@link DownloadJob} and reflects the result back into the queue
 * (progress / complete / fail), so the UI and the notification stay in sync.
 *
 * <p>Note: an in-flight task can't be interrupted mid-stream — pause/cancel on a
 * RUNNING task takes effect at the next task boundary (its result is discarded).
 * True mid-stream cancellation would need cancellable IO.
 */
public final class DownloadService extends Service {

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 1001;

    private DownloadQueue queue;
    private VaultStore vault;
    private volatile boolean running;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        App app = (App) getApplication();
        queue = app.queue();
        vault = app.vault();
        createChannel();
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
                runOne(id, task);
            }
        } finally {
            running = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private void runOne(String id, DownloadTask task) {
        new DownloadJob(vault, getCacheDir()).run(task.url, task.contentType, task.name, true,
                new DownloadJob.Listener() {
                    @Override public void onProgress(long done, long total, String label) {
                        queue.progress(id, done, total);
                        DownloadTask t = queue.byId(id);
                        updateNotification(task.name, label, t != null ? t.percent() : -1);
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
        else b.setProgress(0, 0, true);            // indeterminate
        return b.build();
    }

    private void updateNotification(String name, String label, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(name, label, percent));
    }
}
