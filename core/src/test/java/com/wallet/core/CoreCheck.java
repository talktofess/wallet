package com.wallet.core;

import static com.wallet.core.MicroTest.check;
import static com.wallet.core.MicroTest.eq;
import static com.wallet.core.MicroTest.summary;
import static com.wallet.core.MicroTest.test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.wallet.core.crypto.VaultCrypto;
import com.wallet.core.download.ContentDisposition;
import com.wallet.core.download.DownloadPlanner;
import com.wallet.core.download.FileDownloader;
import com.wallet.core.download.HlsDownloader;
import com.wallet.core.download.M3u8;
import com.wallet.core.download.M3u8Parser;
import com.wallet.core.download.MediaSniffer;

/** The wallet core test suite — runs with a plain JDK, no JUnit/Gradle. */
public final class CoreCheck {

    public static void main(String[] args) throws Exception {
        // -------- VaultCrypto --------
        test("vault seals and opens a round trip", () -> {
            byte[] salt = VaultCrypto.newSalt();
            SecretKey k = VaultCrypto.deriveKey("hunter2".toCharArray(), salt, 10_000);
            byte[] blob = VaultCrypto.seal(k, "top secret".getBytes(StandardCharsets.UTF_8));
            eq(new String(VaultCrypto.open(k, blob), StandardCharsets.UTF_8), "top secret");
        });
        test("vault key derivation is deterministic", () -> {
            byte[] salt = VaultCrypto.newSalt();
            SecretKey a = VaultCrypto.deriveKey("pw".toCharArray(), salt, 10_000);
            SecretKey b = VaultCrypto.deriveKey("pw".toCharArray(), salt, 10_000);
            byte[] blob = VaultCrypto.seal(a, "x".getBytes());
            eq(new String(VaultCrypto.open(b, blob)), "x");          // b opens a's blob
        });
        test("vault rejects the wrong key", () -> {
            byte[] salt = VaultCrypto.newSalt();
            SecretKey good = VaultCrypto.deriveKey("right".toCharArray(), salt, 10_000);
            SecretKey bad = VaultCrypto.deriveKey("wrong".toCharArray(), salt, 10_000);
            byte[] blob = VaultCrypto.seal(good, "x".getBytes());
            boolean threw = false;
            try { VaultCrypto.open(bad, blob); } catch (Exception e) { threw = true; }
            check(threw, "wrong key must fail to open");
        });
        test("vault detects tampering", () -> {
            byte[] salt = VaultCrypto.newSalt();
            SecretKey k = VaultCrypto.deriveKey("pw".toCharArray(), salt, 10_000);
            byte[] blob = VaultCrypto.seal(k, "hello".getBytes());
            blob[blob.length - 1] ^= 0x01;                           // flip a ciphertext bit
            boolean threw = false;
            try { VaultCrypto.open(k, blob); } catch (Exception e) { threw = true; }
            check(threw, "tampered blob must fail the GCM tag");
        });

        // -------- ContentDisposition --------
        test("content-disposition plain filename",
            () -> eq(ContentDisposition.filename("attachment; filename=video.mp4"), "video.mp4"));
        test("content-disposition quoted filename",
            () -> eq(ContentDisposition.filename("attachment; filename=\"my movie.mp4\""), "my movie.mp4"));
        test("content-disposition RFC 5987 filename*",
            () -> eq(ContentDisposition.filename("attachment; filename*=UTF-8''my%20clip.mp4"), "my clip.mp4"));
        test("content-disposition falls back to the URL",
            () -> eq(ContentDisposition.fromUrl("https://x.com/a/b/clip.mp4?token=1"), "clip.mp4"));
        test("content-disposition strips path traversal",
            () -> eq(ContentDisposition.filename("attachment; filename=\"../../etc/passwd\""), "passwd"));

        // -------- MediaSniffer --------
        test("sniff HLS by extension and content-type", () -> {
            eq(MediaSniffer.classify("https://x/v.m3u8", null), MediaSniffer.Kind.HLS);
            eq(MediaSniffer.classify("https://x/v?z=1", "application/vnd.apple.mpegurl"), MediaSniffer.Kind.HLS);
        });
        test("sniff DASH, progressive, and non-media", () -> {
            eq(MediaSniffer.classify("https://x/v.mpd", null), MediaSniffer.Kind.DASH);
            eq(MediaSniffer.classify("https://x/v.mp4", null), MediaSniffer.Kind.PROGRESSIVE);
            eq(MediaSniffer.classify("https://x/v", "video/mp4"), MediaSniffer.Kind.PROGRESSIVE);
            eq(MediaSniffer.classify("https://x/page.html", "text/html"), MediaSniffer.Kind.NONE);
        });

        // -------- M3u8Parser --------
        test("parse master playlist and pick the best variant", () -> {
            String m = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nhigh/index.m3u8\n";
            M3u8 pl = M3u8Parser.parse(m, "https://cdn.example.com/video/master.m3u8");
            check(pl.isMaster(), "should detect a master playlist");
            eq(pl.variants.size(), 2);
            M3u8.Variant best = DownloadPlanner.bestVariant(pl);
            eq(best.bandwidth, 2_500_000L);
            eq(best.uri, "https://cdn.example.com/video/high/index.m3u8");   // relative resolved
        });
        test("parse media playlist: segments, key, relative URLs", () -> {
            String m = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001\n"
                + "#EXTINF:6.0,\nseg0.ts\n#EXTINF:6.0,\nseg1.ts\n#EXT-X-ENDLIST\n";
            M3u8 pl = M3u8Parser.parse(m, "https://cdn.example.com/v/media.m3u8");
            check(!pl.isMaster(), "should detect a media playlist");
            eq(pl.segments.size(), 2);
            eq(pl.segments.get(0).uri, "https://cdn.example.com/v/seg0.ts");
            eq(pl.segments.get(0).key.method, "AES-128");
            eq(pl.segments.get(0).key.uri, "https://cdn.example.com/v/key.bin");
        });

        // -------- FileDownloader --------
        test("download a fresh file (HTTP 200)", () -> {
            byte[] data = "0123456789".getBytes();
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/f.bin", 200, data, "Content-Length", "10", "ETag", "\"abc\"");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FileDownloader.Result r = FileDownloader.fetch(http, "https://x/f.bin", 0, null, out, null);
            eq(out.size(), 10);
            eq(r.totalSize, 10);
            check(!r.restarted, "a fresh download is not a restart");
            eq(r.validator, "\"abc\"");
        });
        test("resume sends Range and accepts 206 Partial Content", () -> {
            byte[] full = "0123456789".getBytes();
            FakeHttpClient http = new FakeHttpClient().handler((url, h) -> {
                FakeHttpClient.Stub s = new FakeHttpClient.Stub();
                if (h.containsKey("Range")) {
                    s.status = 206;
                    s.body = Arrays.copyOfRange(full, 5, 10);
                    s.headers.put("Content-Range", "bytes 5-9/10");
                } else {
                    s.status = 200;
                    s.body = full;
                    s.headers.put("Content-Length", "10");
                }
                return s;
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FileDownloader.Result r = FileDownloader.fetch(http, "https://x/f.bin", 5, "\"abc\"", out, null);
            eq(out.size(), 5);
            eq(r.totalSize, 10);
            check(!r.restarted, "206 means honoured, not restarted");
            eq(http.lastHeaders().get("Range"), "bytes=5-");
        });
        test("a 200 in response to Range signals a restart", () -> {
            byte[] full = "0123456789".getBytes();
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/f.bin", 200, full, "Content-Length", "10");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FileDownloader.Result r = FileDownloader.fetch(http, "https://x/f.bin", 5, null, out, null);
            check(r.restarted, "server ignored Range -> must restart");
            eq(out.size(), 10);
        });

        // -------- HlsDownloader --------
        test("HLS concatenates clear segments in order", () -> {
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/s0.ts", 200, "AAA".getBytes())
                .stub("https://x/s1.ts", 200, "BBB".getBytes());
            List<M3u8.Segment> segs = Arrays.asList(
                new M3u8.Segment("https://x/s0.ts", 6, null, -1, -1),
                new M3u8.Segment("https://x/s1.ts", 6, null, -1, -1));
            M3u8 media = new M3u8(M3u8.Type.MEDIA, Collections.emptyList(), segs, 6, 0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int n = HlsDownloader.download(http, media, out, null);
            eq(n, 2);
            eq(new String(out.toByteArray()), "AAABBB");
        });
        test("HLS decrypts standard AES-128 segments", () -> {
            byte[] key = "0123456789abcdef".getBytes();        // 16-byte AES-128 key
            byte[] iv = new byte[16]; iv[15] = 1;
            byte[] p0 = "hello world segment zero".getBytes();
            byte[] p1 = "and here is segment number one".getBytes();
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/key.bin", 200, key)
                .stub("https://x/s0.ts", 200, aesEncrypt(key, iv, p0))
                .stub("https://x/s1.ts", 200, aesEncrypt(key, iv, p1));
            M3u8.Key k = new M3u8.Key("AES-128", "https://x/key.bin",
                "0x00000000000000000000000000000001");
            List<M3u8.Segment> segs = Arrays.asList(
                new M3u8.Segment("https://x/s0.ts", 6, k, -1, -1),
                new M3u8.Segment("https://x/s1.ts", 6, k, -1, -1));
            M3u8 media = new M3u8(M3u8.Type.MEDIA, Collections.emptyList(), segs, 6, 0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HlsDownloader.download(http, media, out, null);
            check(Arrays.equals(out.toByteArray(), concat(p0, p1)), "decrypted concat mismatch");
        });

        System.exit(summary());
    }

    static byte[] aesEncrypt(byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
