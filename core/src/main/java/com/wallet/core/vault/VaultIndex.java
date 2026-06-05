package com.wallet.core.vault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The vault's metadata catalogue: one {@link Item} per stored file (name, MIME,
 * size, source URL, timestamp). It serialises to bytes that the caller seals with
 * {@code VaultCrypto}/{@code SecretStream}, so the catalogue itself is encrypted
 * at rest — the file list, not just the files, is private. Fields are Base64-wrapped
 * so names with tabs/newlines survive the record format.
 */
public final class VaultIndex {

    public static final class Item {
        public final String id;
        public final String name;
        public final String mime;
        public final long size;
        public final String sourceUrl;
        public final long createdAt;

        public Item(String id, String name, String mime, long size, String sourceUrl, long createdAt) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.sourceUrl = sourceUrl;
            this.createdAt = createdAt;
        }
    }

    private final List<Item> items = new ArrayList<>();

    public void add(Item item) { items.add(item); }
    public List<Item> items() { return new ArrayList<>(items); }
    public int size() { return items.size(); }

    public Item byId(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }

    public boolean remove(String id) {
        return items.removeIf(i -> i.id.equals(id));
    }

    public byte[] serialize() {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) {
            sb.append(b64(i.id)).append('\t')
              .append(b64(i.name)).append('\t')
              .append(b64(i.mime)).append('\t')
              .append(i.size).append('\t')
              .append(b64(i.sourceUrl)).append('\t')
              .append(i.createdAt).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static VaultIndex parse(byte[] data) {
        VaultIndex idx = new VaultIndex();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 6) continue;
            idx.add(new Item(unb64(f[0]), unb64(f[1]), unb64(f[2]),
                    Long.parseLong(f[3]), unb64(f[4]), Long.parseLong(f[5])));
        }
        return idx;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
