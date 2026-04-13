package org.schabi.newpipe.gif;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;
import java.util.Locale;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

public class GifCreationDialog extends DialogFragment {

    private static final String TAG = GifCreationDialog.class.getSimpleName();
    private static final int MAX_CLIP_SECONDS = 15;
    private static final int DEFAULT_CLIP_SECONDS = 5;

    private StreamInfo streamInfo;
    private long currentPositionMs;

    private EditText fileNameEdit;
    private TextView timeRangeLabel;
    private SeekBar startTimeSeekbar;
    private SeekBar endTimeSeekbar;
    private RadioGroup formatGroup;
    private CheckBox optimizeCheckbox;

    private long videoDurationMs;

    public GifCreationDialog() {
        // required empty constructor for fragment recreation
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

        videoDurationMs = streamInfo.getDuration() * 1000L;

        fileNameEdit = view.findViewById(R.id.file_name);
        timeRangeLabel = view.findViewById(R.id.time_range_label);
        startTimeSeekbar = view.findViewById(R.id.start_time_seekbar);
        endTimeSeekbar = view.findViewById(R.id.end_time_seekbar);
        formatGroup = view.findViewById(R.id.format_group);
        optimizeCheckbox = view.findViewById(R.id.optimize_checkbox);

        final int durationSeconds = (int) (videoDurationMs / 1000);
        startTimeSeekbar.setMax(durationSeconds);
        endTimeSeekbar.setMax(durationSeconds);

        final int startSec = (int) (currentPositionMs / 1000);
        final int endSec = Math.min(startSec + DEFAULT_CLIP_SECONDS, durationSeconds);
        startTimeSeekbar.setProgress(startSec);
        endTimeSeekbar.setProgress(endSec);

        updateTimeLabel();
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
                updateTimeLabel();
                updateFileName();
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) { }
        };

        startTimeSeekbar.setOnSeekBarChangeListener(seekListener);
        endTimeSeekbar.setOnSeekBarChangeListener(seekListener);

        formatGroup.setOnCheckedChangeListener((group, checkedId) -> updateFileName());

        final Button addTextButton = view.findViewById(R.id.add_text_button);
        addTextButton.setOnClickListener(v ->
                Toast.makeText(requireContext(),
                        R.string.gif_add_text_stub_toast,
                        Toast.LENGTH_SHORT).show());

        initToolbar(view);
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

        if (clipDuration < 1 || clipDuration > MAX_CLIP_SECONDS) {
            Toast.makeText(requireContext(),
                    R.string.gif_time_range_error,
                    Toast.LENGTH_SHORT).show();
            return;
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

        final Intent intent = new Intent(requireContext(), GifCreationService.class);
        intent.putExtra(GifCreationService.EXTRA_STREAM_URL, streamUrl);
        intent.putExtra(GifCreationService.EXTRA_START_MS, (long) startSec * 1000);
        intent.putExtra(GifCreationService.EXTRA_END_MS, (long) endSec * 1000);
        intent.putExtra(GifCreationService.EXTRA_FORMAT, format);
        intent.putExtra(GifCreationService.EXTRA_OPTIMIZE, optimize);
        intent.putExtra(GifCreationService.EXTRA_FILE_NAME, fileName);
        intent.putExtra(GifCreationService.EXTRA_VIDEO_TITLE, streamInfo.getName());

        ContextCompat.startForegroundService(requireContext(), intent);
        dismiss();
    }

    @Nullable
    private String getVideoStreamUrl() {
        final List<VideoStream> videoStreams =
                getStreamsOfSpecifiedDelivery(streamInfo.getVideoStreams(), PROGRESSIVE_HTTP);
        if (videoStreams.isEmpty()) {
            return null;
        }
        // Pick a stream <= 720p for reasonable file sizes
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
        // Fall back to the last (typically lowest quality) stream
        return videoStreams.get(videoStreams.size() - 1).getContent();
    }

    private void updateTimeLabel() {
        final int startSec = startTimeSeekbar.getProgress();
        final int endSec = endTimeSeekbar.getProgress();
        timeRangeLabel.setText(String.format(Locale.US, "%s - %s",
                formatTime(startSec), formatTime(endSec)));
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
