package org.schabi.newpipe.gif;

import android.graphics.Bitmap;

import com.squareup.gifencoder.FloydSteinbergDitherer;
import com.squareup.gifencoder.ImageOptions;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GifEncoder {

    private static final int FPS = 10;
    private static final int DELAY_MS = 1000 / FPS;

    private GifEncoder() {
    }

    public static byte[] encode(final List<Bitmap> frames,
                                final boolean optimize) throws Exception {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }

        final int width = frames.get(0).getWidth();
        final int height = frames.get(0).getHeight();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final ImageOptions options = new ImageOptions();
        options.setDelay(DELAY_MS, TimeUnit.MILLISECONDS);
        options.setDitherer(FloydSteinbergDitherer.INSTANCE);

        final com.squareup.gifencoder.GifEncoder encoder =
                new com.squareup.gifencoder.GifEncoder(out, width, height, 0);

        for (final Bitmap frame : frames) {
            encoder.addImage(bitmapToRgbArray(frame), options);
        }

        encoder.finishEncoding();
        return out.toByteArray();
    }

    private static int[][] bitmapToRgbArray(final Bitmap bitmap) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int[][] rgbArray = new int[height][width];

        final int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int y = 0; y < height; y++) {
            System.arraycopy(pixels, y * width, rgbArray[y], 0, width);
        }
        return rgbArray;
    }
}
