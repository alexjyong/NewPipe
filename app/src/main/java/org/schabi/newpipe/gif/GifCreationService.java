package org.schabi.newpipe.gif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.schabi.newpipe.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class GifCreationService extends Service {

    private static final String TAG = GifCreationService.class.getSimpleName();
    private static final String CHANNEL_ID = "gif_creation_channel";
    private static final int NOTIFICATION_ID = 9001;

    public static final String EXTRA_STREAM_URL = "stream_url";
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_END_MS = "end_ms";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_OPTIMIZE = "optimize";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_VIDEO_TITLE = "video_title";

    private static final int OUTPUT_WIDTH = 480;
    private static final int GIF_FPS = 10;
    private static final int WEBP_FPS = 15;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String streamUrl = intent.getStringExtra(EXTRA_STREAM_URL);
        final long startMs = intent.getLongExtra(EXTRA_START_MS, 0);
        final long endMs = intent.getLongExtra(EXTRA_END_MS, 0);
        final String format = intent.getStringExtra(EXTRA_FORMAT);
        final boolean optimize = intent.getBooleanExtra(EXTRA_OPTIMIZE, true);
        final String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        final String videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE);

        final String displayName = videoTitle != null ? videoTitle : "GIF";
        final Notification notification = buildProgressNotification(displayName);
        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> {
            try {
                processGifCreation(streamUrl, startMs, endMs, format,
                        optimize, fileName, displayName);
            } catch (final Exception e) {
                Log.e(TAG, "GIF creation failed", e);
                showErrorNotification(displayName, e);
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        }).start();

        return START_NOT_STICKY;
    }

    private void processGifCreation(final String streamUrl, final long startMs,
                                    final long endMs, final String format,
                                    final boolean optimize, final String fileName,
                                    final String displayName) throws IOException {
        final boolean isGif = "gif".equals(format);
        final int fps = isGif ? GIF_FPS : WEBP_FPS;

        final List<Bitmap> frames = FrameExtractor.extract(
                streamUrl, startMs, endMs, OUTPUT_WIDTH, fps);

        if (frames.isEmpty()) {
            throw new IOException("No frames were extracted from the video");
        }

        // Run through the text overlay stub (no-op for now)
        final List<Bitmap> processedFrames = TextOverlayStub.apply(frames);

        final byte[] encoded;
        if (isGif) {
            encoded = GifEncoder.encode(processedFrames, optimize);
        } else {
            encoded = WebPEncoder.encode(processedFrames, optimize);
        }

        final String mimeType = isGif ? "image/gif" : "image/webp";
        final Uri savedUri = saveToMediaStore(fileName, mimeType, encoded);

        // Recycle bitmaps
        for (final Bitmap bmp : processedFrames) {
            bmp.recycle();
        }

        showCompletionNotification(displayName, savedUri);
    }

    private Uri saveToMediaStore(final String fileName, final String mimeType,
                                 final byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(fileName, mimeType, data);
        } else {
            return saveLegacy(fileName, data);
        }
    }

    private Uri saveViaMediaStore(final String fileName, final String mimeType,
                                  final byte[] data) throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        final Uri collection = MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final Uri itemUri = getContentResolver().insert(collection, values);
        if (itemUri == null) {
            throw new IOException("Failed to create MediaStore entry for " + fileName);
        }

        try (OutputStream os = getContentResolver().openOutputStream(itemUri)) {
            if (os == null) {
                throw new IOException("Cannot open output stream for " + itemUri);
            }
            os.write(data);
        }
        return itemUri;
    }

    @SuppressWarnings("deprecation")
    private Uri saveLegacy(final String fileName, final byte[] data) throws IOException {
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create download directory");
        }
        final File file = new File(dir, fileName);
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(data);
        }
        return Uri.fromFile(file);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.gif_creation_title),
                    NotificationManager.IMPORTANCE_LOW);
            final NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildProgressNotification(final String displayName) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gif_creation)
                .setContentTitle(getString(R.string.gif_creating_notification, displayName))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build();
    }

    private void showCompletionNotification(final String displayName,
                                            final Uri contentUri) {
        final String mimeType = displayName.endsWith(".webp") ? "image/webp" : "image/gif";
        final Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(contentUri, mimeType);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, viewIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gif_creation)
                .setContentTitle(getString(R.string.gif_saved_notification, displayName))
                .setContentText(getString(R.string.gif_saved_to_downloads))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID + 1, notification);
        }
    }

    private void showErrorNotification(final String displayName, final Exception e) {
        final String errorMsg = e.getMessage() != null ? e.getMessage()
                : e.getClass().getSimpleName();
        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gif_creation)
                .setContentTitle(getString(R.string.gif_creation_failed, displayName))
                .setContentText(errorMsg)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(errorMsg))
                .setAutoCancel(true)
                .build();

        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID + 1, notification);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
