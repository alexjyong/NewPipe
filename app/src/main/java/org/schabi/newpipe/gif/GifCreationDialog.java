package org.schabi.newpipe.gif;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.textfield.TextInputEditText;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

public class GifCreationDialog extends DialogFragment {

    private static final String TAG = GifCreationDialog.class.getSimpleName();
    private static final int WARN_CLIP_SECONDS = 10;
    private static final int DEFAULT_CLIP_SECONDS = 5;
    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(\\d{1,2}):?(\\d{2})?$");

    private StreamInfo streamInfo;
    private long currentPositionMs;

    private EditText fileNameEdit;
    private TextInputEditText startTimeEdit;
    private TextInputEditText endTimeEdit;
    private SeekBar startTimeSeekbar;
    private SeekBar endTimeSeekbar;
    private RadioGroup formatGroup;
    private CheckBox optimizeCheckbox;

    private int durationSeconds;
    private boolean updatingFromText = false;

    private Intent pendingServiceIntent;

    private final ActivityResultLauncher<Intent> requestSaveAsLauncher =
            registerForActivityResult(new StartActivityForResult(), this::onSaveAsResult);

    public GifCreationDialog() {
    }

    public GifCreationDialog(@NonNull final Context context,
                             @NonNull final StreamInfo info,
                             final long positionMs) {
        this.streamInfo = info;
        this.currentPositionMs = positionMs;
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, ThemeHelper.getDialogTheme(requireContext()));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.gif_creation_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (streamInfo == null) {
            dismiss();
            return;
        }

        final long durationMs = streamInfo.getDuration() * 1000L;
        durationSeconds = (int) (durationMs / 1000);

        fileNameEdit = view.findViewById(R.id.file_name);
        startTimeEdit = view.findViewById(R.id.start_time_edit);
        endTimeEdit = view.findViewById(R.id.end_time_edit);
        startTimeSeekbar = view.findViewById(R.id.start_time_seekbar);
        endTimeSeekbar = view.findViewById(R.id.end_time_seekbar);
        formatGroup = view.findViewById(R.id.format_group);
        optimizeCheckbox = view.findViewById(R.id.optimize_checkbox);

        startTimeSeekbar.setMax(durationSeconds);
        endTimeSeekbar.setMax(durationSeconds);

        final int startSec = (int) (currentPositionMs / 1000);
        final int endSec = Math.min(startSec + DEFAULT_CLIP_SECONDS, durationSeconds);
        startTimeSeekbar.setProgress(startSec);
        endTimeSeekbar.setProgress(endSec);

        setTimeTexts();
        updateFileName();

        final SeekBar.OnSeekBarChangeListener seekListener =
                new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar, final int progress,
                                          final boolean fromUser) {
                if (fromUser) {
                    if (seekBar == startTimeSeekbar
                            && progress >= endTimeSeekbar.getProgress()) {
                        startTimeSeekbar.setProgress(
                                Math.max(0, endTimeSeekbar.getProgress() - 1));
                        return;
                    }
                    if (seekBar == endTimeSeekbar
                            && progress <= startTimeSeekbar.getProgress()) {
                        endTimeSeekbar.setProgress(
                                Math.min(durationSeconds,
                                        startTimeSeekbar.getProgress() + 1));
                        return;
                    }
                }
                setTimeTexts();
                updateFileName();
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) { }
        };

        startTimeSeekbar.setOnSeekBarChangeListener(seekListener);
        endTimeSeekbar.setOnSeekBarChangeListener(seekListener);

        setupTimeTextField(startTimeEdit, true);
        setupTimeTextField(endTimeEdit, false);

        formatGroup.setOnCheckedChangeListener((group, checkedId) -> updateFileName());

        final Button addTextButton = view.findViewById(R.id.add_text_button);
        addTextButton.setOnClickListener(v ->
                Toast.makeText(requireContext(),
                        R.string.gif_add_text_stub_toast,
                        Toast.LENGTH_SHORT).show());

        initToolbar(view);
    }

    private void setupTimeTextField(final TextInputEditText field,
                                    final boolean isStart) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s,
                                          final int start,
                                          final int count,
                                          final int after) { }

            @Override
            public void onTextChanged(final CharSequence s,
                                      final int start,
                                      final int before,
                                      final int count) { }

            @Override
            public void afterTextChanged(final Editable s) {
                final Integer secs = parseTime(s.toString());
                if (secs == null) {
                    return;
                }
                final int clamped = Math.max(0, Math.min(durationSeconds, secs));
                final int otherProgress = isStart
                        ? endTimeSeekbar.getProgress()
                        : startTimeSeekbar.getProgress();

                if (isStart && clamped >= otherProgress) {
                    return;
                }
                if (!isStart && clamped <= otherProgress) {
                    return;
                }

                updatingFromText = true;
                if (isStart) {
                    startTimeSeekbar.setProgress(clamped);
                } else {
                    endTimeSeekbar.setProgress(clamped);
                }
                updatingFromText = false;
                updateFileName();
            }
        });
    }

    private void setTimeTexts() {
        if (updatingFromText) {
            return;
        }
        startTimeEdit.setText(formatTime(startTimeSeekbar.getProgress()));
        endTimeEdit.setText(formatTime(endTimeSeekbar.getProgress()));
    }

    private static Integer parseTime(final String s) {
        final String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final Matcher m = TIME_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            return null;
        }
        try {
            final int minutes = Integer.parseInt(m.group(1));
            final int seconds = m.group(2) != null
                    ? Integer.parseInt(m.group(2))
                    : 0;
            if (seconds >= 60 || minutes < 0) {
                return null;
            }
            return minutes * 60 + seconds;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private void initToolbar(final View view) {
        final Toolbar toolbar = view.findViewById(R.id.toolbar_layout)
                .findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.gif_creation_title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.inflateMenu(R.menu.gif_creation_toolbar);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        toolbar.setNavigationContentDescription(R.string.cancel);

        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.okay) {
                startGifCreation();
                return true;
            }
            return false;
        });
    }

    private void startGifCreation() {
        final int startSec = startTimeSeekbar.getProgress();
        final int endSec = endTimeSeekbar.getProgress();
        final int clipDuration = endSec - startSec;

        if (clipDuration < 1) {
            Toast.makeText(requireContext(),
                    R.string.gif_time_range_error,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (clipDuration > WARN_CLIP_SECONDS) {
            Toast.makeText(requireContext(),
                    R.string.gif_long_clip_warning,
                    Toast.LENGTH_LONG).show();
        }

        final String streamUrl = getVideoStreamUrl();
        if (streamUrl == null) {
            Toast.makeText(requireContext(),
                    "No suitable video stream found",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean isGif = formatGroup.getCheckedRadioButtonId() == R.id.format_gif;
        final String format = isGif ? "gif" : "webp";
        final boolean optimize = optimizeCheckbox.isChecked();
        final String fileName = fileNameEdit.getText().toString().trim();
        final String mimeType = isGif ? "image/gif" : "image/webp";

        final Intent intent = new Intent(requireContext(), GifCreationService.class);
        intent.putExtra(GifCreationService.EXTRA_STREAM_URL, streamUrl);
        intent.putExtra(GifCreationService.EXTRA_START_MS, (long) startSec * 1000);
        intent.putExtra(GifCreationService.EXTRA_END_MS, (long) endSec * 1000);
        intent.putExtra(GifCreationService.EXTRA_FORMAT, format);
        intent.putExtra(GifCreationService.EXTRA_OPTIMIZE, optimize);
        intent.putExtra(GifCreationService.EXTRA_FILE_NAME, fileName);
        intent.putExtra(GifCreationService.EXTRA_VIDEO_TITLE, streamInfo.getName());

        final Context ctx = requireContext();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        final boolean askForSavePath = prefs.getBoolean(
                getString(R.string.downloads_storage_ask), false);

        if (askForSavePath) {
            launchFilePicker(intent, fileName, mimeType);
            return;
        }

        final String videoPath = prefs.getString(
                getString(R.string.download_path_video_key), null);
        if (videoPath != null && !videoPath.isEmpty()) {
            try {
                final StoredDirectoryHelper dir = new StoredDirectoryHelper(
                        ctx, Uri.parse(videoPath), "video");
                final StoredFileHelper file = dir.createFile(fileName, mimeType);
                if (file != null) {
                    intent.putExtra(GifCreationService.EXTRA_OUTPUT_URI,
                            file.getUri().toString());
                    launchService(intent);
                    return;
                }
            } catch (final Exception e) {
                Log.w(TAG, "Cannot use video download folder, falling back to picker", e);
            }
        }

        launchFilePicker(intent, fileName, mimeType);
    }

    private void launchFilePicker(final Intent serviceIntent, final String fileName,
                                  final String mimeType) {
        pendingServiceIntent = serviceIntent;
        final Uri initialPath;
        if (NewPipeSettings.useStorageAccessFramework(requireContext())) {
            initialPath = null;
        } else {
            initialPath = Uri.parse(
                    NewPipeSettings.getDir(Environment.DIRECTORY_MOVIES).getAbsolutePath());
        }

        NoFileManagerSafeGuard.launchSafe(requestSaveAsLauncher,
                StoredFileHelper.getNewPicker(requireContext(), fileName, mimeType, initialPath),
                TAG, requireContext());
    }

    private void onSaveAsResult(@NonNull final ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || pendingServiceIntent == null) {
            return;
        }
        if (result.getData() == null || result.getData().getData() == null) {
            Toast.makeText(requireContext(), R.string.general_error,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final Uri uri = result.getData().getData();
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (final SecurityException e) {
            Log.w(TAG, "Could not take persistable URI permission", e);
        }

        pendingServiceIntent.putExtra(GifCreationService.EXTRA_OUTPUT_URI, uri.toString());
        launchService(pendingServiceIntent);
    }

    private void launchService(final Intent intent) {
        ContextCompat.startForegroundService(requireContext(), intent);
        Toast.makeText(requireContext(),
                R.string.gif_creation_started,
                Toast.LENGTH_SHORT).show();
        dismiss();
    }

    @Nullable
    private String getVideoStreamUrl() {
        final List<VideoStream> videoStreams =
                getStreamsOfSpecifiedDelivery(streamInfo.getVideoStreams(), PROGRESSIVE_HTTP);
        if (videoStreams.isEmpty()) {
            return null;
        }
        for (final VideoStream stream : videoStreams) {
            try {
                final int height = Integer.parseInt(
                        stream.getResolution().replaceAll("[^0-9]", ""));
                if (height <= 720) {
                    return stream.getContent();
                }
            } catch (final NumberFormatException ignored) {
            }
        }
        return videoStreams.get(videoStreams.size() - 1).getContent();
    }

    private void updateFileName() {
        final int startSec = startTimeSeekbar.getProgress();
        final int endSec = endTimeSeekbar.getProgress();
        final boolean isGif = formatGroup.getCheckedRadioButtonId() == R.id.format_gif;
        final String ext = isGif ? "gif" : "webp";
        final String title = streamInfo.getName()
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        final String name = String.format(Locale.US, "%s_%s-%s.%s",
                truncate(title, 50),
                formatTime(startSec).replace(":", ""),
                formatTime(endSec).replace(":", ""),
                ext);
        fileNameEdit.setText(name);
    }

    private static String formatTime(final int totalSeconds) {
        final int minutes = totalSeconds / 60;
        final int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static String truncate(final String s, final int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }
}
