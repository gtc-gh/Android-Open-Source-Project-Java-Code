/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.accounts.Account;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An abstract implementation of a SyncAdapter that spawns a thread to invoke a sync operation.
 * If a sync operation is already in progress when a startSync() request is received then an error
 * will be returned to the new request and the existing request will be allowed to continue.
 * When a startSync() is received and there is no sync operation in progress then a thread
 * will be started to run the operation and {@link #onPerformSync} will be invoked on that thread.
 * If a cancelSync() is received that matches an existing sync operation then the thread
 * that is running that sync operation will be interrupted, which will indicate to the thread
 * that the sync has been canceled.
 */
public abstract class AbstractThreadedSyncAdapter {
    /**
     * Kernel event log tag.  Also listed in data/etc/event-log-tags.
     * @deprecated Private constant.  May go away in the next release.
     */
    @Deprecated
    public static final int LOG_SYNC_DETAILS = 2743;

    private final Context mContext;
    private final AtomicInteger mNumSyncStarts;
    private final ISyncAdapterImpl mISyncAdapterImpl;

    // all accesses to this member variable must be synchronized on mSyncThreadLock
    private final HashMap<Account, SyncThread> mSyncThreads = new HashMap<Account, SyncThread>();
    private final Object mSyncThreadLock = new Object();

    private final boolean mAutoInitialize;
    private boolean mAllowParallelSyncs;

    /**
     * Creates an {@link AbstractThreadedSyncAdapter}.
     * @param context the {@link android.content.Context} that this is running within.
     * @param autoInitialize if true then sync requests that have
     * {@link ContentResolver#SYNC_EXTRAS_INITIALIZE} set will be internally handled by
     * {@link AbstractThreadedSyncAdapter} by calling
     * {@link ContentResolver#setIsSyncable(android.accounts.Account, String, int)} with 1 if it
     * is currently set to <0.
     */
    public AbstractThreadedSyncAdapter(Context context, boolean autoInitialize) {
        this(context, autoInitialize, false /* allowParallelSyncs */);
    }

    /**
     * Creates an {@link AbstractThreadedSyncAdapter}.
     * @param context the {@link android.content.Context} that this is running within.
     * @param autoInitialize if true then sync requests that have
     * {@link ContentResolver#SYNC_EXTRAS_INITIALIZE} set will be internally handled by
     * {@link AbstractThreadedSyncAdapter} by calling
     * {@link ContentResolver#setIsSyncable(android.accounts.Account, String, int)} with 1 if it
     * is currently set to <0.
     * @param allowParallelSyncs if true then allow syncs for different accounts to run
     * at the same time, each in their own thread. This must be consistent with the setting
     * in the SyncAdapter's configuration file.
     */
    public AbstractThreadedSyncAdapter(Context context,
            boolean autoInitialize, boolean allowParallelSyncs) {
        mContext = context;
        mISyncAdapterImpl = new ISyncAdapterImpl();
        mNumSyncStarts = new AtomicInteger(0);
        mAutoInitialize = autoInitialize;
        mAllowParallelSyncs = allowParallelSyncs;
    }

    public Context getContext() {
        return mContext;
    }

    private Account toSyncKey(Account account) {
        if (mAllowParallelSyncs) {
            return account;
        } else {
            return null;
        }
    }

    private class ISyncAdapterImpl extends ISyncAdapter.Stub {
        public void startSync(ISyncContext syncContext, String authority, Account account,
                Bundle extras) {
            final SyncContext syncContextClient = new SyncContext(syncContext);

            boolean alreadyInProgress;
            // synchronize to make sure that mSyncThreads doesn't change between when we
            // check it and when we use it
            final Account threadsKey = toSyncKey(account);
            synchronized (mSyncThreadLock) {
                if (!mSyncThreads.containsKey(threadsKey)) {
                    if (mAutoInitialize
                            && extras != null
                            && extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)) {
                        if (ContentResolver.getIsSyncable(account, authority) < 0) {
                            ContentResolver.setIsSyncable(account, authority, 1);
                        }
                        syncContextClient.onFinished(new SyncResult());
                        return;
                    }
                    SyncThread syncThread = new SyncThread(
                            "SyncAdapterThread-" + mNumSyncStarts.incrementAndGet(),
                            syncContextClient, authority, account, extras);
                    mSyncThreads.put(threadsKey, syncThread);
                    syncThread.start();
                    alreadyInProgress = false;
                } else {
                    alreadyInProgress = true;
                }
            }

            // do this outside since we don't want to call back into the syncContext while
            // holding the synchronization lock
            if (alreadyInProgress) {
                syncContextClient.onFinished(SyncResult.ALREADY_IN_PROGRESS);
            }
        }

        public void cancelSync(ISyncContext syncContext) {
            // synchronize to make sure that mSyncThreads doesn't change between when we
            // check it and when we use it
            SyncThread info = null;
            synchronized (mSyncThreadLock) {
                for (SyncThread current : mSyncThreads.values()) {
                    if (current.mSyncContext.getSyncContextBinder() == syncContext.asBinder()) {
                        info = current;
                        break;
                    }
                }
            }
            if (info != null) {
                if (mAllowParallelSyncs) {
                    onSyncCanceled(info);
                } else {
                    onSyncCanceled();
                }
            }
        }

        public void initialize(Account account, String authority) throws RemoteException {
            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
            startSync(null, authority, account, extras);
        }
    }

    /**
     * The thread that invokes {@link AbstractThreadedSyncAdapter#onPerformSync}. It also acquires
     * the provider for this sync before calling onPerformSync and releases it afterwards. Cancel
     * this thread in order to cancel the sync.
     */
    private class SyncThread extends Thread {
        private final SyncContext mSyncContext;
        private final String mAuthority;
        private final Account mAccount;
        private final Bundle mExtras;
        private final Account mThreadsKey;

        private SyncThread(String name, SyncContext syncContext, String authority,
                Account account, Bundle extras) {
            super(name);
            mSyncContext = syncContext;
            mAuthority = authority;
            mAccount = account;
            mExtras = extras;
            mThreadsKey = toSyncKey(account);
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            SyncResult syncResult = new SyncResult();
            ContentProviderClient provider = null;
            try {
                if (isCanceled()) {
                    return;
                }
                provider = mContext.getContentResolver().acquireContentProviderClient(mAuthority);
                if (provider != null) {
                    AbstractThreadedSyncAdapter.this.onPerformSync(mAccount, mExtras,
                            mAuthority, provider, syncResult);
                } else {
                    syncResult.databaseError = true;
                }
            } finally {
                if (provider != null) {
                    provider.release();
                }
                if (!isCanceled()) {
                    mSyncContext.onFinished(syncResult);
                }
                // synchronize so that the assignment will be seen by other threads
                // that also synchronize accesses to mSyncThreads
                synchronized (mSyncThreadLock) {
                    mSyncThreads.remove(mThreadsKey);
                }
            }
        }

        private boolean isCanceled() {
            return Thread.currentThread().isInterrupted();
        }
    }

    /**
     * @return a reference to the IBinder of the SyncAdapter service.
     */
    public final IBinder getSyncAdapterBinder() {
        return mISyncAdapterImpl.asBinder();
    }

    /**
     * Perform a sync for this account. SyncAdapter-specific parameters may
     * be specified in extras, which is guaranteed to not be null. Invocations
     * of this method are guaranteed to be serialized.
     *
     * @param account the account that should be synced
     * @param extras SyncAdapter-specific parameters
     * @param authority the authority of this sync request
     * @param provider a ContentProviderClient that points to the ContentProvider for this
     *   authority
     * @param syncResult SyncAdapter-specific parameters
     */
    public abstract void onPerformSync(Account account, Bundle extras,
            String authority, ContentProviderClient provider, SyncResult syncResult);

    /**
     * Indicates that a sync operation has been canceled. This will be invoked on a separate
     * thread than the sync thread and so you must consider the multi-threaded implications
     * of the work that you do in this method.
     * <p>
     * This will only be invoked when the SyncAdapter indicates that it doesn't support
     * parallel syncs.
     */
    public void onSyncCanceled() {
        final SyncThread syncThread;
        synchronized (mSyncThreadLock) {
            syncThread = mSyncThreads.get(null);
        }
        if (syncThread != null) {
            syncThread.interrupt();
        }
    }

    /**
     * Indicates that a sync operation has been canceled. This will be invoked on a separate
     * thread than the sync thread and so you must consider the multi-threaded implications
     * of the work that you do in this method.
     * <p>
     * This will only be invoked when the SyncAdapter indicates that it does support
     * parallel syncs.
     * @param thread the Thread of the sync that is to be canceled.
     */
    public void onSyncCanceled(Thread thread) {
        thread.interrupt();
    }
}
