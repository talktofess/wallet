package com.wallet.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;

import com.wallet.core.crypto.VaultCrypto;

/**
 * The on-device encrypted store. A passphrase is stretched into an AES-256 key
 * (the salt is persisted; the key never is), and every saved item — downloaded
 * media included — is sealed with {@link VaultCrypto} before it touches disk.
 * Files live in the app's private {@code filesDir}, so nothing is world-readable.
 */
public final class VaultStore {

    private final File dir;
    private final File saltFile;
    private SecretKey key;   // held only while unlocked

    public VaultStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "vault");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        saltFile = new File(dir, ".salt");
    }

    public boolean isInitialised() {
        return saltFile.exists();
    }

    public boolean isUnlocked() {
        return key != null;
    }

    /** Derive the key from the passphrase, creating the salt on first use. */
    public void unlock(char[] passphrase) throws IOException {
        byte[] salt;
        if (saltFile.exists()) {
            salt = readAll(saltFile);
        } else {
            salt = VaultCrypto.newSalt();
            writeAll(saltFile, salt);
        }
        key = VaultCrypto.deriveKey(passphrase, salt, VaultCrypto.DEFAULT_ITERATIONS);
    }

    public void lock() {
        key = null;
    }

    public void put(String name, byte[] plaintext) throws IOException {
        requireUnlocked();
        writeAll(entryFile(name), VaultCrypto.seal(key, plaintext));
    }

    /** Encrypt a stream into the vault without holding it all in memory twice. */
    public void put(String name, InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
        put(name, buf.toByteArray());
    }

    public byte[] get(String name) throws Exception {
        requireUnlocked();
        return VaultCrypto.open(key, readAll(entryFile(name)));
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".v")) out.add(decodeName(n.substring(0, n.length() - 2)));
            }
        }
        return out;
    }

    // --- helpers -------------------------------------------------------------

    private File entryFile(String name) {
        return new File(dir, encodeName(name) + ".v");
    }

    private static String encodeName(String name) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeName(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private void requireUnlocked() {
        if (key == null) throw new IllegalStateException("vault is locked");
    }

    private static byte[] readAll(File f) throws IOException {
        return Files.readAllBytes(f.toPath());
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
