package com.wallet.core.download;

import java.util.List;

/**
 * A resolved MPEG-DASH stream: the chosen video representation plus the ordered
 * list of URLs to download (the initialization segment first, then media
 * segments). Concatenating those yields a playable fragmented MP4.
 */
public final class Dash {

    public static final class Representation {
        public final String id;
        public final long bandwidth;
        public final int width;
        public final int height;
        public final String mimeType;

        public Representation(String id, long bandwidth, int width, int height, String mimeType) {
            this.id = id;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
        }
    }

    public final Representation video;
    public final List<String> segments;   // init segment first, then media segments

    public Dash(Representation video, List<String> segments) {
        this.video = video;
        this.segments = segments;
    }
}
