package org.schabi.newpipe.util;

import java.util.Locale;

public final class ClipTimeUtils {

    private ClipTimeUtils() {
    }

    /**
     * Formats a clip time as a position within the video, rounded to 0.1 s:
     * {@code m:ss.t}, escalating to {@code h:mm:ss.t} at one hour.
     *
     * @param totalSeconds the time in seconds
     * @return the formatted time, e.g. {@code "0:05.0"} or {@code "1:02:03.5"}
     */
    public static String formatClipTime(final float totalSeconds) {
        final int totalTenths = Math.round(Math.max(0f, totalSeconds) * 10f);
        final int hours = totalTenths / 36000;
        final int minutes = totalTenths % 36000 / 600;
        final int seconds = totalTenths % 600 / 10;
        final int tenths = totalTenths % 10;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%d",
                    hours, minutes, seconds, tenths);
        }
        return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths);
    }

    public static String truncateName(final String s, final int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /**
     * Estimates the memory held by all decoded frames of a clip at
     * {@code targetWidth} pixels width, assuming ARGB_8888 bitmaps.
     *
     * @param durationSeconds the clip duration in seconds
     * @param videoWidth      the source video width in pixels
     * @param videoHeight     the source video height in pixels
     * @param targetWidth     the output frame width in pixels
     * @param fps             the frames per second the clip is sampled at
     * @return the estimated memory footprint of all frames in bytes
     */
    public static long estimateClipBytes(final float durationSeconds, final int videoWidth,
                                         final int videoHeight, final int targetWidth,
                                         final float fps) {
        if (durationSeconds <= 0f || videoWidth <= 0 || videoHeight <= 0
                || targetWidth <= 0 || fps <= 0f) {
            return 0L;
        }
        final int scaledHeight = scaledHeight(videoWidth, videoHeight, targetWidth);
        final long frames = (long) Math.ceil(durationSeconds * fps);
        return frames * (long) targetWidth * scaledHeight * 4L;
    }

    /**
     * Inverse of {@link #estimateClipBytes}: the longest clip duration (in seconds,
     * rounded down to a 0.1 s step) whose decoded frames stay within {@code budgetBytes}.
     *
     * @param videoWidth  the source video width in pixels
     * @param videoHeight the source video height in pixels
     * @param targetWidth the output frame width in pixels
     * @param fps         the frames per second the clip is sampled at
     * @param budgetBytes the maximum memory budget in bytes
     * @return the maximum clip duration in seconds
     */
    public static float maxClipSeconds(final int videoWidth, final int videoHeight,
                                       final int targetWidth, final float fps,
                                       final long budgetBytes) {
        if (videoWidth <= 0 || videoHeight <= 0 || targetWidth <= 0 || fps <= 0f
                || budgetBytes <= 0L) {
            return 0f;
        }
        final long bytesPerFrame = (long) targetWidth
                * scaledHeight(videoWidth, videoHeight, targetWidth) * 4L;
        final float seconds = (float) (budgetBytes / (double) bytesPerFrame / fps);
        return (float) (Math.floor(seconds * 10f) / 10f);
    }

    private static int scaledHeight(final int videoWidth, final int videoHeight,
                                    final int targetWidth) {
        return Math.max(1, Math.round(videoHeight * (targetWidth / (float) videoWidth)));
    }
}
