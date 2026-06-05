package com.wallet.core.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Chunked authenticated encryption for large files (e.g. a downloaded video) that
 * shouldn't be held in memory all at once. The stream is split into fixed-size
 * chunks, each sealed with AES-256-GCM under a {@code base-nonce || counter}
 * nonce. Two attacks are defeated structurally:
 *
 * <ul>
 *   <li><b>reordering</b> — the chunk counter is part of the nonce, so a swapped
 *       chunk fails its tag;</li>
 *   <li><b>truncation</b> — the "is this the final chunk" bit is authenticated
 *       (as AAD), and is derived at decrypt time from EOF, so dropping the last
 *       chunk (or appending one) fails the tag.</li>
 * </ul>
 *
 * Layout: {@code ['W','L','S','1'][chunkSize:4][baseNonce:8]} then, per chunk,
 * {@code [ctLen:4][ciphertext||tag]}.
 */
public final class SecretStream {

    private static final byte[] MAGIC = {'W', 'L', 'S', '1'};
    public static final int DEFAULT_CHUNK = 64 * 1024;

    private SecretStream() {}

    public static void encrypt(SecretKey key, InputStream in, OutputStream out, int chunkSize)
            throws IOException, GeneralSecurityException {
        if (chunkSize <= 0) chunkSize = DEFAULT_CHUNK;
        byte[] base = VaultCrypto.randomBytes(8);

        out.write(MAGIC);
        writeInt(out, chunkSize);
        out.write(base);

        byte[] buf = new byte[chunkSize];
        long counter = 0;
        while (true) {
            int n = readFully(in, buf);
            boolean last = n < chunkSize;       // couldn't fill -> EOF reached
            byte[] plain = (n == chunkSize) ? buf : Arrays.copyOf(buf, n);
            byte[] ct = gcm(Cipher.ENCRYPT_MODE, key, nonce(base, counter), aad(last), plain);
            writeInt(out, ct.length);
            out.write(ct);
            counter++;
            if (last) break;
        }
    }

    public static void decrypt(SecretKey key, InputStream in, OutputStream out)
            throws IOException, GeneralSecurityException {
        byte[] magic = readExactly(in, 4);
        if (magic == null || !Arrays.equals(magic, MAGIC)) throw new IOException("bad stream header");
        int chunkSize = readInt(in);
        if (chunkSize <= 0) throw new IOException("bad chunk size");
        byte[] base = readExactly(in, 8);
        if (base == null) throw new IOException("truncated header");

        long counter = 0;
        Integer len = readLenOrNull(in);
        if (len == null) throw new IOException("truncated: no chunks");
        while (len != null) {
            byte[] ct = readExactly(in, len);
            if (ct == null) throw new IOException("truncated chunk");
            Integer next = readLenOrNull(in);
            boolean last = (next == null);                  // final chunk == nothing follows
            byte[] plain = gcm(Cipher.DECRYPT_MODE, key, nonce(base, counter), aad(last), ct);
            out.write(plain);
            counter++;
            len = next;
        }
    }

    // --- helpers -------------------------------------------------------------

    private static byte[] gcm(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] data)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, nonce));
        c.updateAAD(aad);
        return c.doFinal(data);
    }

    private static byte[] nonce(byte[] base, long counter) {
        byte[] iv = new byte[12];
        System.arraycopy(base, 0, iv, 0, 8);
        iv[8] = (byte) (counter >>> 24);
        iv[9] = (byte) (counter >>> 16);
        iv[10] = (byte) (counter >>> 8);
        iv[11] = (byte) counter;
        return iv;
    }

    private static byte[] aad(boolean last) {
        return new byte[]{(byte) (last ? 1 : 0)};
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    /** Read exactly {@code len} bytes, or null if EOF hits immediately at the start. */
    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                if (off == 0) return null;
                throw new IOException("unexpected EOF");
            }
            off += n;
        }
        return b;
    }

    private static Integer readLenOrNull(InputStream in) throws IOException {
        byte[] b = readExactly(in, 4);
        if (b == null) return null;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] b = readExactly(in, 4);
        if (b == null) throw new IOException("truncated int");
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
