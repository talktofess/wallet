package com.wallet.core.download;

/**
 * One item in the {@link DownloadQueue}. Identity and request details are final;
 * the state and progress fields are mutated only through the queue (which holds
 * the lock), and are {@code volatile} so the UI thread sees fresh values.
 */
public final class DownloadTask {

    public final String id;
    public final String url;
    public final String name;
    public final String contentType;   // may be "" if unknown
    public final long createdAt;

    private volatile DownloadState state = DownloadState.QUEUED;
    private volatile long bytesDone = 0;
    private volatile long bytesTotal = -1;     // -1 = unknown
    private volatile String message = "";

    DownloadTask(String id, String url, String name, String contentType, long createdAt) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.contentType = contentType == null ? "" : contentType;
        this.createdAt = createdAt;
    }

    public DownloadState state() { return state; }
    public long bytesDone() { return bytesDone; }
    public long bytesTotal() { return bytesTotal; }
    public String message() { return message; }

    /** Completion in [0,1], or -1 when the total size is unknown. */
    public double fraction() {
        return bytesTotal > 0 ? (double) bytesDone / bytesTotal : -1;
    }

    public int percent() {
        double f = fraction();
        return f < 0 ? -1 : (int) Math.round(f * 100);
    }

    // package-private mutators — only DownloadQueue calls these
    void setState(DownloadState s) { this.state = s; }
    void setProgress(long done, long total) { this.bytesDone = done; this.bytesTotal = total; }
    void setMessage(String m) { this.message = m == null ? "" : m; }
}
