package com.wallet.core.download;

/**
 * Turns "the user tapped download on this URL" into a concrete plan. The browser
 * already knows the {@link MediaSniffer.Kind}; this picks the best HLS variant
 * when the manifest is a master playlist.
 */
public final class DownloadPlanner {

    private DownloadPlanner() {}

    /** Highest-bandwidth (best quality) variant in a master playlist, or null. */
    public static M3u8.Variant bestVariant(M3u8 master) {
        M3u8.Variant best = null;
        for (M3u8.Variant v : master.variants) {
            if (best == null || v.bandwidth > best.bandwidth) best = v;
        }
        return best;
    }

    /** Lowest-bandwidth variant — useful for a "data saver" download. */
    public static M3u8.Variant smallestVariant(M3u8 master) {
        M3u8.Variant best = null;
        for (M3u8.Variant v : master.variants) {
            if (best == null || v.bandwidth < best.bandwidth) best = v;
        }
        return best;
    }
}
