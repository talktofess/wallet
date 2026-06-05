package com.wallet.core.util;

import java.util.Set;

/** Picks a non-colliding filename: {@code clip.mp4} -> {@code clip (1).mp4} -> … */
public final class UniqueNames {

    private UniqueNames() {}

    public static String make(String desired, Set<String> taken) {
        if (!taken.contains(desired)) return desired;

        String base = desired;
        String ext = "";
        int dot = desired.lastIndexOf('.');
        if (dot > 0) {
            base = desired.substring(0, dot);
            ext = desired.substring(dot);
        }
        for (int i = 1; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!taken.contains(candidate)) return candidate;
        }
    }
}
