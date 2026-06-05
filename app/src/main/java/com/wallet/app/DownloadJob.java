package com.wallet.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import com.wallet.core.download.ContentDisposition;
import com.wallet.core.download.DownloadPlanner;
import com.wallet.core.download.FileDownloader;
import com.wallet.core.download.HlsDownloader;
import com.wallet.core.download.M3u8;
import com.wallet.core.download.M3u8Parser;
import com.wallet.core.download.MediaSniffer;
import com.wallet.core.net.HttpClient;
import com.wallet.core.net.JdkHttpClient;

/**
 * Runs one download end-to-end on a background thread and stores the result
 * encrypted in the vault. This is where the pipeline lives:
 *
 * <ul>
 *   <li><b>HLS</b> — fetch the manifest, follow a master playlist to its best
 *       variant, then download and concatenate every segment.</li>
 *   <li><b>Progressive</b> — a single ranged download (resume-capable).</li>
 * </ul>
 *
 * The naive "GET the URL" approach fails on HLS because the manifest URL holds
 * no video; this resolves it to segments first.
 */
public final class DownloadJob {

    public interface Listener {
        void onProgress(String message);
        void onComplete(String vaultName);
        void onError(Exception error);
    }

    private final HttpClient http = new JdkHttpClient();
    private final VaultStore vault;

    public DownloadJob(VaultStore vault) {
        this.vault = vault;
    }

    public void run(String url, String contentType, String suggestedName, Listener l) {
        try {
            MediaSniffer.Kind kind = MediaSniffer.classify(url, contentType);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String name;

            if (kind == MediaSniffer.Kind.HLS) {
                M3u8 playlist = fetchPlaylist(url);
                if (playlist.isMaster()) {
                    M3u8.Variant best = DownloadPlanner.bestVariant(playlist);
                    l.onProgress("variant " + (best.resolution != null ? best.resolution : best.bandwidth + " bps"));
                    playlist = fetchPlaylist(best.uri);
                }
                final int total = playlist.segments.size();
                HlsDownloader.download(http, playlist, out,
                        (done, n) -> l.onProgress("segment " + done + "/" + n));
                name = stripExt(ContentDisposition.fromUrl(url)) + ".ts";
                if (total == 0) throw new IOException("playlist had no segments");
            } else if (kind == MediaSniffer.Kind.DASH) {
                throw new IOException("MPEG-DASH is not supported yet");
            } else {
                FileDownloader.fetch(http, url, 0, null, out,
                        (done, t) -> l.onProgress(done + (t > 0 ? "/" + t : "") + " bytes"));
                name = ContentDisposition.resolve(null, suggestedName != null ? suggestedName : url);
            }

            vault.put(name, out.toByteArray());
            l.onComplete(name);
        } catch (Exception e) {
            l.onError(e);
        }
    }

    private M3u8 fetchPlaylist(String url) throws IOException {
        try (HttpClient.Response r = http.get(url, Collections.emptyMap())) {
            if (r.status() != 200) throw new IOException("HTTP " + r.status() + " fetching playlist");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = r.body();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
            // resolve relative segment URIs against the manifest's final (post-redirect) URL
            return M3u8Parser.parse(new String(b.toByteArray(), StandardCharsets.UTF_8), r.finalUrl());
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
