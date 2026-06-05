package com.wallet.core.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.wallet.core.net.HttpClient;

/**
 * Downloads an HLS MEDIA playlist: fetch each segment in order, decrypt standard
 * AES-128 segments when the playlist carries an {@code EXT-X-KEY} (the key is
 * served openly from the manifest — this is how every HLS player works, not DRM),
 * and concatenate the segments to the output.
 *
 * <p><b>Scope:</b> this handles ordinary, unprotected HLS. It deliberately does
 * <i>not</i> implement Widevine / PlayReady / CENC DRM circumvention. Concatenated
 * MPEG-TS plays as-is; remuxing to a clean .mp4 is a follow-up (e.g. Media3/FFmpeg).
 */
public final class HlsDownloader {

    public interface Progress {
        void onSegment(int done, int total);
    }

    private HlsDownloader() {}

    public static int download(HttpClient http, M3u8 media, OutputStream out, Progress cb)
            throws IOException, GeneralSecurityException {
        final int total = media.segments.size();
        int done = 0;
        Map<String, byte[]> keyCache = new HashMap<>();

        for (M3u8.Segment seg : media.segments) {
            byte[] data = readAll(http, seg.uri, seg.byteOffset, seg.byteLength);

            if (seg.key != null && "AES-128".equalsIgnoreCase(seg.key.method)) {
                byte[] key = keyCache.get(seg.key.uri);
                if (key == null) {
                    key = readAll(http, seg.key.uri, -1, -1);
                    keyCache.put(seg.key.uri, key);
                }
                byte[] iv = seg.key.iv != null
                        ? hexToBytes(seg.key.iv)
                        : sequenceIv(media.mediaSequence + done);
                data = aes128cbcDecrypt(key, iv, data);
            }

            out.write(data);
            done++;
            if (cb != null) cb.onSegment(done, total);
        }
        return done;
    }

    static byte[] readAll(HttpClient http, String url, long offset, long length) throws IOException {
        Map<String, String> headers = new HashMap<>();
        if (length >= 0) {
            long start = Math.max(offset, 0);
            headers.put("Range", "bytes=" + start + "-" + (start + length - 1));
        }
        try (HttpClient.Response r = http.get(url, headers)) {
            if (r.status() != 200 && r.status() != 206) {
                throw new IOException("HTTP " + r.status() + " for " + url);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = r.body();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static byte[] aes128cbcDecrypt(byte[] key, byte[] iv, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");   // PKCS5 == PKCS7 for AES
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /** Default HLS IV when EXT-X-KEY omits one: the segment's media sequence number. */
    static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0 && sequence != 0; i--) {
            iv[i] = (byte) (sequence & 0xFF);
            sequence >>>= 8;
        }
        return iv;
    }

    static byte[] hexToBytes(String s) {
        String t = (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
        int len = t.length() / 2;
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
