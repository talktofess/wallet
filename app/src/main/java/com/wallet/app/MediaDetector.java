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

    public synchronized List<Hit> all() {
        return new ArrayList<>(hits.values());
    }

    public synchronized int count() {
        return hits.size();
    }

    public synchronized void clear() {
        hits.clear();
    }
}
