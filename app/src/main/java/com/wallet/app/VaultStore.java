package com.wallet.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

import com.wallet.core.crypto.SecretStream;
import com.wallet.core.crypto.VaultCrypto;
import com.wallet.core.util.UniqueNames;
import com.wallet.core.vault.VaultIndex;

/**
 * The encrypted store. A passphrase derives an AES-256 key (only the PBKDF2 salt
 * is persisted). Each file is written with {@link SecretStream} — chunked,
 * authenticated, streamed so even a large video never sits in memory twice. An
 * encrypted {@link VaultIndex} keeps the catalogue (name, MIME, size, source),
 * so the file list itself is private. Everything lives in the app's private
 * {@code filesDir}.
 */
public final class VaultStore {

    private final File dir;
    private final File saltFile;
    private final File indexFile;
    private SecretKey key;
    private VaultIndex index = new VaultIndex();

    public VaultStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "vault");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        saltFile = new File(dir, ".salt");
        indexFile = new File(dir, ".index");
    }

    public boolean isInitialised() { return saltFile.exists(); }
    public boolean isUnlocked() { return key != null; }
    public void lock() { key = null; index = new VaultIndex(); }

    public void unlock(char[] passphrase) throws Exception {
        byte[] salt;
        if (saltFile.exists()) {
            salt = Files.readAllBytes(saltFile.toPath());
        } else {
            salt = VaultCrypto.newSalt();
            writeAll(saltFile, salt);
        }
        key = VaultCrypto.deriveKey(passphrase, salt, VaultCrypto.DEFAULT_ITERATIONS);
        loadIndex();
    }

    /** Stream-encrypt {@code in} into the vault, recording metadata. Returns the (unique) saved name. */
    public String put(String desiredName, InputStream in, String mime, String sourceUrl) throws Exception {
        requireUnlocked();
        String name = UniqueNames.make(desiredName, existingNames());

        CountingInputStream counting = new CountingInputStream(in);
        try (OutputStream out = new FileOutputStream(blobFile(name))) {
            SecretStream.encrypt(key, counting, out, SecretStream.DEFAULT_CHUNK);
        }

        index.add(new VaultIndex.Item(name, name, mime, counting.count(), sourceUrl, System.currentTimeMillis()));
        saveIndex();
        return name;
    }

    /** Decrypt a stored item to {@code out}. */
    public void getTo(String name, OutputStream out) throws Exception {
        requireUnlocked();
        try (InputStream in = new FileInputStream(blobFile(name))) {
            SecretStream.decrypt(key, in, out);
        }
    }

    public byte[] get(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        getTo(name, out);
        return out.toByteArray();
    }

    /** Seal a small named blob (e.g. the serialized download queue) into the vault. */
    public void saveBlob(String name, byte[] data) throws Exception {
        requireUnlocked();
        writeAll(new File(dir, ".blob-" + name), VaultCrypto.seal(key, data));
    }

    /** Open a blob saved with {@link #saveBlob}, or null if it doesn't exist. */
    public byte[] loadBlobOrNull(String name) throws Exception {
        requireUnlocked();
        File f = new File(dir, ".blob-" + name);
        if (!f.exists()) return null;
        return VaultCrypto.open(key, Files.readAllBytes(f.toPath()));
    }

    public List<VaultIndex.Item> items() {
        return index.items();
    }

    public List<String> list() {
        List<String> names = new ArrayList<>();
        for (VaultIndex.Item i : index.items()) names.add(i.name);
        return names;
    }

    // --- internals -----------------------------------------------------------

    private Set<String> existingNames() {
        Set<String> taken = new HashSet<>();
        for (VaultIndex.Item i : index.items()) taken.add(i.name);
        return taken;
    }

    private File blobFile(String name) {
        String enc = Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return new File(dir, enc + ".v");
    }

    private void loadIndex() throws Exception {
        if (indexFile.exists()) {
            index = VaultIndex.parse(VaultCrypto.open(key, Files.readAllBytes(indexFile.toPath())));
        } else {
            index = new VaultIndex();
        }
    }

    private void saveIndex() throws IOException {
        writeAll(indexFile, VaultCrypto.seal(key, index.serialize()));
    }

    private void requireUnlocked() {
        if (key == null) throw new IllegalStateException("vault is locked");
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** Counts plaintext bytes as they stream through, so we can record the true size. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count = 0;
        CountingInputStream(InputStream in) { super(in); }
        long count() { return count; }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
