package com.theost.wavenote;

import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.theost.wavenote.models.Note;
import com.theost.wavenote.models.Tag;
import com.theost.wavenote.adapters.BaseCursorAdapter;
import com.theost.wavenote.utils.DisplayUtils;
import com.theost.wavenote.utils.DrawableUtils;
import com.theost.wavenote.utils.HtmlCompat;
import com.theost.wavenote.widgets.EmptyViewRecyclerView;
import com.simperium.client.Bucket;
import com.simperium.client.BucketObjectNameInvalid;
import com.simperium.client.Query;

import java.lang.ref.SoftReference;
import java.util.List;

import static com.theost.wavenote.models.Tag.NAME_PROPERTY;

public class TagsListFragment extends Fragment implements Bucket.Listener<Tag> {
    private Bucket<Note> mNotesBucket;
    private Bucket<Tag> mTagsBucket;
    private EmptyViewRecyclerView mTagsList;
    private ImageView mEmptyViewImage;
    private MenuItem mSearchMenuItem;
    private String mSearchQuery;
    private TagsAdapter mTagsAdapter;
    private TextView mEmptyViewText;
    private boolean mIsSearching;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public TagsListFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.tags_list, menu);
        DrawableUtils.tintMenuWithAttribute(getActivity(), menu, R.attr.toolbarIconColor);

        mSearchMenuItem = menu.findItem(R.id.menu_search);
        mSearchMenuItem.setOnActionExpandListener(
                new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        mIsSearching = false;
                        return true;
                    }

                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        mIsSearching = true;
                        return true;
                    }
                }
        );
        SearchView searchView = (SearchView) mSearchMenuItem.getActionView();
        LinearLayout searchEditFrame = searchView.findViewById(R.id.search_edit_frame);
        ((LinearLayout.LayoutParams) searchEditFrame.getLayoutParams()).leftMargin = 0;

        // Workaround for setting the search placeholder text color.
        @SuppressWarnings("ResourceType")
        String hintHexColor = getString(R.color.text_title_disabled).replace("ff", "");
        searchView.setQueryHint(
                HtmlCompat.fromHtml(
                        String.format(
                                "<font color=\"%s\">%s</font>",
                                hintHexColor,
                                getString(R.string.search_tags_hint)
                        )
                )
        );

        searchView.setOnQueryTextListener(
                new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextChange(String query) {
                        if (mSearchMenuItem.isActionViewExpanded()) {
                            mSearchQuery = query;
                            refreshTagsSearch();
                            mTagsList.scrollToPosition(0);
                            checkEmptyList();
                        }

                        return true;
                    }

                    @Override
                    public boolean onQueryTextSubmit(String queryText) {
                        return true;
                    }
                }
        );

        searchView.setOnCloseListener(
                () -> {
                    mIsSearching = false;
                    mSearchQuery = "";
                    refreshTags();
                    mTagsList.scrollToPosition(0);
                    checkEmptyList();
                    return false;
                }
        );
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tags_list, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setHasOptionsMenu(true);

        Wavenote application = (Wavenote) requireActivity().getApplication();
        mTagsBucket = application.getTagsBucket();
        mNotesBucket = application.getNotesBucket();

        mTagsList = requireActivity().findViewById(R.id.list);
        mTagsAdapter = new TagsAdapter();
        mTagsList.setAdapter(mTagsAdapter);
        mTagsList.setLayoutManager(new LinearLayoutManager(requireActivity()));
        View emptyView = requireActivity().findViewById(R.id.empty);
        mEmptyViewImage = emptyView.findViewById(R.id.image);
        mEmptyViewText = emptyView.findViewById(R.id.text);
        checkEmptyList();
        mTagsList.setEmptyView(emptyView);

        refreshTags();
    }

    @Override
    public void onResume() {
        super.onResume();

        mNotesBucket.start();
        mTagsBucket.start();

        mTagsBucket.addOnNetworkChangeListener(this);
        mTagsBucket.addOnSaveObjectListener(this);
        mTagsBucket.addOnDeleteObjectListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();

        mTagsBucket.removeOnNetworkChangeListener(this);
        mTagsBucket.removeOnSaveObjectListener(this);
        mTagsBucket.removeOnDeleteObjectListener(this);

        mNotesBucket.stop();
        mTagsBucket.stop();
    }

    public void checkEmptyList() {
        if (mIsSearching) {
            if (DisplayUtils.isLandscape(getActivity()) && !DisplayUtils.isLargeScreen(getActivity())) {
                setEmptyListImage(-1);
                setEmptyListMessage(getString(R.string.empty_tags_search));
            } else {
                setEmptyListImage(R.drawable.ic_search_24dp);
                setEmptyListMessage(getString(R.string.empty_tags_search));
            }
        } else {
            setEmptyListImage(R.drawable.ic_tag_24dp);
            setEmptyListMessage(getString(R.string.empty_tags));
        }
    }

    protected void refreshTags() {
        Query<Tag> tagQuery = Tag.all(mTagsBucket).reorder().orderByKey().include(Tag.NOTE_COUNT_INDEX_NAME);
        Bucket.ObjectCursor<Tag> cursor = tagQuery.execute();
        mTagsAdapter.swapCursor(cursor);
    }

    protected void refreshTagsSearch() {
        Query<Tag> tags = Tag.all(mTagsBucket)
                .where(NAME_PROPERTY, Query.ComparisonType.LIKE, "%" + mSearchQuery + "%")
                .orderByKey().include(Tag.NOTE_COUNT_INDEX_NAME)
                .reorder();
        Bucket.ObjectCursor<Tag> cursor = tags.execute();
        mTagsAdapter.swapCursor(cursor);
    }

    private void setEmptyListImage(@DrawableRes int image) {
        if (mEmptyViewImage != null) {
            if (image != -1) {
                mEmptyViewImage.setVisibility(View.VISIBLE);
                mEmptyViewImage.setImageResource(image);
            } else {
                mEmptyViewImage.setVisibility(View.GONE);
            }
        }
    }

    private void setEmptyListMessage(String message) {
        if (mEmptyViewText != null && message != null) {
            mEmptyViewText.setText(message);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (isAdded()) {
                requireActivity().finish();
                return true;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    // Tag Bucket listeners
    @Override
    public void onDeleteObject(Bucket<Tag> bucket, Tag object) {
        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                if (mIsSearching) {
                    refreshTagsSearch();
                } else {
                    refreshTags();
                }
            });
        }
    }

    @Override
    public void onNetworkChange(Bucket<Tag> bucket, Bucket.ChangeType type, String key) {
        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                if (mIsSearching) {
                    refreshTagsSearch();
                } else {
                    refreshTags();
                }
            });
        }
    }

    @Override
    public void onSaveObject(Bucket<Tag> bucket, Tag object) {
        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                if (mIsSearching) {
                    refreshTagsSearch();
                } else {
                    refreshTags();
                }
            });
        }
    }

    @Override
    public void onBeforeUpdateObject(Bucket<Tag> bucket, Tag object) {
        // noop
    }

    private static class RemoveTagFromNotesTask extends AsyncTask<Tag, Void, Void> {
        private SoftReference<TagsListFragment> mTagsListFragmentReference;

        private RemoveTagFromNotesTask(TagsListFragment context) {
            mTagsListFragmentReference = new SoftReference<>(context);
        }

        @Override
        protected Void doInBackground(Tag... removedTags) {
            TagsListFragment fragment = mTagsListFragmentReference.get();
            Tag tag = removedTags[0];

            if (tag != null) {
                Bucket.ObjectCursor<Note> cursor = tag.findNotes(fragment.mNotesBucket);

                while (cursor.moveToNext()) {
                    Note note = cursor.getObject();
                    List<String> tags = note.getTags();
                    tags.remove(tag.getName());
                    note.setTags(tags);
                    note.save();
                }

                cursor.close();
            }

            return null;
        }
    }

    private class TagsAdapter extends BaseCursorAdapter<TagsAdapter.ViewHolder> {
        public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            private TextView tagTitle;
            private TextView tagCountTextView;

            private ViewHolder(View itemView) {
                super(itemView);

                tagTitle = itemView.findViewById(R.id.tag_name);
                tagCountTextView = itemView.findViewById(R.id.tag_count);
                ImageButton deleteButton = itemView.findViewById(R.id.tag_trash);

                deleteButton.setOnClickListener(view -> {
                    if (!isAdded() || hasItem(getAdapterPosition())) {
                        return;
                    }

                    final Tag tag = ((Bucket.ObjectCursor<Tag>) getItem(getAdapterPosition())).getObject();
                    final int tagCount = mNotesBucket.query().where("tags", Query.ComparisonType.EQUAL_TO, tag.getName()).count();
                    if (tagCount == 0) {
                        deleteTag(tag);
                    } else if (tagCount > 0) {
                        new MaterialDialog.Builder(getContext())
                                .title(R.string.delete_tag)
                                .content(R.string.confirm_delete_tag)
                                .positiveText(R.string.yes)
                                .negativeText(R.string.no)
                                .onPositive((dialog, which) -> deleteTag(tag))
                                .show();
                    }
                });
                deleteButton.setOnLongClickListener(v -> {
                    if (v.isHapticFeedbackEnabled()) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }

                    Toast.makeText(getContext(), requireContext().getString(R.string.delete_tag), Toast.LENGTH_SHORT).show();
                    return true;
                });

                itemView.setOnClickListener(this);
            }

            @Override
            public void onClick(View view) {
                if (!isAdded() || hasItem(getAdapterPosition())) {
                    return;
                }
                final Tag tag = ((Bucket.ObjectCursor<Tag>) getItem(getAdapterPosition())).getObject();
                new MaterialDialog.Builder(getContext())
                        .title(R.string.rename_tag)
                        .positiveText(R.string.save)
                        .negativeText(R.string.cancel)
                        .inputType(InputType.TYPE_CLASS_TEXT)
                        .input(R.string.tag_name, 0, (dialog, input) -> {
                            String value = input.toString().trim();
                            try {
                                tag.renameTo(value, mNotesBucket);
                            } catch (BucketObjectNameInvalid e) {
                                android.util.Log.e(Wavenote.TAG, "Unable to rename tag", e);
                            }
                        }).show();
            }

            private void deleteTag(Tag tag) {
                tag.delete();
                new RemoveTagFromNotesTask(TagsListFragment.this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, tag);
            }
        }

        private TagsAdapter() {
            super(null);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LayoutInflater inflater = LayoutInflater.from(context);
            View contactView = inflater.inflate(R.layout.tags_list_row, parent, false);
            return new ViewHolder(contactView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Cursor cursor) {
            Tag tag = ((Bucket.ObjectCursor<Tag>) cursor).getObject();
            holder.tagTitle.setText(tag.getName());
            final int tagCount = mNotesBucket.query().where("tags", Query.ComparisonType.EQUAL_TO, tag.getName()).count();

            if (tagCount > 0) {
                holder.tagCountTextView.setText(String.valueOf(tagCount));
            } else {
                holder.tagCountTextView.setText("");
            }
        }

        @Override
        public void swapCursor(Cursor newCursor) {
            super.swapCursor(newCursor);
        }
    }
}
