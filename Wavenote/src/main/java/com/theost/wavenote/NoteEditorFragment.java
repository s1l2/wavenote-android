package com.theost.wavenote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils.SimpleStringSplitter;
import android.text.TextWatcher;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;

import androidx.core.content.ContextCompat;
import androidx.core.view.MenuCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.theost.wavenote.models.Note;
import com.theost.wavenote.models.Tag;
import com.theost.wavenote.utils.AutoBullet;
import com.theost.wavenote.utils.ContextUtils;
import com.theost.wavenote.utils.DisplayUtils;
import com.theost.wavenote.utils.DrawableUtils;
import com.theost.wavenote.utils.MatchOffsetHighlighter;
import com.theost.wavenote.utils.NoteUtils;
import com.theost.wavenote.utils.PrefUtils;
import com.theost.wavenote.utils.SyntaxHighlighter;
import com.theost.wavenote.utils.WavenoteLinkify;
import com.theost.wavenote.utils.WavenoteMovementMethod;
import com.theost.wavenote.utils.SpaceTokenizer;
import com.theost.wavenote.utils.TagsMultiAutoCompleteTextView;
import com.theost.wavenote.utils.TagsMultiAutoCompleteTextView.OnTagAddedListener;
import com.theost.wavenote.utils.TextHighlighter;
import com.theost.wavenote.utils.ThemeUtils;
import com.theost.wavenote.utils.WidgetUtils;
import com.theost.wavenote.widgets.WavenoteEditText;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.simperium.client.Bucket;
import com.simperium.client.BucketObjectMissingException;
import com.simperium.client.Query;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Objects;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import static com.theost.wavenote.utils.SearchTokenizer.SPACE;

public class NoteEditorFragment extends Fragment implements Bucket.Listener<Note>,
        TextWatcher, OnTagAddedListener, View.OnFocusChangeListener,
        WavenoteEditText.OnSelectionChangedListener,
        ShareBottomSheetDialog.ShareSheetListener,
        HistoryBottomSheetDialog.HistorySheetListener,
        WavenoteEditText.OnCheckboxToggledListener {

    public static final String ARG_NEW_NOTE = "new_note";
    public static final String ARG_MARKDOWN_ENABLED = "markdown_enabled";
    public static final String ARG_PREVIEW_ENABLED = "preview_enabled";
    public static final String ARG_IS_FROM_WIDGET = "is_from_widget";
    public static final String ARG_ITEM_ID = "item_id";
    public static final String ARG_MATCH_OFFSETS = "match_offsets";
    public static final String STATE_NOTE_ID = "state_note_id";
    private static final int AUTOSAVE_DELAY_MILLIS = 2000;
    private static final int MAX_REVISIONS = 30;
    private static final int PUBLISH_TIMEOUT = 20000;
    private static final int HISTORY_TIMEOUT = 10000;
    private boolean isThemeLight = false;
    private Note mNote;
    private final Runnable mAutoSaveRunnable = this::saveAndSyncNote;
    private Bucket<Note> mNotesBucket;
    private View mRootView;
    private View mTagPadding;
    private WavenoteEditText mContentEditText;
    private ChipGroup mTagChips;
    private TagsMultiAutoCompleteTextView mTagInput;
    private Handler mAutoSaveHandler;
    private Handler mPublishTimeoutHandler;
    private Handler mHistoryTimeoutHandler;
    private LinearLayout mPlaceholderView;
    private CursorAdapter mAutocompleteAdapter;
    private boolean mIsLoadingNote;
    private boolean mIsMarkdownEnabled;
    private boolean mIsPreviewEnabled;
    private boolean mShouldScrollToSearchMatch;
    private ActionMode mActionMode;
    private MenuItem mCopyMenuItem;
    private MenuItem mShareMenuItem;
    private MenuItem mViewLinkMenuItem;
    private String mLinkUrl;
    private String mLinkText;
    private MatchOffsetHighlighter mHighlighter;
    private Drawable mCallIcon;
    private Drawable mCopyIcon;
    private Drawable mEmailIcon;
    private Drawable mMapIcon;
    private Drawable mShareIcon;
    private Drawable mBrowserIcon;
    private MatchOffsetHighlighter.SpanFactory mMatchHighlighter;
    private String mMatchOffsets;
    private int mCurrentCursorPosition;
    private HistoryBottomSheetDialog mHistoryBottomSheet;
    private boolean mIsPaused;
    private boolean mIsFromWidget;
    private Function1 listener;
    private ColorSheet colorSheet;
    private int[] colors;
    private String lightColor;
    private String darkColor;

    // Hides the history bottom sheet if no revisions are loaded
    private final Runnable mHistoryTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }

            requireActivity().runOnUiThread(() -> {
                if (mHistoryBottomSheet.getDialog() != null && mHistoryBottomSheet.getDialog().isShowing() && !mHistoryBottomSheet.isHistoryLoaded()) {
                    mHistoryBottomSheet.dismiss();
                    Toast.makeText(getActivity(), R.string.error_history, Toast.LENGTH_LONG).show();
                }
            });
        }
    };
    private InfoBottomSheetDialog mInfoBottomSheet;
    private ShareBottomSheetDialog mShareBottomSheet;
    // Contextual action bar for dealing with links
    private final ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        // Called when the action mode is created; startActionMode() was called
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();

            if (inflater != null) {
                inflater.inflate(R.menu.view_link, menu);
                mCopyMenuItem = menu.findItem(R.id.menu_copy);
                mShareMenuItem = menu.findItem(R.id.menu_share);
                mViewLinkMenuItem = menu.findItem(R.id.menu_view_link);
                mode.setTitle(getString(R.string.link));
                mode.setTitleOptionalHint(false);

                DrawableUtils.tintMenuWithAttribute(getActivity(), menu, R.attr.toolbarIconColor);
            }

            int colorResId = ThemeUtils.isLightTheme(requireContext()) ? R.color.background_light : R.color.background_dark;
            requireActivity().getWindow().setStatusBarColor(getResources().getColor(colorResId, requireActivity().getTheme()));
            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode, but
        // may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        // Called when the user selects a contextual menu item
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.menu_view_link:
                    if (mLinkUrl != null) {
                        try {
                            Uri uri = Uri.parse(mLinkUrl);
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(uri);
                            startActivity(i);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        mode.finish(); // Action picked, so close the CAB
                    }
                    return true;
                case R.id.menu_copy:
                    if (mLinkText != null && getActivity() != null) {
                        copyToClipboard(mLinkText);
                        Snackbar.make(mRootView, R.string.link_copied, Snackbar.LENGTH_SHORT).show();
                        mode.finish();
                    }
                    return true;
                case R.id.menu_share:
                    if (mLinkText != null) {
                        showShareSheet();
                        mode.finish();
                    }
                    return true;
                default:
                    return false;
            }
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            new Handler().postDelayed(
                    () -> requireActivity().getWindow().setStatusBarColor(getResources().getColor(android.R.color.transparent, requireActivity().getTheme())),
                    requireContext().getResources().getInteger(android.R.integer.config_mediumAnimTime)
            );
        }
    };
    private Snackbar mPublishingSnackbar;
    private boolean mHideActionOnSuccess;
    // Resets note publish status if Simperium never returned the new publish status
    private final Runnable mPublishTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {

                mNote.setPublished(!mNote.isPublished());
                mNote.save();

                updatePublishedState(false);
            });
        }
    };
    private NoteMarkdownFragment mNoteMarkdownFragment;
    private String mCss;
    private WebView mMarkdown;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public NoteEditorFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInfoBottomSheet = new InfoBottomSheetDialog(this);
        mShareBottomSheet = new ShareBottomSheetDialog(this, this);
        mHistoryBottomSheet = new HistoryBottomSheetDialog(this, this);

        Wavenote currentApp = (Wavenote) requireActivity().getApplication();
        mNotesBucket = currentApp.getNotesBucket();

        mCallIcon = DrawableUtils.tintDrawableWithAttribute(getActivity(), R.drawable.ic_call_white_24dp, R.attr.actionModeTextColor);
        mEmailIcon = DrawableUtils.tintDrawableWithAttribute(getActivity(), R.drawable.ic_email_24dp, R.attr.actionModeTextColor);
        mMapIcon = DrawableUtils.tintDrawableWithAttribute(getActivity(), R.drawable.ic_map_24dp, R.attr.actionModeTextColor);
        mBrowserIcon = DrawableUtils.tintDrawableWithAttribute(getActivity(), R.drawable.ic_browser_24dp, R.attr.actionModeTextColor);
        mCopyIcon = DrawableUtils.tintDrawableWithAttribute(getActivity(), R.drawable.ic_copy_24dp, R.attr.actionModeTextColor);
        mShareIcon = DrawableUtils.tintDrawableWithAttribute(getActivity(), R.drawable.ic_share_24dp, R.attr.actionModeTextColor);

        mAutoSaveHandler = new Handler();
        mPublishTimeoutHandler = new Handler();
        mHistoryTimeoutHandler = new Handler();

        mMatchHighlighter = new TextHighlighter(requireActivity(),
                R.attr.editorSearchHighlightForegroundColor, R.attr.editorSearchHighlightBackgroundColor);
        mAutocompleteAdapter = new CursorAdapter(getActivity(), null, 0x0) {
            @SuppressLint("InflateParams")
            @Override
            public View newView(Context context, Cursor cursor, ViewGroup parent) {
                Activity activity = (Activity) context;
                if (activity == null) return null;
                return activity.getLayoutInflater().inflate(R.layout.tag_autocomplete_list_item, null);
            }

            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                TextView textView = (TextView) view;
                textView.setText(convertToString(cursor));
            }

            @Override
            public CharSequence convertToString(Cursor cursor) {
                return cursor.getString(cursor.getColumnIndex(Tag.NAME_PROPERTY));
            }

            @Override
            public Cursor runQueryOnBackgroundThread(CharSequence filter) {
                Activity activity = getActivity();
                if (activity == null) return null;
                Wavenote application = (Wavenote) activity.getApplication();
                Query<Tag> query = application.getTagsBucket().query();
                // make the tag name available to the cursor
                query.include(Tag.NAME_PROPERTY);
                // sort the tags by their names
                query.order(Tag.NAME_PROPERTY);
                // if there's a filter string find only matching tag names
                if (filter != null)
                    query.where(Tag.NAME_PROPERTY, Query.ComparisonType.LIKE, String.format("%s%%", filter));
                return query.execute();
            }
        };
        WidgetUtils.updateNoteWidgets(requireActivity().getApplicationContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.fragment_note_editor, container, false);
        mContentEditText = mRootView.findViewById(R.id.note_content);
        mContentEditText.addOnSelectionChangedListener(this);
        mContentEditText.setOnCheckboxToggledListener(this);
        mContentEditText.setMovementMethod(WavenoteMovementMethod.getInstance());
        mContentEditText.setOnFocusChangeListener(this);
        mTagInput = mRootView.findViewById(R.id.tag_input);
        mTagInput.setDropDownBackgroundResource(R.drawable.bg_list_popup);
        mTagInput.setTokenizer(new SpaceTokenizer());
        mTagInput.setOnFocusChangeListener(this);
        mTagChips = mRootView.findViewById(R.id.tag_chips);
        mTagPadding = mRootView.findViewById(R.id.tag_padding);
        mHighlighter = new MatchOffsetHighlighter(mMatchHighlighter, mContentEditText);
        mPlaceholderView = mRootView.findViewById(R.id.placeholder);

        colorSheet = new ColorSheet();
        colorSheet.cornerRadius(8);

        colors = getResources().getIntArray(R.array.colorsheet_colors);
        lightColor = "#" + Integer.toHexString(ContextCompat.getColor(requireContext(), R.color.background_light)).substring(2).toUpperCase();
        darkColor = "#" + Integer.toHexString(ContextCompat.getColor(requireContext(), R.color.background_dark)).substring(2).toUpperCase();
        if (ThemeUtils.isLightTheme(requireContext())) {
            isThemeLight = true;
        }

        listener = (new Function1() {
            public Object invoke(Object var1) {
                this.invoke(((Number)var1).intValue());
                return Unit.INSTANCE;
            }

            final void invoke(int color) {
                mNote.setSelectedColor(color);
            }
        });

        if (DisplayUtils.isLargeScreenLandscape(getActivity()) && mNote == null) {
            mPlaceholderView.setVisibility(View.VISIBLE);
            requireActivity().invalidateOptionsMenu();
            mMarkdown = mRootView.findViewById(R.id.markdown);
            mCss = ThemeUtils.isLightTheme(requireContext())
                    ? ContextUtils.readCssFile(requireContext(), "light.css")
                    : ContextUtils.readCssFile(requireContext(), "dark.css");
        }

        mTagInput.setAdapter(mAutocompleteAdapter);
        Bundle arguments = getArguments();

        if (arguments != null && arguments.containsKey(ARG_ITEM_ID)) {
            // Load note if we were passed a note Id
            String key = arguments.getString(ARG_ITEM_ID);

            if (arguments.containsKey(ARG_MATCH_OFFSETS)) {
                mMatchOffsets = arguments.getString(ARG_MATCH_OFFSETS);
            }

            mIsFromWidget = arguments.getBoolean(ARG_IS_FROM_WIDGET);
            new LoadNoteTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key);
        } else if (DisplayUtils.isLargeScreenLandscape(getActivity()) && savedInstanceState != null) {
            // Restore selected note when in dual pane mode
            String noteId = savedInstanceState.getString(STATE_NOTE_ID);

            if (noteId != null) {
                setNote(noteId);
            }
        }

        ViewTreeObserver viewTreeObserver = mContentEditText.getViewTreeObserver();
        viewTreeObserver.addOnGlobalLayoutListener(() -> {
            // If a note was loaded with search matches, scroll to the first match in the editor
            if (mShouldScrollToSearchMatch && mMatchOffsets != null) {
                if (!isAdded()) {
                    return;
                }

                // Get the character location of the first search match
                int matchLocation = MatchOffsetHighlighter.getFirstMatchLocation(
                        mContentEditText.getText(),
                        mMatchOffsets
                );
                if (matchLocation == 0) {
                    return;
                }

                // Calculate how far to scroll to bring the match into view
                Layout layout = mContentEditText.getLayout();
                int lineTop = layout.getLineTop(layout.getLineForOffset(matchLocation));
                ((NestedScrollView) mRootView).smoothScrollTo(0, lineTop);
                mShouldScrollToSearchMatch = false;
            }
        });
        setHasOptionsMenu(true);
        return mRootView;
    }

    public void scrollToMatch(int location) {
        if (isAdded()) {
            // Calculate how far to scroll to bring the match into view
            Layout layout = mContentEditText.getLayout();
            int lineTop = layout.getLineTop(layout.getLineForOffset(location));
            ((NestedScrollView) mRootView).smoothScrollTo(0, lineTop);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mNotesBucket.start();
        mNotesBucket.addListener(this);
        mTagInput.setOnTagAddedListener(this);

        if (mContentEditText != null) {
            mContentEditText.setTextSize(TypedValue.COMPLEX_UNIT_SP, PrefUtils.getFontSize(getActivity()));

            if (mContentEditText.hasFocus()) {
                showSoftKeyboard();
            }
        }
    }

    private void showSoftKeyboard() {
        new Handler().postDelayed(() -> {
            if (getActivity() == null) {
                return;
            }

            InputMethodManager inputMethodManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(mContentEditText, 0);
            }
        }, 100);
    }

    @Override
    public void onPause() {
        super.onPause();  // Always call the superclass method first
        mIsPaused = true;

        // Hide soft keyboard if it is showing...
        DisplayUtils.hideKeyboard(mContentEditText);

        mTagInput.setOnTagAddedListener(null);

        if (mAutoSaveHandler != null) {
            mAutoSaveHandler.removeCallbacks(mAutoSaveRunnable);
        }

        if (mPublishTimeoutHandler != null) {
            mPublishTimeoutHandler.removeCallbacks(mPublishTimeoutRunnable);
        }

        if (mHistoryTimeoutHandler != null) {
            mHistoryTimeoutHandler.removeCallbacks(mHistoryTimeoutRunnable);
        }

        mHighlighter.stop();
        saveNote();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mNotesBucket.removeListener(this);
        mNotesBucket.stop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (DisplayUtils.isLargeScreenLandscape(getActivity()) && mNote != null) {
            outState.putString(STATE_NOTE_ID, mNote.getSimperiumKey());
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull MenuInflater inflater) {

        if (!isAdded() || (!mIsFromWidget && DisplayUtils.isLargeScreenLandscape(getActivity()) && mNoteMarkdownFragment == null)) {
            return;
        }

        inflater.inflate(R.menu.note_editor, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);

        final MenuItem colorItem = menu.findItem(R.id.menu_color);
        final ImageView colorItemView = (ImageView) colorItem.getActionView();
        colorItemView.setImageDrawable(colorItem.getIcon());
        TypedValue outValue = new TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        colorItemView.setBackgroundResource(outValue.resourceId);

        int viewSize  = (int) (48 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(viewSize, viewSize);
        colorItemView.setLayoutParams(params);
        colorItemView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        colorItemView.setOnClickListener(v -> onOptionsItemSelected(colorItem));
        colorItemView.setOnLongClickListener(v -> {
            pickTextColor();
            return true;
        });

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_photos:
                startPhotosActivity();
                return true;
            case R.id.menu_color:
                changeTextColor();
                return true;
            case R.id.menu_sheet:
                startChordsActivity();
                return true;
            case R.id.menu_checklist:
                DrawableUtils.startAnimatedVectorDrawable(item.getIcon());
                insertChecklist();
                return true;
            case R.id.menu_copy:
                copyToClipboard(mNote.getPublishedUrl());
                Snackbar.make(mRootView, R.string.link_copied, Snackbar.LENGTH_SHORT).show();
                return true;
            case R.id.menu_history:
                showHistory();
                return true;
            case R.id.menu_info:
                showInfo();
                return true;
            case R.id.menu_markdown:
                setMarkdown(!item.isChecked());
                return true;
            case R.id.menu_pin:
                NoteUtils.setNotePin(mNote, !item.isChecked());
                requireActivity().invalidateOptionsMenu();
                return true;
            case R.id.menu_publish:
                if (item.isChecked()) {
                    unpublishNote();
                } else {
                    publishNote();
                }
                return true;
            case R.id.menu_share:
                shareNote();
                return true;
            case R.id.menu_audiotracks:
                // todo
                showNotAvailable();
                return true;
            case R.id.menu_import:
                // todo
                showNotAvailable();
                return true;
            case R.id.menu_export:
                // todo
                showNotAvailable();
                return true;
            case R.id.menu_trash:
                if (!isAdded()) {
                    return false;
                }
                deleteNote();
                return true;
            case android.R.id.home:
                if (!isAdded()) {
                    return false;
                }
                requireActivity().finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        if (mNote != null) {
            MenuItem infoItem = menu.findItem(R.id.menu_info);
            MenuItem pinItem = menu.findItem(R.id.menu_pin);
            MenuItem shareItem = menu.findItem(R.id.menu_share);
            MenuItem historyItem = menu.findItem(R.id.menu_history);
            MenuItem publishItem = menu.findItem(R.id.menu_publish);
            MenuItem copyLinkItem = menu.findItem(R.id.menu_copy);
            MenuItem markdownItem = menu.findItem(R.id.menu_markdown);
            MenuItem trashItem = menu.findItem(R.id.menu_trash);
            MenuItem checklistItem = menu.findItem(R.id.menu_checklist);
            MenuItem sheetItem = menu.findItem(R.id.menu_sheet);
            MenuItem photoItem = menu.findItem(R.id.menu_photos);
            MenuItem audioItem = menu.findItem(R.id.menu_audiotracks);
            MenuItem importItem = menu.findItem(R.id.menu_import);
            MenuItem exportItem = menu.findItem(R.id.menu_export);
            MenuItem colorItem = menu.findItem(R.id.menu_color);
            ImageView colorItemView = (ImageView) colorItem.getActionView();

            pinItem.setChecked(mNote.isPinned());
            publishItem.setChecked(mNote.isPublished());
            markdownItem.setChecked(mNote.isMarkdownEnabled());

            // Disable actions when note is in Trash or markdown view is shown on large device.
            if (mNote.isDeleted() || (mMarkdown != null && mMarkdown.getVisibility() == View.VISIBLE)) {
                infoItem.setEnabled(false);
                shareItem.setEnabled(false);
                historyItem.setEnabled(false);
                publishItem.setEnabled(false);
                copyLinkItem.setEnabled(false);
                checklistItem.setEnabled(false);
                colorItemView.setEnabled(false);
                sheetItem.setEnabled(false);
                photoItem.setEnabled(false);
                audioItem.setEnabled(false);
                importItem.setEnabled(false);
                exportItem.setEnabled(false);
                sheetItem.setEnabled(false);
                photoItem.setEnabled(false);
                audioItem.setEnabled(false);
                colorItemView.setEnabled(false);
                DrawableUtils.setMenuItemAlpha(checklistItem, 0.3);  // 0.3 is 30% opacity.
                DrawableUtils.setMenuItemAlpha(colorItem, 0.3);
                DrawableUtils.setMenuItemAlpha(sheetItem, 0.3);
                DrawableUtils.setMenuItemAlpha(photoItem, 0.3);
                DrawableUtils.setMenuItemAlpha(audioItem, 0.3);
                mContentEditText.setFocusable(false); // Disable NoteEditor if in Trash
            } else {
                infoItem.setEnabled(true);
                pinItem.setEnabled(true);
                shareItem.setEnabled(true);
                historyItem.setEnabled(true);
                publishItem.setEnabled(true);
                copyLinkItem.setEnabled(mNote.isPublished());
                markdownItem.setEnabled(true);
                checklistItem.setEnabled(true);
                colorItemView.setEnabled(true);
                sheetItem.setEnabled(true);
                photoItem.setEnabled(true);
                audioItem.setEnabled(true);
                importItem.setEnabled(true);
                exportItem.setEnabled(true);
                sheetItem.setEnabled(true);
                photoItem.setEnabled(true);
                audioItem.setEnabled(true);
                colorItemView.setEnabled(true);
                DrawableUtils.setMenuItemAlpha(checklistItem, 1.0);  // 1.0 is 100% opacity.
                DrawableUtils.setMenuItemAlpha(colorItem, 1.0);
                DrawableUtils.setMenuItemAlpha(sheetItem, 1.0);
                DrawableUtils.setMenuItemAlpha(photoItem, 1.0);
                DrawableUtils.setMenuItemAlpha(audioItem, 1.0);
                mContentEditText.setFocusable(true); // Enable NoteEditor if not in Trash
            }

            if (mNote.isDeleted()) {
                trashItem.setTitle(R.string.restore);
            } else {
                trashItem.setTitle(R.string.trash);
            }
        }

        DrawableUtils.tintMenuWithAttribute(getActivity(), menu, R.attr.toolbarIconColor);
        super.onPrepareOptionsMenu(menu);
    }

    // todo: remove when all features done
    private void showNotAvailable() {
        Toast toast = Toast.makeText(getContext(), getContext().getResources().getString(R.string.feature_error), Toast.LENGTH_SHORT);
        TextView v = toast.getView().findViewById(android.R.id.message);
        if( v != null) v.setGravity(Gravity.CENTER);
        toast.show();
    }

    private void insertChecklist() {
        try {
            mContentEditText.insertChecklist();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCheckboxToggled() {
        // Save note (using delay) after toggling a checkbox
        if (mAutoSaveHandler != null) {
            mAutoSaveHandler.removeCallbacks(mAutoSaveRunnable);
            mAutoSaveHandler.postDelayed(mAutoSaveRunnable, AUTOSAVE_DELAY_MILLIS);
        }
    }

    private void deleteNote() {
        NoteUtils.deleteNote(mNote, getActivity());
        requireActivity().finish();
    }

    protected void clearMarkdown() {
        mMarkdown.loadDataWithBaseURL("file:///android_asset/", mCss + "", "text/html", "utf-8", null);
    }

    protected void hideMarkdown() {
        mMarkdown.setVisibility(View.INVISIBLE);
    }

    protected void showMarkdown() {
        loadMarkdownData();
        mMarkdown.setVisibility(View.VISIBLE);

        new Handler().postDelayed(
                () -> requireActivity().invalidateOptionsMenu(),
                getResources().getInteger(R.integer.time_animation)
        );
    }

    private void shareNote() {
        if (mNote != null) {
            mContentEditText.clearFocus();
            showShareSheet();
        }
    }

    private void startPhotosActivity() {
        Intent intent = new Intent(getActivity(), PhotosActivity.class);
        intent.putExtra("noteId", mNote.getSimperiumKey());
        startActivity(intent);
    }

    private void startChordsActivity() {
        Intent intent = new Intent(getActivity(), ChordsActivity.class);
        intent.putExtra("chords", SyntaxHighlighter.getNoteChords(getContext(), mContentEditText.getText().toString()));
        startActivity(intent);
    }

    private void showHistory() {
        if (mNote != null && mNote.getVersion() > 1) {
            mContentEditText.clearFocus();
            mHistoryTimeoutHandler.postDelayed(mHistoryTimeoutRunnable, HISTORY_TIMEOUT);
            showHistorySheet();
        } else {
            Toast.makeText(getActivity(), R.string.error_history, Toast.LENGTH_LONG).show();
        }
    }

    private void showInfo() {
        if (mNote != null) {
            mContentEditText.clearFocus();
            saveNote();
            showInfoSheet();
        }
    }

    private void setMarkdown(boolean isChecked) {
        mIsMarkdownEnabled = isChecked;
        Activity activity = getActivity();

        if (activity instanceof NoteEditorActivity) {
            NoteEditorActivity editorActivity = (NoteEditorActivity) activity;

            if (mIsMarkdownEnabled) {
                editorActivity.showTabs();

                if (mNoteMarkdownFragment == null) {
                    // Get markdown fragment and update content
                    mNoteMarkdownFragment = editorActivity.getNoteMarkdownFragment();
                    mNoteMarkdownFragment.updateMarkdown(mContentEditText.getPlainTextContent().toString());
                }
            } else {
                editorActivity.hideTabs();
            }
        } else if (activity instanceof NotesActivity) {
            setMarkdownEnabled(mIsMarkdownEnabled);
            ((NotesActivity) getActivity()).setMarkdownShowing(false);
        }

        saveNote();

        // Set preference so that next new note will have markdown enabled.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(PrefUtils.PREF_MARKDOWN_ENABLED, isChecked);
        editor.apply();
    }

    private void setMarkdownEnabled(boolean enabled) {
        mIsMarkdownEnabled = enabled;

        if (mIsMarkdownEnabled) {
            loadMarkdownData();
        }
    }

    private void loadMarkdownData() {
        String formattedContent = NoteMarkdownFragment.getMarkdownFormattedContent(
                mCss,
                mContentEditText.getPlainTextContent().toString()
        );

        mMarkdown.loadDataWithBaseURL(null, formattedContent, "text/html", "utf-8", null);
    }

    public void setNote(String noteID, String matchOffsets) {
        if (mAutoSaveHandler != null)
            mAutoSaveHandler.removeCallbacks(mAutoSaveRunnable);

        mPlaceholderView.setVisibility(View.GONE);

        mMatchOffsets = matchOffsets;


        saveNote();

        new LoadNoteTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, noteID);
    }

    private void updateNote(Note updatedNote) {
        // update note if network change arrived
        mNote = updatedNote;
        refreshContent(true);
    }

    private void refreshContent(boolean isNoteUpdate) {
        if (mNote != null) {

            mNote.setThemeText(lightColor, darkColor, isThemeLight);

            // Restore the cursor position if possible.
            int cursorPosition = newCursorLocation(mNote.getContent().toString(), getNoteContentString(), mContentEditText.getSelectionEnd());

            mContentEditText.setText(mNote.getContent());

            if (isNoteUpdate) {
                // Save the note so any local changes get synced
                mNote.save();

                if (mContentEditText.hasFocus()
                        && cursorPosition != mContentEditText.getSelectionEnd()
                        && cursorPosition < Objects.requireNonNull(mContentEditText.getText()).length()) {
                    mContentEditText.setSelection(cursorPosition);
                }
            }

            afterTextChanged(mContentEditText.getText());
            mContentEditText.processChecklists();
            updateTagList();
        }
    }

    private void updateTagList() {
        setChips(mNote.getTagString());
        mTagInput.setText("");
    }

    private int newCursorLocation(String newText, String oldText, int cursorLocation) {
        // Ported from the iOS app :)
        // Cases:
        // 0. All text after cursor (and possibly more) was removed ==> put cursor at end
        // 1. Text was added after the cursor ==> no change
        // 2. Text was added before the cursor ==> location advances
        // 3. Text was removed after the cursor ==> no change
        // 4. Text was removed before the cursor ==> location retreats
        // 5. Text was added/removed on both sides of the cursor ==> not handled

        cursorLocation = Math.max(cursorLocation, 0);

        int newCursorLocation = cursorLocation;

        int deltaLength = newText.length() - oldText.length();

        // Case 0
        if (newText.length() < cursorLocation)
            return newText.length();

        boolean beforeCursorMatches = false;
        boolean afterCursorMatches = false;

        try {
            beforeCursorMatches = oldText.substring(0, cursorLocation).equals(newText.substring(0, cursorLocation));
            afterCursorMatches = oldText.substring(cursorLocation).equals(newText.substring(cursorLocation + deltaLength));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Cases 2 and 4
        if (!beforeCursorMatches && afterCursorMatches)
            newCursorLocation += deltaLength;

        // Cases 1, 3 and 5 have no change
        return newCursorLocation;
    }

    @Override
    public void onTagAdded(String tag) {
        if (mNote == null || !isAdded()) {
            return;
        }

        mNote.setTagString(mNote.getTagString() + String.valueOf(SPACE) + tag);
        mNote.setModificationDate(Calendar.getInstance());
        updateTagList();
        mNote.save();
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
        // Unused
    }

    @Override
    public void afterTextChanged(Editable editable) {
        attemptAutoList(editable);
        setTitleSpan(editable);
        mContentEditText.fixLineSpacing();
    }

    @Override
    public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
        // When text changes, start timer that will fire after AUTOSAVE_DELAY_MILLIS passes
        if (mAutoSaveHandler != null) {
            mAutoSaveHandler.removeCallbacks(mAutoSaveRunnable);
            mAutoSaveHandler.postDelayed(mAutoSaveRunnable, AUTOSAVE_DELAY_MILLIS);
        }

        // Remove search highlight spans when note content changes
        if (mMatchOffsets != null) {
            mMatchOffsets = null;
            mHighlighter.removeMatches();
        }

        if (!DisplayUtils.isLargeScreenLandscape(requireContext())) {
            ((NoteEditorActivity) requireActivity()).setSearchMatchBarVisible(false);
        }

        // Temporarily remove the text watcher as we process checklists to prevent callback looping
        mContentEditText.removeTextChangedListener(this);
        mContentEditText.processChecklists();
        mContentEditText.addTextChangedListener(this);
    }

    /**
     * Set the note title to be a larger size and bold style.
     *
     * Remove all existing spans before applying spans or performance issues will occur.  Since both
     * {@link RelativeSizeSpan} and {@link StyleSpan} inherit from {@link MetricAffectingSpan}, all
     * spans are removed when {@link MetricAffectingSpan} is removed.
     */
    private void setTitleSpan(Editable editable) {
        int newLinePosition = getNoteContentString().indexOf("\n");
        int titleEnd = newLinePosition;
        if (titleEnd == -1) titleEnd = editable.length();

        for (MetricAffectingSpan span : editable.getSpans(0, titleEnd, MetricAffectingSpan.class)) {
            if (span instanceof RelativeSizeSpan || span instanceof StyleSpan) {
                editable.removeSpan(span);
            }
        }

        if (newLinePosition == 0) {
            return;
        }

        int titleEndPosition = (newLinePosition > 0) ? newLinePosition : editable.length();
        editable.setSpan(new RelativeSizeSpan(1.3f), 0, titleEndPosition, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        editable.setSpan(new StyleSpan(Typeface.BOLD), 0, titleEndPosition, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    private void attemptAutoList(Editable editable) {
        int oldCursorPosition = mCurrentCursorPosition;
        mCurrentCursorPosition = mContentEditText.getSelectionStart();
        AutoBullet.apply(editable, oldCursorPosition, mCurrentCursorPosition);
        mCurrentCursorPosition = mContentEditText.getSelectionStart();
    }

    private void saveAndSyncNote() {
        if (mNote == null) {
            return;
        }

        new SaveNoteTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void setPlaceholderVisible(boolean isVisible) {
        if (isVisible) {
            mNote = null;
            mContentEditText.setText("");
        }

        if (mPlaceholderView != null) {
            mPlaceholderView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (!hasFocus) {
            String tags = getNoteTagsString().trim();

            if (mTagInput.getText().toString().trim().length() > 0) {
                onTagAdded(mTagInput.getText().toString());
            } else if (tags.length() > 0) {
                setChips(tags);
            }
        }
    }

    private Note getNote() {
        return mNote;
    }

    public void setNote(String noteID) {
        setNote(noteID, null);
    }

    private String getNoteContentString() {
        if (mContentEditText == null || mContentEditText.getText() == null) {
            return "";
        } else {
            return mContentEditText.getText().toString();
        }
    }

    private String getNoteTagsString() {
        StringBuilder tags = new StringBuilder();

        for (int i= 0; i < mTagChips.getChildCount(); i++) {
            tags.append(((Chip) mTagChips.getChildAt(i)).getText()).append(" ");
        }

        return tags.toString();
    }

    /**
     * Share bottom sheet callbacks
     */

    @Override
    public void onShareDismissed() {
    }

    /**
     * History bottom sheet listeners
     */

    @Override
    public void onHistoryCancelClicked() {
        mContentEditText.setText(mNote.getContent());
        if (mHistoryBottomSheet != null) {
            mHistoryBottomSheet.dismiss();
        }
    }

    @Override
    public void onHistoryRestoreClicked() {
        if (mHistoryBottomSheet != null) {
            mHistoryBottomSheet.dismiss();
        }
        saveAndSyncNote();
    }

    @Override
    public void onHistoryDismissed() {
        if (!mHistoryBottomSheet.didTapOnButton()) {
            mContentEditText.setText(mNote.getContent());
        }

        if (mHistoryTimeoutHandler != null) {
            mHistoryTimeoutHandler.removeCallbacks(mHistoryTimeoutRunnable);
        }
    }

    @Override
    public void onHistoryUpdateNote(Spannable content) {
        mContentEditText.setText(content);
    }

    private void saveNote() {
        try {
            if (mNote == null || mContentEditText == null || mIsLoadingNote ||
                    (mHistoryBottomSheet != null && mHistoryBottomSheet.getDialog() != null && mHistoryBottomSheet.getDialog().isShowing())) {
                return;
            } else {
                Wavenote application = (Wavenote) requireActivity().getApplication();
                Bucket<Note> notesBucket = application.getNotesBucket();
                mNote = notesBucket.get(mNote.getSimperiumKey());
                mIsPreviewEnabled = mNote.isPreviewEnabled();
            }

            Spannable content = mContentEditText.getPlainTextContent();
            String tagString = getNoteTagsString();

            if (mNote.hasChanges(content, tagString.trim(), mNote.isPinned(), mIsMarkdownEnabled, mIsPreviewEnabled)) {
                mNote.setContent(content);
                mNote.setTagString(tagString);
                mNote.setModificationDate(Calendar.getInstance());
                mNote.setMarkdownEnabled(mIsMarkdownEnabled);
                mNote.setPreviewEnabled(mIsPreviewEnabled);
                mNote.save();
            }
        } catch (BucketObjectMissingException exception) {
            exception.printStackTrace();
        }
    }

    // Checks if cursor is at a URL when the selection changes
    // If it is a URL, show the contextual action bar
    @Override
    public void onSelectionChanged(int selStart, int selEnd) {
        mCurrentCursorPosition = selEnd;
        if (selStart == selEnd) {
            Editable noteContent = mContentEditText.getText();
            if (noteContent == null)
                return;

            URLSpan[] urlSpans = noteContent.getSpans(selStart, selStart, URLSpan.class);
            if (urlSpans.length > 0) {
                URLSpan urlSpan = urlSpans[0];
                mLinkUrl = urlSpan.getURL();
                mLinkText = noteContent.subSequence(noteContent.getSpanStart(urlSpan), noteContent.getSpanEnd(urlSpan)).toString();
                if (mActionMode != null) {
                    mActionMode.setSubtitle(mLinkText);
                    updateMenuItems();
                    return;
                }

                // Show the Contextual Action Bar
                if (getActivity() != null) {
                    mActionMode = ((AppCompatActivity) getActivity()).startSupportActionMode(mActionModeCallback);
                    if (mActionMode != null) {
                        mActionMode.setSubtitle(mLinkText);
                    }

                    updateMenuItems();
                }
            } else if (mActionMode != null) {
                mActionMode.finish();
                mActionMode = null;
            }
        } else if (mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
        }
    }

    private void updateMenuItems() {
        mCopyMenuItem.setIcon(mCopyIcon);
        mShareMenuItem.setIcon(mShareIcon);

        if (mViewLinkMenuItem != null && mLinkUrl != null) {
            if (mLinkUrl.startsWith("tel:")) {
                mViewLinkMenuItem.setIcon(mCallIcon);
                mViewLinkMenuItem.setTitle(getString(R.string.call));
            } else if (mLinkUrl.startsWith("mailto:")) {
                mViewLinkMenuItem.setIcon(mEmailIcon);
                mViewLinkMenuItem.setTitle(getString(R.string.email));
            } else if (mLinkUrl.startsWith("geo:")) {
                mViewLinkMenuItem.setIcon(mMapIcon);
                mViewLinkMenuItem.setTitle(getString(R.string.view_map));
            } else {
                mViewLinkMenuItem.setIcon(mBrowserIcon);
                mViewLinkMenuItem.setTitle(getString(R.string.view_in_browser));
            }
        }
    }

    private void setPublishedNote(boolean isPublished) {
        if (mNote != null) {
            mNote.setPublished(isPublished);
            mNote.save();

            // reset publish status in 20 seconds if we don't hear back from Simperium
            mPublishTimeoutHandler.postDelayed(mPublishTimeoutRunnable, PUBLISH_TIMEOUT);
        }
    }

    private void updatePublishedState(boolean isSuccess) {
        if (mPublishingSnackbar == null) {
            return;
        }

        mPublishingSnackbar.dismiss();
        mPublishingSnackbar = null;

        if (isSuccess && isAdded()) {
            if (mNote.isPublished()) {
                if (mHideActionOnSuccess) {
                    Snackbar.make(mRootView, R.string.publish_successful, Snackbar.LENGTH_LONG)
                            .show();
                } else {
                    Snackbar.make(mRootView, R.string.publish_successful, Snackbar.LENGTH_LONG)
                            .setAction(
                                    R.string.undo,
                                    v -> {
                                        mHideActionOnSuccess = true;
                                        unpublishNote();
                                    }
                            )
                            .show();
                }

                copyToClipboard(mNote.getPublishedUrl());
            } else {
                if (mHideActionOnSuccess) {
                    Snackbar.make(mRootView, R.string.unpublish_successful, Snackbar.LENGTH_LONG)
                            .show();
                } else {
                    Snackbar.make(mRootView, R.string.unpublish_successful, Snackbar.LENGTH_LONG)
                            .setAction(
                                    R.string.undo,
                                    v -> {
                                        mHideActionOnSuccess = true;
                                        publishNote();
                                    }
                            )
                            .show();
                }
            }
        } else {
            if (mNote.isPublished()) {
                Snackbar.make(mRootView, R.string.unpublish_error, Snackbar.LENGTH_LONG)
                        .setAction(
                                R.string.retry,
                                v -> {
                                    mHideActionOnSuccess = true;
                                    unpublishNote();
                                }
                        ).show();
            } else {
                Snackbar.make(mRootView, R.string.publish_error, Snackbar.LENGTH_LONG)
                        .setAction(
                                R.string.retry,
                                v -> {
                                    mHideActionOnSuccess = true;
                                    publishNote();
                                }
                        ).show();
            }
        }

        mHideActionOnSuccess = false;
        requireActivity().invalidateOptionsMenu();
    }

    private void publishNote() {
        if (isAdded()) {
            mPublishingSnackbar = Snackbar.make(mRootView, R.string.publishing, Snackbar.LENGTH_INDEFINITE);
            mPublishingSnackbar.show();
        }

        setPublishedNote(true);
    }

    private void unpublishNote() {
        if (isAdded()) {
            mPublishingSnackbar = Snackbar.make(mRootView, R.string.unpublishing, Snackbar.LENGTH_INDEFINITE);
            mPublishingSnackbar.show();
        }

        setPublishedNote(false);
    }

    private void changeTextColor() {
        SyntaxHighlighter.changeTextStyle(mContentEditText, mNote.getTextStyle(), mNote.getSelectedColor(), mNote.getActiveColor());
    }

    private void pickTextColor() {
        colorSheet.colorPicker(colors, mNote.getSelectedColor(), true, listener);
        this.colorSheet.show(getParentFragmentManager());
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) requireActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.app_name), text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
        }
    }

    private void showShareSheet() {
        if (isAdded() && mShareBottomSheet != null && !mShareBottomSheet.isAdded()) {
            mShareBottomSheet.show(getParentFragmentManager(), mNote);
        }
    }

    private void showInfoSheet() {
        if (isAdded() && mInfoBottomSheet != null && !mInfoBottomSheet.isAdded()) {
            mInfoBottomSheet.show(getParentFragmentManager(), mNote);
        }
    }

    private void showHistorySheet() {
        if (isAdded() && mHistoryBottomSheet != null && !mHistoryBottomSheet.isAdded()) {
            // Request revisions for the current note
            mNotesBucket.getRevisions(mNote, MAX_REVISIONS, mHistoryBottomSheet.getRevisionsRequestCallbacks());
            saveNote();

            mHistoryBottomSheet.show(getParentFragmentManager(), mNote);
        }
    }

    /**
     * Simperium listeners
     */

    @Override
    public void onDeleteObject(Bucket<Note> noteBucket, Note note) {

    }

    @Override
    public void onNetworkChange(Bucket<Note> noteBucket, Bucket.ChangeType changeType, final String key) {
        if (changeType == Bucket.ChangeType.MODIFY) {
            if (getNote() != null && getNote().getSimperiumKey().equals(key)) {
                try {
                    final Note updatedNote = mNotesBucket.get(key);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (mPublishTimeoutHandler != null) {
                                mPublishTimeoutHandler.removeCallbacks(mPublishTimeoutRunnable);
                            }

                            updateNote(updatedNote);
                            updatePublishedState(true);
                        });
                    }
                } catch (BucketObjectMissingException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onSaveObject(Bucket<Note> noteBucket, Note note) {
        if (mIsPaused) {
            mNotesBucket.removeListener(this);
            mNotesBucket.stop();
        }
    }

    @Override
    public void onBeforeUpdateObject(Bucket<Note> bucket, Note note) {
        // Don't apply updates if we haven't loaded the note yet
        if (mIsLoadingNote)
            return;

        Note openNote = getNote();
        if (openNote == null || !openNote.getSimperiumKey().equals(note.getSimperiumKey()))
            return;

        note.setContent(mContentEditText.getPlainTextContent());
    }

    private static class LoadNoteTask extends AsyncTask<String, Void, Void> {
        WeakReference<NoteEditorFragment> mNoteEditorFragmentReference;

        LoadNoteTask(NoteEditorFragment fragment) {
            mNoteEditorFragmentReference = new WeakReference<>(fragment);
        }

        @Override
        protected void onPreExecute() {
            NoteEditorFragment fragment = mNoteEditorFragmentReference.get();

            if (fragment != null) {
                fragment.mContentEditText.removeTextChangedListener(fragment);
                fragment.mIsLoadingNote = true;
            }
        }

        @Override
        protected Void doInBackground(String... args) {

            NoteEditorFragment fragment = mNoteEditorFragmentReference.get();

            if (fragment == null || fragment.getActivity() == null) {
                return null;
            }

            String noteID = args[0];
            Wavenote application = (Wavenote) fragment.getActivity().getApplication();
            Bucket<Note> notesBucket = application.getNotesBucket();

            try {
                fragment.mNote = notesBucket.get(noteID);

                // Set the current note in NotesActivity when on a tablet
                if (fragment.getActivity() instanceof NotesActivity) {
                    ((NotesActivity) fragment.getActivity()).setCurrentNote(fragment.mNote);
                }

                // Set markdown and preview flags for current note
                if (fragment.mNote != null) {
                    fragment.mIsMarkdownEnabled = fragment.mNote.isMarkdownEnabled();
                    fragment.mIsPreviewEnabled = fragment.mNote.isPreviewEnabled();
                }
            } catch (BucketObjectMissingException e) {
                // See if the note is in the object store
                Bucket.ObjectCursor<Note> notesCursor = notesBucket.allObjects();

                while (notesCursor.moveToNext()) {
                    Note currentNote = notesCursor.getObject();

                    if (currentNote != null && currentNote.getSimperiumKey().equals(noteID)) {
                        fragment.mNote = currentNote;
                        return null;
                    }
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void nada) {
            final NoteEditorFragment fragment = mNoteEditorFragmentReference.get();
            if (fragment == null || fragment.getActivity() == null || fragment.getActivity().isFinishing()) {
                return;
            }

            fragment.refreshContent(false);

            if (fragment.mMatchOffsets != null) {
                int columnIndex = fragment.mNote.getBucket().getSchema().getFullTextIndex().getColumnIndex(Note.CONTENT_PROPERTY);
                fragment.mHighlighter.highlightMatches(fragment.mMatchOffsets, columnIndex);
                fragment.mShouldScrollToSearchMatch = true;
            }

            fragment.mContentEditText.addTextChangedListener(fragment);

            if (fragment.mNote != null && fragment.mNote.getContent().toString().isEmpty()) {
                // Show soft keyboard
                fragment.mContentEditText.requestFocus();

                new Handler().postDelayed(() -> {
                    if (fragment.getActivity() == null) {
                        return;
                    }

                    InputMethodManager inputMethodManager = (InputMethodManager) fragment.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

                    if (inputMethodManager != null) {
                        inputMethodManager.showSoftInput(fragment.mContentEditText, 0);
                    }
                }, 100);
            } else if (fragment.mNote != null) {
                // If we have a valid note, hide the placeholder
                fragment.setPlaceholderVisible(false);
            }

            fragment.updateMarkdownView();
            fragment.requireActivity().invalidateOptionsMenu();
            fragment.linkifyEditorContent();
            fragment.syntaxHighlightEditorContent();
            fragment.mIsLoadingNote = false;
        }
    }

    private static class SaveNoteTask extends AsyncTask<Void, Void, Void> {
        WeakReference<NoteEditorFragment> mNoteEditorFragmentReference;

        SaveNoteTask(NoteEditorFragment fragment) {
            mNoteEditorFragmentReference = new WeakReference<>(fragment);
        }

        @Override
        protected Void doInBackground(Void... args) {
            NoteEditorFragment fragment = mNoteEditorFragmentReference.get();

            if (fragment != null) {
                fragment.saveNote();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void nada) {
            NoteEditorFragment fragment = mNoteEditorFragmentReference.get();

            if (fragment != null && fragment.getActivity() != null && !fragment.getActivity().isFinishing()) {
                // Update links
                fragment.linkifyEditorContent();
                fragment.syntaxHighlightEditorContent();
                fragment.updateMarkdownView();
            }
        }
    }

    private void syntaxHighlightEditorContent() {
        if (getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        if (PrefUtils.getBoolPref(getActivity(), PrefUtils.PREF_DETECT_SYNTAX)) {
            SyntaxHighlighter.updateSyntaxHighlight(getContext(), mContentEditText, mNote.getActiveColor());
        }
    }

    private void linkifyEditorContent() {
        if (getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        if (PrefUtils.getBoolPref(getActivity(), PrefUtils.PREF_DETECT_LINKS)) {
            WavenoteLinkify.addLinks(mContentEditText, Linkify.ALL);
        }
    }

    // Show tabs if markdown is enabled globally, for current note, and not tablet landscape
    private void updateMarkdownView() {
        if (!mIsMarkdownEnabled) {
            return;
        }

        Activity activity = getActivity();
        if (activity instanceof NotesActivity) {
            // This fragment lives in NotesActivity, so load markdown in this fragment's WebView.
            loadMarkdownData();
        } else {
            // This fragment lives in the NoteEditorActivity's ViewPager.
            if (mNoteMarkdownFragment == null) {
                mNoteMarkdownFragment = ((NoteEditorActivity) requireActivity())
                        .getNoteMarkdownFragment();
                ((NoteEditorActivity) requireActivity()).showTabs();
            }
            // Load markdown in the sibling NoteMarkdownFragment's WebView.
            mNoteMarkdownFragment.updateMarkdown(mContentEditText.getPlainTextContent().toString());
        }
    }

    private ColorStateList getChipBackgroundColor() {
        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked}, // checked
                new int[] {-android.R.attr.state_checked}  // unchecked
        };

        int[] colors = new int[] {
                ThemeUtils.getColorFromAttribute(requireContext(), R.attr.chipCheckedOnBackgroundColor),
                ThemeUtils.getColorFromAttribute(requireContext(), R.attr.chipCheckedOffBackgroundColor)
        };

        return new ColorStateList(states, colors);
    }

    private void setChips(CharSequence text) {
        mTagPadding.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
        mTagChips.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
        mTagChips.setSingleSelection(true);
        mTagChips.removeAllViews();
        SimpleStringSplitter tags = new SimpleStringSplitter(SPACE);
        tags.setString(text.toString());

        for (String tag : tags) {
            final Chip chip = new Chip(requireContext());
            chip.setText(tag);
            chip.setCheckable(true);
            chip.setCheckedIcon(null);
            chip.setChipBackgroundColor(getChipBackgroundColor());
            chip.setTextColor(ThemeUtils.getColorFromAttribute(requireContext(), R.attr.chipTextColor));
            chip.setStateListAnimator(null);
            chip.setOnCheckedChangeListener((buttonView, isChecked) -> chip.setCloseIconVisible(isChecked));
            chip.setOnCloseIconClickListener(view -> {
                mTagChips.removeView(view);
                updateTags();
            });
            mTagChips.addView(chip);
        }
    }

    private void updateTags() {
        if (mNote == null) {
            return;
        }

        mNote.setTagString(getNoteTagsString());
        mNote.setModificationDate(Calendar.getInstance());
        updateTagList();
        mNote.save();
    }
}
