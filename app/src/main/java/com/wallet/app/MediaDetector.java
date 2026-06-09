package com.wallet.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wallet.core.download.MediaSniffer;

/**
 * Collects the downloadable media the browser sees while a page loads. Every
 * intercepted request URL (and any content-type we learn) is run through
 * {@link MediaSniffer}; hits are de-duplicated and kept in arrival order so the
 * UI can show "N downloads found on this page".
 */
public final class MediaDetector {

    public static final class Hit {
        public final String url;
        public final MediaSniffer.Kind kind;
        public final String contentType;

        Hit(String url, MediaSniffer.Kind kind, String contentType) {
            this.url = url;
            this.kind = kind;
            this.contentType = contentType;
        }
    }

    private final Map<String, Hit> hits = new LinkedHashMap<>();

    /** Offer a seen request; returns true if it was a new media hit. */
    public synchronized boolean offer(String url, String contentType) {
        MediaSniffer.Kind kind = MediaSniffer.classify(url, contentType);
        if (kind == MediaSniffer.Kind.NONE) return false;
        if (hits.containsKey(url)) return false;
        hits.put(url, new Hit(url, kind, contentType));
        return true;
    }

    /**
     * Offer the source of a {@code <video>} the page is actually playing (found
     * by the in-page JS bridge). Unlike {@link #offer}, this trusts that it IS a
     * video even when the URL has no media extension and no Content-Type — exactly
     * the private/tokenized case (e.g. {@code /stream/123?token=…}) that a URL
     * sniff alone would miss. Returns true if it was a new hit.
     */
    public synchronized boolean offerVideo(String url) {
        if (url == null || !url.startsWith("http")) return false;   // skip blob:/data:/about:
        if (hits.containsKey(url)) return false;
        MediaSniffer.Kind kind = MediaSniffer.classify(url, null);
        if (kind == MediaSniffer.Kind.NONE) kind = MediaSniffer.Kind.PROGRESSIVE;
        hits.put(url, new Hit(url, kind, null));
        return true;
    }

    public synchronized List<Hit> all() {
        return new ArrayList<>(hits.values());
    }

    /** The most recently seen hit — the best guess at "the video I'm watching". */
    public synchronized Hit latest() {
        Hit last = null;
        for (Hit h : hits.values()) last = h;
        return last;
    }

    public synchronized int count() {
        return hits.size();
    }

    public synchronized void clear() {
        hits.clear();
    }
}
