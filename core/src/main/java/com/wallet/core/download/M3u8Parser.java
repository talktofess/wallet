package com.wallet.core.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an HLS {@code .m3u8} playlist into a {@link M3u8}. Handles master vs
 * media playlists, {@code EXT-X-STREAM-INF} variants, {@code EXTINF} segments,
 * {@code EXT-X-KEY} encryption, {@code EXT-X-BYTERANGE}, and resolves relative
 * segment/variant URIs against the playlist's own URL.
 */
public final class M3u8Parser {

    private M3u8Parser() {}

    public static M3u8 parse(String text, String baseUrl) {
        List<M3u8.Variant> variants = new ArrayList<>();
        List<M3u8.Segment> segments = new ArrayList<>();
        double targetDuration = 0;
        long mediaSequence = 0;
        boolean isMaster = false;

        double pendingDuration = 0;
        long pendingLen = -1, pendingOff = -1;
        long nextByteOffset = 0;
        String pendingVariant = null;
        M3u8.Key currentKey = null;

        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                isMaster = true;
                pendingVariant = line.substring("#EXT-X-STREAM-INF:".length());
            } else if (line.startsWith("#EXTINF:")) {
                String v = line.substring("#EXTINF:".length());
                int comma = v.indexOf(',');
                pendingDuration = parseDouble(comma >= 0 ? v.substring(0, comma) : v, 0);
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = parseDouble(after(line), 0);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = (long) parseDouble(after(line), 0);
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = parseAttributes(line.substring("#EXT-X-KEY:".length()));
                String method = a.getOrDefault("METHOD", "NONE");
                currentKey = "NONE".equalsIgnoreCase(method)
                        ? null
                        : new M3u8.Key(method, resolve(baseUrl, a.get("URI")), a.get("IV"));
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String br = line.substring("#EXT-X-BYTERANGE:".length());
                int at = br.indexOf('@');
                pendingLen = (long) parseDouble(at >= 0 ? br.substring(0, at) : br, -1);
                pendingOff = at >= 0 ? (long) parseDouble(br.substring(at + 1), -1) : -1;
            } else if (line.startsWith("#")) {
                // other tag — ignored
            } else if (pendingVariant != null) {                // variant URI line
                Map<String, String> a = parseAttributes(pendingVariant);
                long bw = (long) parseDouble(a.getOrDefault("BANDWIDTH", "0"), 0);
                variants.add(new M3u8.Variant(resolve(baseUrl, line), bw, a.get("RESOLUTION")));
                pendingVariant = null;
            } else {                                            // segment URI line
                long off = pendingOff;
                if (pendingLen >= 0 && off < 0) off = nextByteOffset;
                if (pendingLen >= 0) nextByteOffset = off + pendingLen;
                segments.add(new M3u8.Segment(resolve(baseUrl, line), pendingDuration,
                        currentKey, pendingLen, off));
                pendingDuration = 0;
                pendingLen = -1;
                pendingOff = -1;
            }
        }

        M3u8.Type type = isMaster ? M3u8.Type.MASTER : M3u8.Type.MEDIA;
        return new M3u8(type, variants, segments, targetDuration, mediaSequence);
    }

    /** Resolve a possibly-relative URI against the playlist URL. */
    static String resolve(String base, String ref) {
        if (ref == null) return null;
        if (base == null) return ref;
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (RuntimeException e) {
            return ref;
        }
    }

    /** Parse {@code KEY=VALUE,KEY="quoted,value"} attribute lists. */
    static Map<String, String> parseAttributes(String s) {
        Map<String, String> m = new HashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            int eq = s.indexOf('=', i);
            if (eq < 0) break;
            String key = s.substring(i, eq).trim();
            int v = eq + 1;
            String val;
            if (v < n && s.charAt(v) == '"') {
                int end = s.indexOf('"', v + 1);
                if (end < 0) end = n;
                val = s.substring(v + 1, end);
                int comma = s.indexOf(',', end);
                i = comma < 0 ? n : comma + 1;
            } else {
                int comma = s.indexOf(',', v);
                int end = comma < 0 ? n : comma;
                val = s.substring(v, end).trim();
                i = comma < 0 ? n : comma + 1;
            }
            if (!key.isEmpty()) m.put(key, val);
        }
        return m;
    }

    private static String after(String line) {
        return line.substring(line.indexOf(':') + 1);
    }

    private static double parseDouble(String s, double dflt) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
