package org.schabi.newpipe.util;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClipTimeUtilsTest {

    private static final float EPSILON = 0.0001f;

    @Test
    public void parseValidSeconds() {
        assertEquals(0.0f, ClipTimeUtils.parseClipTime("0.0s"), EPSILON);
        assertEquals(5.0f, ClipTimeUtils.parseClipTime("5.0s"), EPSILON);
        assertEquals(5.3f, ClipTimeUtils.parseClipTime("5.3s"), EPSILON);
        assertEquals(75.3f, ClipTimeUtils.parseClipTime("75.3s"), EPSILON);
        assertEquals(35999.9f, ClipTimeUtils.parseClipTime("35999.9s"), EPSILON);
    }

    @Test
    public void parseValidColonFormats() {
        assertEquals(5.0f, ClipTimeUtils.parseClipTime("0:05.0"), EPSILON);
        assertEquals(5.3f, ClipTimeUtils.parseClipTime("0:05.3"), EPSILON);
        assertEquals(83.5f, ClipTimeUtils.parseClipTime("1:23.5"), EPSILON);
        assertEquals(120.0f, ClipTimeUtils.parseClipTime("2:00"), EPSILON);
        assertEquals(35999.9f, ClipTimeUtils.parseClipTime("599:59.9"), EPSILON);
    }

    @Test
    public void parseValidHoursFormats() {
        assertEquals(3600.0f, ClipTimeUtils.parseClipTime("1:00:00"), EPSILON);
        assertEquals(3600.0f, ClipTimeUtils.parseClipTime("1:00:00.0"), EPSILON);
        assertEquals(3723.5f, ClipTimeUtils.parseClipTime("1:02:03.5"), EPSILON);
        assertEquals(3723.0f, ClipTimeUtils.parseClipTime("1:2:3"), EPSILON);
        assertEquals(35999.9f, ClipTimeUtils.parseClipTime("9:59:59.9"), EPSILON);
        assertNull(ClipTimeUtils.parseClipTime("1:60:00"));
        assertNull(ClipTimeUtils.parseClipTime("1:00:60"));
    }

    @Test
    public void parseWithoutSuffix() {
        assertEquals(5.0f, ClipTimeUtils.parseClipTime("5.0"), EPSILON);
        assertEquals(83.5f, ClipTimeUtils.parseClipTime("1:23.5"), EPSILON);
    }

    @Test
    public void parseBoundaries() {
        assertEquals(0.0f, ClipTimeUtils.parseClipTime("0"), EPSILON);
        assertEquals(59.9f, ClipTimeUtils.parseClipTime("0:59.9"), EPSILON);
        assertNull(ClipTimeUtils.parseClipTime("0:60"));
        assertEquals(6000.0f, ClipTimeUtils.parseClipTime("100:00"), EPSILON);
        assertNull(ClipTimeUtils.parseClipTime("1000:00"));
    }

    @Test
    public void parseGarbage() {
        assertNull(ClipTimeUtils.parseClipTime(""));
        assertNull(ClipTimeUtils.parseClipTime("   "));
        assertNull(ClipTimeUtils.parseClipTime("abc"));
        assertNull(ClipTimeUtils.parseClipTime("--"));
        assertNull(ClipTimeUtils.parseClipTime("1:2:3:4"));
        assertNull(ClipTimeUtils.parseClipTime("5.0s trailing"));
        assertNull(ClipTimeUtils.parseClipTime("s"));
        assertNull(ClipTimeUtils.parseClipTime(null));
    }

    @Test
    public void formatIsReTypeable() {
        assertEquals("0:00.0", ClipTimeUtils.formatClipTime(0.0f));
        assertEquals("0:05.0", ClipTimeUtils.formatClipTime(5.0f));
        assertEquals("1:15.3", ClipTimeUtils.formatClipTime(75.3f));
        assertEquals("1:02:03.5", ClipTimeUtils.formatClipTime(3723.5f));
        assertEquals("9:59:59.9", ClipTimeUtils.formatClipTime(35999.9f));
    }

    @Test
    public void formatRoundTrip() {
        final float[] values = {0.0f, 0.1f, 5.0f, 5.3f, 59.9f, 60.0f, 75.3f, 599.9f,
            3600.0f, 3723.5f, 35999.9f};
        for (final float value : values) {
            final String formatted = ClipTimeUtils.formatClipTime(value);
            final Float parsed = ClipTimeUtils.parseClipTime(formatted);
            assertNotNull(parsed);
            assertEquals(value, parsed, 0.05f);
        }
    }

    @Test
    public void formatIsLocaleIndependent() {
        final Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("0:05.3", ClipTimeUtils.formatClipTime(5.3f));
            assertEquals(5.3f, ClipTimeUtils.parseClipTime(
                    ClipTimeUtils.formatClipTime(5.3f)), EPSILON);
        } finally {
            Locale.setDefault(original);
        }
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
    public void maxClipSecondsInvalidInput() {
        assertEquals(0.0f, ClipTimeUtils.maxClipSeconds(0, 1280, 480, 15f,
                1024L), EPSILON);
        assertEquals(0.0f, ClipTimeUtils.maxClipSeconds(720, 1280, 480, 15f,
                0L), EPSILON);
    }
}
