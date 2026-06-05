package com.wallet.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Remuxes a concatenated HLS {@code .ts} (or any container Android can read) into
 * a clean {@code .mp4} <b>without re-encoding</b>: {@link MediaExtractor} reads
 * the elementary streams and {@link MediaMuxer} writes them straight into an MP4
 * container. This is the on-device, FFmpeg-free path — fast and lossless, since
 * samples are copied, not transcoded.
 */
public final class TsRemuxer {

    private TsRemuxer() {}

    public static void remux(File input, File output) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackCount = extractor.getTrackCount();
            int[] outputTrack = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i);
                    outputTrack[i] = muxer.addTrack(format);
                } else {
                    outputTrack[i] = -1;
                }
            }

            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(1 << 20);   // 1 MiB sample buffer
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                int track = extractor.getSampleTrackIndex();
                if (outputTrack[track] >= 0) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(outputTrack[track], buffer, info);
                }
                extractor.advance();
            }
            muxer.stop();
        } finally {
            extractor.release();
            if (muxer != null) {
                try { muxer.release(); } catch (IllegalStateException ignored) { /* never started */ }
            }
        }
    }
}
