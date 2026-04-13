package org.schabi.newpipe.gif;

import android.graphics.Bitmap;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Animated WebP encoder. Compresses individual frames via Bitmap.compress,
 * then parses out the VP8/VP8L bitstream chunks and assembles them into
 * a RIFF-based animated WebP container per the WebP spec.
 *
 * optimize=true uses lossy VP8 at quality 75.
 * optimize=false uses lossless VP8L at quality 100.
 */
@SuppressWarnings("deprecation")
public final class WebPEncoder {

    private static final int LOSSY_QUALITY = 75;
    private static final int LOSSLESS_QUALITY = 100;

    private WebPEncoder() {
    }

    public static byte[] encode(final List<Bitmap> frames,
                                final boolean optimize) throws IOException {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }

        final int width = frames.get(0).getWidth();
        final int height = frames.get(0).getHeight();
        final int frameDurationMs = 67; // 15 fps
        final int quality = optimize ? LOSSY_QUALITY : LOSSLESS_QUALITY;
        final Bitmap.CompressFormat format = pickFormat(optimize);

        final ByteArrayOutputStream riff = new ByteArrayOutputStream();

        // 12-byte RIFF header placeholder (filled at the end)
        riff.write(new byte[12]);

        // VP8X extended header: animation flag set
        writeChunkHeader(riff, "VP8X", 10);
        writeLe32(riff, 0x02); // flags byte: bit 1 = animation
        writeLe24(riff, width - 1);
        writeLe24(riff, height - 1);

        // ANIM global animation parameters
        writeChunkHeader(riff, "ANIM", 6);
        writeLe32(riff, 0); // background color BGRA (transparent)
        writeLe16(riff, 0); // loop count 0 = infinite

        for (final Bitmap frame : frames) {
            final byte[] framePayload = extractFramePayload(frame, format, quality);

            final int anmfDataSize = 16 + framePayload.length;
            writeChunkHeader(riff, "ANMF", anmfDataSize);
            writeLe24(riff, 0); // frame X / 2
            writeLe24(riff, 0); // frame Y / 2
            writeLe24(riff, width - 1);
            writeLe24(riff, height - 1);
            writeLe24(riff, frameDurationMs);
            riff.write(0); // flags: no alpha blending, no disposal

            riff.write(framePayload);

            // RIFF chunks must be even-aligned
            if (anmfDataSize % 2 != 0) {
                riff.write(0);
            }
        }

        final byte[] result = riff.toByteArray();
        fillRiffHeader(result);
        return result;
    }

    // Compress a single frame and extract only the VP8/VP8L/ALPH chunks,
    // skipping VP8X and other extended chunks not valid inside ANMF.
    private static byte[] extractFramePayload(final Bitmap frame,
                                              final Bitmap.CompressFormat fmt,
                                              final int quality) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        frame.compress(fmt, quality, buf);
        final byte[] raw = buf.toByteArray();

        // Expect a RIFF/WEBP file
        if (raw.length < 12 || raw[0] != 'R' || raw[1] != 'I'
                || raw[2] != 'F' || raw[3] != 'F') {
            throw new IOException("Bitmap.compress did not produce a valid WebP file");
        }

        // Walk the inner chunks starting after RIFF header (12 bytes).
        // Keep only VP8, VP8L, and ALPH chunks (valid ANMF frame data).
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int pos = 12;
        while (pos + 8 <= raw.length) {
            final String tag = new String(raw, pos, 4, StandardCharsets.US_ASCII);
            final int chunkDataSize = readLe32(raw, pos + 4);
            // total bytes for this chunk: 8 (header) + data + optional pad
            final int paddedDataSize = chunkDataSize + (chunkDataSize % 2);
            final int chunkTotalSize = 8 + paddedDataSize;

            if ("VP8 ".equals(tag) || "VP8L".equals(tag) || "ALPH".equals(tag)) {
                final int bytesToCopy = Math.min(chunkTotalSize, raw.length - pos);
                payload.write(raw, pos, bytesToCopy);
            }
            pos += chunkTotalSize;
        }

        if (payload.size() == 0) {
            throw new IOException("No VP8/VP8L data found in compressed frame");
        }
        return payload.toByteArray();
    }

    private static Bitmap.CompressFormat pickFormat(final boolean optimize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return optimize
                    ? Bitmap.CompressFormat.WEBP_LOSSY
                    : Bitmap.CompressFormat.WEBP_LOSSLESS;
        }
        return Bitmap.CompressFormat.WEBP;
    }

    private static void fillRiffHeader(final byte[] result) {
        final int fileSize = result.length - 8;
        result[0] = 'R';
        result[1] = 'I';
        result[2] = 'F';
        result[3] = 'F';
        result[4] = (byte) (fileSize & 0xFF);
        result[5] = (byte) ((fileSize >> 8) & 0xFF);
        result[6] = (byte) ((fileSize >> 16) & 0xFF);
        result[7] = (byte) ((fileSize >> 24) & 0xFF);
        result[8] = 'W';
        result[9] = 'E';
        result[10] = 'B';
        result[11] = 'P';
    }

    private static int readLe32(final byte[] data, final int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeChunkHeader(final ByteArrayOutputStream out,
                                         final String fourCC,
                                         final int size) {
        for (final char c : fourCC.toCharArray()) {
            out.write(c);
        }
        writeLe32(out, size);
    }

    private static void writeLe16(final ByteArrayOutputStream out, final int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLe24(final ByteArrayOutputStream out, final int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
    }

    private static void writeLe32(final ByteArrayOutputStream out, final int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
