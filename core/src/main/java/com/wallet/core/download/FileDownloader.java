package com.wallet.core.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import com.wallet.core.net.HttpClient;

/**
 * Progressive (single-file) download with resume. To continue an interrupted
 * download it sends {@code Range: bytes=N-} plus {@code If-Range: <validator>};
 * a {@code 206} means the server honoured it (append), a {@code 200} means it
 * ignored it (start over — {@link Result#restarted}).
 */
public final class FileDownloader {

    public interface Progress {
        void onProgress(long done, long total);   // total == -1 if unknown
    }

    public static final class Result {
        public final long received;     // bytes written this call
        public final boolean restarted; // server ignored our Range -> caller must truncate
        public final long totalSize;    // full resource size, or -1 if unknown
        public final String validator;  // ETag/Last-Modified to store for the next resume

        Result(long received, boolean restarted, long totalSize, String validator) {
            this.received = received;
            this.restarted = restarted;
            this.totalSize = totalSize;
            this.validator = validator;
        }
    }

    private FileDownloader() {}

    public static Result fetch(HttpClient http, String url, long from, String validator,
                               OutputStream out, Progress cb) throws IOException {
        Map<String, String> headers = new HashMap<>();
        if (from > 0) {
            headers.put("Range", "bytes=" + from + "-");
            if (validator != null) headers.put("If-Range", validator);
        }

        try (HttpClient.Response r = http.get(url, headers)) {
            int status = r.status();
            if (status != 200 && status != 206) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            boolean partial = status == 206;
            boolean restarted = from > 0 && !partial;       // asked to resume, got full body
            long total = parseTotal(r, partial, from);
            String newValidator = firstNonNull(r.header("ETag"), r.header("Last-Modified"));
            long base = (partial && !restarted) ? from : 0;

            long received = copy(r.body(), out, base, total, cb);
            return new Result(received, restarted, total, newValidator);
        }
    }

    /** Total resource size: from Content-Range's "/total" if present, else Content-Length. */
    static long parseTotal(HttpClient.Response r, boolean partial, long from) {
        String cr = r.header("Content-Range");      // "bytes 5-9/10"
        if (cr != null) {
            int slash = cr.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(cr.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) { /* fall through */ }
            }
        }
        long cl = r.contentLength();
        if (cl < 0) return -1;
        return partial ? from + cl : cl;
    }

    static long copy(InputStream in, OutputStream out, long base, long total, Progress cb)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long received = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            received += n;
            if (cb != null) cb.onProgress(base + received, total);
        }
        return received;
    }

    static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
