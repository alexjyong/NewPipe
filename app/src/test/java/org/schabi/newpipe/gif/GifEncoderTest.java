package org.schabi.newpipe.gif;

import org.junit.Test;

import java.util.ArrayList;

public class GifEncoderTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyFrames() {
        GifEncoder.encode(new ArrayList<>());
    }
}
