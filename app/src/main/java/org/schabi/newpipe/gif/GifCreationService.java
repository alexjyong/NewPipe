package org.schabi.newpipe.gif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.download.DownloadActivity;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.IOException;
import java.util.List;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.service.DownloadManagerService;

public class GifCreationService extends Service {

    private static final String TAG = GifCreationService.class.getSimpleName();
    private static final String CHANNEL_ID = "gif_creation_channel";
    private static final int NOTIFICATION_ID = 9001;

    public static final String EXTRA_STREAM_URL = "stream_url";
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_END_MS = "end_ms";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_VIDEO_TITLE = "video_title";
    public static final String EXTRA_OUTPUT_URI = "output_uri";

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
        final String videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE);
        final String outputUri = intent.getStringExtra(EXTRA_OUTPUT_URI);

        final String displayName = videoTitle != null ? videoTitle : "GIF";
        final Notification notification = buildProgressNotification(displayName);
        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> {
            try {
                processGifCreation(streamUrl, startMs, endMs, format,
                        displayName, outputUri);
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
                                    final String displayName,
                                    final String outputUri) throws Exception {
        final boolean isGif = "gif".equals(format);
        final int fps = isGif ? GIF_FPS : WEBP_FPS;

        final List<Bitmap> frames = FrameExtractor.extract(
                streamUrl, startMs, endMs, OUTPUT_WIDTH, fps);

        if (frames.isEmpty()) {
            throw new IOException("No frames were extracted from the video");
        }

        final List<Bitmap> processedFrames = TextOverlayStub.apply(frames);

        final byte[] encoded;
        if (isGif) {
            encoded = GifEncoder.encode(processedFrames);
        } else {
            encoded = WebPEncoder.encode(processedFrames);
        }

        final String mimeType = isGif ? "image/gif" : "image/webp";
        writeOutput(outputUri, mimeType, encoded);

        for (final Bitmap bmp : processedFrames) {
            bmp.recycle();
        }

        addToDownloadQueue(streamUrl, outputUri, mimeType, encoded.length, isGif);
        showCompletionNotification(displayName);
    }

    private void writeOutput(final String outputUri, final String mimeType,
                             final byte[] data) throws IOException {
        if (outputUri == null) {
            throw new IOException("No output URI provided");
        }

        final StoredFileHelper file = new StoredFileHelper(
                this, Uri.parse(outputUri), mimeType);
        try (SharpStream stream = file.getStream()) {
            stream.write(data);
        }
    }

    private void addToDownloadQueue(final String sourceUrl, final String outputUri,
                                    final String mimeType, final long fileSize,
                                    final boolean isGif) {
        final Context ctx = this;
        final Intent bindIntent = new Intent(ctx, DownloadManagerService.class);
        bindService(bindIntent, new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName name, final IBinder service) {
                try {
                    final DownloadManagerService.DownloadManagerBinder binder =
                            (DownloadManagerService.DownloadManagerBinder) service;
                    final FinishedMission mission = new FinishedMission();
                    mission.source = sourceUrl != null ? sourceUrl : "";
                    mission.length = fileSize;
                    mission.timestamp = System.currentTimeMillis();
                    mission.kind = isGif ? 'g' : 'w';
                    mission.storage = new StoredFileHelper(
                            ctx, Uri.parse(outputUri), mimeType);
                    binder.addFinishedMission(mission);
                } catch (final Exception e) {
                    Log.e(TAG, "Failed to add GIF to download queue", e);
                } finally {
                    unbindService(this);
                }
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
                // no-op
            }
        }, Context.BIND_AUTO_CREATE);
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
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.gif_creating_notification, displayName))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build();
    }

    private void showCompletionNotification(final String displayName) {
        final Intent openDownloads = new Intent(this, DownloadActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openDownloads,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
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
                .setSmallIcon(android.R.drawable.stat_sys_warning)
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
