package com.wallet.core.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A minimal MPEG-TS demuxer: walks the 188-byte packet grid, validates the 0x47
 * sync byte, and extracts each packet's PID, payload-unit-start flag, and payload
 * (after any adaptation field). This is the demux half of turning concatenated
 * HLS {@code .ts} into a clean container; the actual {@code .ts -> .mp4} remux on
 * device is done with Android's {@code MediaExtractor}/{@code MediaMuxer} (see the
 * app module). Useful here for validating and inspecting a downloaded stream.
 */
public final class MpegTs {

    public static final int PACKET_SIZE = 188;
    public static final int SYNC_BYTE = 0x47;

    public static final class Packet {
        public final int pid;
        public final boolean payloadStart;
        public final byte[] payload;

        Packet(int pid, boolean payloadStart, byte[] payload) {
            this.pid = pid;
            this.payloadStart = payloadStart;
            this.payload = payload;
        }
    }

    private MpegTs() {}

    public static boolean looksLikeTs(byte[] data) {
        return data.length >= PACKET_SIZE && (data[0] & 0xFF) == SYNC_BYTE;
    }

    public static List<Packet> parse(byte[] data) {
        List<Packet> out = new ArrayList<>();
        for (int off = 0; off + PACKET_SIZE <= data.length; off += PACKET_SIZE) {
            if ((data[off] & 0xFF) != SYNC_BYTE) {
                throw new IllegalArgumentException("lost TS sync at byte " + off);
            }
            int b1 = data[off + 1] & 0xFF;
            int b2 = data[off + 2] & 0xFF;
            int b3 = data[off + 3] & 0xFF;

            int pid = ((b1 & 0x1F) << 8) | b2;
            boolean payloadStart = (b1 & 0x40) != 0;
            int adaptationControl = (b3 >> 4) & 0x3;
            boolean hasAdaptation = (adaptationControl & 0x2) != 0;
            boolean hasPayload = (adaptationControl & 0x1) != 0;

            int p = off + 4;
            if (hasAdaptation) {
                int afLen = data[p] & 0xFF;
                p += 1 + afLen;
            }
            byte[] payload = (hasPayload && p < off + PACKET_SIZE)
                    ? Arrays.copyOfRange(data, p, off + PACKET_SIZE)
                    : new byte[0];

            out.add(new Packet(pid, payloadStart, payload));
        }
        return out;
    }

    /** The distinct PIDs present, in first-seen order. */
    public static Set<Integer> pids(byte[] data) {
        Set<Integer> set = new LinkedHashSet<>();
        for (Packet p : parse(data)) set.add(p.pid);
        return set;
    }
}
