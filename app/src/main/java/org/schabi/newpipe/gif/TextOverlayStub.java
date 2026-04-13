package org.schabi.newpipe.gif;

import android.graphics.Bitmap;

import java.util.List;

public final class TextOverlayStub {

    private TextOverlayStub() {
    }

    // Returns frames unmodified. Will apply text overlay in v2.
    public static List<Bitmap> apply(final List<Bitmap> frames) {
        return frames;
    }
}
