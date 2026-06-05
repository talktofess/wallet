package com.wallet.core.download;

import java.util.List;

/**
 * A parsed HLS playlist — either a MASTER playlist (a menu of quality variants)
 * or a MEDIA playlist (the actual ordered list of segments). This is the model a
 * naive "download the URL" pipeline is missing: the video lives in the segments,
 * not at the manifest URL.
 */
public final class M3u8 {

    public enum Type { MASTER, MEDIA }

    /** One quality option in a master playlist. */
    public static final class Variant {
        public final String uri;
        public final long bandwidth;       // bits/sec, for picking quality
        public final String resolution;    // e.g. "1920x1080", may be null

        public Variant(String uri, long bandwidth, String resolution) {
            this.uri = uri;
            this.bandwidth = bandwidth;
            this.resolution = resolution;
        }
    }

    /** Segment encryption from EXT-X-KEY (standard HLS, not DRM). */
    public static final class Key {
        public final String method;   // e.g. "AES-128", or "NONE"
        public final String uri;      // where the key is served
        public final String iv;       // optional explicit IV, hex ("0x...")

        public Key(String method, String uri, String iv) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
        }
    }

    /** One media segment (a .ts / fragment), with optional byte-range and key. */
    public static final class Segment {
        public final String uri;
        public final double duration;
        public final Key key;          // null if unencrypted
        public final long byteLength;  // -1 if the whole resource
        public final long byteOffset;  // -1 if not byte-ranged

        public Segment(String uri, double duration, Key key, long byteLength, long byteOffset) {
            this.uri = uri;
            this.duration = duration;
            this.key = key;
            this.byteLength = byteLength;
            this.byteOffset = byteOffset;
        }
    }

    public final Type type;
    public final List<Variant> variants;   // populated for MASTER
    public final List<Segment> segments;    // populated for MEDIA
    public final double targetDuration;
    public final long mediaSequence;

    public M3u8(Type type, List<Variant> variants, List<Segment> segments,
                double targetDuration, long mediaSequence) {
        this.type = type;
        this.variants = variants;
        this.segments = segments;
        this.targetDuration = targetDuration;
        this.mediaSequence = mediaSequence;
    }

    public boolean isMaster() { return type == Type.MASTER; }
}
