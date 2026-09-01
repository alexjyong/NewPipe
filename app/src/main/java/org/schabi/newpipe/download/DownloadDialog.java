package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.collection.SparseArrayCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import com.evernote.android.state.State;
import com.livefront.bridge.Bridge;
import com.nononsenseapps.filepicker.Utils;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.gif.FrameExtractor;
import org.schabi.newpipe.gif.GifCreationService;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.AudioTrackAdapter;
import org.schabi.newpipe.util.AudioTrackAdapter.AudioTracksWrapper;
import org.schabi.newpipe.util.ClipTimeUtils;
import org.schabi.newpipe.util.FilePickerActivityHelper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.SecondaryStreamHelper;
import org.schabi.newpipe.util.SimpleOnSeekBarChangeListener;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;
import org.schabi.newpipe.util.ThemeHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.service.DownloadManagerService.DownloadManagerBinder;
import us.shandian.giga.service.MissionState;

public class DownloadDialog extends DialogFragment
        implements RadioGroup.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {
    private static final String TAG = "DialogFragment";
    private static final boolean DEBUG = MainActivity.DEBUG;

    private static final int WARN_CLIP_SECONDS = 10;
    private static final int DEFAULT_CLIP_SECONDS = 5;
    private static final long MAX_CLIP_BYTES = 160L * 1024 * 1024;
    private static final int FALLBACK_PROBE_WIDTH = 480;
    private static final int FALLBACK_PROBE_HEIGHT = 853;

    @State
    StreamInfo currentInfo;
    @State
    StreamInfoWrapper<VideoStream> wrappedVideoStreams;
    @State
    StreamInfoWrapper<SubtitlesStream> wrappedSubtitleStreams;
    @State
    AudioTracksWrapper wrappedAudioTracks;
    @State
    int selectedAudioTrackIndex;
    @State
    int selectedVideoIndex; // set in the constructor
    @State
    int selectedAudioIndex = 0; // default to the first item
    @State
    int selectedSubtitleIndex = 0; // default to the first item
    @State
    long clipStartPositionMs = 0; // set in the constructor, seeds the GIF clip range
    @State
    boolean startOnGifTab = false; // set in the constructor

    private StoredDirectoryHelper mainStorageAudio = null;
    private StoredDirectoryHelper mainStorageVideo = null;
    private DownloadManager downloadManager = null;
    private MenuItem okButton = null;
    private Context context = null;
    private boolean askForSavePath;

    private AudioTrackAdapter audioTrackAdapter;
    private StreamItemAdapter<AudioStream, Stream> audioStreamsAdapter;
    private StreamItemAdapter<VideoStream, AudioStream> videoStreamsAdapter;
    private StreamItemAdapter<SubtitlesStream, Stream> subtitleStreamsAdapter;

    private final CompositeDisposable disposables = new CompositeDisposable();

    private DownloadDialogBinding dialogBinding;

    private SharedPreferences prefs;

    // Variables for file name and MIME type when picking new folder because it's not set yet
    private String filenameTmp;
    private String mimeTmp;

    private int clipDurationSeconds;
    private float clipLengthSec = DEFAULT_CLIP_SECONDS;
    // last auto-generated clip filename, so we can tell an untouched name from an edited one.
    // not @State on purpose: after recreation it's null and a restored name is left alone
    private String lastGeneratedGifName;

    @State
    boolean pendingGifActive = false;
    @State
    String pendingGifStreamUrl;
    @State
    long pendingGifStartMs;
    @State
    long pendingGifEndMs;
    @State
    String pendingGifFormat;
    @State
    String pendingGifFileName;
    @State
    String pendingGifVideoTitle;

    private final ActivityResultLauncher<Intent> requestDownloadSaveAsLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadSaveAsResult);
    private final ActivityResultLauncher<Intent> requestDownloadPickAudioFolderLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadPickAudioFolderResult);
    private final ActivityResultLauncher<Intent> requestDownloadPickVideoFolderLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadPickVideoFolderResult);
    private final ActivityResultLauncher<Intent> requestGifSaveAsLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestGifSaveAsResult);

    /*//////////////////////////////////////////////////////////////////////////
    // Instance creation
    //////////////////////////////////////////////////////////////////////////*/

    public DownloadDialog() {
        // Just an empty default no-arg ctor to keep Fragment.instantiate() happy
        // otherwise InstantiationException will be thrown when fragment is recreated
        // TODO: Maybe use a custom FragmentFactory instead?
    }

    /**
     * Create a new download dialog with the video, audio and subtitle streams from the provided
     * stream info. Video streams and video-only streams will be put into a single list menu,
     * sorted according to their resolution and the default video resolution will be selected.
     *
     * @param context the context to use just to obtain preferences and strings (will not be stored)
     * @param info    the info from which to obtain downloadable streams and other info (e.g. title)
     */
    public DownloadDialog(@NonNull final Context context, @NonNull final StreamInfo info) {
        this(context, info, 0L, false);
    }

    /**
     * Same as {@link #DownloadDialog(Context, StreamInfo)}, but allows opening the dialog
     * directly on the GIF/WebP tab with the clip range seeded from the playback position.
     *
     * @param context     the context to use just to obtain preferences and strings
     * @param info        the info from which to obtain downloadable streams and other info
     * @param clipStartMs playback position in milliseconds used as the clip start, or 0
     * @param openGifTab  whether to preselect the GIF/WebP tab
     */
    public DownloadDialog(@NonNull final Context context, @NonNull final StreamInfo info,
                          final long clipStartMs, final boolean openGifTab) {
        this.currentInfo = info;
        this.clipStartPositionMs = clipStartMs;
        this.startOnGifTab = openGifTab;

        final List<AudioStream> audioStreams =
                getStreamsOfSpecifiedDelivery(info.getAudioStreams(), PROGRESSIVE_HTTP);
        final List<List<AudioStream>> groupedAudioStreams =
                ListHelper.getGroupedAudioStreams(context, audioStreams);
        this.wrappedAudioTracks = new AudioTracksWrapper(groupedAudioStreams, context);
        this.selectedAudioTrackIndex =
                ListHelper.getDefaultAudioTrackGroup(context, groupedAudioStreams);

        // TODO: Adapt this code when the downloader support other types of stream deliveries
        final List<VideoStream> videoStreams = ListHelper.getSortedStreamVideosList(
                context,
                getStreamsOfSpecifiedDelivery(info.getVideoStreams(), PROGRESSIVE_HTTP),
                getStreamsOfSpecifiedDelivery(info.getVideoOnlyStreams(), PROGRESSIVE_HTTP),
                false,
                // If there are multiple languages available, prefer streams without audio
                // to allow language selection
                wrappedAudioTracks.size() > 1
        );

        this.wrappedVideoStreams = new StreamInfoWrapper<>(videoStreams, context);
        this.wrappedSubtitleStreams = new StreamInfoWrapper<>(
                getStreamsOfSpecifiedDelivery(info.getSubtitles(), PROGRESSIVE_HTTP), context);

        this.selectedVideoIndex = ListHelper.getDefaultResolutionIndex(context, videoStreams);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Android lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }

        if (!PermissionHelper.checkStoragePermissions(getActivity(),
                PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
            dismiss();
            return;
        }

        // context will remain null if dismiss() was called above, allowing to check whether the
        // dialog is being dismissed in onViewCreated()
        context = getContext();

        setStyle(STYLE_NO_TITLE, ThemeHelper.getDialogTheme(context));
        Bridge.restoreInstanceState(this, savedInstanceState);

        this.audioTrackAdapter = new AudioTrackAdapter(wrappedAudioTracks);
        this.subtitleStreamsAdapter = new StreamItemAdapter<>(wrappedSubtitleStreams);
        updateSecondaryStreams();

        final Intent intent = new Intent(context, DownloadManagerService.class);
        context.startService(intent);

        context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName cname, final IBinder service) {
                final DownloadManagerBinder mgr = (DownloadManagerBinder) service;

                mainStorageAudio = mgr.getMainStorageAudio();
                mainStorageVideo = mgr.getMainStorageVideo();
                downloadManager = mgr.getDownloadManager();
                askForSavePath = mgr.askForSavePath();

                okButton.setEnabled(true);

                context.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
                // nothing to do
            }
        }, Context.BIND_AUTO_CREATE);
    }

    /**
     * Update the displayed video streams based on the selected audio track.
     */
    private void updateSecondaryStreams() {
        final StreamInfoWrapper<AudioStream> audioStreams = getWrappedAudioStreams();
        final var secondaryStreams = new SparseArrayCompat<SecondaryStreamHelper<AudioStream>>(4);
        final List<VideoStream> videoStreams = wrappedVideoStreams.getStreamsList();
        wrappedVideoStreams.resetInfo();

        for (int i = 0; i < videoStreams.size(); i++) {
            if (!videoStreams.get(i).isVideoOnly()) {
                continue;
            }
            final AudioStream audioStream = SecondaryStreamHelper.getAudioStreamFor(
                    context, audioStreams.getStreamsList(), videoStreams.get(i));

            if (audioStream != null) {
                secondaryStreams.append(i, new SecondaryStreamHelper<>(audioStreams, audioStream));
            } else if (DEBUG) {
                final MediaFormat mediaFormat = videoStreams.get(i).getFormat();
                if (mediaFormat != null) {
                    Log.w(TAG, "No audio stream candidates for video format "
                            + mediaFormat.name());
                } else {
                    Log.w(TAG, "No audio stream candidates for unknown video format");
                }
            }
        }

        this.videoStreamsAdapter = new StreamItemAdapter<>(wrappedVideoStreams, secondaryStreams);
        this.audioStreamsAdapter = new StreamItemAdapter<>(audioStreams);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreateView() called with: "
                    + "inflater = [" + inflater + "], container = [" + container + "], "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }
        return inflater.inflate(R.layout.download_dialog, container);
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        dialogBinding = DownloadDialogBinding.bind(view);
        if (context == null) {
            return; // the dialog is being dismissed, see the call to dismiss() in onCreate()
        }

        dialogBinding.fileName.setText(FilenameUtils.createFilename(getContext(),
                currentInfo.getName()));
        selectedAudioIndex = ListHelper.getDefaultAudioFormat(getContext(),
                getWrappedAudioStreams().getStreamsList());

        selectedSubtitleIndex = getSubtitleIndexBy(subtitleStreamsAdapter.getAll());

        dialogBinding.qualitySpinner.setOnItemSelectedListener(this);
        dialogBinding.audioStreamSpinner.setOnItemSelectedListener(this);
        dialogBinding.audioTrackSpinner.setOnItemSelectedListener(this);
        dialogBinding.videoAudioGroup.setOnCheckedChangeListener(this);

        initToolbar(dialogBinding.toolbarLayout.toolbar);
        initClipPanel();
        setupDownloadOptions();

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        final int threads = prefs.getInt(getString(R.string.default_download_threads), 3);
        dialogBinding.threadsCount.setText(String.valueOf(threads));
        dialogBinding.threads.setProgress(threads - 1);
        dialogBinding.threads.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(@NonNull final SeekBar seekbar,
                                          final int progress,
                                          final boolean fromUser) {
                final int newProgress = progress + 1;
                prefs.edit().putInt(getString(R.string.default_download_threads), newProgress)
                        .apply();
                dialogBinding.threadsCount.setText(String.valueOf(newProgress));
            }
        });

        fetchStreamsSize();
    }

    private void initToolbar(final Toolbar toolbar) {
        if (DEBUG) {
            Log.d(TAG, "initToolbar() called with: toolbar = [" + toolbar + "]");
        }

        toolbar.setTitle(R.string.download_dialog_title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.inflateMenu(R.menu.dialog_url);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        toolbar.setNavigationContentDescription(R.string.cancel);

        okButton = toolbar.getMenu().findItem(R.id.okay);
        okButton.setEnabled(false); // disable until the download service connection is done

        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.okay) {
                prepareSelectedDownload();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
    }

    @Override
    public void onDestroyView() {
        dialogBinding = null;
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        Bridge.saveInstanceState(this, outState);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Video, audio and subtitle spinners
    //////////////////////////////////////////////////////////////////////////*/

    private void fetchStreamsSize() {
        disposables.clear();
        disposables.add(StreamInfoWrapper.fetchMoreInfoForWrapper(wrappedVideoStreams)
                .subscribe(result -> {
                    if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId()
                            == R.id.video_button) {
                        setupVideoSpinner();
                    }
                }, throwable -> ErrorUtil.showSnackbar(context,
                        new ErrorInfo(throwable, UserAction.DOWNLOAD_OPEN_DIALOG,
                                "Downloading video stream size", currentInfo))));
        disposables.add(StreamInfoWrapper.fetchMoreInfoForWrapper(getWrappedAudioStreams())
                .subscribe(result -> {
                    if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId()
                            == R.id.audio_button) {
                        setupAudioSpinner();
                    }
                }, throwable -> ErrorUtil.showSnackbar(context,
                        new ErrorInfo(throwable, UserAction.DOWNLOAD_OPEN_DIALOG,
                                "Downloading audio stream size", currentInfo))));
        disposables.add(StreamInfoWrapper.fetchMoreInfoForWrapper(wrappedSubtitleStreams)
                .subscribe(result -> {
                    if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId()
                            == R.id.subtitle_button) {
                        setupSubtitleSpinner();
                    }
                }, throwable -> ErrorUtil.showSnackbar(context,
                        new ErrorInfo(throwable, UserAction.DOWNLOAD_OPEN_DIALOG,
                                "Downloading subtitle stream size", currentInfo))));
    }

    private void setupAudioTrackSpinner() {
        if (getContext() == null) {
            return;
        }

        dialogBinding.audioTrackSpinner.setAdapter(audioTrackAdapter);
        dialogBinding.audioTrackSpinner.setSelection(selectedAudioTrackIndex);
    }

    private void setClipPanelVisible(final boolean visible) {
        dialogBinding.clipLayout.setVisibility(visible ? View.VISIBLE : View.GONE);

        final int streamViews = visible ? View.GONE : View.VISIBLE;
        dialogBinding.threadsTextView.setVisibility(streamViews);
        dialogBinding.threadsLayout.setVisibility(streamViews);
        dialogBinding.streamsNote.setVisibility(streamViews);

        if (!visible && isGeneratedGifNameInField()) {
            // leaving the GIF tab: undo the generated clip name, unless the user edited it
            lastGeneratedGifName = null;
            dialogBinding.fileName.setText(
                    FilenameUtils.createFilename(context, currentInfo.getName()));
        }
    }

    private void setupGifPanel() {
        if (getContext() == null) {
            return;
        }

        setClipPanelVisible(true);
        setRadioButtonsState(true);
        dialogBinding.qualitySpinner.setVisibility(View.GONE);
        dialogBinding.audioStreamSpinner.setVisibility(View.GONE);
        dialogBinding.audioTrackSpinner.setVisibility(View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(View.GONE);

        // only generate a clip name if the field was not edited by the user
        final String plainName = FilenameUtils.createFilename(context, currentInfo.getName());
        final String current = Objects.requireNonNullElse(
                dialogBinding.fileName.getText(), "").toString();
        if (current.isEmpty() || current.equals(plainName) || isGeneratedGifNameInField()) {
            updateGifFileName();
        }
    }

    private void setupAudioSpinner() {
        if (getContext() == null) {
            return;
        }

        setClipPanelVisible(false);
        dialogBinding.qualitySpinner.setVisibility(View.GONE);
        setRadioButtonsState(true);
        dialogBinding.audioStreamSpinner.setAdapter(audioStreamsAdapter);
        dialogBinding.audioStreamSpinner.setSelection(selectedAudioIndex);
        dialogBinding.audioStreamSpinner.setVisibility(View.VISIBLE);
        dialogBinding.audioTrackSpinner.setVisibility(
                wrappedAudioTracks.size() > 1 ? View.VISIBLE : View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(View.GONE);
    }

    private void setupVideoSpinner() {
        if (getContext() == null) {
            return;
        }

        setClipPanelVisible(false);
        dialogBinding.qualitySpinner.setAdapter(videoStreamsAdapter);
        dialogBinding.qualitySpinner.setSelection(selectedVideoIndex);
        dialogBinding.qualitySpinner.setVisibility(View.VISIBLE);
        setRadioButtonsState(true);
        dialogBinding.audioStreamSpinner.setVisibility(View.GONE);
        onVideoStreamSelected();
    }

    private void onVideoStreamSelected() {
        final boolean isVideoOnly = videoStreamsAdapter.getItem(selectedVideoIndex).isVideoOnly();

        dialogBinding.audioTrackSpinner.setVisibility(
                isVideoOnly && wrappedAudioTracks.size() > 1 ? View.VISIBLE : View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(
                !isVideoOnly && wrappedAudioTracks.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private void setupSubtitleSpinner() {
        if (getContext() == null) {
            return;
        }

        setClipPanelVisible(false);
        dialogBinding.qualitySpinner.setAdapter(subtitleStreamsAdapter);
        dialogBinding.qualitySpinner.setSelection(selectedSubtitleIndex);
        dialogBinding.qualitySpinner.setVisibility(View.VISIBLE);
        setRadioButtonsState(true);
        dialogBinding.audioStreamSpinner.setVisibility(View.GONE);
        dialogBinding.audioTrackSpinner.setVisibility(View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(View.GONE);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // GIF/WebP clip panel
    //////////////////////////////////////////////////////////////////////////*/

    private void initClipPanel() {
        clipDurationSeconds = (int) currentInfo.getDuration();
        final int maxDuration = Math.max(clipDurationSeconds, 1);

        // clamp start so there's always room for 0.1s after it, and round to the slider's
        // 0.1 step size, else setValue() throws on non-multiple values
        final float startSec = Math.round(
                Math.min(clipStartPositionMs / 1000f, maxDuration - 0.1f) * 10f) / 10f;

        dialogBinding.clipStartSlider.setValueFrom(0f);
        dialogBinding.clipStartSlider.setValueTo(maxDuration);
        dialogBinding.clipStartSlider.setValue(Math.max(startSec, 0f));
        dialogBinding.clipStartSlider.setLabelFormatter(
                value -> ClipTimeUtils.formatClipTime(value));

        dialogBinding.clipDurationEdit.setText(
                String.format(Locale.US, "%.1f", clipLengthSec));

        // attached after setValue() so seeding the slider doesn't fire it
        dialogBinding.clipStartSlider.addOnChangeListener((slider, value, fromUser) -> {
            updateClipTimes();
            updateClipDuration();
            if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId() == R.id.gif_button) {
                updateGifFileName();
            }
        });

        setupClipDurationField();

        updateClipTimes();
        updateClipDuration();
    }

    private float getClipStartSec() {
        return dialogBinding.clipStartSlider.getValue();
    }

    private float getClipEndSec() {
        return Math.min(getClipStartSec() + clipLengthSec, clipDurationSeconds);
    }

    private void setupClipDurationField() {
        dialogBinding.clipDurationEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start,
                                          final int count, final int after) { }

            @Override
            public void onTextChanged(final CharSequence s, final int start,
                                      final int before, final int count) { }

            @Override
            public void afterTextChanged(final Editable s) {
                final String raw = s.toString().trim().replace(',', '.');
                final float len;
                try {
                    len = Float.parseFloat(raw);
                } catch (final NumberFormatException e) {
                    dialogBinding.clipDurationEdit.setError(raw.isEmpty()
                            ? null : getString(R.string.clip_length_invalid));
                    return;
                }
                if (len < 0.1f) {
                    dialogBinding.clipDurationEdit.setError(
                            getString(R.string.clip_length_invalid));
                    return;
                }
                dialogBinding.clipDurationEdit.setError(null);
                clipLengthSec = len;
                updateClipTimes();
                updateClipDuration();
                if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId() == R.id.gif_button) {
                    updateGifFileName();
                }
            }
        });
    }

    private void updateClipTimes() {
        dialogBinding.clipStartText.setText(getString(R.string.gif_start_time) + " "
                + ClipTimeUtils.formatClipTime(getClipStartSec()));
        dialogBinding.clipEndText.setText(getString(R.string.gif_end_time) + " "
                + ClipTimeUtils.formatClipTime(getClipEndSec()));
    }

    private void updateClipDuration() {
        final float duration = getClipEndSec() - getClipStartSec();
        dialogBinding.clipDurationText.setText(getString(R.string.gif_clip_duration, duration));
        dialogBinding.clipDurationWarning.setVisibility(
                duration > WARN_CLIP_SECONDS ? View.VISIBLE : View.GONE);
    }

    private void updateGifFileName() {
        final String title = currentInfo.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        final String name = String.format(Locale.US, "%s_%s-%s",
                ClipTimeUtils.truncateName(title, 50),
                ClipTimeUtils.formatClipTime(getClipStartSec()).replaceAll("[^0-9]", ""),
                ClipTimeUtils.formatClipTime(getClipEndSec()).replaceAll("[^0-9]", ""));
        lastGeneratedGifName = name;
        dialogBinding.fileName.setText(name);
    }

    private boolean isGeneratedGifNameInField() {
        return lastGeneratedGifName != null && lastGeneratedGifName.contentEquals(
                Objects.requireNonNullElse(dialogBinding.fileName.getText(), ""));
    }

    /**
     * @return the URL of a progressive HTTP video stream suitable for frame extraction,
     *         preferring one at or below 720p, or null if there is none
     */
    @Nullable
    private String getGifStreamUrl() {
        final List<VideoStream> videoStreams =
                getStreamsOfSpecifiedDelivery(currentInfo.getVideoStreams(), PROGRESSIVE_HTTP);
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
                // resolution string was not parseable, try the next stream
            }
        }
        return videoStreams.get(videoStreams.size() - 1).getContent();
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Activity results
    //////////////////////////////////////////////////////////////////////////*/

    private void requestDownloadPickAudioFolderResult(final ActivityResult result) {
        requestDownloadPickFolderResult(
                result, getString(R.string.download_path_audio_key), DownloadManager.TAG_AUDIO);
    }

    private void requestDownloadPickVideoFolderResult(final ActivityResult result) {
        requestDownloadPickFolderResult(
                result, getString(R.string.download_path_video_key), DownloadManager.TAG_VIDEO);
    }

    private void requestDownloadSaveAsResult(@NonNull final ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }

        if (result.getData() == null || result.getData().getData() == null) {
            showFailedDialog(R.string.general_error);
            return;
        }

        if (FilePickerActivityHelper.isOwnFileUri(context, result.getData().getData())) {
            final File file = Utils.getFileForUri(result.getData().getData());
            checkSelectedDownload(null, Uri.fromFile(file), file.getName(),
                    StoredFileHelper.DEFAULT_MIME);
            return;
        }

        final DocumentFile docFile = DocumentFile.fromSingleUri(context,
                result.getData().getData());
        if (docFile == null) {
            showFailedDialog(R.string.general_error);
            return;
        }

        // check if the selected file was previously used
        checkSelectedDownload(null, result.getData().getData(), docFile.getName(),
                docFile.getType());
    }

    private void requestDownloadPickFolderResult(@NonNull final ActivityResult result,
                                                 final String key,
                                                 final String tag) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }

        if (result.getData() == null || result.getData().getData() == null) {
            showFailedDialog(R.string.general_error);
            return;
        }

        Uri uri = result.getData().getData();
        if (FilePickerActivityHelper.isOwnFileUri(context, uri)) {
            uri = Uri.fromFile(Utils.getFileForUri(uri));
        } else {
            context.grantUriPermission(context.getPackageName(), uri,
                    StoredDirectoryHelper.PERMISSION_FLAGS);
        }

        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(key,
                uri.toString()).apply();

        try {
            final StoredDirectoryHelper mainStorage = new StoredDirectoryHelper(context, uri, tag);
            checkSelectedDownload(mainStorage, mainStorage.findFile(filenameTmp),
                    filenameTmp, mimeTmp);
        } catch (final IOException e) {
            showFailedDialog(R.string.general_error);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Listeners
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCheckedChanged(final RadioGroup group, @IdRes final int checkedId) {
        if (DEBUG) {
            Log.d(TAG, "onCheckedChanged() called with: "
                    + "group = [" + group + "], checkedId = [" + checkedId + "]");
        }
        boolean flag = true;

        if (checkedId == R.id.audio_button) {
            setupAudioSpinner();
        } else if (checkedId == R.id.video_button) {
            setupVideoSpinner();
        } else if (checkedId == R.id.subtitle_button) {
            setupSubtitleSpinner();
            flag = false;
        } else if (checkedId == R.id.gif_button) {
            setupGifPanel();
            flag = false;
        }

        dialogBinding.threads.setEnabled(flag);
    }

    @Override
    public void onItemSelected(final AdapterView<?> parent,
                               final View view,
                               final int position,
                               final long id) {
        if (DEBUG) {
            Log.d(TAG, "onItemSelected() called with: "
                    + "parent = [" + parent + "], view = [" + view + "], "
                    + "position = [" + position + "], id = [" + id + "]");
        }

        final int parentId = parent.getId();
        if (parentId == R.id.quality_spinner) {
            final int checkedRadioButtonId = dialogBinding.videoAudioGroup
                    .getCheckedRadioButtonId();
            if (checkedRadioButtonId == R.id.video_button) {
                selectedVideoIndex = position;
                onVideoStreamSelected();
            } else if (checkedRadioButtonId == R.id.subtitle_button) {
                selectedSubtitleIndex = position;
            }
            onItemSelectedSetFileName();
        } else if (parentId == R.id.audio_track_spinner) {
            final boolean trackChanged = selectedAudioTrackIndex != position;
            selectedAudioTrackIndex = position;
            if (trackChanged) {
                updateSecondaryStreams();
                fetchStreamsSize();
            }
        } else if (parentId == R.id.audio_stream_spinner) {
            selectedAudioIndex = position;
        }
    }

    private void onItemSelectedSetFileName() {
        if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId() == R.id.gif_button) {
            // the GIF tab owns the file name field, spinner events must not overwrite it
            return;
        }

        final String fileName = FilenameUtils.createFilename(getContext(), currentInfo.getName());
        final String prevFileName = Optional.ofNullable(dialogBinding.fileName.getText())
                .map(Object::toString)
                .orElse("");

        if (prevFileName.isEmpty()
                || prevFileName.equals(fileName)
                || prevFileName.startsWith(getString(R.string.caption_file_name, fileName, ""))) {
            // only update the file name field if it was not edited by the user

            final int radioButtonId = dialogBinding.videoAudioGroup
                    .getCheckedRadioButtonId();
            if (radioButtonId == R.id.audio_button || radioButtonId == R.id.video_button) {
                if (!prevFileName.equals(fileName)) {
                    // since the user might have switched between audio and video, the correct
                    // text might already be in place, so avoid resetting the cursor position
                    dialogBinding.fileName.setText(fileName);
                }
            } else if (radioButtonId == R.id.subtitle_button) {
                final String setSubtitleLanguageCode = subtitleStreamsAdapter
                        .getItem(selectedSubtitleIndex).getLanguageTag();
                // this will reset the cursor position, which is bad UX, but it can't be avoided
                dialogBinding.fileName.setText(getString(
                        R.string.caption_file_name, fileName, setSubtitleLanguageCode));
            }
        }
    }

    @Override
    public void onNothingSelected(final AdapterView<?> parent) {
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Download
    //////////////////////////////////////////////////////////////////////////*/

    protected void setupDownloadOptions() {
        setRadioButtonsState(false);
        setupAudioTrackSpinner();

        final boolean isVideoStreamsAvailable = videoStreamsAdapter.getCount() > 0;
        final boolean isAudioStreamsAvailable = audioStreamsAdapter.getCount() > 0;
        final boolean isSubtitleStreamsAvailable = subtitleStreamsAdapter.getCount() > 0;
        // GIF frames are extracted from a progressive HTTP video stream
        final boolean isGifAvailable = !getStreamsOfSpecifiedDelivery(
                currentInfo.getVideoStreams(), PROGRESSIVE_HTTP).isEmpty();

        dialogBinding.audioButton.setVisibility(isAudioStreamsAvailable ? View.VISIBLE
                : View.GONE);
        dialogBinding.videoButton.setVisibility(isVideoStreamsAvailable ? View.VISIBLE
                : View.GONE);
        dialogBinding.subtitleButton.setVisibility(isSubtitleStreamsAvailable
                ? View.VISIBLE : View.GONE);
        dialogBinding.gifButton.setVisibility(isGifAvailable ? View.VISIBLE : View.GONE);

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        final String defaultMedia = prefs.getString(getString(R.string.last_used_download_type),
                getString(R.string.last_download_type_video_key));

        if (startOnGifTab && isGifAvailable) {
            dialogBinding.gifButton.setChecked(true);
            setupGifPanel();
            return;
        }

        if (isVideoStreamsAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_video_key)))) {
            dialogBinding.videoButton.setChecked(true);
            setupVideoSpinner();
        } else if (isAudioStreamsAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_audio_key)))) {
            dialogBinding.audioButton.setChecked(true);
            setupAudioSpinner();
        } else if (isSubtitleStreamsAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_subtitle_key)))) {
            dialogBinding.subtitleButton.setChecked(true);
            setupSubtitleSpinner();
        } else if (isGifAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_gif_key)))) {
            dialogBinding.gifButton.setChecked(true);
            setupGifPanel();
        } else if (isVideoStreamsAvailable) {
            dialogBinding.videoButton.setChecked(true);
            setupVideoSpinner();
        } else if (isAudioStreamsAvailable) {
            dialogBinding.audioButton.setChecked(true);
            setupAudioSpinner();
        } else if (isSubtitleStreamsAvailable) {
            dialogBinding.subtitleButton.setChecked(true);
            setupSubtitleSpinner();
        } else {
            Toast.makeText(getContext(), R.string.no_streams_available_download,
                    Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }

    private void setRadioButtonsState(final boolean enabled) {
        dialogBinding.audioButton.setEnabled(enabled);
        dialogBinding.videoButton.setEnabled(enabled);
        dialogBinding.subtitleButton.setEnabled(enabled);
        dialogBinding.gifButton.setEnabled(enabled);
    }

    private StreamInfoWrapper<AudioStream> getWrappedAudioStreams() {
        if (selectedAudioTrackIndex < 0 || selectedAudioTrackIndex > wrappedAudioTracks.size()) {
            return StreamInfoWrapper.empty();
        }
        return wrappedAudioTracks.getTracksList().get(selectedAudioTrackIndex);
    }

    private int getSubtitleIndexBy(@NonNull final List<SubtitlesStream> streams) {
        final Localization preferredLocalization = NewPipe.getPreferredLocalization();

        int candidate = 0;
        for (int i = 0; i < streams.size(); i++) {
            final Locale streamLocale = streams.get(i).getLocale();

            final boolean languageEquals = streamLocale.getLanguage() != null
                    && preferredLocalization.getLanguageCode() != null
                    && streamLocale.getLanguage()
                    .equals(new Locale(preferredLocalization.getLanguageCode()).getLanguage());
            final boolean countryEquals = streamLocale.getCountry() != null
                    && streamLocale.getCountry().equals(preferredLocalization.getCountryCode());

            if (languageEquals) {
                if (countryEquals) {
                    return i;
                }

                candidate = i;
            }
        }

        return candidate;
    }

    @NonNull
    private String getNameEditText() {
        final String str = Objects.requireNonNull(dialogBinding.fileName.getText()).toString()
                .trim();

        return FilenameUtils.createFilename(context, str.isEmpty() ? currentInfo.getName() : str);
    }

    private void showFailedDialog(@StringRes final int msg) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.general_error)
                .setMessage(msg)
                .setNegativeButton(getString(R.string.ok), null)
                .show();
    }

    private void launchDirectoryPicker(final ActivityResultLauncher<Intent> launcher) {
        NoFileManagerSafeGuard.launchSafe(launcher, StoredDirectoryHelper.getPicker(context), TAG,
                context);
    }

    private void prepareSelectedDownload() {
        if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId() == R.id.gif_button) {
            prepareGifCreation();
            return;
        }

        final StoredDirectoryHelper mainStorage;
        final MediaFormat format;
        final String selectedMediaType;
        final long size;

        // first, build the filename and get the output folder (if possible)
        // later, run a very very very large file checking logic

        filenameTmp = getNameEditText().concat(".");

        final int checkedRadioButtonId = dialogBinding.videoAudioGroup.getCheckedRadioButtonId();
        if (checkedRadioButtonId == R.id.audio_button) {
            selectedMediaType = getString(R.string.last_download_type_audio_key);
            mainStorage = mainStorageAudio;
            format = audioStreamsAdapter.getItem(selectedAudioIndex).getFormat();
            size = getWrappedAudioStreams().getSizeInBytes(selectedAudioIndex);
            if (format == MediaFormat.WEBMA_OPUS) {
                mimeTmp = "audio/ogg";
                filenameTmp += "opus";
            } else if (format != null) {
                mimeTmp = format.mimeType;
                filenameTmp += format.getSuffix();
            }
        } else if (checkedRadioButtonId == R.id.video_button) {
            selectedMediaType = getString(R.string.last_download_type_video_key);
            mainStorage = mainStorageVideo;
            format = videoStreamsAdapter.getItem(selectedVideoIndex).getFormat();
            size = wrappedVideoStreams.getSizeInBytes(selectedVideoIndex);
            if (format != null) {
                mimeTmp = format.mimeType;
                filenameTmp += format.getSuffix();
            }
        } else if (checkedRadioButtonId == R.id.subtitle_button) {
            selectedMediaType = getString(R.string.last_download_type_subtitle_key);
            mainStorage = mainStorageVideo; // subtitle & video files go together
            format = subtitleStreamsAdapter.getItem(selectedSubtitleIndex).getFormat();
            size = wrappedSubtitleStreams.getSizeInBytes(selectedSubtitleIndex);
            if (format != null) {
                mimeTmp = format.mimeType;
            }

            if (format == MediaFormat.TTML) {
                filenameTmp += MediaFormat.SRT.getSuffix();
            } else if (format != null) {
                filenameTmp += format.getSuffix();
            }
        } else {
            throw new RuntimeException("No stream selected");
        }

        if (!askForSavePath && (mainStorage == null
                || mainStorage.isDirect() == NewPipeSettings.useStorageAccessFramework(context)
                || mainStorage.isInvalidSafStorage())) {
            // Pick new download folder if one of:
            // - Download folder is not set
            // - Download folder uses SAF while SAF is disabled
            // - Download folder doesn't use SAF while SAF is enabled
            // - Download folder uses SAF but the user manually revoked access to it
            Toast.makeText(context, getString(R.string.no_dir_yet),
                    Toast.LENGTH_LONG).show();

            if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId() == R.id.audio_button) {
                launchDirectoryPicker(requestDownloadPickAudioFolderLauncher);
            } else {
                launchDirectoryPicker(requestDownloadPickVideoFolderLauncher);
            }

            return;
        }

        if (askForSavePath) {
            final Uri initialPath;
            if (NewPipeSettings.useStorageAccessFramework(context)) {
                initialPath = null;
            } else {
                final File initialSavePath;
                if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId() == R.id.audio_button) {
                    initialSavePath = NewPipeSettings.getDir(Environment.DIRECTORY_MUSIC);
                } else {
                    initialSavePath = NewPipeSettings.getDir(Environment.DIRECTORY_MOVIES);
                }
                initialPath = Uri.parse(initialSavePath.getAbsolutePath());
            }

            NoFileManagerSafeGuard.launchSafe(requestDownloadSaveAsLauncher,
                    StoredFileHelper.getNewPicker(context, filenameTmp, mimeTmp, initialPath), TAG,
                    context);

            return;
        }

        // Check for free storage space
        final long freeSpace = mainStorage.getFreeStorageSpace();
        if (freeSpace <= size) {
            Toast.makeText(context, getString(R.
                    string.error_insufficient_storage), Toast.LENGTH_LONG).show();
            // move the user to storage setting tab
            final Intent storageSettingsIntent = new Intent(Settings.
                    ACTION_INTERNAL_STORAGE_SETTINGS);
            if (storageSettingsIntent.resolveActivity(context.getPackageManager())
                    != null) {
                startActivity(storageSettingsIntent);
            }
            return;
        }

        // check for existing file with the same name
        checkSelectedDownload(mainStorage, mainStorage.findFile(filenameTmp), filenameTmp,
                mimeTmp);

        // remember the last media type downloaded by the user
        prefs.edit().putString(getString(R.string.last_used_download_type), selectedMediaType)
                .apply();
    }

    private void prepareGifCreation() {
        final float startSec = getClipStartSec();
        final float endSec = getClipEndSec();

        if (endSec - startSec < 0.1f) {
            Toast.makeText(context, R.string.gif_time_range_error, Toast.LENGTH_SHORT).show();
            return;
        }

        final String streamUrl = getGifStreamUrl();
        if (streamUrl == null) {
            Toast.makeText(context, R.string.no_streams_available_download,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean isGif = dialogBinding.formatGroup.getCheckedButtonId() == R.id.format_gif;
        final String mime = isGif ? "image/gif" : "image/webp";
        final String filename = getNameEditText().concat(isGif ? ".gif" : ".webp");

        final int fps = isGif ? GifCreationService.GIF_FPS : GifCreationService.WEBP_FPS;
        final int probeWidth = FALLBACK_PROBE_WIDTH;
        final int probeHeight = FALLBACK_PROBE_HEIGHT;
        try {
            final int[] dims = FrameExtractor.probeVideoDimensions(streamUrl);
            final long estimatedBytes = ClipTimeUtils.estimateClipBytes(
                    endSec - startSec, dims[0], dims[1],
                    GifCreationService.OUTPUT_WIDTH, fps);
            if (estimatedBytes > MAX_CLIP_BYTES) {
                Toast.makeText(context, R.string.gif_clip_too_long, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (final IOException e) {
            final long estimatedBytes = ClipTimeUtils.estimateClipBytes(
                    endSec - startSec, probeWidth, probeHeight,
                    GifCreationService.OUTPUT_WIDTH, fps);
            if (estimatedBytes > MAX_CLIP_BYTES) {
                Toast.makeText(context, R.string.gif_clip_too_long, Toast.LENGTH_LONG).show();
                return;
            }
        }

        final Intent intent = new Intent(context, GifCreationService.class);
        intent.putExtra(GifCreationService.EXTRA_STREAM_URL, streamUrl);
        intent.putExtra(GifCreationService.EXTRA_START_MS, (long) (startSec * 1000));
        intent.putExtra(GifCreationService.EXTRA_END_MS, (long) (endSec * 1000));
        intent.putExtra(GifCreationService.EXTRA_FORMAT, isGif ? "gif" : "webp");
        intent.putExtra(GifCreationService.EXTRA_FILE_NAME, filename);
        intent.putExtra(GifCreationService.EXTRA_VIDEO_TITLE, currentInfo.getName());

        prefs.edit().putString(getString(R.string.last_used_download_type),
                getString(R.string.last_download_type_gif_key)).apply();

        // GIF output goes to the video download folder, same as regular video downloads
        if (!askForSavePath && mainStorageVideo != null
                && !mainStorageVideo.isInvalidSafStorage()) {
            final StoredFileHelper file = mainStorageVideo.createFile(filename, mime);
            if (file != null && file.canWrite()) {
                intent.putExtra(GifCreationService.EXTRA_OUTPUT_URI, file.getUri().toString());
                launchGifService(intent, isGif);
                return;
            }
        }

        launchGifPicker(intent, filename, mime);
    }

    private void launchGifPicker(final Intent serviceIntent, final String filename,
                                 final String mime) {
        pendingGifActive = true;
        pendingGifStreamUrl = serviceIntent.getStringExtra(GifCreationService.EXTRA_STREAM_URL);
        pendingGifStartMs = serviceIntent.getLongExtra(GifCreationService.EXTRA_START_MS, 0);
        pendingGifEndMs = serviceIntent.getLongExtra(GifCreationService.EXTRA_END_MS, 0);
        pendingGifFormat = serviceIntent.getStringExtra(GifCreationService.EXTRA_FORMAT);
        pendingGifFileName = filename;
        pendingGifVideoTitle = serviceIntent.getStringExtra(GifCreationService.EXTRA_VIDEO_TITLE);

        final Uri initialPath;
        if (NewPipeSettings.useStorageAccessFramework(context)) {
            initialPath = null;
        } else {
            initialPath = Uri.parse(
                    NewPipeSettings.getDir(Environment.DIRECTORY_MOVIES).getAbsolutePath());
        }

        NoFileManagerSafeGuard.launchSafe(requestGifSaveAsLauncher,
                StoredFileHelper.getNewPicker(context, filename, mime, initialPath), TAG, context);
    }

    private void requestGifSaveAsResult(@NonNull final ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || !pendingGifActive) {
            return;
        }
        if (result.getData() == null || result.getData().getData() == null) {
            showFailedDialog(R.string.general_error);
            return;
        }

        final Uri uri = result.getData().getData();
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (final SecurityException e) {
            Log.w(TAG, "Could not take persistable URI permission", e);
        }

        final Intent intent = new Intent(context, GifCreationService.class);
        intent.putExtra(GifCreationService.EXTRA_STREAM_URL, pendingGifStreamUrl);
        intent.putExtra(GifCreationService.EXTRA_START_MS, pendingGifStartMs);
        intent.putExtra(GifCreationService.EXTRA_END_MS, pendingGifEndMs);
        intent.putExtra(GifCreationService.EXTRA_FORMAT, pendingGifFormat);
        intent.putExtra(GifCreationService.EXTRA_FILE_NAME, pendingGifFileName);
        intent.putExtra(GifCreationService.EXTRA_VIDEO_TITLE, pendingGifVideoTitle);
        intent.putExtra(GifCreationService.EXTRA_OUTPUT_URI, uri.toString());

        final boolean isGif = "gif".equals(pendingGifFormat);
        pendingGifActive = false;
        launchGifService(intent, isGif);
    }

    private void launchGifService(final Intent intent, final boolean isGif) {
        ContextCompat.startForegroundService(context, intent);
        Toast.makeText(context,
                isGif ? R.string.gif_creation_started : R.string.webp_creation_started,
                Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void checkSelectedDownload(final StoredDirectoryHelper mainStorage,
                                       final Uri targetFile,
                                       final String filename,
                                       final String mime) {
        StoredFileHelper storage;

        try {
            if (mainStorage == null) {
                // using SAF on older android version
                storage = new StoredFileHelper(context, null, targetFile, "");
            } else if (targetFile == null) {
                // the file does not exist, but it is probably used in a pending download
                storage = new StoredFileHelper(mainStorage.getUri(), filename, mime,
                        mainStorage.getTag());
            } else {
                // the target filename is already use, attempt to use it
                storage = new StoredFileHelper(context, mainStorage.getUri(), targetFile,
                        mainStorage.getTag());
            }
        } catch (final Exception e) {
            ErrorUtil.createNotification(requireContext(),
                    new ErrorInfo(e, UserAction.DOWNLOAD_FAILED, "Getting storage"));
            return;
        }

        // get state of potential mission referring to the same file
        final MissionState state = downloadManager.checkForExistingMission(storage);
        @StringRes final int msgBtn;
        @StringRes final int msgBody;

        // this switch checks if there is already a mission referring to the same file
        switch (state) {
            case Finished: // there is already a finished mission
                msgBtn = R.string.overwrite;
                msgBody = R.string.overwrite_finished_warning;
                break;
            case Pending:
                msgBtn = R.string.overwrite;
                msgBody = R.string.download_already_pending;
                break;
            case PendingRunning:
                msgBtn = R.string.generate_unique_name;
                msgBody = R.string.download_already_running;
                break;
            case None: // there is no mission referring to the same file
                if (mainStorage == null) {
                    // This part is called if:
                    // * using SAF on older android version
                    // * save path not defined
                    // * if the file exists overwrite it, is not necessary ask
                    if (!storage.existsAsFile() && !storage.create()) {
                        showFailedDialog(R.string.error_file_creation);
                        return;
                    }
                    continueSelectedDownload(storage);
                    return;
                } else if (targetFile == null) {
                    // This part is called if:
                    // * the filename is not used in a pending/finished download
                    // * the file does not exists, create

                    if (!mainStorage.mkdirs()) {
                        showFailedDialog(R.string.error_path_creation);
                        return;
                    }

                    storage = mainStorage.createFile(filename, mime);
                    if (storage == null || !storage.canWrite()) {
                        showFailedDialog(R.string.error_file_creation);
                        return;
                    }

                    continueSelectedDownload(storage);
                    return;
                }
                msgBtn = R.string.overwrite;
                msgBody = R.string.overwrite_unrelated_warning;
                break;
            default:
                return; // unreachable
        }

        final AlertDialog.Builder askDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.download_dialog_title)
                .setMessage(msgBody)
                .setNegativeButton(R.string.cancel, null);
        final StoredFileHelper finalStorage = storage;


        if (mainStorage == null) {
            // This part is called if:
            // * using SAF on older android version
            // * save path not defined
            switch (state) {
                case Pending:
                case Finished:
                    askDialog.setPositiveButton(msgBtn, (dialog, which) -> {
                        dialog.dismiss();
                        downloadManager.forgetMission(finalStorage);
                        continueSelectedDownload(finalStorage);
                    });
                    break;
            }

            askDialog.show();
            return;
        }

        askDialog.setPositiveButton(msgBtn, (dialog, which) -> {
            dialog.dismiss();

            StoredFileHelper storageNew;
            switch (state) {
                case Finished:
                case Pending:
                    downloadManager.forgetMission(finalStorage);
                case None:
                    if (targetFile == null) {
                        storageNew = mainStorage.createFile(filename, mime);
                    } else {
                        try {
                            // try take (or steal) the file
                            storageNew = new StoredFileHelper(context, mainStorage.getUri(),
                                    targetFile, mainStorage.getTag());
                        } catch (final IOException e) {
                            Log.e(TAG, "Failed to take (or steal) the file in "
                                    + targetFile.toString());
                            storageNew = null;
                        }
                    }

                    if (storageNew != null && storageNew.canWrite()) {
                        continueSelectedDownload(storageNew);
                    } else {
                        showFailedDialog(R.string.error_file_creation);
                    }
                    break;
                case PendingRunning:
                    storageNew = mainStorage.createUniqueFile(filename, mime);
                    if (storageNew == null) {
                        showFailedDialog(R.string.error_file_creation);
                    } else {
                        continueSelectedDownload(storageNew);
                    }
                    break;
            }
        });

        askDialog.show();
    }

    private void continueSelectedDownload(@NonNull final StoredFileHelper storage) {
        if (!storage.canWrite()) {
            showFailedDialog(R.string.permission_denied);
            return;
        }

        // check if the selected file has to be overwritten, by simply checking its length
        try {
            if (storage.length() > 0) {
                storage.truncate();
            }
        } catch (final IOException e) {
            Log.e(TAG, "Failed to truncate the file: " + storage.getUri().toString(), e);
            showFailedDialog(R.string.overwrite_failed);
            return;
        }

        final Stream selectedStream;
        Stream secondaryStream = null;
        final char kind;
        int threads = dialogBinding.threads.getProgress() + 1;
        final String[] urls;
        final List<MissionRecoveryInfo> recoveryInfo;
        String psName = null;
        String[] psArgs = null;
        long nearLength = 0;

        // more download logic: select muxer, subtitle converter, etc.
        final int checkedRadioButtonId = dialogBinding.videoAudioGroup.getCheckedRadioButtonId();
        if (checkedRadioButtonId == R.id.audio_button) {
            kind = 'a';
            selectedStream = audioStreamsAdapter.getItem(selectedAudioIndex);

            if (selectedStream.getFormat() == MediaFormat.M4A) {
                psName = Postprocessing.ALGORITHM_M4A_NO_DASH;
            } else if (selectedStream.getFormat() == MediaFormat.WEBMA_OPUS) {
                psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
            }
        } else if (checkedRadioButtonId == R.id.video_button) {
            kind = 'v';
            selectedStream = videoStreamsAdapter.getItem(selectedVideoIndex);

            final SecondaryStreamHelper<AudioStream> secondary = videoStreamsAdapter
                    .getAllSecondary()
                    .get(wrappedVideoStreams.getStreamsList().indexOf(selectedStream));

            if (secondary != null) {
                secondaryStream = secondary.getStream();

                if (selectedStream.getFormat() == MediaFormat.MPEG_4) {
                    psName = Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER;
                } else {
                    psName = Postprocessing.ALGORITHM_WEBM_MUXER;
                }

                final long videoSize = wrappedVideoStreams.getSizeInBytes(
                        (VideoStream) selectedStream);

                // set nearLength, only, if both sizes are fetched or known. This probably
                // does not work on slow networks but is later updated in the downloader
                if (secondary.getSizeInBytes() > 0 && videoSize > 0) {
                    nearLength = secondary.getSizeInBytes() + videoSize;
                }
            }
        } else if (checkedRadioButtonId == R.id.subtitle_button) {
            threads = 1; // use unique thread for subtitles due small file size
            kind = 's';
            selectedStream = subtitleStreamsAdapter.getItem(selectedSubtitleIndex);

            if (selectedStream.getFormat() == MediaFormat.TTML) {
                psName = Postprocessing.ALGORITHM_TTML_CONVERTER;
                psArgs = new String[]{
                        selectedStream.getFormat().getSuffix(),
                        "false" // ignore empty frames
                };
            }
        } else {
            return;
        }

        if (secondaryStream == null) {
            urls = new String[] {
                    selectedStream.getContent()
            };
            recoveryInfo = List.of(new MissionRecoveryInfo(selectedStream));
        } else {
            if (secondaryStream.getDeliveryMethod() != PROGRESSIVE_HTTP) {
                throw new IllegalArgumentException("Unsupported stream delivery format"
                        + secondaryStream.getDeliveryMethod());
            }

            urls = new String[] {
                    selectedStream.getContent(), secondaryStream.getContent()
            };
            recoveryInfo = List.of(
                    new MissionRecoveryInfo(selectedStream),
                    new MissionRecoveryInfo(secondaryStream)
            );
        }

        DownloadManagerService.startMission(context, urls, storage, kind, threads,
                currentInfo, psName, psArgs, nearLength, new ArrayList<>(recoveryInfo));

        Toast.makeText(context, getString(R.string.download_has_started),
                Toast.LENGTH_SHORT).show();

        dismiss();
    }
}
