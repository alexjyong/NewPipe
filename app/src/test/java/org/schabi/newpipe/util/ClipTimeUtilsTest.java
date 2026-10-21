package org.schabi.newpipe.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClipTimeUtilsTest {

    private static final float EPSILON = 0.0001f;

    @Test
    public void formatIsReTypeable() {
        assertEquals("0:00.0", ClipTimeUtils.formatClipTime(0.0f));
        assertEquals("0:05.0", ClipTimeUtils.formatClipTime(5.0f));
        assertEquals("1:15.3", ClipTimeUtils.formatClipTime(75.3f));
        assertEquals("1:02:03.5", ClipTimeUtils.formatClipTime(3723.5f));
        assertEquals("9:59:59.9", ClipTimeUtils.formatClipTime(35999.9f));
    }

    @Test
    public void truncateName() {
        assertEquals("short.gif", ClipTimeUtils.truncateName("short.gif", 50));
        assertEquals("a-very-long-video-title-that-keeps-going",
                ClipTimeUtils.truncateName(
                        "a-very-long-video-title-that-keeps-going-and-going", 40));
    }

    @Test
    public void estimateClipBytesLandscape() {
        final long bytes = ClipTimeUtils.estimateClipBytes(10f, 1280, 720, 480, 15f);
        assertEquals(150L * 480 * 270 * 4, bytes);
    }

    @Test
    public void estimateClipBytesPortrait() {
        final long bytes = ClipTimeUtils.estimateClipBytes(10f, 720, 1280, 480, 15f);
        assertEquals(150L * 480 * 853 * 4, bytes);
    }

    @Test
    public void estimateClipBytesInvalidInput() {
        assertEquals(0L, ClipTimeUtils.estimateClipBytes(0f, 1280, 720, 480, 15f));
        assertEquals(0L, ClipTimeUtils.estimateClipBytes(10f, 0, 720, 480, 15f));
        assertEquals(0L, ClipTimeUtils.estimateClipBytes(10f, 1280, 720, 0, 0f));
    }

    @Test
    public void maxClipSecondsBoundsEstimate() {
        final long budget = 160L * 1024 * 1024;
        final float maxSeconds = ClipTimeUtils.maxClipSeconds(720, 1280, 480, 15f, budget);
        final long estimate = ClipTimeUtils.estimateClipBytes(
                maxSeconds, 720, 1280, 480, 15f);
        assertTrue(estimate <= budget);
        assertTrue(maxSeconds > 0f);
    }

    @Test
    public void maxClipSecondsEstimateStaysWithinBudget() {
        // grid over budgets and orientations: the returned duration is the largest
        // 0.1 s step whose own estimate stays within the budget at every boundary
        final long bytesPerFrame = 150L * 480 * 853 * 4;
        final long budgetMb = (long) (1.6 * bytesPerFrame / (1024 * 1024));
        assertTrue(ClipTimeUtils.estimateClipBytes(
                ClipTimeUtils.maxClipSeconds(720, 1280, 480, 15f,
                        budgetMb * 1024 * 1024),
                720, 1280, 480, 15f) <= budgetMb * 1024 * 1024);
        for (final int[] dims : new int[][]{{1280, 720}, {720, 1280}}) {
            for (final float fps : new float[]{10f, 15f}) {
                for (long budget = 1024L * 1024;
                        budget <= 512L * 1024 * 1024;
                        budget += 1024L * 1024) {
                    final float maxSeconds = ClipTimeUtils.maxClipSeconds(
                            dims[0], dims[1], 480, fps, budget);
                    assertTrue("budget " + budget + " dims " + dims[0] + "x" + dims[1]
                                    + " fps " + fps + " exceeded",
                            ClipTimeUtils.estimateClipBytes(maxSeconds, dims[0], dims[1],
                                    480, fps) <= budget);
                }
            }
        }
    }

    @Test
    public void maxClipSecondsInvalidInput() {
        assertEquals(0.0f, ClipTimeUtils.maxClipSeconds(0, 1280, 480, 15f,
                1024L), EPSILON);
        assertEquals(0.0f, ClipTimeUtils.maxClipSeconds(720, 1280, 480, 15f,
                0L), EPSILON);
    }
}
