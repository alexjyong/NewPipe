package org.schabi.newpipe.gif;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class FrameExtractor {

    private static final String TAG = FrameExtractor.class.getSimpleName();

    private FrameExtractor() {
    }

    /**
     * @param videoUrl direct stream URL
     * @param startMs  clip start in milliseconds
     * @param endMs    clip end in milliseconds
     * @param widthPx  target width; height derived from aspect ratio
     * @param fps      frames per second to sample
     * @return ordered list of ARGB_8888 Bitmaps
     */
    public static List<Bitmap> extract(final String videoUrl, final long startMs,
                                       final long endMs, final int widthPx,
                                       final int fps) throws IOException {
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new IOException("Video URL is null or empty");
        }

        final List<Bitmap> frames = new ArrayList<>();
        final long frameDurationUs = 1_000_000L / fps;
        final MediaExtractor extractor = new MediaExtractor();

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

            final int videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            final int videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (videoWidth <= 0 || videoHeight <= 0) {
                throw new IOException("Invalid video dimensions: " + videoWidth
                        + "x" + videoHeight);
            }

            final float scale = (float) widthPx / videoWidth;
            final int targetHeight = Math.round(videoHeight * scale);

            final MediaCodec codec = MediaCodec.createDecoderByType(mime);
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

            while (true) {
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
                if (outputIndex >= 0) {
                    final long presentationTimeUs = bufferInfo.presentationTimeUs;

                    if (presentationTimeUs >= nextFrameTimeUs
                            && presentationTimeUs <= endUs) {
                        final Image image = codec.getOutputImage(outputIndex);
                        if (image != null) {
                            final Bitmap bitmap = imageToBitmap(image, widthPx, targetHeight);
                            frames.add(bitmap);
                            image.close();
                        }
                        nextFrameTimeUs += frameDurationUs;
                    }

                    codec.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        break;
                    }
                }
            }

            codec.stop();
            codec.release();
        } finally {
            extractor.release();
        }

        return frames;
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
                                        final int targetHeight) {
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

        final Bitmap fullBitmap = Bitmap.createBitmap(
                argbPixels, width, height, Bitmap.Config.ARGB_8888);
        if (width == targetWidth && height == targetHeight) {
            return fullBitmap;
        }
        final Bitmap scaled = Bitmap.createScaledBitmap(
                fullBitmap, targetWidth, targetHeight, true);
        fullBitmap.recycle();
        return scaled;
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }
}
