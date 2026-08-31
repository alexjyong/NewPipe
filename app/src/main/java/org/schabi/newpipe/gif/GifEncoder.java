package org.schabi.newpipe.gif;

import android.graphics.Bitmap;

import com.squareup.gifencoder.ImageOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GifEncoder {

    private static final int FPS = 10;
    private static final int DELAY_MS = 1000 / FPS;

    private GifEncoder() {
    }

    public static byte[] encode(final List<Bitmap> frames) throws IOException {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }

        final Bitmap first = frames.get(0);
        final int[][] rgbFrames = new int[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            rgbFrames[i] = toRgbPixels(frames.get(i));
        }
        return encodeFrames(first.getWidth(), first.getHeight(), rgbFrames);
    }

    static byte[] encodeFrames(final int width, final int height,
                               final int[][] rgbFrames) throws IOException {
        if (rgbFrames.length == 0) {
            throw new IllegalArgumentException("No frames to encode");
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final com.squareup.gifencoder.GifEncoder encoder =
                new com.squareup.gifencoder.GifEncoder(out, width, height, 0);
        final ImageOptions options =
                new ImageOptions().setDelay(DELAY_MS, TimeUnit.MILLISECONDS);
        for (final int[] rgbData : rgbFrames) {
            encoder.addImage(rgbData, width, options);
        }
        encoder.finishEncoding();
        return out.toByteArray();
    }

    private static int[] toRgbPixels(final Bitmap frame) {
        final int[] pixels = new int[frame.getWidth() * frame.getHeight()];
        frame.getPixels(pixels, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
        return pixels;
    }
}
