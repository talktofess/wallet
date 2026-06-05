package com.wallet.core.download;

/**
 * Decides whether a request seen while browsing is downloadable media, and of
 * what kind. This is what the in-app browser runs over every intercepted request
 * (URL + Content-Type) to surface a "download" action.
 */
public final class MediaSniffer {

    public enum Kind { NONE, PROGRESSIVE, HLS, DASH }

    private static final String[] PROGRESSIVE_EXTS = {
        ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".m4a", ".mp3", ".aac", ".ogg", ".wav", ".flac", ".ts"
    };

    private MediaSniffer() {}

    public static Kind classify(String url, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String path = pathOf(url).toLowerCase();

        // Manifests first — they advertise streamed media a naive download misses.
        if (ct.contains("mpegurl") || path.endsWith(".m3u8")) return Kind.HLS;
        if (ct.contains("dash+xml") || path.endsWith(".mpd")) return Kind.DASH;

        if (ct.startsWith("video/") || ct.startsWith("audio/")) return Kind.PROGRESSIVE;
        for (String ext : PROGRESSIVE_EXTS) {
            if (path.endsWith(ext)) return Kind.PROGRESSIVE;
        }
        return Kind.NONE;
    }

    public static boolean isMedia(String url, String contentType) {
        return classify(url, contentType) != Kind.NONE;
    }

    /** The path portion of a URL, without query or fragment. */
    static String pathOf(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int h = url.indexOf('#');
        if (h >= 0) url = url.substring(0, h);
        return url;
    }
}
