package org.schabi.newpipe.gif;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.util.List;

import io.nickolasbailey.animatedgifencoder.AnimatedGifEncoder;

public final class GifEncoder {

    private static final int FPS = 10;
    private static final int DELAY_MS = 1000 / FPS;

    private GifEncoder() {
    }

    public static byte[] encode(final List<Bitmap> frames) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AnimatedGifEncoder encoder = new AnimatedGifEncoder();

        encoder.start(out);
        encoder.setDelay(DELAY_MS);
        encoder.setRepeat(0);
        encoder.setQuality(1);

        for (final Bitmap frame : frames) {
            encoder.addFrame(frame);
        }

        encoder.finish();
        return out.toByteArray();
    }
}
