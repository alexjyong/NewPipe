package org.schabi.newpipe.gif;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GifEncoderTest {

    private static int[] solid(final int width, final int height, final int rgb) {
        final int[] pixels = new int[width * height];
        Arrays.fill(pixels, 0xFF000000 | rgb);
        return pixels;
    }

    @Test
    public void writesGif89aHeaderAndLoopingExtension() throws IOException {
        final int[][] frames = {solid(2, 2, 0xFF0000), solid(2, 2, 0x00FF00)};
        final byte[] gif = GifEncoder.encodeFrames(2, 2, frames);

        assertEquals("GIF89a",
                new String(gif, 0, 6, StandardCharsets.US_ASCII));
        assertTrue(gif.length > 20);
        assertTrue(new String(gif, 0, Math.min(gif.length, 32), StandardCharsets.US_ASCII)
                .contains("NETSCAPE"));
    }

    @Test
    public void encodingIsDeterministic() throws IOException {
        final int[][] frames = {solid(2, 2, 0xFF0000), solid(2, 2, 0x00FF00),
            solid(2, 2, 0x0000FF)};
        assertTrue(Arrays.equals(GifEncoder.encodeFrames(2, 2, frames),
                GifEncoder.encodeFrames(2, 2, frames)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyFrames() throws IOException {
        GifEncoder.encodeFrames(2, 2, new int[0][]);
    }
}
