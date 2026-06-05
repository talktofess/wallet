package com.wallet.core.download;

import java.nio.charset.StandardCharsets;

/**
 * Works out the filename to save a download under. Prefers the server's
 * {@code Content-Disposition} header — including the RFC 5987 {@code filename*}
 * form ({@code UTF-8''my%20file.mp4}) — and falls back to the URL's last path
 * segment. Always sanitised so a header can't write outside the target folder.
 */
public final class ContentDisposition {

    private ContentDisposition() {}

    /** Filename from a Content-Disposition header value, or null if none present. */
    public static String filename(String header) {
        if (header == null) return null;

        String extended = paramValue(header, "filename*");
        if (extended != null) {
            // charset'lang'percent-encoded-name  -> drop the charset'lang' prefix
            int firstQuote = extended.indexOf('\'');
            if (firstQuote >= 0) {
                int secondQuote = extended.indexOf('\'', firstQuote + 1);
                if (secondQuote >= 0) extended = extended.substring(secondQuote + 1);
            }
            return sanitize(percentDecode(extended));
        }

        String plain = paramValue(header, "filename");
        if (plain != null) return sanitize(plain);
        return null;
    }

    /** Best-effort filename for a download: header if present, else from the URL. */
    public static String resolve(String contentDispositionHeader, String url) {
        String fromHeader = filename(contentDispositionHeader);
        if (fromHeader != null && !fromHeader.isEmpty()) return fromHeader;
        return fromUrl(url);
    }

    public static String fromUrl(String url) {
        String path = MediaSniffer.pathOf(url);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = sanitize(percentDecode(name));
        return name.isEmpty() ? "download" : name;
    }

    // --- helpers -------------------------------------------------------------

    /** Find {@code name=value} or {@code name="value"} in a header, case-insensitive name. */
    static String paramValue(String header, String name) {
        String lower = header.toLowerCase();
        String target = name.toLowerCase();
        int from = 0;
        while (true) {
            int i = lower.indexOf(target, from);
            if (i < 0) return null;
            int after = i + target.length();
            // require the match to be a whole parameter name, then '='
            boolean boundaryBefore = i == 0 || !isTokenChar(lower.charAt(i - 1));
            int eq = after;
            while (eq < header.length() && header.charAt(eq) == ' ') eq++;
            if (boundaryBefore && eq < header.length() && header.charAt(eq) == '=') {
                int v = eq + 1;
                while (v < header.length() && header.charAt(v) == ' ') v++;
                if (v < header.length() && header.charAt(v) == '"') {
                    int end = header.indexOf('"', v + 1);
                    if (end < 0) end = header.length();
                    return header.substring(v + 1, end);
                }
                int end = v;
                while (end < header.length() && header.charAt(end) != ';') end++;
                return header.substring(v, end).trim();
            }
            from = after;
        }
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '*' || c == '-' || c == '_';
    }

    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            bytes.write(c);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Strip directory separators and control chars so the name stays a leaf. */
    static String sanitize(String name) {
        if (name == null) return "";
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || "<>:\"|?*".indexOf(c) >= 0) c = '_';
            b.append(c);
        }
        String out = b.toString().trim();
        if (out.equals(".") || out.equals("..")) out = "download";
        return out;
    }
}
