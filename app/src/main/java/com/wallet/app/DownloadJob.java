package com.wallet.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import com.wallet.core.download.CancelSignal;
import com.wallet.core.download.CancelledException;
import com.wallet.core.download.ContentDisposition;
import com.wallet.core.download.Dash;
import com.wallet.core.download.DashParser;
import com.wallet.core.download.DownloadPlanner;
import com.wallet.core.download.FileDownloader;
import com.wallet.core.download.HlsDownloader;
import com.wallet.core.download.M3u8;
import com.wallet.core.download.M3u8Parser;
import com.wallet.core.download.MediaSniffer;
import com.wallet.core.net.HttpClient;
import com.wallet.core.net.JdkHttpClient;
import com.wallet.core.net.RetryHttpClient;

/**
 * Runs one download end-to-end on a background thread, then stores the result
 * encrypted in the vault. Pipeline by media kind:
 *
 * <ul>
 *   <li><b>HLS</b> — resolve master → best variant → media playlist, download and
 *       concatenate segments to a temp {@code .ts}, then remux to {@code .mp4}
 *       (lossless, via {@link TsRemuxer}).</li>
 *   <li><b>DASH</b> — pick the best representation, download init + media segments
 *       (a fragmented MP4) and concatenate.</li>
 *   <li><b>Progressive</b> — a single ranged download.</li>
 * </ul>
 *
 * All HTTP goes through {@link RetryHttpClient} so a transient CDN hiccup doesn't
 * kill a long download.
 */
public final class DownloadJob {

    public interface Listener {
        /** {@code total} is -1 when unknown; units are bytes (progressive) or segments (HLS/DASH). */
        void onProgress(long done, long total, String label);
        void onComplete(String vaultName);
        void onError(Exception error);
    }

    private final HttpClient http = new RetryHttpClient(new JdkHttpClient());
    private final VaultStore vault;
    private final File workDir;

    public DownloadJob(VaultStore vault, File workDir) {
        this.vault = vault;
        this.workDir = workDir;
    }

    public void run(String url, String contentType, String suggestedName, boolean remux,
                    CancelSignal cancel, Listener l) {
        File temp = null;
        File remuxed = null;
        try {
            MediaSniffer.Kind kind = MediaSniffer.classify(url, contentType);
            String base = stripExt(ContentDisposition.fromUrl(url));
            String name;
            String mime;
            File toStore;

            if (kind == MediaSniffer.Kind.HLS) {
                M3u8 playlist = M3u8Parser.parse(fetchText(url), finalUrlOf(url));
                if (playlist.isMaster()) {
                    M3u8.Variant best = DownloadPlanner.bestVariant(playlist);
                    l.onProgress(0, -1, "variant " + (best.resolution != null ? best.resolution : best.bandwidth + " bps"));
                    playlist = M3u8Parser.parse(fetchText(best.uri), best.uri);
                }
                if (playlist.segments.isEmpty()) throw new IOException("playlist had no segments");
                temp = newTemp(".ts");
                try (OutputStream out = new FileOutputStream(temp)) {
                    HlsDownloader.download(http, playlist, out,
                            (done, n) -> l.onProgress(done, n, "segment " + done + "/" + n), cancel);
                }
                if (remux) {
                    remuxed = newTemp(".mp4");
                    TsRemuxer.remux(temp, remuxed);
                    toStore = remuxed;
                    name = base + ".mp4";
                    mime = "video/mp4";
                } else {
                    toStore = temp;
                    name = base + ".ts";
                    mime = "video/mp2t";
                }
            } else if (kind == MediaSniffer.Kind.DASH) {
                Dash dash = DashParser.parse(fetchText(url), finalUrlOf(url));
                temp = newTemp(".mp4");
                int i = 0;
                try (OutputStream out = new FileOutputStream(temp)) {
                    for (String segment : dash.segments) {
                        if (cancel.cancelled()) throw new CancelledException();
                        copyUrl(segment, out);
                        i++;
                        l.onProgress(i, dash.segments.size(), "segment " + i + "/" + dash.segments.size());
                    }
                }
                toStore = temp;
                name = base + ".mp4";
                mime = dash.video.mimeType != null ? dash.video.mimeType : "video/mp4";
            } else {
                temp = newTemp(".bin");
                try (OutputStream out = new FileOutputStream(temp)) {
                    FileDownloader.fetch(http, url, 0, null, out,
                            (done, t) -> l.onProgress(done, t, done + (t > 0 ? "/" + t : "") + " bytes"), cancel);
                }
                toStore = temp;
                name = ContentDisposition.resolve(null, suggestedName != null ? suggestedName : url);
                mime = contentType != null ? contentType : "application/octet-stream";
            }

            try (InputStream in = new FileInputStream(toStore)) {
                String saved = vault.put(name, in, mime, url);
                l.onComplete(saved);
            }
        } catch (Exception e) {
            l.onError(e);
        } finally {
            deleteQuietly(temp);
            deleteQuietly(remuxed);
        }
    }

    // --- helpers -------------------------------------------------------------

    private String fetchText(String url) throws IOException {
        try (HttpClient.Response r = http.get(url, Collections.emptyMap())) {
            if (r.status() != 200) throw new IOException("HTTP " + r.status() + " for " + url);
            return new String(readAll(r.body()), StandardCharsets.UTF_8);
        }
    }

    private String finalUrlOf(String url) throws IOException {
        try (HttpClient.Response r = http.get(url, Collections.emptyMap())) {
            return r.finalUrl();
        }
    }

    private void copyUrl(String url, OutputStream out) throws IOException {
        try (HttpClient.Response r = http.get(url, Collections.emptyMap())) {
            if (r.status() != 200 && r.status() != 206) throw new IOException("HTTP " + r.status() + " for " + url);
            InputStream in = r.body();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private File newTemp(String ext) {
        return new File(workDir, "dl-" + System.nanoTime() + ext);
    }

    private static void deleteQuietly(File f) {
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
