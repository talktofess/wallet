package com.wallet.core;

import static com.wallet.core.MicroTest.check;
import static com.wallet.core.MicroTest.eq;
import static com.wallet.core.MicroTest.summary;
import static com.wallet.core.MicroTest.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.wallet.core.crypto.SecretStream;
import com.wallet.core.crypto.VaultCrypto;
import com.wallet.core.download.Cancellation;
import com.wallet.core.download.ContentDisposition;
import com.wallet.core.download.Dash;
import com.wallet.core.download.DashParser;
import com.wallet.core.download.DownloadPlanner;
import com.wallet.core.download.DownloadQueue;
import com.wallet.core.download.DownloadState;
import com.wallet.core.download.DownloadTask;
import com.wallet.core.download.FileDownloader;
import com.wallet.core.download.HlsDownloader;
import com.wallet.core.download.M3u8;
import com.wallet.core.download.M3u8Parser;
import com.wallet.core.download.MediaSniffer;
import com.wallet.core.download.ProgressMeter;
import com.wallet.core.media.MpegTs;
import com.wallet.core.net.HttpClient;
import com.wallet.core.net.RetryHttpClient;
import com.wallet.core.util.ByteFormat;
import com.wallet.core.util.UniqueNames;
import com.wallet.core.vault.VaultIndex;

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

        // -------- SecretStream (chunked streaming AEAD) --------
        test("secret stream round-trips across many chunks", () -> {
            SecretKey k = VaultCrypto.deriveKey("pw".toCharArray(), VaultCrypto.newSalt(), 10_000);
            byte[] plain = new byte[100];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) i;
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            SecretStream.encrypt(k, new ByteArrayInputStream(plain), enc, 16);
            ByteArrayOutputStream dec = new ByteArrayOutputStream();
            SecretStream.decrypt(k, new ByteArrayInputStream(enc.toByteArray()), dec);
            check(Arrays.equals(dec.toByteArray(), plain), "round-trip mismatch");
        });
        test("secret stream rejects a tampered chunk", () -> {
            SecretKey k = VaultCrypto.deriveKey("pw".toCharArray(), VaultCrypto.newSalt(), 10_000);
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            SecretStream.encrypt(k, new ByteArrayInputStream("some secret bytes here".getBytes()), enc, 8);
            byte[] blob = enc.toByteArray();
            blob[blob.length - 1] ^= 0x01;
            boolean threw = false;
            try { SecretStream.decrypt(k, new ByteArrayInputStream(blob), new ByteArrayOutputStream()); }
            catch (Exception e) { threw = true; }
            check(threw, "tampered stream must fail");
        });
        test("secret stream detects truncation", () -> {
            SecretKey k = VaultCrypto.deriveKey("pw".toCharArray(), VaultCrypto.newSalt(), 10_000);
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            SecretStream.encrypt(k, new ByteArrayInputStream(new byte[64]), enc, 16);
            byte[] cut = Arrays.copyOf(enc.toByteArray(), enc.size() - 8);   // lose the final chunk
            boolean threw = false;
            try { SecretStream.decrypt(k, new ByteArrayInputStream(cut), new ByteArrayOutputStream()); }
            catch (Exception e) { threw = true; }
            check(threw, "truncated stream must fail");
        });

        // -------- RetryHttpClient --------
        test("retry client recovers after transient 5xx", () -> {
            final int[] calls = {0};
            FakeHttpClient fake = new FakeHttpClient().handler((url, h) -> {
                calls[0]++;
                FakeHttpClient.Stub s = new FakeHttpClient.Stub();
                if (calls[0] < 3) { s.status = 503; } else { s.status = 200; s.body = "ok".getBytes(); }
                return s;
            });
            RetryHttpClient retry = new RetryHttpClient(fake, 3, 1, ms -> { /* no real wait */ });
            try (HttpClient.Response r = retry.get("https://x/f", Collections.emptyMap())) {
                eq(r.status(), 200);
            }
            eq(calls[0], 3);
        });

        // -------- UniqueNames --------
        test("unique names disambiguate collisions", () -> {
            Set<String> taken = new HashSet<>(Arrays.asList("clip.mp4"));
            eq(UniqueNames.make("clip.mp4", taken), "clip (1).mp4");
            taken.add("clip (1).mp4");
            eq(UniqueNames.make("clip.mp4", taken), "clip (2).mp4");
            eq(UniqueNames.make("fresh.mp4", taken), "fresh.mp4");
        });

        // -------- VaultIndex --------
        test("vault index serialises, parses, and removes", () -> {
            VaultIndex idx = new VaultIndex();
            idx.add(new VaultIndex.Item("id1", "my video.mp4", "video/mp4", 12345L, "https://x/v.m3u8", 1000L));
            idx.add(new VaultIndex.Item("id2", "song.mp3", "audio/mpeg", 99L, "", 2000L));
            VaultIndex back = VaultIndex.parse(idx.serialize());
            eq(back.size(), 2);
            eq(back.byId("id1").name, "my video.mp4");
            eq(back.byId("id1").size, 12345L);
            eq(back.byId("id2").mime, "audio/mpeg");
            check(back.remove("id1"), "remove should succeed");
            eq(back.size(), 1);
        });
        test("vault index can be sealed and reopened", () -> {
            SecretKey k = VaultCrypto.deriveKey("pw".toCharArray(), VaultCrypto.newSalt(), 10_000);
            VaultIndex idx = new VaultIndex();
            idx.add(new VaultIndex.Item("a", "f.mp4", "video/mp4", 1, "u", 1));
            VaultIndex back = VaultIndex.parse(VaultCrypto.open(k, VaultCrypto.seal(k, idx.serialize())));
            eq(back.size(), 1);
            eq(back.byId("a").name, "f.mp4");
        });

        // -------- MpegTs --------
        test("mpeg-ts parses packets, PUSI flags, and PIDs", () -> {
            byte[] stream = concat(tsPacket(0x100, true, (byte) 0xAA), tsPacket(0x101, false, (byte) 0xBB));
            List<MpegTs.Packet> packets = MpegTs.parse(stream);
            eq(packets.size(), 2);
            eq(packets.get(0).pid, 0x100);
            check(packets.get(0).payloadStart, "first packet has payload-unit-start");
            check(!packets.get(1).payloadStart, "second packet does not");
            eq(packets.get(0).payload[0] & 0xFF, 0xAA);
            check(MpegTs.pids(stream).contains(0x101), "PIDs should include 0x101");
        });

        // -------- DashParser --------
        test("dash: pick best variant and expand a SegmentTimeline", () -> {
            String mpd = """
                <?xml version="1.0"?>
                <MPD mediaPresentationDuration="PT12S">
                  <Period>
                    <AdaptationSet mimeType="video/mp4">
                      <Representation id="v0" bandwidth="800000" width="640" height="360">
                        <SegmentTemplate initialization="init-$RepresentationID$.m4s" media="seg-$RepresentationID$-$Number$.m4s" startNumber="1" timescale="1000">
                          <SegmentTimeline><S t="0" d="6000" r="1"/></SegmentTimeline>
                        </SegmentTemplate>
                      </Representation>
                      <Representation id="v1" bandwidth="2500000" width="1280" height="720">
                        <SegmentTemplate initialization="init-$RepresentationID$.m4s" media="seg-$RepresentationID$-$Number$.m4s" startNumber="1" timescale="1000">
                          <SegmentTimeline><S t="0" d="6000" r="1"/></SegmentTimeline>
                        </SegmentTemplate>
                      </Representation>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """;
            Dash d = DashParser.parse(mpd, "https://cdn.example.com/dash/manifest.mpd");
            eq(d.video.id, "v1");
            eq(d.video.height, 720);
            eq(d.segments.size(), 3);   // init + 2 media
            eq(d.segments.get(0), "https://cdn.example.com/dash/init-v1.m4s");
            eq(d.segments.get(2), "https://cdn.example.com/dash/seg-v1-2.m4s");
        });
        test("dash: expand an @duration template with %0Nd numbering", () -> {
            String mpd = """
                <?xml version="1.0"?>
                <MPD mediaPresentationDuration="PT12S">
                  <Period>
                    <AdaptationSet mimeType="video/mp4">
                      <Representation id="r0" bandwidth="1000000" width="640" height="360">
                        <SegmentTemplate initialization="init.mp4" media="$Number%03d$.m4s" startNumber="1" timescale="1" duration="4"/>
                      </Representation>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """;
            Dash d = DashParser.parse(mpd, "https://cdn.example.com/d/manifest.mpd");
            eq(d.segments.size(), 4);   // init + 3 media (12s / 4s)
            check(d.segments.get(1).endsWith("/001.m4s"), "first media should be 001");
            check(d.segments.get(3).endsWith("/003.m4s"), "third media should be 003");
        });

        // -------- DownloadQueue --------
        test("queue add starts QUEUED and is next to run", () -> {
            DownloadQueue q = new DownloadQueue();
            DownloadTask t = q.add("https://x/a.mp4", "a.mp4", "video/mp4", 1000);
            eq(t.state(), DownloadState.QUEUED);
            eq(q.nextQueued().id, t.id);
            check(q.hasWork(), "should have work");
        });
        test("queue runs a task to completion", () -> {
            DownloadQueue q = new DownloadQueue();
            String id = q.add("https://x/a.mp4", "a.mp4", "video/mp4", 1).id;
            q.start(id);
            eq(q.byId(id).state(), DownloadState.RUNNING);
            q.progress(id, 50, 100);
            eq(q.byId(id).percent(), 50);
            q.complete(id);
            eq(q.byId(id).state(), DownloadState.COMPLETED);
            eq(q.byId(id).bytesDone(), 100L);
            check(!q.hasWork(), "no work after completion");
        });
        test("queue pause and resume", () -> {
            DownloadQueue q = new DownloadQueue();
            String id = q.add("u", "n", "", 1).id;
            q.pause(id);
            eq(q.byId(id).state(), DownloadState.PAUSED);
            check(!q.hasWork(), "paused task is not runnable");
            q.resume(id);
            eq(q.byId(id).state(), DownloadState.QUEUED);
        });
        test("queue fail then retry resets progress", () -> {
            DownloadQueue q = new DownloadQueue();
            String id = q.add("u", "n", "", 1).id;
            q.start(id);
            q.progress(id, 30, 100);
            q.fail(id, "boom");
            eq(q.byId(id).state(), DownloadState.FAILED);
            eq(q.byId(id).message(), "boom");
            q.retry(id);
            eq(q.byId(id).state(), DownloadState.QUEUED);
            eq(q.byId(id).bytesDone(), 0L);
        });
        test("queue rejects an illegal transition", () -> {
            DownloadQueue q = new DownloadQueue();
            String id = q.add("u", "n", "", 1).id;
            boolean threw = false;
            try { q.complete(id); } catch (IllegalStateException e) { threw = true; }   // QUEUED can't complete
            check(threw, "completing a queued task must throw");
        });
        test("nextQueued skips running and completed tasks", () -> {
            DownloadQueue q = new DownloadQueue();
            String a = q.add("u1", "a", "", 1).id;
            String b = q.add("u2", "b", "", 2).id;
            q.start(a);
            q.complete(a);
            eq(q.nextQueued().id, b);
        });
        test("queue serialises and restores (running -> queued)", () -> {
            DownloadQueue q = new DownloadQueue();
            String a = q.add("https://x/a.mp4", "a.mp4", "video/mp4", 10).id;
            String b = q.add("https://x/b.mp4", "b.mp4", "video/mp4", 20).id;
            q.start(a);
            q.progress(a, 25, 100);
            q.start(b);
            q.complete(b);
            DownloadQueue back = DownloadQueue.parse(q.serialize());
            eq(back.tasks().size(), 2);
            eq(back.byId(a).state(), DownloadState.QUEUED);    // interrupted RUNNING resumes as QUEUED
            eq(back.byId(a).bytesDone(), 25L);
            eq(back.byId(b).state(), DownloadState.COMPLETED);
        });
        test("queue notifies listeners on every change", () -> {
            DownloadQueue q = new DownloadQueue();
            final int[] hits = {0};
            q.addListener(() -> hits[0]++);
            String id = q.add("u", "n", "", 1).id;   // change 1
            q.start(id);                              // change 2
            q.complete(id);                           // change 3
            check(hits[0] >= 3, "listener should fire on each change");
        });

        // -------- cancellation --------
        test("FileDownloader honours cancellation", () -> {
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/f.bin", 200, new byte[1000], "Content-Length", "1000");
            Cancellation c = new Cancellation();
            c.cancel();
            boolean threw = false;
            try {
                FileDownloader.fetch(http, "https://x/f.bin", 0, null, new ByteArrayOutputStream(), null, c);
            } catch (Exception e) { threw = true; }
            check(threw, "a pre-cancelled fetch must throw");
        });
        test("HLS download cancels after the first segment", () -> {
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/s0.ts", 200, "AAA".getBytes())
                .stub("https://x/s1.ts", 200, "BBB".getBytes())
                .stub("https://x/s2.ts", 200, "CCC".getBytes());
            List<M3u8.Segment> segs = Arrays.asList(
                new M3u8.Segment("https://x/s0.ts", 6, null, -1, -1),
                new M3u8.Segment("https://x/s1.ts", 6, null, -1, -1),
                new M3u8.Segment("https://x/s2.ts", 6, null, -1, -1));
            M3u8 media = new M3u8(M3u8.Type.MEDIA, Collections.emptyList(), segs, 6, 0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Cancellation c = new Cancellation();
            boolean threw = false;
            try {
                HlsDownloader.download(http, media, out, (done, total) -> { if (done == 1) c.cancel(); }, c);
            } catch (Exception e) { threw = true; }
            check(threw, "cancelled HLS must throw");
            eq(new String(out.toByteArray()), "AAA");      // only the first segment landed
        });

        // -------- ProgressMeter / ByteFormat --------
        test("progress meter computes speed and ETA", () -> {
            ProgressMeter m = new ProgressMeter(10_000);
            m.sample(0, 0);
            m.sample(1000, 100);
            m.sample(2000, 200);
            eq((long) m.bytesPerSecond(), 100L);           // 200 bytes over 2s
            eq(m.etaSeconds(1000), 8L);                    // (1000-200)/100
        });
        test("byte format is human readable", () -> {
            eq(ByteFormat.bytes(512), "512 B");
            eq(ByteFormat.bytes(1536), "1.5 KB");
            eq(ByteFormat.bytes(5L * 1024 * 1024), "5.0 MB");
            eq(ByteFormat.rate(1024 * 1024), "1.0 MB/s");
        });

        // -------- HLS EXT-X-MAP (fMP4 init segment) --------
        test("HLS parses an EXT-X-MAP init segment", () -> {
            String m = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\nseg0.m4s\n#EXTINF:6.0,\nseg1.m4s\n";
            M3u8 pl = M3u8Parser.parse(m, "https://cdn.example.com/v/media.m3u8");
            eq(pl.initSegment, "https://cdn.example.com/v/init.mp4");
            eq(pl.segments.size(), 2);
        });
        test("HLS writes the init segment before the media", () -> {
            FakeHttpClient http = new FakeHttpClient()
                .stub("https://x/init.mp4", 200, "INIT".getBytes())
                .stub("https://x/s0.m4s", 200, "AAA".getBytes())
                .stub("https://x/s1.m4s", 200, "BBB".getBytes());
            List<M3u8.Segment> segs = Arrays.asList(
                new M3u8.Segment("https://x/s0.m4s", 6, null, -1, -1),
                new M3u8.Segment("https://x/s1.m4s", 6, null, -1, -1));
            M3u8 media = new M3u8(M3u8.Type.MEDIA, Collections.emptyList(), segs, 6, 0, "https://x/init.mp4");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HlsDownloader.download(http, media, out, null);
            eq(new String(out.toByteArray()), "INITAAABBB");
        });

        // -------- queue note + restore --------
        test("queue note sets a status line", () -> {
            DownloadQueue q = new DownloadQueue();
            String id = q.add("u", "n", "", 1).id;
            q.start(id);
            q.note(id, "1.5 MB/s");
            eq(q.byId(id).message(), "1.5 MB/s");
        });
        test("queue restores from a serialized blob", () -> {
            DownloadQueue q = new DownloadQueue();
            q.add("https://x/a.mp4", "a.mp4", "video/mp4", 1);
            byte[] blob = q.serialize();
            DownloadQueue q2 = new DownloadQueue();
            q2.add("other", "o", "", 9);          // will be replaced
            q2.loadSerialized(blob);
            eq(q2.tasks().size(), 1);
            eq(q2.tasks().get(0).name, "a.mp4");
        });

        // -------- chess (the unlock disguise) --------
        test("chess opening position is set up correctly", () -> {
            com.wallet.core.chess.ChessEngine.Piece[][] b = com.wallet.core.chess.ChessEngine.initialBoard();
            eq(b[7][4].type, com.wallet.core.chess.ChessEngine.Type.K);      // white king on e1
            eq(b[7][4].color, com.wallet.core.chess.ChessEngine.Color.W);
            eq(b[0][3].type, com.wallet.core.chess.ChessEngine.Type.Q);      // black queen on d8
            eq(b[0][3].color, com.wallet.core.chess.ChessEngine.Color.B);
            check(b[4][4] == null, "e4 starts empty");
        });
        test("a pawn can advance one or two from its start", () -> {
            com.wallet.core.chess.ChessEngine.Piece[][] b = com.wallet.core.chess.ChessEngine.initialBoard();
            List<com.wallet.core.chess.ChessEngine.Pos> mv =
                    com.wallet.core.chess.ChessEngine.legalMoves(b, new com.wallet.core.chess.ChessEngine.Pos(6, 4)); // e2
            eq(mv.size(), 2);
            check(mv.contains(new com.wallet.core.chess.ChessEngine.Pos(5, 4)), "e3 available");
            check(mv.contains(new com.wallet.core.chess.ChessEngine.Pos(4, 4)), "e4 available");
        });
        test("squareName maps coordinates to algebraic", () -> {
            eq(com.wallet.core.chess.ChessEngine.squareName(new com.wallet.core.chess.ChessEngine.Pos(6, 4)), "e2");
            eq(com.wallet.core.chess.ChessEngine.squareName(new com.wallet.core.chess.ChessEngine.Pos(0, 0)), "a8");
            eq(com.wallet.core.chess.ChessEngine.squareName(new com.wallet.core.chess.ChessEngine.Pos(7, 7)), "h1");
        });
        test("move applies, captures, and does not mutate the source board", () -> {
            com.wallet.core.chess.ChessEngine.Piece[][] b = com.wallet.core.chess.ChessEngine.initialBoard();
            com.wallet.core.chess.ChessEngine.Piece[][] after = com.wallet.core.chess.ChessEngine.move(
                    b, new com.wallet.core.chess.ChessEngine.Pos(6, 4), new com.wallet.core.chess.ChessEngine.Pos(4, 4));
            check(b[6][4] != null, "original board is unchanged");
            check(after[6][4] == null, "e2 vacated on the new board");
            eq(after[4][4].type, com.wallet.core.chess.ChessEngine.Type.P);  // pawn now on e4
        });
        test("a played sequence derives a stable, domain-separated secret", () -> {
            String s = com.wallet.core.chess.MoveKey.movesToSecret(Arrays.asList("E2E4", "e7e5", "g1f3"));
            eq(s, "chesskey-v1|e2e4 e7e5 g1f3");                              // lower-cased, space-joined
            // Same moves -> same secret -> same key (so the vault opens deterministically).
            byte[] salt = VaultCrypto.newSalt();
            SecretKey k1 = VaultCrypto.deriveKey(s.toCharArray(), salt, 10_000);
            SecretKey k2 = VaultCrypto.deriveKey(
                    com.wallet.core.chess.MoveKey.movesToSecret(Arrays.asList("e2e4", "e7e5", "g1f3")).toCharArray(),
                    salt, 10_000);
            byte[] blob = VaultCrypto.seal(k1, "open sesame".getBytes(StandardCharsets.UTF_8));
            eq(new String(VaultCrypto.open(k2, blob), StandardCharsets.UTF_8), "open sesame");
        });
        test("a different sequence derives a different key", () -> {
            byte[] salt = VaultCrypto.newSalt();
            SecretKey good = VaultCrypto.deriveKey(
                    com.wallet.core.chess.MoveKey.movesToSecret(Arrays.asList("e2e4", "e7e5")).toCharArray(), salt, 10_000);
            SecretKey bad = VaultCrypto.deriveKey(
                    com.wallet.core.chess.MoveKey.movesToSecret(Arrays.asList("d2d4", "d7d5")).toCharArray(), salt, 10_000);
            byte[] blob = VaultCrypto.seal(good, "x".getBytes());
            boolean threw = false;
            try { VaultCrypto.open(bad, blob); } catch (Exception e) { threw = true; }
            check(threw, "wrong opening must not open the vault");
        });

        System.exit(summary());
    }

    static byte[] tsPacket(int pid, boolean payloadStart, byte fill) {
        byte[] p = new byte[MpegTs.PACKET_SIZE];
        p[0] = (byte) MpegTs.SYNC_BYTE;
        int b1 = (pid >> 8) & 0x1F;
        if (payloadStart) b1 |= 0x40;
        p[1] = (byte) b1;
        p[2] = (byte) (pid & 0xFF);
        p[3] = (byte) 0x10;                 // adaptation_field_control = 01 (payload only)
        for (int i = 4; i < p.length; i++) p[i] = fill;
        return p;
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
