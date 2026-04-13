package org.schabi.newpipe.gif;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Pure-Java GIF89a animated encoder.
 * When optimize=true, builds a global 256-color palette via median-cut
 * quantization. Otherwise uses a per-frame local palette.
 */
public final class GifEncoder {

    private static final int MAX_COLORS = 256;

    private GifEncoder() {
    }

    public static byte[] encode(final List<Bitmap> frames,
                                final boolean optimize) throws IOException {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }

        final int width = frames.get(0).getWidth();
        final int height = frames.get(0).getHeight();
        // 10 fps = 100ms per frame = 10 in GIF hundredths-of-a-second units
        final int delayCs = 10;

        final int[][] globalPalette;
        if (optimize) {
            globalPalette = buildGlobalPalette(frames);
        } else {
            globalPalette = null;
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeHeader(out);
        writeLogicalScreenDescriptor(out, width, height, globalPalette != null);
        if (globalPalette != null) {
            writeColorTable(out, globalPalette);
        }
        writeNetscapeExtension(out);

        for (final Bitmap frame : frames) {
            final int[] pixels = new int[width * height];
            frame.getPixels(pixels, 0, width, 0, 0, width, height);

            final int[][] palette;
            if (globalPalette != null) {
                palette = globalPalette;
            } else {
                palette = buildFramePalette(pixels);
            }

            final byte[] indexedPixels = quantizePixels(pixels, palette);

            writeGraphicControlExtension(out, delayCs);
            writeImageDescriptor(out, width, height, globalPalette == null);
            if (globalPalette == null) {
                writeColorTable(out, palette);
            }
            writeLzwCompressed(out, indexedPixels, 8);
        }

        writeTrailer(out);
        return out.toByteArray();
    }

    private static int[][] buildGlobalPalette(final List<Bitmap> frames) {
        // Sample pixels from all frames for palette generation
        final int sampleSize = Math.min(10000, frames.size() * 1000);
        final int[][] colorSamples = new int[sampleSize][3];
        int idx = 0;

        for (final Bitmap frame : frames) {
            final int w = frame.getWidth();
            final int h = frame.getHeight();
            final int pixelsPerFrame = sampleSize / frames.size();
            final int step = Math.max(1, (w * h) / pixelsPerFrame);

            for (int i = 0; i < w * h && idx < sampleSize; i += step) {
                final int pixel = frame.getPixel(i % w, i / w);
                colorSamples[idx][0] = (pixel >> 16) & 0xFF;
                colorSamples[idx][1] = (pixel >> 8) & 0xFF;
                colorSamples[idx][2] = pixel & 0xFF;
                idx++;
            }
        }

        return medianCut(colorSamples, idx, MAX_COLORS);
    }

    private static int[][] buildFramePalette(final int[] pixels) {
        final int sampleSize = Math.min(pixels.length, 10000);
        final int[][] samples = new int[sampleSize][3];
        final int step = Math.max(1, pixels.length / sampleSize);

        int idx = 0;
        for (int i = 0; i < pixels.length && idx < sampleSize; i += step) {
            samples[idx][0] = (pixels[i] >> 16) & 0xFF;
            samples[idx][1] = (pixels[i] >> 8) & 0xFF;
            samples[idx][2] = pixels[i] & 0xFF;
            idx++;
        }

        return medianCut(samples, idx, MAX_COLORS);
    }

    private static int[][] medianCut(final int[][] colors, final int count,
                                     final int targetColors) {
        if (count == 0) {
            final int[][] palette = new int[MAX_COLORS][3];
            return palette;
        }

        final List<int[]> bucket = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bucket.add(colors[i]);
        }

        final List<List<int[]>> buckets = new java.util.ArrayList<>();
        buckets.add(bucket);

        while (buckets.size() < targetColors) {
            int maxRange = -1;
            int splitIdx = 0;

            for (int i = 0; i < buckets.size(); i++) {
                final List<int[]> b = buckets.get(i);
                if (b.size() < 2) {
                    continue;
                }
                final int range = findMaxRange(b);
                if (range > maxRange) {
                    maxRange = range;
                    splitIdx = i;
                }
            }

            if (maxRange <= 0) {
                break;
            }

            final List<int[]> toSplit = buckets.remove(splitIdx);
            final int channel = findMaxRangeChannel(toSplit);
            toSplit.sort((a, b) -> Integer.compare(a[channel], b[channel]));

            final int mid = toSplit.size() / 2;
            buckets.add(new java.util.ArrayList<>(toSplit.subList(0, mid)));
            buckets.add(new java.util.ArrayList<>(toSplit.subList(mid, toSplit.size())));
        }

        final int[][] palette = new int[MAX_COLORS][3];
        for (int i = 0; i < buckets.size() && i < MAX_COLORS; i++) {
            final List<int[]> b = buckets.get(i);
            long rSum = 0;
            long gSum = 0;
            long bSum = 0;
            for (final int[] c : b) {
                rSum += c[0];
                gSum += c[1];
                bSum += c[2];
            }
            palette[i][0] = (int) (rSum / b.size());
            palette[i][1] = (int) (gSum / b.size());
            palette[i][2] = (int) (bSum / b.size());
        }

        return palette;
    }

    private static int findMaxRange(final List<int[]> colors) {
        int maxRange = 0;
        for (int ch = 0; ch < 3; ch++) {
            int min = 255;
            int max = 0;
            for (final int[] c : colors) {
                min = Math.min(min, c[ch]);
                max = Math.max(max, c[ch]);
            }
            maxRange = Math.max(maxRange, max - min);
        }
        return maxRange;
    }

    private static int findMaxRangeChannel(final List<int[]> colors) {
        int maxRange = 0;
        int maxCh = 0;
        for (int ch = 0; ch < 3; ch++) {
            int min = 255;
            int max = 0;
            for (final int[] c : colors) {
                min = Math.min(min, c[ch]);
                max = Math.max(max, c[ch]);
            }
            if (max - min > maxRange) {
                maxRange = max - min;
                maxCh = ch;
            }
        }
        return maxCh;
    }

    private static byte[] quantizePixels(final int[] pixels, final int[][] palette) {
        final byte[] indexed = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            final int r = (pixels[i] >> 16) & 0xFF;
            final int g = (pixels[i] >> 8) & 0xFF;
            final int b = pixels[i] & 0xFF;
            indexed[i] = (byte) findClosestColor(r, g, b, palette);
        }
        return indexed;
    }

    private static int findClosestColor(final int r, final int g, final int b,
                                        final int[][] palette) {
        int minDist = Integer.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < MAX_COLORS; i++) {
            final int dr = r - palette[i][0];
            final int dg = g - palette[i][1];
            final int db = b - palette[i][2];
            final int dist = dr * dr + dg * dg + db * db;
            if (dist < minDist) {
                minDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    // GIF format writing methods

    private static void writeHeader(final ByteArrayOutputStream out) {
        writeString(out, "GIF89a");
    }

    private static void writeLogicalScreenDescriptor(final ByteArrayOutputStream out,
                                                     final int width, final int height,
                                                     final boolean hasGlobalTable) {
        writeShort(out, width);
        writeShort(out, height);
        // packed field: global color table flag, color resolution (7), sort flag, size of GCT
        final int packed = hasGlobalTable ? 0xF7 : 0x00;
        out.write(packed);
        out.write(0); // background color index
        out.write(0); // pixel aspect ratio
    }

    private static void writeColorTable(final ByteArrayOutputStream out,
                                        final int[][] palette) {
        for (int i = 0; i < MAX_COLORS; i++) {
            out.write(palette[i][0]);
            out.write(palette[i][1]);
            out.write(palette[i][2]);
        }
    }

    private static void writeNetscapeExtension(final ByteArrayOutputStream out) {
        // loop forever
        out.write(0x21); // extension introducer
        out.write(0xFF); // application extension label
        out.write(11);   // block size
        writeString(out, "NETSCAPE2.0");
        out.write(3);    // sub-block size
        out.write(1);    // sub-block ID
        writeShort(out, 0); // loop count (0 = infinite)
        out.write(0);    // block terminator
    }

    private static void writeGraphicControlExtension(final ByteArrayOutputStream out,
                                                     final int delayCs) {
        out.write(0x21); // extension introducer
        out.write(0xF9); // graphic control label
        out.write(4);    // block size
        out.write(0);    // packed byte (no transparency, no disposal)
        writeShort(out, delayCs);
        out.write(0);    // transparent color index
        out.write(0);    // block terminator
    }

    private static void writeImageDescriptor(final ByteArrayOutputStream out,
                                             final int width, final int height,
                                             final boolean hasLocalTable) {
        out.write(0x2C); // image separator
        writeShort(out, 0); // left
        writeShort(out, 0); // top
        writeShort(out, width);
        writeShort(out, height);
        // packed: local color table flag, interlace, sort, size
        final int packed = hasLocalTable ? 0x87 : 0x00;
        out.write(packed);
    }

    private static void writeLzwCompressed(final ByteArrayOutputStream out,
                                           final byte[] pixels,
                                           final int minCodeSize) throws IOException {
        out.write(minCodeSize);

        final int clearCode = 1 << minCodeSize;
        final int eoiCode = clearCode + 1;
        final int tableStart = eoiCode + 1;

        final ByteArrayOutputStream subBlockBuffer = new ByteArrayOutputStream();
        final int currentBits = 0;
        int currentByte = 0;
        int bitPos = 0;
        int codeSize = minCodeSize + 1;

        // Simple LZW: reset table frequently to keep it small
        int nextCode = tableStart;
        final int maxTableSize = 4096;

        // Use a simple hash table for LZW dictionary
        final java.util.HashMap<Long, Integer> table = new java.util.HashMap<>();

        // Write clear code
        currentByte = writeBits(subBlockBuffer, clearCode, codeSize,
                currentByte, bitPos);
        bitPos = (bitPos + codeSize) % 8;

        int prev = pixels[0] & 0xFF;

        for (int i = 1; i < pixels.length; i++) {
            final int curr = pixels[i] & 0xFF;
            final long key = ((long) prev << 12) | curr;

            if (table.containsKey(key)) {
                prev = table.get(key);
            } else {
                currentByte = writeBits(subBlockBuffer, prev, codeSize,
                        currentByte, bitPos);
                bitPos = (bitPos + codeSize) % 8;

                if (nextCode < maxTableSize) {
                    table.put(key, nextCode++);
                    if (nextCode > (1 << codeSize) && codeSize < 12) {
                        codeSize++;
                    }
                } else {
                    // Table full, emit clear code and reset
                    currentByte = writeBits(subBlockBuffer, clearCode, codeSize,
                            currentByte, bitPos);
                    bitPos = (bitPos + codeSize) % 8;
                    table.clear();
                    nextCode = tableStart;
                    codeSize = minCodeSize + 1;
                }

                prev = curr;
            }
        }

        // Write remaining code
        currentByte = writeBits(subBlockBuffer, prev, codeSize,
                currentByte, bitPos);
        bitPos = (bitPos + codeSize) % 8;

        // Write EOI
        currentByte = writeBits(subBlockBuffer, eoiCode, codeSize,
                currentByte, bitPos);
        bitPos = (bitPos + codeSize) % 8;

        // Flush remaining bits
        if (bitPos > 0) {
            subBlockBuffer.write(currentByte);
        }

        // Write sub-blocks (max 255 bytes each)
        final byte[] data = subBlockBuffer.toByteArray();
        int offset = 0;
        while (offset < data.length) {
            final int blockSize = Math.min(255, data.length - offset);
            out.write(blockSize);
            out.write(data, offset, blockSize);
            offset += blockSize;
        }

        out.write(0); // block terminator
    }

    private static int writeBits(final ByteArrayOutputStream out,
                                 final int code, final int codeSize,
                                 final int currentByte, final int bitPos) {
        int bitsToWrite = codeSize;
        int codeVal = code;
        int pos = bitPos;
        int byteVal = currentByte;

        while (bitsToWrite > 0) {
            byteVal |= (codeVal & 1) << pos;
            codeVal >>= 1;
            pos++;
            bitsToWrite--;

            if (pos == 8) {
                out.write(byteVal);
                byteVal = 0;
                pos = 0;
            }
        }

        return byteVal;
    }

    private static void writeTrailer(final ByteArrayOutputStream out) {
        out.write(0x3B);
    }

    private static void writeShort(final ByteArrayOutputStream out, final int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeString(final ByteArrayOutputStream out, final String s) {
        for (final char c : s.toCharArray()) {
            out.write(c);
        }
    }
}
