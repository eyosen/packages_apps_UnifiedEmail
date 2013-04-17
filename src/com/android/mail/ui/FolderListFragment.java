/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.ui;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.Contacts.Photo;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.android.mail.R;
import com.android.mail.adapter.DrawerItem;
import com.android.mail.browse.LetterTileUtils;
import com.android.mail.content.ObjectCursor;
import com.android.mail.content.ObjectCursorLoader;
import com.android.mail.photomanager.BitmapUtil;
import com.android.mail.providers.Account;
import com.android.mail.providers.AccountObserver;
import com.android.mail.providers.AllAccountObserver;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderObserver;
import com.android.mail.providers.FolderWatcher;
import com.android.mail.providers.RecentFolderObserver;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.FolderType;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The folder list UI component.
 */
public class FolderListFragment extends ListFragment implements
        LoaderManager.LoaderCallbacks<ObjectCursor<Folder>> {
    private static final String LOG_TAG = LogTag.getLogTag();
    /** The parent activity */
    private ControllableActivity mActivity;
    /** The underlying list view */
    private ListView mListView;
    /** URI that points to the list of folders for the current account. */
    private Uri mFolderListUri;
    /** True if you want a sectioned FolderList, false otherwise. */
    protected boolean mIsSectioned;
    /** An {@link ArrayList} of {@link FolderType}s to exclude from displaying. */
    private ArrayList<Integer> mExcludedFolderTypes;
    /** Object that changes folders on our behalf. */
    private FolderListSelectionListener mFolderChanger;
    /** Object that changes accounts on our behalf */
    private AccountController mAccountChanger;

    /** The currently selected folder (the folder being viewed).  This is never null. */
    private Uri mSelectedFolderUri = Uri.EMPTY;
    /**
     * The current folder from the controller.  This is meant only to check when the unread count
     * goes out of sync and fixing it.
     */
    private Folder mCurrentFolderForUnreadCheck;
    /** Parent of the current folder, or null if the current folder is not a child. */
    private Folder mParentFolder;

    private static final int FOLDER_LOADER_ID = 0;
    /** Key to store {@link #mParentFolder}. */
    private static final String ARG_PARENT_FOLDER = "arg-parent-folder";
    /** Key to store {@link #mIsSectioned} */
    private static final String ARG_IS_SECTIONED = "arg-is-sectioned";
    /** Key to store {@link #mFolderListUri}. */
    private static final String ARG_FOLDER_LIST_URI = "arg-folder-list-uri";
    /** Key to store {@link #mExcludedFolderTypes} */
    private static final String ARG_EXCLUDED_FOLDER_TYPES = "arg-excluded-folder-types";
    /** Key to store {@link #mType} */
    private static final String ARG_TYPE = "arg-flf-type";

    /** Either {@link #TYPE_DRAWER} for drawers or {@link #TYPE_TREE} for hierarchy trees */
    private int mType;
    /** This fragment is a drawer */
    private static final int TYPE_DRAWER = 0;
    /** This fragment is a folder tree */
    private static final int TYPE_TREE = 1;

    private static final String BUNDLE_LIST_STATE = "flf-list-state";
    private static final String BUNDLE_SELECTED_FOLDER = "flf-selected-folder";
    private static final String BUNDLE_SELECTED_TYPE = "flf-selected-type";

    private FolderListFragmentCursorAdapter mCursorAdapter;
    /** Observer to wait for changes to the current folder so we can change the selected folder */
    private FolderObserver mFolderObserver = null;
    /** Listen for account changes. */
    private AccountObserver mAccountObserver = null;

    /** Listen to changes to list of all accounts */
    private AllAccountObserver mAllAccountsObserver = null;
    /**
     * Type of currently selected folder: {@link DrawerItem#FOLDER_SYSTEM},
     * {@link DrawerItem#FOLDER_RECENT} or {@link DrawerItem#FOLDER_USER}.
     */
    // Setting to INERT_HEADER = leaving uninitialized.
    private int mSelectedFolderType = DrawerItem.UNSET;
    /** The current account according to the controller */
    private Account mCurrentAccount;

    /**
     * Constructor needs to be public to handle orientation changes and activity lifecycle events.
     */
    public FolderListFragment() {
        super();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(super.toString());
        sb.setLength(sb.length() - 1);
        sb.append(" folder=");
        sb.append(mFolderListUri);
        sb.append(" parent=");
        sb.append(mParentFolder);
        sb.append(" adapterCount=");
        sb.append(mCursorAdapter != null ? mCursorAdapter.getCount() : -1);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Creates a new instance of {@link FolderListFragment}. Gets the current account and current
     * folder through observers.
     */
    public static FolderListFragment ofDrawer() {
        final FolderListFragment fragment = new FolderListFragment();
        // The drawer is always sectioned
        final boolean isSectioned = true;
        fragment.setArguments(getBundleFromArgs(TYPE_DRAWER, null, null, isSectioned, null));
        return fragment;
    }

    /**
     * Creates a new instance of {@link FolderListFragment}, initialized
     * to display the folder and its immediate children.
     * @param folder parent folder whose children are shown
     *
     */
    public static FolderListFragment ofTree(Folder folder) {
        final FolderListFragment fragment = new FolderListFragment();
        // Trees are never sectioned.
        final boolean isSectioned = false;
        fragment.setArguments(getBundleFromArgs(TYPE_TREE, folder, folder.childFoldersListUri,
                isSectioned, null));
        return fragment;
    }

    /**
     * Creates a new instance of {@link FolderListFragment}, initialized
     * to display the folder and its immediate children.
     * @param folderListUri the URI which contains all the list of folders
     * @param excludedFolderTypes A list of {@link FolderType}s to exclude from displaying
     */
    public static FolderListFragment ofTopLevelTree(Uri folderListUri,
            final ArrayList<Integer> excludedFolderTypes) {
        final FolderListFragment fragment = new FolderListFragment();
        // Trees are never sectioned.
        final boolean isSectioned = false;
        fragment.setArguments(getBundleFromArgs(TYPE_TREE, null, folderListUri,
                isSectioned, excludedFolderTypes));
        return fragment;
    }

    /**
     * Construct a bundle that represents the state of this fragment.
     * @param type the type of FLF: {@link #TYPE_DRAWER} or {@link #TYPE_TREE}
     * @param parentFolder non-null for trees, the parent of this list
     * @param isSectioned true if this drawer is sectioned, false otherwise
     * @param folderListUri the URI which contains all the list of folders
     * @param excludedFolderTypes if non-null, this indicates folders to exclude in lists.
     * @return Bundle containing parentFolder, sectioned list boolean and
     *         excluded folder types
     */
    private static Bundle getBundleFromArgs(int type, Folder parentFolder, Uri folderListUri,
            boolean isSectioned, final ArrayList<Integer> excludedFolderTypes) {
        final Bundle args = new Bundle();
        args.putInt(ARG_TYPE, type);
        if (parentFolder != null) {
            args.putParcelable(ARG_PARENT_FOLDER, parentFolder);
        }
        if (folderListUri != null) {
            args.putString(ARG_FOLDER_LIST_URI, folderListUri.toString());
        }
        args.putBoolean(ARG_IS_SECTIONED, isSectioned);
        if (excludedFolderTypes != null) {
            args.putIntegerArrayList(ARG_EXCLUDED_FOLDER_TYPES, excludedFolderTypes);
        }
        return args;
    }

    @Override
    public void onActivityCreated(Bundle savedState) {
        super.onActivityCreated(savedState);
        // Strictly speaking, we get back an android.app.Activity from getActivity. However, the
        // only activity creating a ConversationListContext is a MailActivity which is of type
        // ControllableActivity, so this cast should be safe. If this cast fails, some other
        // activity is creating ConversationListFragments. This activity must be of type
        // ControllableActivity.
        final Activity activity = getActivity();
        Folder currentFolder = null;
        if (! (activity instanceof ControllableActivity)){
            LogUtils.wtf(LOG_TAG, "FolderListFragment expects only a ControllableActivity to" +
                    "create it. Cannot proceed.");
        }
        mActivity = (ControllableActivity) activity;
        final FolderController controller = mActivity.getFolderController();
        // Listen to folder changes in the future
        mFolderObserver = new FolderObserver() {
            @Override
            public void onChanged(Folder newFolder) {
                setSelectedFolder(newFolder);
            }
        };
        if (controller != null) {
            // Only register for selected folder updates if we have a controller.
            currentFolder = mFolderObserver.initialize(controller);
            mCurrentFolderForUnreadCheck = currentFolder;
        }

        // Initialize adapter for folder/heirarchical list.  Note this relies on
        // mActivity being initialized.
        final Folder selectedFolder;
        if (mParentFolder != null) {
            mCursorAdapter = new HierarchicalFolderListAdapter(null, mParentFolder);
            selectedFolder = mActivity.getHierarchyFolder();
        } else {
            mCursorAdapter = new FolderListAdapter(mIsSectioned);
            selectedFolder = currentFolder;
        }
        // Is the selected folder fresher than the one we have restored from a bundle?
        if (selectedFolder != null && !selectedFolder.uri.equals(mSelectedFolderUri)) {
            setSelectedFolder(selectedFolder);
        }

        // Assign observers for current account & all accounts
        final AccountController accountController = mActivity.getAccountController();
        mAccountObserver = new AccountObserver() {
            @Override
            public void onChanged(Account newAccount) {
                setSelectedAccount(newAccount);
            }
        };
        if (accountController != null) {
            // Current account and its observer.
            setSelectedAccount(mAccountObserver.initialize(accountController));
            // List of all accounts and its observer.
            mAllAccountsObserver = new AllAccountObserver(){
                @Override
                public void onChanged(Account[] allAccounts) {
                    mCursorAdapter.notifyAllAccountsChanged();
                }
            };
            mAllAccountsObserver.initialize(accountController);
            mAccountChanger = accountController;
        }

        mFolderChanger = mActivity.getFolderListSelectionListener();
        if (mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }

        setListAdapter(mCursorAdapter);
    }

    /**
     * Set the instance variables from the arguments provided here.
     * @param args
     */
    private void setInstanceFromBundle(Bundle args) {
        if (args == null) {
            return;
        }
        mParentFolder = (Folder) args.getParcelable(ARG_PARENT_FOLDER);
        final String folderUri = args.getString(ARG_FOLDER_LIST_URI);
        if (folderUri == null) {
            mFolderListUri = Uri.EMPTY;
        } else {
            mFolderListUri = Uri.parse(folderUri);
        }
        mIsSectioned = args.getBoolean(ARG_IS_SECTIONED);
        mExcludedFolderTypes = args.getIntegerArrayList(ARG_EXCLUDED_FOLDER_TYPES);
        mType = args.getInt(ARG_TYPE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedState) {
        final View rootView = inflater.inflate(R.layout.folder_list, null);
        mListView = (ListView) rootView.findViewById(android.R.id.list);
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setEmptyView(null);
        if (savedState != null && savedState.containsKey(BUNDLE_LIST_STATE)) {
            mListView.onRestoreInstanceState(savedState.getParcelable(BUNDLE_LIST_STATE));
        }
        if (savedState != null && savedState.containsKey(BUNDLE_SELECTED_FOLDER)) {
            mSelectedFolderUri = Uri.parse(savedState.getString(BUNDLE_SELECTED_FOLDER));
            mSelectedFolderType = savedState.getInt(BUNDLE_SELECTED_TYPE);
        } else if (mParentFolder != null) {
            mSelectedFolderUri = mParentFolder.uri;
            // No selected folder type required for hierarchical lists.
        }

        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mListView != null) {
            outState.putParcelable(BUNDLE_LIST_STATE, mListView.onSaveInstanceState());
        }
        if (mSelectedFolderUri != null) {
            outState.putString(BUNDLE_SELECTED_FOLDER, mSelectedFolderUri.toString());
        }
        outState.putInt(BUNDLE_SELECTED_TYPE, mSelectedFolderType);
    }

    @Override
    public void onDestroyView() {
        if (mCursorAdapter != null) {
            mCursorAdapter.destroy();
        }
        // Clear the adapter.
        setListAdapter(null);
        if (mFolderObserver != null) {
            mFolderObserver.unregisterAndDestroy();
            mFolderObserver = null;
        }
        if (mAccountObserver != null) {
            mAccountObserver.unregisterAndDestroy();
            mAccountObserver = null;
        }
        if (mAllAccountsObserver != null) {
            mAllAccountsObserver.unregisterAndDestroy();
            mAllAccountsObserver = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        viewFolderOrChangeAccount(position);
    }

    /**
     * Display the conversation list from the folder at the position given.
     * @param position a zero indexed position into the list.
     */
    private void viewFolderOrChangeAccount(int position) {
        final Object item = getListAdapter().getItem(position);
        LogUtils.i(LOG_TAG, "viewFolderOrChangeAccount(%d): %s", position, item);
        final Folder folder;
        if (item instanceof DrawerItem) {
            final DrawerItem drawerItem = (DrawerItem) item;
            // Could be a folder or account.
            final int itemType = mCursorAdapter.getItemType(drawerItem);
            if (itemType == DrawerItem.VIEW_ACCOUNT) {
                // Account, so switch.
                folder = null;
                final Account account = drawerItem.mAccount;
                mAccountChanger.changeAccount(account);
            } else if (itemType == DrawerItem.VIEW_FOLDER) {
                // Folder type, so change folders only.
                folder = drawerItem.mFolder;
                mSelectedFolderType = drawerItem.mFolderType;
                LogUtils.i(LOG_TAG, "FLF.viewFolderOrChangeAccount folder=%s, type=%d",
                        folder, mSelectedFolderType);
            } else {
                // Do nothing.
                LogUtils.i(LOG_TAG, "FolderListFragment: viewFolderOrChangeAccount():"
                        + " Clicked on unset item in drawer. Offending item is " + item);
                return;
            }
        } else if (item instanceof Folder) {
            folder = (Folder) item;
        } else if (item instanceof ObjectCursor){
            folder = ((ObjectCursor<Folder>) item).getModel();
        } else {
            // Don't know how we got here.
            LogUtils.wtf(LOG_TAG, "viewFolderOrChangeAccount(): invalid item");
            folder = null;
        }
        if (folder != null) {
            // Since we may be looking at hierarchical views, if we can
            // determine the parent of the folder we have tapped, set it here.
            // If we are looking at the folder we are already viewing, don't
            // update its parent!
            folder.parent = folder.equals(mParentFolder) ? null : mParentFolder;
            // Go to the conversation list for this folder.
            mFolderChanger.onFolderSelected(folder);
        }
    }

    @Override
    public Loader<ObjectCursor<Folder>> onCreateLoader(int id, Bundle args) {
        mListView.setEmptyView(null);
        final Uri folderListUri;
        if (mType == TYPE_TREE) {
            // Folder trees, they specify a URI at construction time.
            folderListUri = mFolderListUri;
        } else if (mType == TYPE_DRAWER) {
            // Drawers should have a valid account
            if (mCurrentAccount != null) {
                folderListUri = mCurrentAccount.folderListUri;
            } else {
                LogUtils.wtf(LOG_TAG, "FLF.onCreateLoader() for Drawer with null account");
                return null;
            }
        } else {
            LogUtils.wtf(LOG_TAG, "FLF.onCreateLoader() with weird type");
            return null;
        }
        return new ObjectCursorLoader<Folder>(mActivity.getActivityContext(), folderListUri,
                UIProvider.FOLDERS_PROJECTION, Folder.FACTORY);
    }

    @Override
    public void onLoadFinished(Loader<ObjectCursor<Folder>> loader, ObjectCursor<Folder> data) {
        if (mCursorAdapter != null) {
            mCursorAdapter.setCursor(data);
        }
    }

    @Override
    public void onLoaderReset(Loader<ObjectCursor<Folder>> loader) {
        if (mCursorAdapter != null) {
            mCursorAdapter.setCursor(null);
        }
    }

    /**
     *  Returns the sorted list of accounts. The AAC always has the current list, sorted by
     *  frequency of use.
     * @return a list of accounts, sorted by frequency of use
     */
    private Account[] getAllAccounts() {
        if (mAllAccountsObserver != null) {
            return mAllAccountsObserver.getAllAccounts();
        }
        return new Account[0];
    }

    /**
     * Interface for all cursor adapters that allow setting a cursor and being destroyed.
     */
    private interface FolderListFragmentCursorAdapter extends ListAdapter {
        /** Update the folder list cursor with the cursor given here. */
        void setCursor(ObjectCursor<Folder> cursor);
        /**
         * Given an item, find the type of the item, which should only be {@link
         * DrawerItem#VIEW_FOLDER} or {@link DrawerItem#VIEW_ACCOUNT}
         * @return item the type of the item.
         */
        int getItemType(DrawerItem item);
        /** Get the folder associated with this item. **/
        Folder getFullFolder(DrawerItem item);
        /** Notify that the all accounts changed. */
        void notifyAllAccountsChanged();
        /** Remove all observers and destroy the object. */
        void destroy();
        /** Notifies the adapter that the data has changed. */
        void notifyDataSetChanged();
    }

    /**
     * An adapter for flat folder lists.
     */
    private class FolderListAdapter extends BaseAdapter implements FolderListFragmentCursorAdapter {

        private final RecentFolderObserver mRecentFolderObserver = new RecentFolderObserver() {
            @Override
            public void onChanged() {
                recalculateList();
            }
        };
        /** Database columns for email address -> photo_id query */
        private final String[] DATA_COLS = new String[] { Email.DATA, Email.PHOTO_ID };
        /** Database columns for photo_id -> photo query */
        private final String[] PHOTO_COLS = new String[] { Photo._ID, Photo.PHOTO };
        /** No resource used for string header in folder list */
        private static final int NO_HEADER_RESOURCE = -1;
        /** Cache of most recently used folders */
        private final RecentFolderList mRecentFolders;
        /** True if the list is sectioned, false otherwise */
        private final boolean mIsSectioned;
        /** All the items */
        private List<DrawerItem> mItemList = new ArrayList<DrawerItem>();
        /** Cursor into the folder list. This might be null. */
        private ObjectCursor<Folder> mCursor = null;
        /** Watcher for tracking and receiving unread counts for mail */
        private FolderWatcher mFolderWatcher = null;
        /**
         * DO NOT USE off the UI thread. Will cause ConcurrentModificationExceptions otherwise
         *
         * Email address -> Bitmap
         * Caveat: at some point we will want this to be from URI to Bitmap.
         */
        private final HashMap<String, Bitmap> mEmailToPhotoMap = new HashMap<String, Bitmap>();

        /**
         * Creates a {@link FolderListAdapter}.This is a list of all the accounts and folders.
         *
         * @param isSectioned true if folder list is flat, false if sectioned by label group
         */
        public FolderListAdapter(boolean isSectioned) {
            super();
            mIsSectioned = isSectioned;
            final RecentFolderController controller = mActivity.getRecentFolderController();
            if (controller != null && mIsSectioned) {
                mRecentFolders = mRecentFolderObserver.initialize(controller);
            } else {
                mRecentFolders = null;
            }
            mFolderWatcher = new FolderWatcher(mActivity, this);
            mFolderWatcher.updateAccountList(getAllAccounts());
        }

        @Override
        public void notifyAllAccountsChanged() {
            mFolderWatcher.updateAccountList(getAllAccounts());
            retrieveContactPhotos();
            recalculateList();
        }

        /**
         * AsyncTask for loading all photos that populates the email address -> Bitmap Hash Map.
         * Does the querying and loading of photos in the background along with creating
         * default images in case contact photos aren't found.
         *
         * The task is of type <String, Void, HashMap<String, Bitmap>> which corresponds to
         * the input being an array of String and the result being a HashMap that will get merged to
         * {@link FolderListAdapter#mEmailToPhotoMap}.
         */
        private class LoadPhotosTask extends AsyncTask<Account, Void, HashMap<String, Bitmap>> {
            private final ContentResolver mResolver;
            private final Context mContext;
            private final int mImageSize;

            /**
             * Construct the async task for downloading the photos.
             */
            public LoadPhotosTask(final Context context, final int imageSize) {
                mResolver = context.getContentResolver();
                mContext = context;
                mImageSize = imageSize;
            }

            /**
             * Runs account photo retrieval in the background. Note, mEmailToPhotoMap should NOT be
             * modified here since this is run on a background thread and not the UI thread.
             *
             * The {@link Account#accountFromAddresses} is used for letter tiles and is required
             * in order to properly assign the tile to the respective account.
             */
            @Override
            protected HashMap<String, Bitmap> doInBackground(final Account... allAccounts) {
                final HashMap<String, String> addressToDisplayNameMap = new HashMap<
                        String, String>();
                for (final Account account : allAccounts) {
                    addressToDisplayNameMap.put(account.name, account.accountFromAddresses);
                }

                return getAccountPhoto(addressToDisplayNameMap);
            }

            @Override
            protected void onPostExecute(final HashMap<String, Bitmap> accountPhotos) {
                mEmailToPhotoMap.putAll(accountPhotos);
            }

            /**
             * Queries the database for the photos. First finds the corresponding photo_id and then
             * proceeds to find the photo through subsequent queries for {photo_id, bytes}. If the
             * photo is not found for the address at the end, creates a letter tile using the
             * display name/email address and then adds that to the finished HashMap
             *
             * @param addresses array of email addresses (strings)
             * @param addressToDisplayNameMap map of email addresses to display names used for
             *              letter tiles
             * @return map of email addresses to the corresponding photos
             */
            private HashMap<String, Bitmap> getAccountPhoto(
                    final HashMap<String, String> addressToDisplayNameMap) {
                // Columns for email address, photo_id
                final int DATA_EMAIL_COLUMN = 0;
                final int DATA_PHOTO_COLUMN = 1;
                final HashMap<String, Bitmap> photoMap = new HashMap<String, Bitmap>();
                final Set<String> addressSet = addressToDisplayNameMap.keySet();

                String address;
                long photoId;
                Cursor photoIdsCursor = null;


                try {
                    // Build query for address -> photo_id
                    final StringBuilder query = new StringBuilder().append(Data.MIMETYPE)
                            .append("='").append(Email.CONTENT_ITEM_TYPE).append("' AND ")
                            .append(Email.DATA).append(" IN (");
                    appendQuestionMarks(query, addressSet.size());
                    query.append(')');
                    photoIdsCursor = mResolver
                            .query(Data.CONTENT_URI, DATA_COLS, query.toString(),
                                    addressSet.toArray(new String[addressSet.size()]), null);

                    // Iterate through cursor and attempt to find a matching photo_id
                    if (photoIdsCursor != null) {
                        while (photoIdsCursor.moveToNext()) {
                            // If photo_id is found, query for the encoded bitmap
                            if (!photoIdsCursor.isNull(DATA_PHOTO_COLUMN)) {
                                address = photoIdsCursor.getString(DATA_EMAIL_COLUMN);
                                photoId = photoIdsCursor.getLong(DATA_PHOTO_COLUMN);
                                final byte[] bitmapBytes = getPhotoForId(photoId);
                                if (bitmapBytes != null && photoMap.get(address) == null) {
                                    final Bitmap contactPhoto = BitmapUtil.decodeBitmapFromBytes(
                                            bitmapBytes, mImageSize, mImageSize);
                                    photoMap.put(address, contactPhoto);
                                }
                            }
                        }
                    }
                } finally {
                    if(photoIdsCursor != null) {
                        photoIdsCursor.close();
                    }
                }

                // Finally, make sure that for any addresses in the original list for which
                // we are unable to find contact photos, we're adding the LetterTiles
                for(final String emailAddress : addressSet) {
                    if(!photoMap.containsKey(emailAddress)) {
                        final Bitmap letterTile = LetterTileUtils.generateLetterTile(
                                addressToDisplayNameMap.get(emailAddress), emailAddress, mContext,
                                mImageSize, mImageSize);
                        photoMap.put(emailAddress, letterTile);
                    }
                }

                return photoMap;
            }

            /**
             * Find the photo by running a query on the photoId provided.
             *
             * @param resolver ContentResolver to query on
             * @param photoId id corresponding to the photo (if found)
             * @return array containing photo bytes
             */
            private byte[] getPhotoForId(final long photoId) {
                // Column for the photo blob
                final int DATA_PHOTO_COLUMN = 1;

                byte[] bitmapBytes = null;
                // First try getting photos from Contacts
                Cursor contactCursor = null;
                try {
                    final String[] selectionArgs = { String.valueOf(photoId) };
                    contactCursor = mResolver.query(Data.CONTENT_URI, PHOTO_COLS,
                            Photo._ID + " = ?", selectionArgs, null);
                    while (contactCursor.moveToNext()) {
                        if (!contactCursor.isNull(1)) {
                            bitmapBytes = contactCursor.getBlob(1);
                            break;
                        }
                    }
                } finally {
                    if (contactCursor != null) {
                        contactCursor.close();
                    }
                }

                // Photo not found in contacts, try profiles instead
                if(bitmapBytes == null) {
                    if (ContactsContract.isProfileId(photoId)) {
                        Cursor profileCursor = null;
                        try {
                            profileCursor = mResolver.query(
                                    ContentUris.withAppendedId(Data.CONTENT_URI, photoId),
                                    PHOTO_COLS, null, null, null);
                            if (profileCursor != null && profileCursor.moveToFirst()) {
                                bitmapBytes = profileCursor.getBlob(DATA_PHOTO_COLUMN);
                            }
                        } finally {
                            if (profileCursor != null) {
                                profileCursor.close();
                            }
                        }
                    }
                }
                return bitmapBytes;
            }

            /**
             * Prepare the Selection clause for the given query by appending question marks
             * followed by commas (Comma-delimited list of question marks as listed by
             * the itemCount.
             *
             * @param query {@link StringBuilder} representing the query thus far
             * @param itemCount number of selection arguments to add
             */
            private void appendQuestionMarks(final StringBuilder query, final int itemCount) {
                final String[] questionMarks = new String[itemCount];
                Arrays.fill(questionMarks, "?");
                final String selection = TextUtils.join(", ", questionMarks);
                query.append(selection);
            }
        }

        /**
         * Retrieve photos for accounts that do not yet have a mapping in
         * {@link FolderListAdapter#mEmailToPhotoMap} by querying over the database. Every account
         * is guaranteed to have either the account contact photo, letter tile, or a default gray
         * picture for non-English account names.
         */
        public synchronized void retrieveContactPhotos() {
            final Account[] allAccounts = getAllAccounts();
            if (allAccounts == null) {
                return;
            }
            /** Fresh accounts that were recently added to the system. */
            final HashSet<Account> freshAccounts = new HashSet<Account>();
            /** All current account email addresses. */
            final HashSet<String> currentEmailList = new HashSet<String>();
            final Context context = mActivity.getActivityContext();
            final int imageSize = context.getResources().getDimensionPixelSize(
                    R.dimen.folder_list_item_minimum_height);

            for (final Account account : allAccounts) {
                final String email = account.name;
                if (!mEmailToPhotoMap.containsKey(email)) {
                    freshAccounts.add(account);
                    // For multiple tasks running very closely together, make sure we don't end up
                    // loading pictures for an address more than once
                    mEmailToPhotoMap.put(email, null);
                }
                currentEmailList.add(email);
            }
            // Find all the stale accounts in our map, and remove them.
            final Set<String> emails = ImmutableSet.copyOf(mEmailToPhotoMap.keySet());
            for (final String email : emails) {
                if (!currentEmailList.contains(email)) {
                    mEmailToPhotoMap.remove(email);
                }
            }
            // Fetch contact photos or letter tiles for each fresh account.
            if (!freshAccounts.isEmpty()) {
                new LoadPhotosTask(context, imageSize).execute(
                        freshAccounts.toArray(new Account[freshAccounts.size()]));
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final DrawerItem item = (DrawerItem) getItem(position);
            final View view = item.getView(position, convertView, parent);
            final int type = item.mType;
            if (mListView != null) {
                final boolean isSelected =
                        item.isHighlighted(mCurrentFolderForUnreadCheck, mSelectedFolderType);
                if (type == DrawerItem.VIEW_FOLDER) {
                    mListView.setItemChecked(position, isSelected);
                }
                // If this is the current folder, also check to verify that the unread count
                // matches what the action bar shows.
                if (type == DrawerItem.VIEW_FOLDER
                        && isSelected
                        && (mCurrentFolderForUnreadCheck != null)
                        && item.mFolder.unreadCount != mCurrentFolderForUnreadCheck.unreadCount) {
                    ((FolderItemView) view).overrideUnreadCount(
                            mCurrentFolderForUnreadCheck.unreadCount);
                }
            }
            LogUtils.i(LOG_TAG, "FLF.getView(%d) returns view of item %s", position, item);
            return view;
        }

        @Override
        public int getViewTypeCount() {
            // Accounts, headers, folders (all parts of drawer view types)
            return DrawerItem.getViewTypes();
        }

        @Override
        public int getItemViewType(int position) {
            return ((DrawerItem) getItem(position)).mType;
        }

        @Override
        public int getCount() {
            return mItemList.size();
        }

        @Override
        public boolean isEnabled(int position) {
            return ((DrawerItem) getItem(position)).isItemEnabled();
        }

        private Uri getCurrentAccountUri() {
            return mCurrentAccount == null ? Uri.EMPTY : mCurrentAccount.uri;
        }

        @Override
        public boolean areAllItemsEnabled() {
            // We have headers and thus some items are not enabled.
            return false;
        }

        /**
         * Returns all the recent folders from the list given here. Safe to call with a null list.
         * @param recentList a list of all recently accessed folders.
         * @return a valid list of folders, which are all recent folders.
         */
        private List<Folder> getRecentFolders(RecentFolderList recentList) {
            final List<Folder> folderList = new ArrayList<Folder>();
            if (recentList == null) {
                return folderList;
            }
            // Get all recent folders, after removing system folders.
            for (final Folder f : recentList.getRecentFolderList(null)) {
                if (!f.isProviderFolder()) {
                    folderList.add(f);
                }
            }
            return folderList;
        }

        /**
         * Responsible for verifying mCursor, and ensuring any recalculate
         * conditions are met. Also calls notifyDataSetChanged once it's finished
         * populating {@link FolderListAdapter#mItemList}
         */
        private void recalculateList() {
            final List<DrawerItem> newFolderList = new ArrayList<DrawerItem>();
            recalculateListAccounts(newFolderList);
            recalculateListFolders(newFolderList);
            mItemList = newFolderList;
            // Ask the list to invalidate its views.
            notifyDataSetChanged();
        }

        /**
         * Recalculates the accounts if not null and adds them to the list.
         *
         * @param itemList List of drawer items to populate
         */
        private void recalculateListAccounts(List<DrawerItem> itemList) {
            final Account[] allAccounts = getAllAccounts();
            // Add all accounts and then the current account
            final Uri currentAccountUri = getCurrentAccountUri();
            for (final Account account : allAccounts) {
                if (!currentAccountUri.equals(account.uri)) {
                    final int unreadCount = mFolderWatcher.getUnreadCount(account);
                    itemList.add(DrawerItem.ofAccount(mActivity, account, unreadCount, false,
                            mEmailToPhotoMap.get(account.name)));
                }
            }
            if (mCurrentAccount == null) {
                LogUtils.wtf(LOG_TAG, "recalculateListAccounts() with null current account.");
            } else {
                // We don't show the unread count for the current account, so set this to zero.
                itemList.add(DrawerItem.ofAccount(mActivity, mCurrentAccount, 0, true,
                        mEmailToPhotoMap.get(mCurrentAccount.name)));
            }
        }

        /**
         * Recalculates the system, recent and user label lists.
         * This method modifies all the three lists on every single invocation.
         *
         * @param itemList List of drawer items to populate
         */
        private void recalculateListFolders(List<DrawerItem> itemList) {
            // If we are waiting for folder initialization, we don't have any kinds of folders,
            // just the "Waiting for initialization" item. Note, this should only be done
            // when we're waiting for account initialization or initial sync.
            if (isCursorInvalid(mCursor)) {
                if(!mCurrentAccount.isAccountReady()) {
                    itemList.add(DrawerItem.forWaitView(mActivity));
                }
                return;
            }

            if (!mIsSectioned) {
                // Adapter for a flat list. Everything is a FOLDER_USER, and there are no headers.
                do {
                    final Folder f = mCursor.getModel();
                    if (!isFolderTypeExcluded(f)) {
                        itemList.add(DrawerItem.ofFolder(mActivity, f, DrawerItem.FOLDER_USER,
                                mCursor.getPosition()));
                    }
                } while (mCursor.moveToNext());
                return;
            }

            // Otherwise, this is an adapter for a sectioned list.
            final List<DrawerItem> allFoldersList = new ArrayList<DrawerItem>();
            final List<DrawerItem> inboxFolders = new ArrayList<DrawerItem>();
            do {
                final Folder f = mCursor.getModel();
                if (!isFolderTypeExcluded(f)) {
                    if (f.isProviderFolder() && f.isInbox()) {
                        inboxFolders.add(DrawerItem.ofFolder(
                                mActivity, f, DrawerItem.FOLDER_SYSTEM, mCursor.getPosition()));
                    } else {
                        allFoldersList.add(DrawerItem.ofFolder(
                                mActivity, f, DrawerItem.FOLDER_USER, mCursor.getPosition()));
                    }
                }
            } while (mCursor.moveToNext());

            // Add all inboxes (sectioned included) before recents.
            addFolderSection(itemList, inboxFolders, NO_HEADER_RESOURCE);

            // Add most recently folders (in alphabetical order) next.
            addRecentsToList(itemList);

            // Add the remaining provider folders followed by all labels.
            addFolderSection(itemList, allFoldersList,  R.string.all_folders_heading);
        }

        /**
         * Given a list of folders as {@link DrawerItem}s, add them to the item
         * list as needed. Passing in a non-0 integer for the resource will
         * enable a header
         *
         * @param destination List of drawer items to populate
         * @param source List of drawer items representing folders to add to the drawer
         * @param headerStringResource
         *            {@link FolderListAdapter#NO_HEADER_RESOURCE} if no header
         *            is required, or res-id otherwise
         */
        private void addFolderSection(List<DrawerItem> destination, List<DrawerItem> source,
                int headerStringResource) {
            if (source.size() > 0) {
                if(headerStringResource != NO_HEADER_RESOURCE) {
                    destination.add(DrawerItem.ofHeader(mActivity, headerStringResource));
                }
                destination.addAll(source);
            }
        }

        /**
         * Add recent folders to the list in order as acquired by the {@link RecentFolderList}.
         *
         * @param destination List of drawer items to populate
         */
        private void addRecentsToList(List<DrawerItem> destination) {
            // If there are recent folders, add them.
            final List<Folder> recentFolderList = getRecentFolders(mRecentFolders);

            // Remove any excluded folder types
            if (mExcludedFolderTypes != null) {
                final Iterator<Folder> iterator = recentFolderList.iterator();
                while (iterator.hasNext()) {
                    if (isFolderTypeExcluded(iterator.next())) {
                        iterator.remove();
                    }
                }
            }

            if (recentFolderList.size() > 0) {
                destination.add(DrawerItem.ofHeader(mActivity, R.string.recent_folders_heading));
                // Recent folders are not queried for position.
                final int position = -1;
                for (Folder f : recentFolderList) {
                    destination.add(DrawerItem.ofFolder(mActivity, f, DrawerItem.FOLDER_RECENT,
                            position));
                }
            }
        }

        /**
         * Check if the cursor provided is valid.
         * @param mCursor
         * @return True if cursor is invalid, false otherwise
         */
        private boolean isCursorInvalid(Cursor mCursor) {
            return mCursor == null || mCursor.isClosed()|| mCursor.getCount() <= 0
                    || !mCursor.moveToFirst();
        }

        @Override
        public void setCursor(ObjectCursor<Folder> cursor) {
            mCursor = cursor;
            recalculateList();
        }

        @Override
        public Object getItem(int position) {
            return mItemList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).hashCode();
        }

        @Override
        public final void destroy() {
            mRecentFolderObserver.unregisterAndDestroy();
        }

        @Override
        public int getItemType(DrawerItem item) {
            return item.mType;
        }

        // TODO(viki): This is strange. We have the full folder and yet we create on from scratch.
        @Override
        public Folder getFullFolder(DrawerItem folderItem) {
            if (folderItem.mFolderType == DrawerItem.FOLDER_RECENT) {
                return folderItem.mFolder;
            } else {
                final int pos = folderItem.mPosition;
                if (pos > -1 && mCursor != null && !mCursor.isClosed()
                        && mCursor.moveToPosition(folderItem.mPosition)) {
                    return mCursor.getModel();
                } else {
                    return null;
                }
            }
        }
    }

    private class HierarchicalFolderListAdapter extends ArrayAdapter<Folder>
            implements FolderListFragmentCursorAdapter{

        private static final int PARENT = 0;
        private static final int CHILD = 1;
        private final Uri mParentUri;
        private final Folder mParent;
        private final FolderItemView.DropHandler mDropHandler;
        private ObjectCursor<Folder> mCursor;

        public HierarchicalFolderListAdapter(ObjectCursor<Folder> c, Folder parentFolder) {
            super(mActivity.getActivityContext(), R.layout.folder_item);
            mDropHandler = mActivity;
            mParent = parentFolder;
            mParentUri = parentFolder.uri;
            setCursor(c);
        }

        @Override
        public int getViewTypeCount() {
            // Child and Parent
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            final Folder f = getItem(position);
            return f.uri.equals(mParentUri) ? PARENT : CHILD;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final FolderItemView folderItemView;
            final Folder folder = getItem(position);
            boolean isParent = folder.uri.equals(mParentUri);
            if (convertView != null) {
                folderItemView = (FolderItemView) convertView;
            } else {
                int resId = isParent ? R.layout.folder_item : R.layout.child_folder_item;
                folderItemView = (FolderItemView) LayoutInflater.from(
                        mActivity.getActivityContext()).inflate(resId, null);
            }
            folderItemView.bind(folder, mDropHandler);
            if (folder.uri.equals(mSelectedFolderUri)) {
                getListView().setItemChecked(position, true);
                // If this is the current folder, also check to verify that the unread count
                // matches what the action bar shows.
                final boolean unreadCountDiffers = (mCurrentFolderForUnreadCheck != null)
                        && folder.unreadCount != mCurrentFolderForUnreadCheck.unreadCount;
                if (unreadCountDiffers) {
                    folderItemView.overrideUnreadCount(mCurrentFolderForUnreadCheck.unreadCount);
                }
            }
            Folder.setFolderBlockColor(folder, folderItemView.findViewById(R.id.color_block));
            Folder.setIcon(folder, (ImageView) folderItemView.findViewById(R.id.folder_icon));
            return folderItemView;
        }

        @Override
        public void setCursor(ObjectCursor<Folder> cursor) {
            mCursor = cursor;
            clear();
            if (mParent != null) {
                add(mParent);
            }
            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                do {
                    Folder f = cursor.getModel();
                    f.parent = mParent;
                    add(f);
                } while (cursor.moveToNext());
            }
        }

        @Override
        public void destroy() {
            // Do nothing.
        }

        @Override
        public int getItemType(DrawerItem item) {
            // Always returns folders for now.
            return DrawerItem.VIEW_FOLDER;
        }

        @Override
        public Folder getFullFolder(DrawerItem folderItem) {
            final int pos = folderItem.mPosition;
            if (mCursor == null || mCursor.isClosed()) {
                return null;
            }
            if (pos > -1 && mCursor != null && !mCursor.isClosed()
                    && mCursor.moveToPosition(folderItem.mPosition)) {
                return mCursor.getModel();
            } else {
                return null;
            }
        }

        @Override
        public void notifyAllAccountsChanged() {
            // Do nothing. We don't care about changes to all accounts.
        }
    }

    public Folder getParentFolder() {
        return mParentFolder;
    }

    /**
     * Sets the currently selected folder safely.
     * @param folder
     */
    private void setSelectedFolder(Folder folder) {
        if (folder == null) {
            mSelectedFolderUri = Uri.EMPTY;
            LogUtils.e(LOG_TAG, "FolderListFragment.setSelectedFolder(null) called!");
            return;
        }
        mCurrentFolderForUnreadCheck = folder;
        mSelectedFolderUri = folder.uri;
        setSelectedFolderType(folder);
        final boolean viewChanged =
                !FolderItemView.areSameViews(folder, mCurrentFolderForUnreadCheck);
        if (mCursorAdapter != null && viewChanged) {
            mCursorAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Sets the selected folder type safely.
     * @param folder folder to set to.
     */
    private void setSelectedFolderType(Folder folder) {
        if (mSelectedFolderType == DrawerItem.UNSET) {
            mSelectedFolderType = folder.isProviderFolder() ? DrawerItem.FOLDER_SYSTEM
                    : DrawerItem.FOLDER_USER;
        }
    }

    /**
     * Sets the current account to the one provided here.
     * @param account the current account to set to.
     */
    private void setSelectedAccount(Account account){
        final boolean changed = (account != null) && (mCurrentAccount == null
                || !mCurrentAccount.uri.equals(account.uri));
        mCurrentAccount = account;
        if (changed) {
            // We no longer have proper folder objects. Let the new ones come in
            mCursorAdapter.setCursor(null);
            // If currentAccount is different from the one we set, restart the loader. Look at the
            // comment on {@link AbstractActivityController#restartOptionalLoader} to see why we
            // don't just do restartLoader.
            final LoaderManager manager = getLoaderManager();
            manager.destroyLoader(FOLDER_LOADER_ID);
            manager.restartLoader(FOLDER_LOADER_ID, Bundle.EMPTY, this);
            // An updated cursor causes the entire list to refresh. No need to refresh the list.
        } else if (account == null) {
            // This should never happen currently, but is a safeguard against a very incorrect
            // non-null account -> null account transition.
            LogUtils.e(LOG_TAG, "FLF.setSelectedAccount(null) called! Destroying existing loader.");
            final LoaderManager manager = getLoaderManager();
            manager.destroyLoader(FOLDER_LOADER_ID);
        }
    }

    public interface FolderListSelectionListener {
        public void onFolderSelected(Folder folder);
    }

    /**
     * Get whether the FolderListFragment is currently showing the hierarchy
     * under a single parent.
     */
    public boolean showingHierarchy() {
        return mParentFolder != null;
    }

    /**
     * Checks if the specified {@link Folder} is a type that we want to exclude from displaying.
     */
    private boolean isFolderTypeExcluded(final Folder folder) {
        if (mExcludedFolderTypes == null) {
            return false;
        }

        for (final int excludedType : mExcludedFolderTypes) {
            if (folder.isType(excludedType)) {
                return true;
            }
        }

        return false;
    }
}
