package com.wallet.core.download;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * An ordered, thread-safe queue of {@link DownloadTask}s with an explicit state
 * machine. The pure logic — valid transitions, picking the next runnable task,
 * progress bookkeeping, persistence — lives here and is unit-tested; the Android
 * service drives it (a worker calls {@link #nextQueued()} / {@link #start} /
 * {@link #progress} / {@link #complete}) and the UI observes it via a listener.
 *
 * <p>Transitions are validated; an illegal one (e.g. completing a queued task)
 * throws {@link IllegalStateException} rather than silently corrupting state.
 */
public final class DownloadQueue {

    public interface Listener {
        void onQueueChanged();
    }

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private long seq = 0;

    public synchronized void addListener(Listener l) { listeners.add(l); }

    public synchronized void removeListener(Listener l) { listeners.remove(l); }

    public synchronized DownloadTask add(String url, String name, String contentType, long now) {
        DownloadTask t = new DownloadTask(now + "-" + (++seq), url, name, contentType, now);
        tasks.add(t);
        notifyChanged();
        return t;
    }

    public synchronized List<DownloadTask> tasks() {
        return new ArrayList<>(tasks);
    }

    public synchronized DownloadTask byId(String id) {
        for (DownloadTask t : tasks) if (t.id.equals(id)) return t;
        return null;
    }

    /** The first task waiting to run, or null. */
    public synchronized DownloadTask nextQueued() {
        for (DownloadTask t : tasks) if (t.state() == DownloadState.QUEUED) return t;
        return null;
    }

    public synchronized boolean hasWork() {
        return nextQueued() != null;
    }

    public synchronized int countByState(DownloadState state) {
        int n = 0;
        for (DownloadTask t : tasks) if (t.state() == state) n++;
        return n;
    }

    // --- transitions ---------------------------------------------------------

    public synchronized void start(String id) {
        DownloadTask t = require(id, DownloadState.QUEUED, DownloadState.PAUSED);
        t.setState(DownloadState.RUNNING);
        t.setMessage("");
        notifyChanged();
    }

    public synchronized void progress(String id, long done, long total) {
        DownloadTask t = byId(id);
        if (t == null || t.state() != DownloadState.RUNNING) return;   // ignore stale callbacks
        t.setProgress(done, total);
        notifyChanged();
    }

    public synchronized void complete(String id) {
        DownloadTask t = require(id, DownloadState.RUNNING);
        if (t.bytesTotal() > 0) t.setProgress(t.bytesTotal(), t.bytesTotal());
        t.setState(DownloadState.COMPLETED);
        notifyChanged();
    }

    public synchronized void fail(String id, String message) {
        DownloadTask t = require(id, DownloadState.RUNNING, DownloadState.QUEUED);
        t.setMessage(message);
        t.setState(DownloadState.FAILED);
        notifyChanged();
    }

    public synchronized void pause(String id) {
        DownloadTask t = require(id, DownloadState.QUEUED, DownloadState.RUNNING);
        t.setState(DownloadState.PAUSED);
        notifyChanged();
    }

    public synchronized void resume(String id) {
        DownloadTask t = require(id, DownloadState.PAUSED);
        t.setState(DownloadState.QUEUED);
        notifyChanged();
    }

    public synchronized void cancel(String id) {
        DownloadTask t = require(id, DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED);
        t.setState(DownloadState.CANCELED);
        notifyChanged();
    }

    public synchronized void retry(String id) {
        DownloadTask t = require(id, DownloadState.FAILED, DownloadState.CANCELED);
        t.setProgress(0, -1);
        t.setMessage("");
        t.setState(DownloadState.QUEUED);
        notifyChanged();
    }

    public synchronized boolean remove(String id) {
        boolean removed = tasks.removeIf(t -> t.id.equals(id));
        if (removed) notifyChanged();
        return removed;
    }

    /** Attach a status line (e.g. "1.2 MB/s • ETA 8s") without changing state. */
    public synchronized void note(String id, String message) {
        DownloadTask t = byId(id);
        if (t == null || t.state().isTerminal()) return;
        t.setMessage(message);
        notifyChanged();
    }

    /** Replace the queue contents from a serialized blob (used to restore on unlock). */
    public synchronized void loadSerialized(byte[] data) {
        tasks.clear();
        tasks.addAll(parse(data).tasks);
        notifyChanged();
    }

    // --- persistence ---------------------------------------------------------

    public synchronized byte[] serialize() {
        StringBuilder sb = new StringBuilder();
        for (DownloadTask t : tasks) {
            sb.append(b64(t.id)).append('\t')
              .append(b64(t.url)).append('\t')
              .append(b64(t.name)).append('\t')
              .append(b64(t.contentType)).append('\t')
              .append(t.state().name()).append('\t')
              .append(t.bytesDone()).append('\t')
              .append(t.bytesTotal()).append('\t')
              .append(b64(t.message())).append('\t')
              .append(t.createdAt).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static DownloadQueue parse(byte[] data) {
        DownloadQueue q = new DownloadQueue();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 9) continue;
            DownloadTask t = new DownloadTask(unb64(f[0]), unb64(f[1]), unb64(f[2]), unb64(f[3]),
                    Long.parseLong(f[8]));
            DownloadState state = DownloadState.valueOf(f[4]);
            // a download interrupted mid-flight (RUNNING at save) resumes as QUEUED
            t.setState(state == DownloadState.RUNNING ? DownloadState.QUEUED : state);
            t.setProgress(Long.parseLong(f[5]), Long.parseLong(f[6]));
            t.setMessage(unb64(f[7]));
            q.tasks.add(t);
        }
        return q;
    }

    // --- internals -----------------------------------------------------------

    private DownloadTask require(String id, DownloadState... allowed) {
        DownloadTask t = byId(id);
        if (t == null) throw new IllegalStateException("no such task: " + id);
        for (DownloadState a : allowed) if (t.state() == a) return t;
        throw new IllegalStateException("illegal transition from " + t.state() + " for task " + id);
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onQueueChanged();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
