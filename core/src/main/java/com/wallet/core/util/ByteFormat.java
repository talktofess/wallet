package com.wallet.core.util;

import java.util.Locale;

/** Human-readable byte sizes and transfer rates (binary units). */
public final class ByteFormat {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB", "PB"};

    private ByteFormat() {}

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        int i = -1;
        while (v >= 1024 && i < UNITS.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", v, UNITS[i]);
    }

    public static String rate(double bytesPerSecond) {
        return bytes((long) bytesPerSecond) + "/s";
    }
}
