package org.schabi.newpipe.gif;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class FrameExtractor {

    private static final String TAG = FrameExtractor.class.getSimpleName();
    private static final long DRAIN_DEADLINE_MS = 500;

    private FrameExtractor() {
    }

    /**
     * Reads the coded video dimensions without decoding any frame.
     *
     * @param videoUrl direct stream URL
     * @return {width, height} of the first video track
     * @throws IOException if the stream cannot be opened or has no video track
     */
    public static int[] probeVideoDimensions(final String videoUrl) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(videoUrl);
            final int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No video track found in stream");
            }
            final MediaFormat format = extractor.getTrackFormat(trackIndex);
            return new int[]{
                    format.getInteger(MediaFormat.KEY_WIDTH),
                    format.getInteger(MediaFormat.KEY_HEIGHT)
            };
        } finally {
            extractor.release();
        }
    }

    /**
     * @param videoUrl   direct stream URL
     * @param startMs    clip start in milliseconds
     * @param endMs      clip end in milliseconds
     * @param widthPx    target width; height derived from the aspect ratio of each frame
     * @param fps        frames per second to sample
     * @param stopSignal polled between frames; a true result stops extraction early
     * @return ordered list of ARGB_8888 bitmaps
     */
    public static List<Bitmap> extract(final String videoUrl, final long startMs,
                                       final long endMs, final int widthPx,
                                       final int fps,
                                       final BooleanSupplier stopSignal) throws IOException {
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new IOException("Video URL is null or empty");
        }

        final List<Bitmap> frames = new ArrayList<>();
        final long frameDurationUs = 1_000_000L / fps;
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;

        try {
            extractor.setDataSource(videoUrl);

            final int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No video track found in stream");
            }

            extractor.selectTrack(trackIndex);
            final MediaFormat format = extractor.getTrackFormat(trackIndex);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || mime.isEmpty()) {
                throw new IOException("Cannot determine video codec");
            }

            int rotationDegrees = getIntegerOr(format, MediaFormat.KEY_ROTATION, 0);

            codec = MediaCodec.createDecoderByType(mime);
            if (codec == null) {
                throw new IOException("Codec not available for: " + mime);
            }
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities
                            .COLOR_FormatYUV420Flexible);
            codec.configure(format, null, null, 0);
            codec.start();

            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long nextFrameTimeUs = startMs * 1000;
            final long endUs = endMs * 1000;
            long drainDeadlineMs = 0L;
            int nullImageFrames = 0;

            while (!stopSignal.getAsBoolean()) {
                if (!inputDone) {
                    final int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        final ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        final int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0 || extractor.getSampleTime() > endUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                final int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rotationDegrees = getIntegerOr(codec.getOutputFormat(),
                            MediaFormat.KEY_ROTATION, rotationDegrees);
                } else if (outputIndex >= 0) {
                    final long presentationTimeUs = bufferInfo.presentationTimeUs;

                    if (presentationTimeUs >= nextFrameTimeUs
                            && presentationTimeUs <= endUs) {
                        final Image image = codec.getOutputImage(outputIndex);
                        if (image != null) {
                            frames.add(imageToBitmap(image, widthPx, rotationDegrees));
                            image.close();
                        } else if (++nullImageFrames == 1) {
                            Log.w(TAG, "Decoder returned a null image; skipping frame");
                        }
                        nextFrameTimeUs += frameDurationUs;
                    }

                    codec.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        if (drainDeadlineMs == 0L) {
                            drainDeadlineMs = SystemClock.elapsedRealtime()
                                    + DRAIN_DEADLINE_MS;
                        } else if (SystemClock.elapsedRealtime() >= drainDeadlineMs) {
                            break;
                        }
                    }
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (final IllegalStateException e) {
                    Log.w(TAG, "Codec was not in a stoppable state", e);
                }
                codec.release();
            }
            extractor.release();
        }

        return frames;
    }

    private static int getIntegerOr(final MediaFormat format, final String key,
                                    final int fallback) {
        try {
            return format.getInteger(key);
        } catch (final Exception e) {
            return fallback;
        }
    }

    private static int selectVideoTrack(final MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            final String mime = extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private static Bitmap imageToBitmap(final Image image,
                                        final int targetWidth,
                                        final int rotationDegrees) {
        final Image.Plane[] planes = image.getPlanes();
        final ByteBuffer yBuffer = planes[0].getBuffer();
        final ByteBuffer uBuffer = planes[1].getBuffer();
        final ByteBuffer vBuffer = planes[2].getBuffer();

        final int width = image.getWidth();
        final int height = image.getHeight();
        final int yRowStride = planes[0].getRowStride();
        final int uvRowStride = planes[1].getRowStride();
        final int uvPixelStride = planes[1].getPixelStride();

        final int[] argbPixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int yVal = yBuffer.get(y * yRowStride + x) & 0xFF;
                final int uVal = uBuffer.get((y / 2) * uvRowStride
                        + (x / 2) * uvPixelStride) & 0xFF;
                final int vVal = vBuffer.get((y / 2) * uvRowStride
                        + (x / 2) * uvPixelStride) & 0xFF;

                int r = (int) (yVal + 1.370705f * (vVal - 128));
                int g = (int) (yVal - 0.337633f * (uVal - 128)
                        - 0.698001f * (vVal - 128));
                int b = (int) (yVal + 1.732446f * (uVal - 128));

                r = clamp(r);
                g = clamp(g);
                b = clamp(b);

                argbPixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(
                argbPixels, width, height, Bitmap.Config.ARGB_8888);

        if (rotationDegrees % 360 != 0) {
            final Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            final Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, width, height, matrix, true);
            bitmap.recycle();
            bitmap = rotated;
        }

        if (bitmap.getWidth() == targetWidth) {
            return bitmap;
        }

        final int scaledHeight = Math.max(1, Math.round(
                bitmap.getHeight() * (targetWidth / (float) bitmap.getWidth())));
        final Bitmap scaled = Bitmap.createScaledBitmap(
                bitmap, targetWidth, scaledHeight, true);
        bitmap.recycle();
        return scaled;
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }
}
