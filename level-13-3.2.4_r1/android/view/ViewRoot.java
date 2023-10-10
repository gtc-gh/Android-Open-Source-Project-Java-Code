/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.content.ClipDescription;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.LatencyTimer;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.Config;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.View.MeasureSpec;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;
import com.android.internal.policy.PolicyManager;
import com.android.internal.view.BaseSurfaceHolder;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.RootViewSurfaceTaker;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * The top of a view hierarchy, implementing the needed protocol between View
 * and the WindowManager.  This is for the most part an internal implementation
 * detail of {@link WindowManagerImpl}.
 *
 * {@hide}
 */
@SuppressWarnings({"EmptyCatchBlock", "PointlessBooleanExpression"})
public final class ViewRoot extends Handler implements ViewParent,
        View.AttachInfo.Callbacks, HardwareRenderer.HardwareDrawCallbacks {
    private static final String TAG = "ViewRoot";
    private static final boolean DBG = false;
    private static final boolean SHOW_FPS = false;
    private static final boolean LOCAL_LOGV = false;
    /** @noinspection PointlessBooleanExpression*/
    private static final boolean DEBUG_DRAW = false || LOCAL_LOGV;
    private static final boolean DEBUG_LAYOUT = false || LOCAL_LOGV;
    private static final boolean DEBUG_DIALOG = false || LOCAL_LOGV;
    private static final boolean DEBUG_INPUT_RESIZE = false || LOCAL_LOGV;
    private static final boolean DEBUG_ORIENTATION = false || LOCAL_LOGV;
    private static final boolean DEBUG_TRACKBALL = false || LOCAL_LOGV;
    private static final boolean DEBUG_IMF = false || LOCAL_LOGV;
    private static final boolean DEBUG_CONFIGURATION = false || LOCAL_LOGV;
    private static final boolean WATCH_POINTER = false;

    private static final boolean MEASURE_LATENCY = false;
    private static LatencyTimer lt;

    /**
     * Maximum time we allow the user to roll the trackball enough to generate
     * a key event, before resetting the counters.
     */
    static final int MAX_TRACKBALL_DELAY = 250;

    static IWindowSession sWindowSession;

    static final Object mStaticInit = new Object();
    static boolean mInitialized = false;

    static final ThreadLocal<RunQueue> sRunQueues = new ThreadLocal<RunQueue>();

    static final ArrayList<Runnable> sFirstDrawHandlers = new ArrayList<Runnable>();
    static boolean sFirstDrawComplete = false;
    
    static final ArrayList<ComponentCallbacks> sConfigCallbacks
            = new ArrayList<ComponentCallbacks>();
    
    private static int sDrawTime;

    long mLastTrackballTime = 0;
    final TrackballAxis mTrackballAxisX = new TrackballAxis();
    final TrackballAxis mTrackballAxisY = new TrackballAxis();

    int mLastJoystickXDirection;
    int mLastJoystickYDirection;
    int mLastJoystickXKeyCode;
    int mLastJoystickYKeyCode;

    final int[] mTmpLocation = new int[2];

    final TypedValue mTmpValue = new TypedValue();
    
    final InputMethodCallback mInputMethodCallback;
    final SparseArray<Object> mPendingEvents = new SparseArray<Object>();
    int mPendingEventSeq = 0;

    final Thread mThread;

    final WindowLeaked mLocation;

    final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();

    final W mWindow;

    View mView;
    View mFocusedView;
    View mRealFocusedView;  // this is not set to null in touch mode
    int mViewVisibility;
    boolean mAppVisible = true;

    // Set to true if the owner of this window is in the stopped state,
    // so the window should no longer be active.
    boolean mStopped = false;
    
    boolean mLastInCompatMode = false;

    SurfaceHolder.Callback2 mSurfaceHolderCallback;
    BaseSurfaceHolder mSurfaceHolder;
    boolean mIsCreating;
    boolean mDrawingAllowed;
    
    final Region mTransparentRegion;
    final Region mPreviousTransparentRegion;

    int mWidth;
    int mHeight;
    Rect mDirty;
    final Rect mCurrentDirty = new Rect();
    final Rect mPreviousDirty = new Rect();
    boolean mIsAnimating;

    CompatibilityInfo.Translator mTranslator;

    final View.AttachInfo mAttachInfo;
    InputChannel mInputChannel;
    InputQueue.Callback mInputQueueCallback;
    InputQueue mInputQueue;
    FallbackEventHandler mFallbackEventHandler;
    
    final Rect mTempRect; // used in the transaction to not thrash the heap.
    final Rect mVisRect; // used to retrieve visible rect of focused view.

    boolean mTraversalScheduled;
    boolean mWillDrawSoon;
    boolean mLayoutRequested;
    boolean mFirst;
    boolean mReportNextDraw;
    boolean mFullRedrawNeeded;
    boolean mNewSurfaceNeeded;
    boolean mHasHadWindowFocus;
    boolean mLastWasImTarget;

    boolean mWindowAttributesChanged = false;

    // These can be accessed by any thread, must be protected with a lock.
    // Surface can never be reassigned or cleared (use Surface.clear()).
    private final Surface mSurface = new Surface();

    boolean mAdded;
    boolean mAddedTouchMode;

    CompatibilityInfoHolder mCompatibilityInfo;

    /*package*/ int mAddNesting;

    // These are accessed by multiple threads.
    final Rect mWinFrame; // frame given by window manager.

    final Rect mPendingVisibleInsets = new Rect();
    final Rect mPendingContentInsets = new Rect();
    final ViewTreeObserver.InternalInsetsInfo mLastGivenInsets
            = new ViewTreeObserver.InternalInsetsInfo();

    final Configuration mLastConfiguration = new Configuration();
    final Configuration mPendingConfiguration = new Configuration();
    
    class ResizedInfo {
        Rect coveredInsets;
        Rect visibleInsets;
        Configuration newConfig;
    }
    
    boolean mScrollMayChange;
    int mSoftInputMode;
    View mLastScrolledFocus;
    int mScrollY;
    int mCurScrollY;
    Scroller mScroller;
    Bitmap mResizeBitmap;
    long mResizeBitmapStartTime;
    int mResizeBitmapDuration;
    static final Interpolator mResizeInterpolator = new AccelerateDecelerateInterpolator();

    final ViewConfiguration mViewConfiguration;

    /* Drag/drop */
    ClipDescription mDragDescription;
    View mCurrentDragView;
    volatile Object mLocalDragState;
    final PointF mDragPoint = new PointF();
    final PointF mLastTouchPoint = new PointF();

    /**
     * see {@link #playSoundEffect(int)}
     */
    AudioManager mAudioManager;

    private final int mDensity;

    public static IWindowSession getWindowSession(Looper mainLooper) {
        synchronized (mStaticInit) {
            if (!mInitialized) {
                try {
                    InputMethodManager imm = InputMethodManager.getInstance(mainLooper);
                    sWindowSession = Display.getWindowManager().openSession(
                            imm.getClient(), imm.getInputContext());
                    mInitialized = true;
                } catch (RemoteException e) {
                }
            }
            return sWindowSession;
        }
    }
    
    public ViewRoot(Context context) {
        super();

        if (MEASURE_LATENCY) {
            if (lt == null) {
                lt = new LatencyTimer(100, 1000);
            }
        }

        // Initialize the statics when this class is first instantiated. This is
        // done here instead of in the static block because Zygote does not
        // allow the spawning of threads.
        getWindowSession(context.getMainLooper());
        
        mThread = Thread.currentThread();
        mLocation = new WindowLeaked(null);
        mLocation.fillInStackTrace();
        mWidth = -1;
        mHeight = -1;
        mDirty = new Rect();
        mTempRect = new Rect();
        mVisRect = new Rect();
        mWinFrame = new Rect();
        mWindow = new W(this);
        mInputMethodCallback = new InputMethodCallback(this);
        mViewVisibility = View.GONE;
        mTransparentRegion = new Region();
        mPreviousTransparentRegion = new Region();
        mFirst = true; // true for the first time the view is added
        mAdded = false;
        mAttachInfo = new View.AttachInfo(sWindowSession, mWindow, this, this);
        mViewConfiguration = ViewConfiguration.get(context);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        mFallbackEventHandler = PolicyManager.makeNewFallbackEventHandler(context);
    }

    public static void addFirstDrawHandler(Runnable callback) {
        synchronized (sFirstDrawHandlers) {
            if (!sFirstDrawComplete) {
                sFirstDrawHandlers.add(callback);
            }
        }
    }
    
    public static void addConfigCallback(ComponentCallbacks callback) {
        synchronized (sConfigCallbacks) {
            sConfigCallbacks.add(callback);
        }
    }
    
    // FIXME for perf testing only
    private boolean mProfile = false;

    /**
     * Call this to profile the next traversal call.
     * FIXME for perf testing only. Remove eventually
     */
    public void profile() {
        mProfile = true;
    }

    /**
     * Indicates whether we are in touch mode. Calling this method triggers an IPC
     * call and should be avoided whenever possible.
     *
     * @return True, if the device is in touch mode, false otherwise.
     *
     * @hide
     */
    static boolean isInTouchMode() {
        if (mInitialized) {
            try {
                return sWindowSession.getInTouchMode();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    /**
     * We have one child
     */
    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
        synchronized (this) {
            if (mView == null) {
                mView = view;
                mFallbackEventHandler.setView(view);
                mWindowAttributes.copyFrom(attrs);
                attrs = mWindowAttributes;
                
                if (view instanceof RootViewSurfaceTaker) {
                    mSurfaceHolderCallback =
                            ((RootViewSurfaceTaker)view).willYouTakeTheSurface();
                    if (mSurfaceHolderCallback != null) {
                        mSurfaceHolder = new TakenSurfaceHolder();
                        mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                    }
                }

                // If the application owns the surface, don't enable hardware acceleration
                if (mSurfaceHolder == null) {
                    enableHardwareAcceleration(attrs);
                }

                CompatibilityInfo compatibilityInfo = mCompatibilityInfo.get();
                mTranslator = compatibilityInfo.getTranslator();

                if (mTranslator != null) {
                    mSurface.setCompatibilityTranslator(mTranslator);
                }

                boolean restore = false;
                if (mTranslator != null) {
                    restore = true;
                    attrs.backup();
                    mTranslator.translateWindowLayout(attrs);
                }
                if (DEBUG_LAYOUT) Log.d(TAG, "WindowLayout in setView:" + attrs);

                if (!compatibilityInfo.supportsScreen()) {
                    attrs.flags |= WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                    mLastInCompatMode = true;
                }

                mSoftInputMode = attrs.softInputMode;
                mWindowAttributesChanged = true;
                mAttachInfo.mRootView = view;
                mAttachInfo.mScalingRequired = mTranslator != null;
                mAttachInfo.mApplicationScale =
                        mTranslator == null ? 1.0f : mTranslator.applicationScale;
                if (panelParentView != null) {
                    mAttachInfo.mPanelParentWindowToken
                            = panelParentView.getApplicationWindowToken();
                }
                mAdded = true;
                int res; /* = WindowManagerImpl.ADD_OKAY; */

                // Schedule the first layout -before- adding to the window
                // manager, to make sure we do the relayout before receiving
                // any other events from the system.
                requestLayout();
                mInputChannel = new InputChannel();
                try {
                    res = sWindowSession.add(mWindow, mWindowAttributes,
                            getHostVisibility(), mAttachInfo.mContentInsets,
                            mInputChannel);
                } catch (RemoteException e) {
                    mAdded = false;
                    mView = null;
                    mAttachInfo.mRootView = null;
                    mInputChannel = null;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    throw new RuntimeException("Adding window failed", e);
                } finally {
                    if (restore) {
                        attrs.restore();
                    }
                }
                
                if (mTranslator != null) {
                    mTranslator.translateRectInScreenToAppWindow(mAttachInfo.mContentInsets);
                }
                mPendingContentInsets.set(mAttachInfo.mContentInsets);
                mPendingVisibleInsets.set(0, 0, 0, 0);
                if (DEBUG_LAYOUT) Log.v(TAG, "Added window " + mWindow);
                if (res < WindowManagerImpl.ADD_OKAY) {
                    mView = null;
                    mAttachInfo.mRootView = null;
                    mAdded = false;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    switch (res) {
                        case WindowManagerImpl.ADD_BAD_APP_TOKEN:
                        case WindowManagerImpl.ADD_BAD_SUBWINDOW_TOKEN:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not valid; is your activity running?");
                        case WindowManagerImpl.ADD_NOT_APP_TOKEN:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not for an application");
                        case WindowManagerImpl.ADD_APP_EXITING:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- app for token " + attrs.token
                                + " is exiting");
                        case WindowManagerImpl.ADD_DUPLICATE_ADD:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- window " + mWindow
                                + " has already been added");
                        case WindowManagerImpl.ADD_STARTING_NOT_NEEDED:
                            // Silently ignore -- we would have just removed it
                            // right away, anyway.
                            return;
                        case WindowManagerImpl.ADD_MULTIPLE_SINGLETON:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- another window of this type already exists");
                        case WindowManagerImpl.ADD_PERMISSION_DENIED:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- permission denied for this window type");
                    }
                    throw new RuntimeException(
                        "Unable to add window -- unknown error code " + res);
                }

                if (view instanceof RootViewSurfaceTaker) {
                    mInputQueueCallback =
                        ((RootViewSurfaceTaker)view).willYouTakeTheInputQueue();
                }
                if (mInputQueueCallback != null) {
                    mInputQueue = new InputQueue(mInputChannel);
                    mInputQueueCallback.onInputQueueCreated(mInputQueue);
                } else {
                    InputQueue.registerInputChannel(mInputChannel, mInputHandler,
                            Looper.myQueue());
                }
                
                view.assignParent(this);
                mAddedTouchMode = (res&WindowManagerImpl.ADD_FLAG_IN_TOUCH_MODE) != 0;
                mAppVisible = (res&WindowManagerImpl.ADD_FLAG_APP_VISIBLE) != 0;
            }
        }
    }

    private void enableHardwareAcceleration(WindowManager.LayoutParams attrs) {
        mAttachInfo.mHardwareAccelerated = false;
        mAttachInfo.mHardwareAccelerationRequested = false;

        // Try to enable hardware acceleration if requested
        final boolean hardwareAccelerated = 
                (attrs.flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;

        if (attrs != null && hardwareAccelerated) {
            // Only enable hardware acceleration if we are not in the system process
            // The window manager creates ViewRoots to display animated preview windows
            // of launching apps and we don't want those to be hardware accelerated

            final boolean systemHwAccelerated =
                (attrs.flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED_SYSTEM) != 0;

            if (!HardwareRenderer.sRendererDisabled || systemHwAccelerated) {
                // Don't enable hardware acceleration when we're not on the main thread
                if (!systemHwAccelerated && Looper.getMainLooper() != Looper.myLooper()) {
                    Log.w(HardwareRenderer.LOG_TAG, "Attempting to initialize hardware "
                            + "acceleration outside of the main thread, aborting");
                    return;
                }

                final boolean translucent = attrs.format != PixelFormat.OPAQUE;
                if (mAttachInfo.mHardwareRenderer != null) {
                    mAttachInfo.mHardwareRenderer.destroy(true);
                }                
                mAttachInfo.mHardwareRenderer = HardwareRenderer.createGlRenderer(2, translucent);
                mAttachInfo.mHardwareAccelerated = mAttachInfo.mHardwareAccelerationRequested
                        = mAttachInfo.mHardwareRenderer != null;
            } else if (HardwareRenderer.isAvailable()) {
                mAttachInfo.mHardwareAccelerationRequested = true;
            }
        }
    }

    public View getView() {
        return mView;
    }

    final WindowLeaked getLocation() {
        return mLocation;
    }

    void setLayoutParams(WindowManager.LayoutParams attrs, boolean newView) {
        synchronized (this) {
            int oldSoftInputMode = mWindowAttributes.softInputMode;
            // preserve compatible window flag if exists.
            int compatibleWindowFlag =
                mWindowAttributes.flags & WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
            mWindowAttributes.copyFrom(attrs);
            mWindowAttributes.flags |= compatibleWindowFlag;
            
            if (newView) {
                mSoftInputMode = attrs.softInputMode;
                requestLayout();
            }
            // Don't lose the mode we last auto-computed.
            if ((attrs.softInputMode&WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                    == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                mWindowAttributes.softInputMode = (mWindowAttributes.softInputMode
                        & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        | (oldSoftInputMode
                                & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            }
            mWindowAttributesChanged = true;
            scheduleTraversals();
        }
    }

    void handleAppVisibility(boolean visible) {
        if (mAppVisible != visible) {
            mAppVisible = visible;
            scheduleTraversals();
        }
    }

    void handleGetNewSurface() {
        mNewSurfaceNeeded = true;
        mFullRedrawNeeded = true;
        scheduleTraversals();
    }

    /**
     * {@inheritDoc}
     */
    public void requestLayout() {
        checkThread();
        mLayoutRequested = true;
        scheduleTraversals();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isLayoutRequested() {
        return mLayoutRequested;
    }

    public void invalidateChild(View child, Rect dirty) {
        checkThread();
        if (DEBUG_DRAW) Log.v(TAG, "Invalidate child: " + dirty);
        if (dirty == null) {
            // Fast invalidation for GL-enabled applications; GL must redraw everything
            invalidate();
            return;
        }
        if (mCurScrollY != 0 || mTranslator != null) {
            mTempRect.set(dirty);
            dirty = mTempRect;
            if (mCurScrollY != 0) {
               dirty.offset(0, -mCurScrollY);
            }
            if (mTranslator != null) {
                mTranslator.translateRectInAppWindowToScreen(dirty);
            }
            if (mAttachInfo.mScalingRequired) {
                dirty.inset(-1, -1);
            }
        }
        if (!mDirty.isEmpty() && !mDirty.contains(dirty)) {
            mAttachInfo.mIgnoreDirtyState = true;
        }
        mDirty.union(dirty);
        if (!mWillDrawSoon) {
            scheduleTraversals();
        }
    }
    
    void invalidate() {
        mDirty.set(0, 0, mWidth, mHeight);
        scheduleTraversals();
    }

    void setStopped(boolean stopped) {
        if (mStopped != stopped) {
            mStopped = stopped;
            if (!stopped) {
                scheduleTraversals();
            }
        }
    }
    
    public ViewParent getParent() {
        return null;
    }

    public ViewParent invalidateChildInParent(final int[] location, final Rect dirty) {
        invalidateChild(null, dirty);
        return null;
    }

    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        if (child != mView) {
            throw new RuntimeException("child is not mine, honest!");
        }
        // Note: don't apply scroll offset, because we want to know its
        // visibility in the virtual canvas being given to the view hierarchy.
        return r.intersect(0, 0, mWidth, mHeight);
    }

    public void bringChildToFront(View child) {
    }

    public void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            sendEmptyMessage(DO_TRAVERSAL);
        }
    }

    public void unscheduleTraversals() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            removeMessages(DO_TRAVERSAL);
        }
    }

    int getHostVisibility() {
        return mAppVisible ? mView.getVisibility() : View.GONE;
    }

    void disposeResizeBitmap() {
        if (mResizeBitmap != null) {
            mResizeBitmap.recycle();
            mResizeBitmap = null;
        }
    }

    private void performTraversals() {
        // cache mView since it is used so much below...
        final View host = mView;

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals");
            host.debug();
        }

        if (host == null || !mAdded)
            return;

        mTraversalScheduled = false;
        mWillDrawSoon = true;
        boolean windowSizeMayChange = false;
        boolean fullRedrawNeeded = mFullRedrawNeeded;
        boolean newSurface = false;
        boolean surfaceChanged = false;
        WindowManager.LayoutParams lp = mWindowAttributes;

        int desiredWindowWidth;
        int desiredWindowHeight;
        int childWidthMeasureSpec;
        int childHeightMeasureSpec;

        final View.AttachInfo attachInfo = mAttachInfo;

        final int viewVisibility = getHostVisibility();
        boolean viewVisibilityChanged = mViewVisibility != viewVisibility
                || mNewSurfaceNeeded;

        WindowManager.LayoutParams params = null;
        if (mWindowAttributesChanged) {
            mWindowAttributesChanged = false;
            surfaceChanged = true;
            params = lp;
        }
        CompatibilityInfo compatibilityInfo = mCompatibilityInfo.get();
        if (compatibilityInfo.supportsScreen() == mLastInCompatMode) {
            params = lp;
            fullRedrawNeeded = true;
            mLayoutRequested = true;
            if (mLastInCompatMode) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = false;
            } else {
                params.flags |= WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = true;
            }
        }
        Rect frame = mWinFrame;
        if (mFirst) {
            fullRedrawNeeded = true;
            mLayoutRequested = true;

            if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL) {
                // NOTE -- system code, won't try to do compat mode.
                Display disp = WindowManagerImpl.getDefault().getDefaultDisplay();
                desiredWindowWidth = disp.getRealWidth();
                desiredWindowHeight = disp.getRealHeight();
            } else {
                DisplayMetrics packageMetrics =
                    mView.getContext().getResources().getDisplayMetrics();
                desiredWindowWidth = packageMetrics.widthPixels;
                desiredWindowHeight = packageMetrics.heightPixels;
            }

            // For the very first time, tell the view hierarchy that it
            // is attached to the window.  Note that at this point the surface
            // object is not initialized to its backing store, but soon it
            // will be (assuming the window is visible).
            attachInfo.mSurface = mSurface;
            // We used to use the following condition to choose 32 bits drawing caches:
            // PixelFormat.hasAlpha(lp.format) || lp.format == PixelFormat.RGBX_8888
            // However, windows are now always 32 bits by default, so choose 32 bits
            attachInfo.mUse32BitDrawingCache = true;
            attachInfo.mHasWindowFocus = false;
            attachInfo.mWindowVisibility = viewVisibility;
            attachInfo.mRecomputeGlobalAttributes = false;
            attachInfo.mKeepScreenOn = false;
            attachInfo.mSystemUiVisibility = 0;
            viewVisibilityChanged = false;
            mLastConfiguration.setTo(host.getResources().getConfiguration());
            host.dispatchAttachedToWindow(attachInfo, 0);
            //Log.i(TAG, "Screen on initialized: " + attachInfo.mKeepScreenOn);

        } else {
            desiredWindowWidth = frame.width();
            desiredWindowHeight = frame.height();
            if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
                if (DEBUG_ORIENTATION) Log.v(TAG,
                        "View " + host + " resized to: " + frame);
                fullRedrawNeeded = true;
                mLayoutRequested = true;
                windowSizeMayChange = true;
            }
        }

        if (viewVisibilityChanged) {
            attachInfo.mWindowVisibility = viewVisibility;
            host.dispatchWindowVisibilityChanged(viewVisibility);
            if (viewVisibility != View.VISIBLE || mNewSurfaceNeeded) {
                if (mAttachInfo.mHardwareRenderer != null) {
                    mAttachInfo.mHardwareRenderer.destroy(false);
                }                
            }
            if (viewVisibility == View.GONE) {
                // After making a window gone, we will count it as being
                // shown for the first time the next time it gets focus.
                mHasHadWindowFocus = false;
            }
        }

        boolean insetsChanged = false;

        if (mLayoutRequested && !mStopped) {
            // Execute enqueued actions on every layout in case a view that was detached
            // enqueued an action after being detached
            getRunQueue().executeActions(attachInfo.mHandler);

            final Resources res = mView.getContext().getResources();

            if (mFirst) {
                host.fitSystemWindows(mAttachInfo.mContentInsets);
                // make sure touch mode code executes by setting cached value
                // to opposite of the added touch mode.
                mAttachInfo.mInTouchMode = !mAddedTouchMode;
                ensureTouchModeLocally(mAddedTouchMode);
            } else {
                if (!mAttachInfo.mContentInsets.equals(mPendingContentInsets)) {
                    if (mWidth > 0 && mHeight > 0 &&
                            mSurface != null && mSurface.isValid() &&
                            !mAttachInfo.mTurnOffWindowResizeAnim &&
                            mAttachInfo.mHardwareRenderer != null &&
                            mAttachInfo.mHardwareRenderer.isEnabled() &&
                            lp != null && !PixelFormat.formatHasAlpha(lp.format)) {

                        disposeResizeBitmap();

                        boolean completed = false;
                        try {
                            mResizeBitmap = Bitmap.createBitmap(mWidth, mHeight,
                                    Bitmap.Config.ARGB_8888);
                            mResizeBitmap.setHasAlpha(false);
                            Canvas canvas = new Canvas(mResizeBitmap);
                            canvas.drawColor(0xff000000, PorterDuff.Mode.SRC);
                            int yoff;
                            final boolean scrolling = mScroller != null
                                    && mScroller.computeScrollOffset();
                            if (scrolling) {
                                yoff = mScroller.getCurrY();
                                mScroller.abortAnimation();
                            } else {
                                yoff = mScrollY;
                            }
                            canvas.translate(0, -yoff);
                            if (mTranslator != null) {
                                mTranslator.translateCanvas(canvas);
                            }
                            canvas.setScreenDensity(mAttachInfo.mScalingRequired
                                    ? DisplayMetrics.DENSITY_DEVICE : 0);
                            mView.draw(canvas);
                            mResizeBitmapStartTime = SystemClock.uptimeMillis();
                            mResizeBitmapDuration = mView.getResources().getInteger(
                                    com.android.internal.R.integer.config_mediumAnimTime);
                            completed = true;
                        } catch (OutOfMemoryError e) {
                            Log.w(TAG, "Not enough memory for content change anim buffer", e);
                        } finally {
                            if (!completed) {
                                mResizeBitmap = null;
                            }
                        }
                    }
                    mAttachInfo.mContentInsets.set(mPendingContentInsets);
                    host.fitSystemWindows(mAttachInfo.mContentInsets);
                    insetsChanged = true;
                    if (DEBUG_LAYOUT) Log.v(TAG, "Content insets changing to: "
                            + mAttachInfo.mContentInsets);
                }
                if (!mAttachInfo.mVisibleInsets.equals(mPendingVisibleInsets)) {
                    mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                    if (DEBUG_LAYOUT) Log.v(TAG, "Visible insets changing to: "
                            + mAttachInfo.mVisibleInsets);
                }
                if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    windowSizeMayChange = true;

                    if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL) {
                        // NOTE -- system code, won't try to do compat mode.
                        Display disp = WindowManagerImpl.getDefault().getDefaultDisplay();
                        desiredWindowWidth = disp.getRealWidth();
                        desiredWindowHeight = disp.getRealHeight();
                    } else {
                        DisplayMetrics packageMetrics = res.getDisplayMetrics();
                        desiredWindowWidth = packageMetrics.widthPixels;
                        desiredWindowHeight = packageMetrics.heightPixels;
                    }
                }
            }

            // Ask host how big it wants to be
            if (DEBUG_ORIENTATION || DEBUG_LAYOUT) Log.v(TAG,
                    "Measuring " + host + " in display " + desiredWindowWidth
                    + "x" + desiredWindowHeight + "...");

            boolean goodMeasure = false;
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                    || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                // On large screens, we don't want to allow dialogs to just
                // stretch to fill the entire width of the screen to display
                // one line of text.  First try doing the layout at a smaller
                // size to see if it will fit.
                final DisplayMetrics packageMetrics = res.getDisplayMetrics();
                res.getValue(com.android.internal.R.dimen.config_prefDialogWidth, mTmpValue, true);
                int baseSize = 0;
                if (mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                    baseSize = (int)mTmpValue.getDimension(packageMetrics);
                }
                if (DEBUG_DIALOG) Log.v(TAG, "Window " + mView + ": baseSize=" + baseSize);
                if (baseSize != 0 && desiredWindowWidth > baseSize) {
                    childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width);
                    childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);
                    host.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                    if (DEBUG_DIALOG) Log.v(TAG, "Window " + mView + ": measured ("
                            + host.getMeasuredWidth() + "," + host.getMeasuredHeight() + ")");
                    if ((host.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL) == 0) {
                        goodMeasure = true;
                    } else {
                        // Didn't fit in that size... try expanding a bit.
                        baseSize = (baseSize+desiredWindowWidth)/2;
                        if (DEBUG_DIALOG) Log.v(TAG, "Window " + mView + ": next baseSize="
                                + baseSize);
                        childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width);
                        host.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                        if (DEBUG_DIALOG) Log.v(TAG, "Window " + mView + ": measured ("
                                + host.getMeasuredWidth() + "," + host.getMeasuredHeight() + ")");
                        if ((host.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL) == 0) {
                            if (DEBUG_DIALOG) Log.v(TAG, "Good!");
                            goodMeasure = true;
                        }
                    }
                }
            }

            if (!goodMeasure) {
                childWidthMeasureSpec = getRootMeasureSpec(desiredWindowWidth, lp.width);
                childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);
                host.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                if (mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight()) {
                    windowSizeMayChange = true;
                }
            }

            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after measure");
                host.debug();
            }
        }

        if (attachInfo.mRecomputeGlobalAttributes && host.mAttachInfo != null) {
            //Log.i(TAG, "Computing view hierarchy attributes!");
            attachInfo.mRecomputeGlobalAttributes = false;
            boolean oldScreenOn = attachInfo.mKeepScreenOn;
            int oldVis = attachInfo.mSystemUiVisibility;
            attachInfo.mKeepScreenOn = false;
            attachInfo.mSystemUiVisibility = 0;
            attachInfo.mHasSystemUiListeners = false;
            host.dispatchCollectViewAttributes(0);
            if (attachInfo.mKeepScreenOn != oldScreenOn
                    || attachInfo.mSystemUiVisibility != oldVis
                    || attachInfo.mHasSystemUiListeners) {
                params = lp;
            }
        }

        if (mFirst || attachInfo.mViewVisibilityChanged) {
            attachInfo.mViewVisibilityChanged = false;
            int resizeMode = mSoftInputMode &
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
            // If we are in auto resize mode, then we need to determine
            // what mode to use now.
            if (resizeMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                final int N = attachInfo.mScrollContainers.size();
                for (int i=0; i<N; i++) {
                    if (attachInfo.mScrollContainers.get(i).isShown()) {
                        resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                    }
                }
                if (resizeMode == 0) {
                    resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
                }
                if ((lp.softInputMode &
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) != resizeMode) {
                    lp.softInputMode = (lp.softInputMode &
                            ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) |
                            resizeMode;
                    params = lp;
                }
            }
        }

        if (params != null && (host.mPrivateFlags & View.REQUEST_TRANSPARENT_REGIONS) != 0) {
            if (!PixelFormat.formatHasAlpha(params.format)) {
                params.format = PixelFormat.TRANSLUCENT;
            }
        }

        boolean windowShouldResize = mLayoutRequested && windowSizeMayChange
            && ((mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight())
                || (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        frame.width() < desiredWindowWidth && frame.width() != mWidth)
                || (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        frame.height() < desiredWindowHeight && frame.height() != mHeight));

        final boolean computesInternalInsets =
                attachInfo.mTreeObserver.hasComputeInternalInsetsListeners();

        boolean insetsPending = false;
        int relayoutResult = 0;

        if (mFirst || windowShouldResize || insetsChanged ||
                viewVisibilityChanged || params != null) {

            if (viewVisibility == View.VISIBLE) {
                // If this window is giving internal insets to the window
                // manager, and it is being added or changing its visibility,
                // then we want to first give the window manager "fake"
                // insets to cause it to effectively ignore the content of
                // the window during layout.  This avoids it briefly causing
                // other windows to resize/move based on the raw frame of the
                // window, waiting until we can finish laying out this window
                // and get back to the window manager with the ultimately
                // computed insets.
                insetsPending = computesInternalInsets && (mFirst || viewVisibilityChanged);
            }

            if (mSurfaceHolder != null) {
                mSurfaceHolder.mSurfaceLock.lock();
                mDrawingAllowed = true;
            }

            boolean hwInitialized = false;
            boolean contentInsetsChanged = false;
            boolean visibleInsetsChanged;
            boolean hadSurface = mSurface.isValid();

            try {
                int fl = 0;
                if (params != null) {
                    fl = params.flags;
                    if (attachInfo.mKeepScreenOn) {
                        params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                    }
                    params.subtreeSystemUiVisibility = attachInfo.mSystemUiVisibility;
                    params.hasSystemUiListeners = attachInfo.mHasSystemUiListeners
                            || params.subtreeSystemUiVisibility != 0
                            || params.systemUiVisibility != 0;
                }
                if (DEBUG_LAYOUT) {
                    Log.i(TAG, "host=w:" + host.getMeasuredWidth() + ", h:" +
                            host.getMeasuredHeight() + ", params=" + params);
                }

                final int surfaceGenerationId = mSurface.getGenerationId();
                relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);

                if (params != null) {
                    params.flags = fl;
                }

                if (DEBUG_LAYOUT) Log.v(TAG, "relayout: frame=" + frame.toShortString()
                        + " content=" + mPendingContentInsets.toShortString()
                        + " visible=" + mPendingVisibleInsets.toShortString()
                        + " surface=" + mSurface);

                if (mPendingConfiguration.seq != 0) {
                    if (DEBUG_CONFIGURATION) Log.v(TAG, "Visible with new config: "
                            + mPendingConfiguration);
                    updateConfiguration(mPendingConfiguration, !mFirst);
                    mPendingConfiguration.seq = 0;
                }
                
                contentInsetsChanged = !mPendingContentInsets.equals(
                        mAttachInfo.mContentInsets);
                visibleInsetsChanged = !mPendingVisibleInsets.equals(
                        mAttachInfo.mVisibleInsets);
                if (contentInsetsChanged) {
                    mAttachInfo.mContentInsets.set(mPendingContentInsets);
                    host.fitSystemWindows(mAttachInfo.mContentInsets);
                    if (DEBUG_LAYOUT) Log.v(TAG, "Content insets changing to: "
                            + mAttachInfo.mContentInsets);
                }
                if (visibleInsetsChanged) {
                    mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                    if (DEBUG_LAYOUT) Log.v(TAG, "Visible insets changing to: "
                            + mAttachInfo.mVisibleInsets);
                }

                if (!hadSurface) {
                    if (mSurface.isValid()) {
                        // If we are creating a new surface, then we need to
                        // completely redraw it.  Also, when we get to the
                        // point of drawing it we will hold off and schedule
                        // a new traversal instead.  This is so we can tell the
                        // window manager about all of the windows being displayed
                        // before actually drawing them, so it can display then
                        // all at once.
                        newSurface = true;
                        fullRedrawNeeded = true;
                        mPreviousTransparentRegion.setEmpty();

                        if (mAttachInfo.mHardwareRenderer != null) {
                            try {
                                hwInitialized = mAttachInfo.mHardwareRenderer.initialize(mHolder);
                            } catch (Surface.OutOfResourcesException e) {
                                Log.e(TAG, "OutOfResourcesException initializing HW surface", e);
                                try {
                                    if (!sWindowSession.outOfMemory(mWindow)) {
                                        Slog.w(TAG, "No processes killed for memory; killing self");
                                        Process.killProcess(Process.myPid());
                                    }
                                } catch (RemoteException ex) {
                                }
                                mLayoutRequested = true;    // ask wm for a new surface next time.
                                return;
                            }
                        }
                    }
                } else if (!mSurface.isValid()) {
                    // If the surface has been removed, then reset the scroll
                    // positions.
                    mLastScrolledFocus = null;
                    mScrollY = mCurScrollY = 0;
                    if (mScroller != null) {
                        mScroller.abortAnimation();
                    }
                    disposeResizeBitmap();
                } else if (surfaceGenerationId != mSurface.getGenerationId() &&
                        mSurfaceHolder == null && mAttachInfo.mHardwareRenderer != null) {
                    fullRedrawNeeded = true;
                    try {
                        mAttachInfo.mHardwareRenderer.updateSurface(mHolder);
                    } catch (Surface.OutOfResourcesException e) {
                        Log.e(TAG, "OutOfResourcesException updating HW surface", e);
                        try {
                            if (!sWindowSession.outOfMemory(mWindow)) {
                                Slog.w(TAG, "No processes killed for memory; killing self");
                                Process.killProcess(Process.myPid());
                            }
                        } catch (RemoteException ex) {
                        }
                        mLayoutRequested = true;    // ask wm for a new surface next time.
                        return;
                    }
                }
            } catch (RemoteException e) {
            }
            
            if (DEBUG_ORIENTATION) Log.v(
                    TAG, "Relayout returned: frame=" + frame + ", surface=" + mSurface);

            attachInfo.mWindowLeft = frame.left;
            attachInfo.mWindowTop = frame.top;

            // !!FIXME!! This next section handles the case where we did not get the
            // window size we asked for. We should avoid this by getting a maximum size from
            // the window session beforehand.
            mWidth = frame.width();
            mHeight = frame.height();

            if (mSurfaceHolder != null) {
                // The app owns the surface; tell it about what is going on.
                if (mSurface.isValid()) {
                    // XXX .copyFrom() doesn't work!
                    //mSurfaceHolder.mSurface.copyFrom(mSurface);
                    mSurfaceHolder.mSurface = mSurface;
                }
                mSurfaceHolder.setSurfaceFrameSize(mWidth, mHeight);
                mSurfaceHolder.mSurfaceLock.unlock();
                if (mSurface.isValid()) {
                    if (!hadSurface) {
                        mSurfaceHolder.ungetCallbacks();

                        mIsCreating = true;
                        mSurfaceHolderCallback.surfaceCreated(mSurfaceHolder);
                        SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                        if (callbacks != null) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceCreated(mSurfaceHolder);
                            }
                        }
                        surfaceChanged = true;
                    }
                    if (surfaceChanged) {
                        mSurfaceHolderCallback.surfaceChanged(mSurfaceHolder,
                                lp.format, mWidth, mHeight);
                        SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                        if (callbacks != null) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceChanged(mSurfaceHolder, lp.format,
                                        mWidth, mHeight);
                            }
                        }
                    }
                    mIsCreating = false;
                } else if (hadSurface) {
                    mSurfaceHolder.ungetCallbacks();
                    SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                    mSurfaceHolderCallback.surfaceDestroyed(mSurfaceHolder);
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceDestroyed(mSurfaceHolder);
                        }
                    }
                    mSurfaceHolder.mSurfaceLock.lock();
                    try {
                        mSurfaceHolder.mSurface = new Surface();
                    } finally {
                        mSurfaceHolder.mSurfaceLock.unlock();
                    }
                }
            }

            if (hwInitialized || ((windowShouldResize || params != null) &&
                    mAttachInfo.mHardwareRenderer != null &&
                    mAttachInfo.mHardwareRenderer.isEnabled())) {
                mAttachInfo.mHardwareRenderer.setup(mWidth, mHeight);
            }

            if (!mStopped) {
                boolean focusChangedDueToTouchMode = ensureTouchModeLocally(
                        (relayoutResult&WindowManagerImpl.RELAYOUT_IN_TOUCH_MODE) != 0);
                if (focusChangedDueToTouchMode || mWidth != host.getMeasuredWidth()
                        || mHeight != host.getMeasuredHeight() || contentInsetsChanged) {
                    childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width);
                    childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height);
    
                    if (DEBUG_LAYOUT) Log.v(TAG, "Ooops, something changed!  mWidth="
                            + mWidth + " measuredWidth=" + host.getMeasuredWidth()
                            + " mHeight=" + mHeight
                            + " measuredHeight=" + host.getMeasuredHeight()
                            + " coveredInsetsChanged=" + contentInsetsChanged);
    
                     // Ask host how big it wants to be
                    host.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    
                    // Implementation of weights from WindowManager.LayoutParams
                    // We just grow the dimensions as needed and re-measure if
                    // needs be
                    int width = host.getMeasuredWidth();
                    int height = host.getMeasuredHeight();
                    boolean measureAgain = false;
    
                    if (lp.horizontalWeight > 0.0f) {
                        width += (int) ((mWidth - width) * lp.horizontalWeight);
                        childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
                    if (lp.verticalWeight > 0.0f) {
                        height += (int) ((mHeight - height) * lp.verticalWeight);
                        childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
    
                    if (measureAgain) {
                        if (DEBUG_LAYOUT) Log.v(TAG,
                                "And hey let's measure once more: width=" + width
                                + " height=" + height);
                        host.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                    }
    
                    mLayoutRequested = true;
                }
            }
        }

        final boolean didLayout = mLayoutRequested && !mStopped;
        boolean triggerGlobalLayoutListener = didLayout
                || attachInfo.mRecomputeGlobalAttributes;
        if (didLayout) {
            mLayoutRequested = false;
            mScrollMayChange = true;
            if (DEBUG_ORIENTATION || DEBUG_LAYOUT) Log.v(
                TAG, "Laying out " + host + " to (" +
                host.getMeasuredWidth() + ", " + host.getMeasuredHeight() + ")");
            long startTime = 0L;
            if (ViewDebug.DEBUG_PROFILE_LAYOUT) {
                startTime = SystemClock.elapsedRealtime();
            }
            host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

            if (Config.DEBUG && ViewDebug.consistencyCheckEnabled) {
                if (!host.dispatchConsistencyCheck(ViewDebug.CONSISTENCY_LAYOUT)) {
                    throw new IllegalStateException("The view hierarchy is an inconsistent state,"
                            + "please refer to the logs with the tag "
                            + ViewDebug.CONSISTENCY_LOG_TAG + " for more infomation.");
                }
            }

            if (ViewDebug.DEBUG_PROFILE_LAYOUT) {
                EventLog.writeEvent(60001, SystemClock.elapsedRealtime() - startTime);
            }

            // By this point all views have been sized and positionned
            // We can compute the transparent area

            if ((host.mPrivateFlags & View.REQUEST_TRANSPARENT_REGIONS) != 0) {
                // start out transparent
                // TODO: AVOID THAT CALL BY CACHING THE RESULT?
                host.getLocationInWindow(mTmpLocation);
                mTransparentRegion.set(mTmpLocation[0], mTmpLocation[1],
                        mTmpLocation[0] + host.mRight - host.mLeft,
                        mTmpLocation[1] + host.mBottom - host.mTop);

                host.gatherTransparentRegion(mTransparentRegion);
                if (mTranslator != null) {
                    mTranslator.translateRegionInWindowToScreen(mTransparentRegion);
                }

                if (!mTransparentRegion.equals(mPreviousTransparentRegion)) {
                    mPreviousTransparentRegion.set(mTransparentRegion);
                    // reconfigure window manager
                    try {
                        sWindowSession.setTransparentRegion(mWindow, mTransparentRegion);
                    } catch (RemoteException e) {
                    }
                }
            }

            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after setFrame");
                host.debug();
            }
        }

        if (triggerGlobalLayoutListener) {
            attachInfo.mRecomputeGlobalAttributes = false;
            attachInfo.mTreeObserver.dispatchOnGlobalLayout();
        }

        if (computesInternalInsets) {
            // Clear the original insets.
            final ViewTreeObserver.InternalInsetsInfo insets = attachInfo.mGivenInternalInsets;
            insets.reset();

            // Compute new insets in place.
            attachInfo.mTreeObserver.dispatchOnComputeInternalInsets(insets);

            // Tell the window manager.
            if (insetsPending || !mLastGivenInsets.equals(insets)) {
                mLastGivenInsets.set(insets);

                // Translate insets to screen coordinates if needed.
                final Rect contentInsets;
                final Rect visibleInsets;
                final Region touchableRegion;
                if (mTranslator != null) {
                    contentInsets = mTranslator.getTranslatedContentInsets(insets.contentInsets);
                    visibleInsets = mTranslator.getTranslatedVisibleInsets(insets.visibleInsets);
                    touchableRegion = mTranslator.getTranslatedTouchableArea(insets.touchableRegion);
                } else {
                    contentInsets = insets.contentInsets;
                    visibleInsets = insets.visibleInsets;
                    touchableRegion = insets.touchableRegion;
                }

                try {
                    sWindowSession.setInsets(mWindow, insets.mTouchableInsets,
                            contentInsets, visibleInsets, touchableRegion);
                } catch (RemoteException e) {
                }
            }
        }

        if (mFirst) {
            // handle first focus request
            if (DEBUG_INPUT_RESIZE) Log.v(TAG, "First: mView.hasFocus()="
                    + mView.hasFocus());
            if (mView != null) {
                if (!mView.hasFocus()) {
                    mView.requestFocus(View.FOCUS_FORWARD);
                    mFocusedView = mRealFocusedView = mView.findFocus();
                    if (DEBUG_INPUT_RESIZE) Log.v(TAG, "First: requested focused view="
                            + mFocusedView);
                } else {
                    mRealFocusedView = mView.findFocus();
                    if (DEBUG_INPUT_RESIZE) Log.v(TAG, "First: existing focused view="
                            + mRealFocusedView);
                }
            }
        }

        mFirst = false;
        mWillDrawSoon = false;
        mNewSurfaceNeeded = false;
        mViewVisibility = viewVisibility;

        if (mAttachInfo.mHasWindowFocus) {
            final boolean imTarget = WindowManager.LayoutParams
                    .mayUseInputMethod(mWindowAttributes.flags);
            if (imTarget != mLastWasImTarget) {
                mLastWasImTarget = imTarget;
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null && imTarget) {
                    imm.startGettingWindowFocus(mView);
                    imm.onWindowFocus(mView, mView.findFocus(),
                            mWindowAttributes.softInputMode,
                            !mHasHadWindowFocus, mWindowAttributes.flags);
                }
            }
        }

        boolean cancelDraw = attachInfo.mTreeObserver.dispatchOnPreDraw();

        if (!cancelDraw && !newSurface) {
            mFullRedrawNeeded = false;
            draw(fullRedrawNeeded);

            if ((relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0
                    || mReportNextDraw) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "FINISHED DRAWING: " + mWindowAttributes.getTitle());
                }
                mReportNextDraw = false;
                if (mSurfaceHolder != null && mSurface.isValid()) {
                    mSurfaceHolderCallback.surfaceRedrawNeeded(mSurfaceHolder);
                    SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            if (c instanceof SurfaceHolder.Callback2) {
                                ((SurfaceHolder.Callback2)c).surfaceRedrawNeeded(
                                        mSurfaceHolder);
                            }
                        }
                    }
                }
                try {
                    sWindowSession.finishDrawing(mWindow);
                } catch (RemoteException e) {
                }
            }
        } else {
            // We were supposed to report when we are done drawing. Since we canceled the
            // draw, remember it here.
            if ((relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                mReportNextDraw = true;
            }
            if (fullRedrawNeeded) {
                mFullRedrawNeeded = true;
            }
            // Try again
            scheduleTraversals();
        }
    }

    public void requestTransparentRegion(View child) {
        // the test below should not fail unless someone is messing with us
        checkThread();
        if (mView == child) {
            mView.mPrivateFlags |= View.REQUEST_TRANSPARENT_REGIONS;
            // Need to make sure we re-evaluate the window attributes next
            // time around, to ensure the window has the correct format.
            mWindowAttributesChanged = true;
            requestLayout();
        }
    }

    /**
     * Figures out the measure spec for the root view in a window based on it's
     * layout params.
     *
     * @param windowSize
     *            The available width or height of the window
     *
     * @param rootDimension
     *            The layout params for one dimension (width or height) of the
     *            window.
     *
     * @return The measure spec to use to measure the root view.
     */
    private int getRootMeasureSpec(int windowSize, int rootDimension) {
        int measureSpec;
        switch (rootDimension) {

        case ViewGroup.LayoutParams.MATCH_PARENT:
            // Window can't resize. Force root view to be windowSize.
            measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.EXACTLY);
            break;
        case ViewGroup.LayoutParams.WRAP_CONTENT:
            // Window can resize. Set max size for root view.
            measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.AT_MOST);
            break;
        default:
            // Window wants to be an exact size. Force root view to be that size.
            measureSpec = MeasureSpec.makeMeasureSpec(rootDimension, MeasureSpec.EXACTLY);
            break;
        }
        return measureSpec;
    }

    int mHardwareYOffset;
    int mResizeAlpha;
    final Paint mResizePaint = new Paint();

    public void onHardwarePreDraw(Canvas canvas) {
        canvas.translate(0, -mHardwareYOffset);
    }

    public void onHardwarePostDraw(Canvas canvas) {
        if (mResizeBitmap != null) {
            canvas.translate(0, mHardwareYOffset);
            mResizePaint.setAlpha(mResizeAlpha);
            canvas.drawBitmap(mResizeBitmap, 0, 0, mResizePaint);
        }
    }

    private void draw(boolean fullRedrawNeeded) {
        Surface surface = mSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }

        if (!sFirstDrawComplete) {
            synchronized (sFirstDrawHandlers) {
                sFirstDrawComplete = true;
                final int count = sFirstDrawHandlers.size();
                for (int i = 0; i< count; i++) {
                    post(sFirstDrawHandlers.get(i));
                }
            }
        }
        
        scrollToRectOrFocus(null, false);

        if (mAttachInfo.mViewScrollChanged) {
            mAttachInfo.mViewScrollChanged = false;
            mAttachInfo.mTreeObserver.dispatchOnScrollChanged();
        }

        int yoff;
        boolean animating = mScroller != null && mScroller.computeScrollOffset();
        if (animating) {
            yoff = mScroller.getCurrY();
        } else {
            yoff = mScrollY;
        }
        if (mCurScrollY != yoff) {
            mCurScrollY = yoff;
            fullRedrawNeeded = true;
        }
        float appScale = mAttachInfo.mApplicationScale;
        boolean scalingRequired = mAttachInfo.mScalingRequired;

        int resizeAlpha = 0;
        if (mResizeBitmap != null) {
            long deltaTime = SystemClock.uptimeMillis() - mResizeBitmapStartTime;
            if (deltaTime < mResizeBitmapDuration) {
                float amt = deltaTime/(float)mResizeBitmapDuration;
                amt = mResizeInterpolator.getInterpolation(amt);
                animating = true;
                resizeAlpha = 255 - (int)(amt*255);
            } else {
                disposeResizeBitmap();
            }
        }

        Rect dirty = mDirty;
        if (mSurfaceHolder != null) {
            // The app owns the surface, we won't draw.
            dirty.setEmpty();
            if (animating) {
                if (mScroller != null) {
                    mScroller.abortAnimation();
                }
                disposeResizeBitmap();
            }
            return;
        }

        if (fullRedrawNeeded) {
            mAttachInfo.mIgnoreDirtyState = true;
            dirty.union(0, 0, (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        }
        
        if (mAttachInfo.mHardwareRenderer != null && mAttachInfo.mHardwareRenderer.isEnabled()) {
            if (!dirty.isEmpty() || mIsAnimating) {
                mIsAnimating = false;
                mHardwareYOffset = yoff;
                mResizeAlpha = resizeAlpha;

                mCurrentDirty.set(dirty);
                mCurrentDirty.union(mPreviousDirty);
                mPreviousDirty.set(dirty);
                dirty.setEmpty();

                Rect currentDirty = mCurrentDirty;
                if (animating) {
                    currentDirty = null;
                }

                mAttachInfo.mHardwareRenderer.draw(mView, mAttachInfo, this, currentDirty);
            }

            if (animating) {
                mFullRedrawNeeded = true;
                scheduleTraversals();
            }

            return;
        }

        if (DEBUG_ORIENTATION || DEBUG_DRAW) {
            Log.v(TAG, "Draw " + mView + "/"
                    + mWindowAttributes.getTitle()
                    + ": dirty={" + dirty.left + "," + dirty.top
                    + "," + dirty.right + "," + dirty.bottom + "} surface="
                    + surface + " surface.isValid()=" + surface.isValid() + ", appScale:" +
                    appScale + ", width=" + mWidth + ", height=" + mHeight);
        }

        if (!dirty.isEmpty() || mIsAnimating) {
            Canvas canvas;
            try {
                int left = dirty.left;
                int top = dirty.top;
                int right = dirty.right;
                int bottom = dirty.bottom;

                canvas = surface.lockCanvas(dirty);

                if (left != dirty.left || top != dirty.top || right != dirty.right ||
                        bottom != dirty.bottom) {
                    mAttachInfo.mIgnoreDirtyState = true;
                }

                // TODO: Do this in native
                canvas.setDensity(mDensity);
            } catch (Surface.OutOfResourcesException e) {
                Log.e(TAG, "OutOfResourcesException locking surface", e);
                try {
                    if (!sWindowSession.outOfMemory(mWindow)) {
                        Slog.w(TAG, "No processes killed for memory; killing self");
                        Process.killProcess(Process.myPid());
                    }
                } catch (RemoteException ex) {
                }
                mLayoutRequested = true;    // ask wm for a new surface next time.
                return;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "IllegalArgumentException locking surface", e);
                // Don't assume this is due to out of memory, it could be
                // something else, and if it is something else then we could
                // kill stuff (or ourself) for no reason.
                mLayoutRequested = true;    // ask wm for a new surface next time.
                return;
            }

            try {
                if (!dirty.isEmpty() || mIsAnimating) {
                    long startTime = 0L;

                    if (DEBUG_ORIENTATION || DEBUG_DRAW) {
                        Log.v(TAG, "Surface " + surface + " drawing to bitmap w="
                                + canvas.getWidth() + ", h=" + canvas.getHeight());
                        //canvas.drawARGB(255, 255, 0, 0);
                    }

                    if (ViewDebug.DEBUG_PROFILE_DRAWING) {
                        startTime = SystemClock.elapsedRealtime();
                    }

                    // If this bitmap's format includes an alpha channel, we
                    // need to clear it before drawing so that the child will
                    // properly re-composite its drawing on a transparent
                    // background. This automatically respects the clip/dirty region
                    // or
                    // If we are applying an offset, we need to clear the area
                    // where the offset doesn't appear to avoid having garbage
                    // left in the blank areas.
                    if (!canvas.isOpaque() || yoff != 0) {
                        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                    }

                    dirty.setEmpty();
                    mIsAnimating = false;
                    mAttachInfo.mDrawingTime = SystemClock.uptimeMillis();
                    mView.mPrivateFlags |= View.DRAWN;

                    if (DEBUG_DRAW) {
                        Context cxt = mView.getContext();
                        Log.i(TAG, "Drawing: package:" + cxt.getPackageName() +
                                ", metrics=" + cxt.getResources().getDisplayMetrics() +
                                ", compatibilityInfo=" + cxt.getResources().getCompatibilityInfo());
                    }
                    try {
                        canvas.translate(0, -yoff);
                        if (mTranslator != null) {
                            mTranslator.translateCanvas(canvas);
                        }
                        canvas.setScreenDensity(scalingRequired
                                ? DisplayMetrics.DENSITY_DEVICE : 0);
                        mView.draw(canvas);
                    } finally {
                        mAttachInfo.mIgnoreDirtyState = false;
                    }

                    if (Config.DEBUG && ViewDebug.consistencyCheckEnabled) {
                        mView.dispatchConsistencyCheck(ViewDebug.CONSISTENCY_DRAWING);
                    }

                    if (SHOW_FPS || ViewDebug.DEBUG_SHOW_FPS) {
                        int now = (int)SystemClock.elapsedRealtime();
                        if (sDrawTime != 0) {
                            nativeShowFPS(canvas, now - sDrawTime);
                        }
                        sDrawTime = now;
                    }

                    if (ViewDebug.DEBUG_PROFILE_DRAWING) {
                        EventLog.writeEvent(60000, SystemClock.elapsedRealtime() - startTime);
                    }
                }

            } finally {
                surface.unlockCanvasAndPost(canvas);
            }
        }

        if (LOCAL_LOGV) {
            Log.v(TAG, "Surface " + surface + " unlockCanvasAndPost");
        }

        if (animating) {
            mFullRedrawNeeded = true;
            scheduleTraversals();
        }
    }

    boolean scrollToRectOrFocus(Rect rectangle, boolean immediate) {
        final View.AttachInfo attachInfo = mAttachInfo;
        final Rect ci = attachInfo.mContentInsets;
        final Rect vi = attachInfo.mVisibleInsets;
        int scrollY = 0;
        boolean handled = false;

        if (vi.left > ci.left || vi.top > ci.top
                || vi.right > ci.right || vi.bottom > ci.bottom) {
            // We'll assume that we aren't going to change the scroll
            // offset, since we want to avoid that unless it is actually
            // going to make the focus visible...  otherwise we scroll
            // all over the place.
            scrollY = mScrollY;
            // We can be called for two different situations: during a draw,
            // to update the scroll position if the focus has changed (in which
            // case 'rectangle' is null), or in response to a
            // requestChildRectangleOnScreen() call (in which case 'rectangle'
            // is non-null and we just want to scroll to whatever that
            // rectangle is).
            View focus = mRealFocusedView;

            // When in touch mode, focus points to the previously focused view,
            // which may have been removed from the view hierarchy. The following
            // line checks whether the view is still in our hierarchy.
            if (focus == null || focus.mAttachInfo != mAttachInfo) {
                mRealFocusedView = null;
                return false;
            }

            if (focus != mLastScrolledFocus) {
                // If the focus has changed, then ignore any requests to scroll
                // to a rectangle; first we want to make sure the entire focus
                // view is visible.
                rectangle = null;
            }
            if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Eval scroll: focus=" + focus
                    + " rectangle=" + rectangle + " ci=" + ci
                    + " vi=" + vi);
            if (focus == mLastScrolledFocus && !mScrollMayChange
                    && rectangle == null) {
                // Optimization: if the focus hasn't changed since last
                // time, and no layout has happened, then just leave things
                // as they are.
                if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Keeping scroll y="
                        + mScrollY + " vi=" + vi.toShortString());
            } else if (focus != null) {
                // We need to determine if the currently focused view is
                // within the visible part of the window and, if not, apply
                // a pan so it can be seen.
                mLastScrolledFocus = focus;
                mScrollMayChange = false;
                if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Need to scroll?");
                // Try to find the rectangle from the focus view.
                if (focus.getGlobalVisibleRect(mVisRect, null)) {
                    if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Root w="
                            + mView.getWidth() + " h=" + mView.getHeight()
                            + " ci=" + ci.toShortString()
                            + " vi=" + vi.toShortString());
                    if (rectangle == null) {
                        focus.getFocusedRect(mTempRect);
                        if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Focus " + focus
                                + ": focusRect=" + mTempRect.toShortString());
                        if (mView instanceof ViewGroup) {
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focus, mTempRect);
                        }
                        if (DEBUG_INPUT_RESIZE) Log.v(TAG,
                                "Focus in window: focusRect="
                                + mTempRect.toShortString()
                                + " visRect=" + mVisRect.toShortString());
                    } else {
                        mTempRect.set(rectangle);
                        if (DEBUG_INPUT_RESIZE) Log.v(TAG,
                                "Request scroll to rect: "
                                + mTempRect.toShortString()
                                + " visRect=" + mVisRect.toShortString());
                    }
                    if (mTempRect.intersect(mVisRect)) {
                        if (DEBUG_INPUT_RESIZE) Log.v(TAG,
                                "Focus window visible rect: "
                                + mTempRect.toShortString());
                        if (mTempRect.height() >
                                (mView.getHeight()-vi.top-vi.bottom)) {
                            // If the focus simply is not going to fit, then
                            // best is probably just to leave things as-is.
                            if (DEBUG_INPUT_RESIZE) Log.v(TAG,
                                    "Too tall; leaving scrollY=" + scrollY);
                        } else if ((mTempRect.top-scrollY) < vi.top) {
                            scrollY -= vi.top - (mTempRect.top-scrollY);
                            if (DEBUG_INPUT_RESIZE) Log.v(TAG,
                                    "Top covered; scrollY=" + scrollY);
                        } else if ((mTempRect.bottom-scrollY)
                                > (mView.getHeight()-vi.bottom)) {
                            scrollY += (mTempRect.bottom-scrollY)
                                    - (mView.getHeight()-vi.bottom);
                            if (DEBUG_INPUT_RESIZE) Log.v(TAG,
                                    "Bottom covered; scrollY=" + scrollY);
                        }
                        handled = true;
                    }
                }
            }
        }

        if (scrollY != mScrollY) {
            if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Pan scroll changed: old="
                    + mScrollY + " , new=" + scrollY);
            if (!immediate && mResizeBitmap == null) {
                if (mScroller == null) {
                    mScroller = new Scroller(mView.getContext());
                }
                mScroller.startScroll(0, mScrollY, 0, scrollY-mScrollY);
            } else if (mScroller != null) {
                mScroller.abortAnimation();
            }
            mScrollY = scrollY;
        }

        return handled;
    }

    public void requestChildFocus(View child, View focused) {
        checkThread();
        if (mFocusedView != focused) {
            mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(mFocusedView, focused);
            scheduleTraversals();
        }
        mFocusedView = mRealFocusedView = focused;
        if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Request child focus: focus now "
                + mFocusedView);
    }

    public void clearChildFocus(View child) {
        checkThread();

        View oldFocus = mFocusedView;

        if (DEBUG_INPUT_RESIZE) Log.v(TAG, "Clearing child focus");
        mFocusedView = mRealFocusedView = null;
        if (mView != null && !mView.hasFocus()) {
            // If a view gets the focus, the listener will be invoked from requestChildFocus()
            if (!mView.requestFocus(View.FOCUS_FORWARD)) {
                mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(oldFocus, null);
            }
        } else if (oldFocus != null) {
            mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(oldFocus, null);
        }
    }


    public void focusableViewAvailable(View v) {
        checkThread();

        if (mView != null && !mView.hasFocus()) {
            v.requestFocus();
        } else {
            // the one case where will transfer focus away from the current one
            // is if the current view is a view group that prefers to give focus
            // to its children first AND the view is a descendant of it.
            mFocusedView = mView.findFocus();
            boolean descendantsHaveDibsOnFocus =
                    (mFocusedView instanceof ViewGroup) &&
                        (((ViewGroup) mFocusedView).getDescendantFocusability() ==
                                ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (descendantsHaveDibsOnFocus && isViewDescendantOf(v, mFocusedView)) {
                // If a view gets the focus, the listener will be invoked from requestChildFocus()
                v.requestFocus();
            }
        }
    }

    public void recomputeViewAttributes(View child) {
        checkThread();
        if (mView == child) {
            mAttachInfo.mRecomputeGlobalAttributes = true;
            if (!mWillDrawSoon) {
                scheduleTraversals();
            }
        }
    }

    void dispatchDetachedFromWindow() {
        if (mView != null && mView.mAttachInfo != null) {
            mView.dispatchDetachedFromWindow();
        }

        mView = null;
        mAttachInfo.mRootView = null;
        mAttachInfo.mSurface = null;

        destroyHardwareRenderer();

        mSurface.release();

        if (mInputChannel != null) {
            if (mInputQueueCallback != null) {
                mInputQueueCallback.onInputQueueDestroyed(mInputQueue);
                mInputQueueCallback = null;
            } else {
                InputQueue.unregisterInputChannel(mInputChannel);
            }
        }
        
        try {
            sWindowSession.remove(mWindow);
        } catch (RemoteException e) {
        }
        
        // Dispose the input channel after removing the window so the Window Manager
        // doesn't interpret the input channel being closed as an abnormal termination.
        if (mInputChannel != null) {
            mInputChannel.dispose();
            mInputChannel = null;
        }
    }

    void updateConfiguration(Configuration config, boolean force) {
        if (DEBUG_CONFIGURATION) Log.v(TAG,
                "Applying new config to window "
                + mWindowAttributes.getTitle()
                + ": " + config);

        CompatibilityInfo ci = mCompatibilityInfo.getIfNeeded();
        if (ci != null) {
            config = new Configuration(config);
            ci.applyToConfiguration(config);
        }

        synchronized (sConfigCallbacks) {
            for (int i=sConfigCallbacks.size()-1; i>=0; i--) {
                sConfigCallbacks.get(i).onConfigurationChanged(config);
            }
        }
        if (mView != null) {
            // At this point the resources have been updated to
            // have the most recent config, whatever that is.  Use
            // the on in them which may be newer.
            if (mView != null) {
                config = mView.getResources().getConfiguration();
            }
            if (force || mLastConfiguration.diff(config) != 0) {
                mLastConfiguration.setTo(config);
                mView.dispatchConfigurationChanged(config);
            }
        }
    }
    
    /**
     * Return true if child is an ancestor of parent, (or equal to the parent).
     */
    private static boolean isViewDescendantOf(View child, View parent) {
        if (child == parent) {
            return true;
        }

        final ViewParent theParent = child.getParent();
        return (theParent instanceof ViewGroup) && isViewDescendantOf((View) theParent, parent);
    }

    private static void forceLayout(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                forceLayout(group.getChildAt(i));
            }
        }
    }

    public final static int DO_TRAVERSAL = 1000;
    public final static int DIE = 1001;
    public final static int RESIZED = 1002;
    public final static int RESIZED_REPORT = 1003;
    public final static int WINDOW_FOCUS_CHANGED = 1004;
    public final static int DISPATCH_KEY = 1005;
    public final static int DISPATCH_POINTER = 1006;
    public final static int DISPATCH_TRACKBALL = 1007;
    public final static int DISPATCH_APP_VISIBILITY = 1008;
    public final static int DISPATCH_GET_NEW_SURFACE = 1009;
    public final static int FINISHED_EVENT = 1010;
    public final static int DISPATCH_KEY_FROM_IME = 1011;
    public final static int FINISH_INPUT_CONNECTION = 1012;
    public final static int CHECK_FOCUS = 1013;
    public final static int CLOSE_SYSTEM_DIALOGS = 1014;
    public final static int DISPATCH_DRAG_EVENT = 1015;
    public final static int DISPATCH_DRAG_LOCATION_EVENT = 1016;
    public final static int DISPATCH_SYSTEM_UI_VISIBILITY = 1017;
    public final static int DISPATCH_GENERIC_MOTION = 1018;
    public final static int UPDATE_CONFIGURATION = 1019;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case View.AttachInfo.INVALIDATE_MSG:
            ((View) msg.obj).invalidate();
            break;
        case View.AttachInfo.INVALIDATE_RECT_MSG:
            final View.AttachInfo.InvalidateInfo info = (View.AttachInfo.InvalidateInfo) msg.obj;
            info.target.invalidate(info.left, info.top, info.right, info.bottom);
            info.release();
            break;
        case DO_TRAVERSAL:
            if (mProfile) {
                Debug.startMethodTracing("ViewRoot");
            }

            performTraversals();

            if (mProfile) {
                Debug.stopMethodTracing();
                mProfile = false;
            }
            break;
        case FINISHED_EVENT:
            handleFinishedEvent(msg.arg1, msg.arg2 != 0);
            break;
        case DISPATCH_KEY:
            deliverKeyEvent((KeyEvent)msg.obj, msg.arg1 != 0);
            break;
        case DISPATCH_POINTER:
            deliverPointerEvent((MotionEvent) msg.obj, msg.arg1 != 0);
            break;
        case DISPATCH_TRACKBALL:
            deliverTrackballEvent((MotionEvent) msg.obj, msg.arg1 != 0);
            break;
        case DISPATCH_GENERIC_MOTION:
            deliverGenericMotionEvent((MotionEvent) msg.obj, msg.arg1 != 0);
            break;
        case DISPATCH_APP_VISIBILITY:
            handleAppVisibility(msg.arg1 != 0);
            break;
        case DISPATCH_GET_NEW_SURFACE:
            handleGetNewSurface();
            break;
        case RESIZED:
            ResizedInfo ri = (ResizedInfo)msg.obj;

            if (mWinFrame.width() == msg.arg1 && mWinFrame.height() == msg.arg2
                    && mPendingContentInsets.equals(ri.coveredInsets)
                    && mPendingVisibleInsets.equals(ri.visibleInsets)
                    && ((ResizedInfo)msg.obj).newConfig == null) {
                break;
            }
            // fall through...
        case RESIZED_REPORT:
            if (mAdded) {
                Configuration config = ((ResizedInfo)msg.obj).newConfig;
                if (config != null) {
                    updateConfiguration(config, false);
                }
                mWinFrame.left = 0;
                mWinFrame.right = msg.arg1;
                mWinFrame.top = 0;
                mWinFrame.bottom = msg.arg2;
                mPendingContentInsets.set(((ResizedInfo)msg.obj).coveredInsets);
                mPendingVisibleInsets.set(((ResizedInfo)msg.obj).visibleInsets);
                if (msg.what == RESIZED_REPORT) {
                    mReportNextDraw = true;
                }

                if (mView != null) {
                    forceLayout(mView);
                }
                requestLayout();
            }
            break;
        case WINDOW_FOCUS_CHANGED: {
            if (mAdded) {
                boolean hasWindowFocus = msg.arg1 != 0;
                mAttachInfo.mHasWindowFocus = hasWindowFocus;
                if (hasWindowFocus) {
                    boolean inTouchMode = msg.arg2 != 0;
                    ensureTouchModeLocally(inTouchMode);

                    if (mAttachInfo.mHardwareRenderer != null &&
                            mSurface != null && mSurface.isValid()) {
                        mFullRedrawNeeded = true;
                        try {
                            mAttachInfo.mHardwareRenderer.initializeIfNeeded(mWidth, mHeight,
                                    mAttachInfo, mHolder);
                        } catch (Surface.OutOfResourcesException e) {
                            Log.e(TAG, "OutOfResourcesException locking surface", e);
                            try {
                                if (!sWindowSession.outOfMemory(mWindow)) {
                                    Slog.w(TAG, "No processes killed for memory; killing self");
                                    Process.killProcess(Process.myPid());
                                }
                            } catch (RemoteException ex) {
                            }
                            // Retry in a bit.
                            sendMessageDelayed(obtainMessage(msg.what, msg.arg1, msg.arg2), 500);
                            return;
                        }
                    }
                }

                mLastWasImTarget = WindowManager.LayoutParams
                        .mayUseInputMethod(mWindowAttributes.flags);

                InputMethodManager imm = InputMethodManager.peekInstance();
                if (mView != null) {
                    if (hasWindowFocus && imm != null && mLastWasImTarget) {
                        imm.startGettingWindowFocus(mView);
                    }
                    mAttachInfo.mKeyDispatchState.reset();
                    mView.dispatchWindowFocusChanged(hasWindowFocus);
                }

                // Note: must be done after the focus change callbacks,
                // so all of the view state is set up correctly.
                if (hasWindowFocus) {
                    if (imm != null && mLastWasImTarget) {
                        imm.onWindowFocus(mView, mView.findFocus(),
                                mWindowAttributes.softInputMode,
                                !mHasHadWindowFocus, mWindowAttributes.flags);
                    }
                    // Clear the forward bit.  We can just do this directly, since
                    // the window manager doesn't care about it.
                    mWindowAttributes.softInputMode &=
                            ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                    ((WindowManager.LayoutParams)mView.getLayoutParams())
                            .softInputMode &=
                                ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                    mHasHadWindowFocus = true;
                }

                if (hasWindowFocus && mView != null) {
                    sendAccessibilityEvents();
                }
            }
        } break;
        case DIE:
            doDie();
            break;
        case DISPATCH_KEY_FROM_IME: {
            if (LOCAL_LOGV) Log.v(
                TAG, "Dispatching key "
                + msg.obj + " from IME to " + mView);
            KeyEvent event = (KeyEvent)msg.obj;
            if ((event.getFlags()&KeyEvent.FLAG_FROM_SYSTEM) != 0) {
                // The IME is trying to say this event is from the
                // system!  Bad bad bad!
                event = KeyEvent.changeFlags(event, event.getFlags() & ~KeyEvent.FLAG_FROM_SYSTEM);
            }
            deliverKeyEventPostIme((KeyEvent)msg.obj, false);
        } break;
        case FINISH_INPUT_CONNECTION: {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.reportFinishInputConnection((InputConnection)msg.obj);
            }
        } break;
        case CHECK_FOCUS: {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.checkFocus();
            }
        } break;
        case CLOSE_SYSTEM_DIALOGS: {
            if (mView != null) {
                mView.onCloseSystemDialogs((String)msg.obj);
            }
        } break;
        case DISPATCH_DRAG_EVENT:
        case DISPATCH_DRAG_LOCATION_EVENT: {
            DragEvent event = (DragEvent)msg.obj;
            event.mLocalState = mLocalDragState;    // only present when this app called startDrag()
            handleDragEvent(event);
        } break;
        case DISPATCH_SYSTEM_UI_VISIBILITY: {
            handleDispatchSystemUiVisibilityChanged(msg.arg1);
        } break;
        case UPDATE_CONFIGURATION: {
            Configuration config = (Configuration)msg.obj;
            if (config.isOtherSeqNewer(mLastConfiguration)) {
                config = mLastConfiguration;
            }
            updateConfiguration(config, false);
        } break;
        }
    }
    
    private void startInputEvent(InputQueue.FinishedCallback finishedCallback) {
        if (mFinishedCallback != null) {
            Slog.w(TAG, "Received a new input event from the input queue but there is "
                    + "already an unfinished input event in progress.");
        }

        mFinishedCallback = finishedCallback;
    }

    private void finishInputEvent(boolean handled) {
        if (LOCAL_LOGV) Log.v(TAG, "Telling window manager input event is finished");

        if (mFinishedCallback != null) {
            mFinishedCallback.finished(handled);
            mFinishedCallback = null;
        } else {
            Slog.w(TAG, "Attempted to tell the input queue that the current input event "
                    + "is finished but there is no input event actually in progress.");
        }
    }
    
    /**
     * Something in the current window tells us we need to change the touch mode.  For
     * example, we are not in touch mode, and the user touches the screen.
     *
     * If the touch mode has changed, tell the window manager, and handle it locally.
     *
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    boolean ensureTouchMode(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchMode(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);
        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        // tell the window manager
        try {
            sWindowSession.setInTouchMode(inTouchMode);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // handle the change
        return ensureTouchModeLocally(inTouchMode);
    }

    /**
     * Ensure that the touch mode for this window is set, and if it is changing,
     * take the appropriate action.
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    private boolean ensureTouchModeLocally(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchModeLocally(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);

        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        mAttachInfo.mInTouchMode = inTouchMode;
        mAttachInfo.mTreeObserver.dispatchOnTouchModeChanged(inTouchMode);

        return (inTouchMode) ? enterTouchMode() : leaveTouchMode();
    }

    private boolean enterTouchMode() {
        if (mView != null) {
            if (mView.hasFocus()) {
                // note: not relying on mFocusedView here because this could
                // be when the window is first being added, and mFocused isn't
                // set yet.
                final View focused = mView.findFocus();
                if (focused != null && !focused.isFocusableInTouchMode()) {

                    final ViewGroup ancestorToTakeFocus =
                            findAncestorToTakeFocusInTouchMode(focused);
                    if (ancestorToTakeFocus != null) {
                        // there is an ancestor that wants focus after its descendants that
                        // is focusable in touch mode.. give it focus
                        return ancestorToTakeFocus.requestFocus();
                    } else {
                        // nothing appropriate to have focus in touch mode, clear it out
                        mView.unFocus();
                        mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(focused, null);
                        mFocusedView = null;
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Find an ancestor of focused that wants focus after its descendants and is
     * focusable in touch mode.
     * @param focused The currently focused view.
     * @return An appropriate view, or null if no such view exists.
     */
    private ViewGroup findAncestorToTakeFocusInTouchMode(View focused) {
        ViewParent parent = focused.getParent();
        while (parent instanceof ViewGroup) {
            final ViewGroup vgParent = (ViewGroup) parent;
            if (vgParent.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                    && vgParent.isFocusableInTouchMode()) {
                return vgParent;
            }
            if (vgParent.isRootNamespace()) {
                return null;
            } else {
                parent = vgParent.getParent();
            }
        }
        return null;
    }

    private boolean leaveTouchMode() {
        if (mView != null) {
            if (mView.hasFocus()) {
                // i learned the hard way to not trust mFocusedView :)
                mFocusedView = mView.findFocus();
                if (!(mFocusedView instanceof ViewGroup)) {
                    // some view has focus, let it keep it
                    return false;
                } else if (((ViewGroup)mFocusedView).getDescendantFocusability() !=
                        ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                    // some view group has focus, and doesn't prefer its children
                    // over itself for focus, so let them keep it.
                    return false;
                }
            }

            // find the best view to give focus to in this brave new non-touch-mode
            // world
            final View focused = focusSearch(null, View.FOCUS_DOWN);
            if (focused != null) {
                return focused.requestFocus(View.FOCUS_DOWN);
            }
        }
        return false;
    }

    private void deliverPointerEvent(MotionEvent event, boolean sendDone) {
        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishMotionEvent(event, sendDone, false);
            return;
        }

        // Translate the pointer event for compatibility, if needed.
        if (mTranslator != null) {
            mTranslator.translateEventInScreenToAppWindow(event);
        }

        // Enter touch mode on the down.
        boolean isDown = event.getAction() == MotionEvent.ACTION_DOWN;
        if (isDown) {
            ensureTouchMode(true);
        }
        if(Config.LOGV) {
            captureMotionLog("captureDispatchPointer", event);
        }

        // Offset the scroll position.
        if (mCurScrollY != 0) {
            event.offsetLocation(0, mCurScrollY);
        }
        if (MEASURE_LATENCY) {
            lt.sample("A Dispatching PointerEvents", System.nanoTime() - event.getEventTimeNano());
        }

        // Remember the touch position for possible drag-initiation.
        mLastTouchPoint.x = event.getRawX();
        mLastTouchPoint.y = event.getRawY();

        // Dispatch touch to view hierarchy.
        boolean handled = mView.dispatchPointerEvent(event);
        if (MEASURE_LATENCY) {
            lt.sample("B Dispatched PointerEvents ", System.nanoTime() - event.getEventTimeNano());
        }
        if (handled) {
            finishMotionEvent(event, sendDone, true);
            return;
        }

        // Apply edge slop and try again, if appropriate.
        final int edgeFlags = event.getEdgeFlags();
        if (edgeFlags != 0 && mView instanceof ViewGroup) {
            final int edgeSlop = mViewConfiguration.getScaledEdgeSlop();
            int direction = View.FOCUS_UP;
            int x = (int)event.getX();
            int y = (int)event.getY();
            final int[] deltas = new int[2];

            if ((edgeFlags & MotionEvent.EDGE_TOP) != 0) {
                direction = View.FOCUS_DOWN;
                if ((edgeFlags & MotionEvent.EDGE_LEFT) != 0) {
                    deltas[0] = edgeSlop;
                    x += edgeSlop;
                } else if ((edgeFlags & MotionEvent.EDGE_RIGHT) != 0) {
                    deltas[0] = -edgeSlop;
                    x -= edgeSlop;
                }
            } else if ((edgeFlags & MotionEvent.EDGE_BOTTOM) != 0) {
                direction = View.FOCUS_UP;
                if ((edgeFlags & MotionEvent.EDGE_LEFT) != 0) {
                    deltas[0] = edgeSlop;
                    x += edgeSlop;
                } else if ((edgeFlags & MotionEvent.EDGE_RIGHT) != 0) {
                    deltas[0] = -edgeSlop;
                    x -= edgeSlop;
                }
            } else if ((edgeFlags & MotionEvent.EDGE_LEFT) != 0) {
                direction = View.FOCUS_RIGHT;
            } else if ((edgeFlags & MotionEvent.EDGE_RIGHT) != 0) {
                direction = View.FOCUS_LEFT;
            }

            View nearest = FocusFinder.getInstance().findNearestTouchable(
                    ((ViewGroup) mView), x, y, direction, deltas);
            if (nearest != null) {
                event.offsetLocation(deltas[0], deltas[1]);
                event.setEdgeFlags(0);
                if (mView.dispatchPointerEvent(event)) {
                    finishMotionEvent(event, sendDone, true);
                    return;
                }
            }
        }

        // Pointer event was unhandled.
        finishMotionEvent(event, sendDone, false);
    }

    private void finishMotionEvent(MotionEvent event, boolean sendDone, boolean handled) {
        event.recycle();
        if (sendDone) {
            finishInputEvent(handled);
        }
        if (LOCAL_LOGV || WATCH_POINTER) {
            if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                Log.i(TAG, "Done dispatching!");
            }
        }
    }

    private void deliverTrackballEvent(MotionEvent event, boolean sendDone) {
        if (DEBUG_TRACKBALL) Log.v(TAG, "Motion event:" + event);

        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishMotionEvent(event, sendDone, false);
            return;
        }

        // Deliver the trackball event to the view.
        if (mView.dispatchTrackballEvent(event)) {
            // If we reach this, we delivered a trackball event to mView and
            // mView consumed it. Because we will not translate the trackball
            // event into a key event, touch mode will not exit, so we exit
            // touch mode here.
            ensureTouchMode(false);

            finishMotionEvent(event, sendDone, true);
            mLastTrackballTime = Integer.MIN_VALUE;
            return;
        }

        // Translate the trackball event into DPAD keys and try to deliver those.
        final TrackballAxis x = mTrackballAxisX;
        final TrackballAxis y = mTrackballAxisY;

        long curTime = SystemClock.uptimeMillis();
        if ((mLastTrackballTime + MAX_TRACKBALL_DELAY) < curTime) {
            // It has been too long since the last movement,
            // so restart at the beginning.
            x.reset(0);
            y.reset(0);
            mLastTrackballTime = curTime;
        }

        final int action = event.getAction();
        final int metaState = event.getMetaState();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                x.reset(2);
                y.reset(2);
                deliverKeyEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD), false);
                break;
            case MotionEvent.ACTION_UP:
                x.reset(2);
                y.reset(2);
                deliverKeyEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD), false);
                break;
        }

        if (DEBUG_TRACKBALL) Log.v(TAG, "TB X=" + x.position + " step="
                + x.step + " dir=" + x.dir + " acc=" + x.acceleration
                + " move=" + event.getX()
                + " / Y=" + y.position + " step="
                + y.step + " dir=" + y.dir + " acc=" + y.acceleration
                + " move=" + event.getY());
        final float xOff = x.collect(event.getX(), event.getEventTime(), "X");
        final float yOff = y.collect(event.getY(), event.getEventTime(), "Y");

        // Generate DPAD events based on the trackball movement.
        // We pick the axis that has moved the most as the direction of
        // the DPAD.  When we generate DPAD events for one axis, then the
        // other axis is reset -- we don't want to perform DPAD jumps due
        // to slight movements in the trackball when making major movements
        // along the other axis.
        int keycode = 0;
        int movement = 0;
        float accel = 1;
        if (xOff > yOff) {
            movement = x.generate((2/event.getXPrecision()));
            if (movement != 0) {
                keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                        : KeyEvent.KEYCODE_DPAD_LEFT;
                accel = x.acceleration;
                y.reset(2);
            }
        } else if (yOff > 0) {
            movement = y.generate((2/event.getYPrecision()));
            if (movement != 0) {
                keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                        : KeyEvent.KEYCODE_DPAD_UP;
                accel = y.acceleration;
                x.reset(2);
            }
        }

        if (keycode != 0) {
            if (movement < 0) movement = -movement;
            int accelMovement = (int)(movement * accel);
            if (DEBUG_TRACKBALL) Log.v(TAG, "Move: movement=" + movement
                    + " accelMovement=" + accelMovement
                    + " accel=" + accel);
            if (accelMovement > movement) {
                if (DEBUG_TRACKBALL) Log.v("foo", "Delivering fake DPAD: "
                        + keycode);
                movement--;
                int repeatCount = accelMovement - movement;
                deliverKeyEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_MULTIPLE, keycode, repeatCount, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD), false);
            }
            while (movement > 0) {
                if (DEBUG_TRACKBALL) Log.v("foo", "Delivering fake DPAD: "
                        + keycode);
                movement--;
                curTime = SystemClock.uptimeMillis();
                deliverKeyEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_DOWN, keycode, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD), false);
                deliverKeyEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_UP, keycode, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD), false);
                }
            mLastTrackballTime = curTime;
        }

        // Unfortunately we can't tell whether the application consumed the keys, so
        // we always consider the trackball event handled.
        finishMotionEvent(event, sendDone, true);
    }

    private void deliverGenericMotionEvent(MotionEvent event, boolean sendDone) {
        final int source = event.getSource();
        final boolean isJoystick = (source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0;

        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            if (isJoystick) {
                updateJoystickDirection(event, false);
            }
            finishMotionEvent(event, sendDone, false);
            return;
        }

        // Deliver the event to the view.
        if (mView.dispatchGenericMotionEvent(event)) {
            if (isJoystick) {
                updateJoystickDirection(event, false);
            }
            finishMotionEvent(event, sendDone, true);
            return;
        }

        if (isJoystick) {
            // Translate the joystick event into DPAD keys and try to deliver those.
            updateJoystickDirection(event, true);
            finishMotionEvent(event, sendDone, true);
        } else {
            finishMotionEvent(event, sendDone, false);
        }
    }

    private void updateJoystickDirection(MotionEvent event, boolean synthesizeNewKeys) {
        final long time = event.getEventTime();
        final int metaState = event.getMetaState();
        final int deviceId = event.getDeviceId();
        final int source = event.getSource();

        int xDirection = joystickAxisValueToDirection(event.getAxisValue(MotionEvent.AXIS_HAT_X));
        if (xDirection == 0) {
            xDirection = joystickAxisValueToDirection(event.getX());
        }

        int yDirection = joystickAxisValueToDirection(event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (yDirection == 0) {
            yDirection = joystickAxisValueToDirection(event.getY());
        }

        if (xDirection != mLastJoystickXDirection) {
            if (mLastJoystickXKeyCode != 0) {
                deliverKeyEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_UP, mLastJoystickXKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source), false);
                mLastJoystickXKeyCode = 0;
            }

            mLastJoystickXDirection = xDirection;

            if (xDirection != 0 && synthesizeNewKeys) {
                mLastJoystickXKeyCode = xDirection > 0
                        ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                deliverKeyEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_DOWN, mLastJoystickXKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source), false);
            }
        }

        if (yDirection != mLastJoystickYDirection) {
            if (mLastJoystickYKeyCode != 0) {
                deliverKeyEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_UP, mLastJoystickYKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source), false);
                mLastJoystickYKeyCode = 0;
            }

            mLastJoystickYDirection = yDirection;

            if (yDirection != 0 && synthesizeNewKeys) {
                mLastJoystickYKeyCode = yDirection > 0
                        ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
                deliverKeyEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_DOWN, mLastJoystickYKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source), false);
            }
        }
    }

    private static int joystickAxisValueToDirection(float value) {
        if (value >= 0.5f) {
            return 1;
        } else if (value <= -0.5f) {
            return -1;
        } else {
            return 0;
        }
    }

    /**
     * Returns true if the key is used for keyboard navigation.
     * @param keyEvent The key event.
     * @return True if the key is used for keyboard navigation.
     */
    private static boolean isNavigationKey(KeyEvent keyEvent) {
        switch (keyEvent.getKeyCode()) {
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_PAGE_UP:
        case KeyEvent.KEYCODE_PAGE_DOWN:
        case KeyEvent.KEYCODE_MOVE_HOME:
        case KeyEvent.KEYCODE_MOVE_END:
        case KeyEvent.KEYCODE_TAB:
        case KeyEvent.KEYCODE_SPACE:
        case KeyEvent.KEYCODE_ENTER:
            return true;
        }
        return false;
    }

    /**
     * Returns true if the key is used for typing.
     * @param keyEvent The key event.
     * @return True if the key is used for typing.
     */
    private static boolean isTypingKey(KeyEvent keyEvent) {
        return keyEvent.getUnicodeChar() > 0;
    }

    /**
     * See if the key event means we should leave touch mode (and leave touch mode if so).
     * @param event The key event.
     * @return Whether this key event should be consumed (meaning the act of
     *   leaving touch mode alone is considered the event).
     */
    private boolean checkForLeavingTouchModeAndConsume(KeyEvent event) {
        // Only relevant in touch mode.
        if (!mAttachInfo.mInTouchMode) {
            return false;
        }

        // Only consider leaving touch mode on DOWN or MULTIPLE actions, never on UP.
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) {
            return false;
        }

        // Don't leave touch mode if the IME told us not to.
        if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0) {
            return false;
        }

        // If the key can be used for keyboard navigation then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // When a new focused view is selected, we consume the navigation key because
        // navigation doesn't make much sense unless a view already has focus so
        // the key's purpose is to set focus.
        if (isNavigationKey(event)) {
            return ensureTouchMode(false);
        }

        // If the key can be used for typing then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // Always allow the view to process the typing key.
        if (isTypingKey(event)) {
            ensureTouchMode(false);
            return false;
        }

        return false;
    }

    /**
     * log motion events
     */
    private static void captureMotionLog(String subTag, MotionEvent ev) {
        //check dynamic switch
        if (ev == null ||
                SystemProperties.getInt(ViewDebug.SYSTEM_PROPERTY_CAPTURE_EVENT, 0) == 0) {
            return;
        }

        StringBuilder sb = new StringBuilder(subTag + ": ");
        sb.append(ev.getDownTime()).append(',');
        sb.append(ev.getEventTime()).append(',');
        sb.append(ev.getAction()).append(',');
        sb.append(ev.getX()).append(',');
        sb.append(ev.getY()).append(',');
        sb.append(ev.getPressure()).append(',');
        sb.append(ev.getSize()).append(',');
        sb.append(ev.getMetaState()).append(',');
        sb.append(ev.getXPrecision()).append(',');
        sb.append(ev.getYPrecision()).append(',');
        sb.append(ev.getDeviceId()).append(',');
        sb.append(ev.getEdgeFlags());
        Log.d(TAG, sb.toString());
    }
    /**
     * log motion events
     */
    private static void captureKeyLog(String subTag, KeyEvent ev) {
        //check dynamic switch
        if (ev == null ||
                SystemProperties.getInt(ViewDebug.SYSTEM_PROPERTY_CAPTURE_EVENT, 0) == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder(subTag + ": ");
        sb.append(ev.getDownTime()).append(',');
        sb.append(ev.getEventTime()).append(',');
        sb.append(ev.getAction()).append(',');
        sb.append(ev.getKeyCode()).append(',');
        sb.append(ev.getRepeatCount()).append(',');
        sb.append(ev.getMetaState()).append(',');
        sb.append(ev.getDeviceId()).append(',');
        sb.append(ev.getScanCode());
        Log.d(TAG, sb.toString());
    }

    int enqueuePendingEvent(Object event, boolean sendDone) {
        int seq = mPendingEventSeq+1;
        if (seq < 0) seq = 0;
        mPendingEventSeq = seq;
        mPendingEvents.put(seq, event);
        return sendDone ? seq : -seq;
    }

    Object retrievePendingEvent(int seq) {
        if (seq < 0) seq = -seq;
        Object event = mPendingEvents.get(seq);
        if (event != null) {
            mPendingEvents.remove(seq);
        }
        return event;
    }

    private void deliverKeyEvent(KeyEvent event, boolean sendDone) {
        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishKeyEvent(event, sendDone, false);
            return;
        }

        if (LOCAL_LOGV) Log.v(TAG, "Dispatching key " + event + " to " + mView);

        // Perform predispatching before the IME.
        if (mView.dispatchKeyEventPreIme(event)) {
            finishKeyEvent(event, sendDone, true);
            return;
        }

        // Dispatch to the IME before propagating down the view hierarchy.
        // The IME will eventually call back into handleFinishedEvent.
        if (mLastWasImTarget) {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                int seq = enqueuePendingEvent(event, sendDone);
                if (DEBUG_IMF) Log.v(TAG, "Sending key event to IME: seq="
                        + seq + " event=" + event);
                imm.dispatchKeyEvent(mView.getContext(), seq, event, mInputMethodCallback);
                return;
            }
        }

        // Not dispatching to IME, continue with post IME actions.
        deliverKeyEventPostIme(event, sendDone);
    }

    private void handleFinishedEvent(int seq, boolean handled) {
        final KeyEvent event = (KeyEvent)retrievePendingEvent(seq);
        if (DEBUG_IMF) Log.v(TAG, "IME finished event: seq=" + seq
                + " handled=" + handled + " event=" + event);
        if (event != null) {
            final boolean sendDone = seq >= 0;
            if (handled) {
                finishKeyEvent(event, sendDone, true);
            } else {
                deliverKeyEventPostIme(event, sendDone);
            }
        }
    }

    private void deliverKeyEventPostIme(KeyEvent event, boolean sendDone) {
        // If the view went away, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishKeyEvent(event, sendDone, false);
            return;
        }

        // If the key's purpose is to exit touch mode then we consume it and consider it handled.
        if (checkForLeavingTouchModeAndConsume(event)) {
            finishKeyEvent(event, sendDone, true);
            return;
        }

        if (Config.LOGV) {
            captureKeyLog("captureDispatchKeyEvent", event);
        }

        // Make sure the fallback event policy sees all keys that will be delivered to the
        // view hierarchy.
        mFallbackEventHandler.preDispatchKeyEvent(event);

        // Deliver the key to the view hierarchy.
        if (mView.dispatchKeyEvent(event)) {
            finishKeyEvent(event, sendDone, true);
            return;
        }

        // If the Control modifier is held, try to interpret the key as a shortcut.
        if (event.getAction() == KeyEvent.ACTION_UP
                && event.isCtrlPressed()
                && !KeyEvent.isModifierKey(event.getKeyCode())) {
            if (mView.dispatchKeyShortcutEvent(event)) {
                finishKeyEvent(event, sendDone, true);
                return;
            }
        }

        // Apply the fallback event policy.
        if (mFallbackEventHandler.dispatchKeyEvent(event)) {
            finishKeyEvent(event, sendDone, true);
            return;
        }

        // Handle automatic focus changes.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int direction = 0;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (event.hasNoModifiers()) {
                    direction = View.FOCUS_LEFT;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.hasNoModifiers()) {
                    direction = View.FOCUS_RIGHT;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.hasNoModifiers()) {
                    direction = View.FOCUS_UP;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.hasNoModifiers()) {
                    direction = View.FOCUS_DOWN;
                }
                break;
            case KeyEvent.KEYCODE_TAB:
                if (event.hasNoModifiers()) {
                    direction = View.FOCUS_FORWARD;
                } else if (event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                    direction = View.FOCUS_BACKWARD;
                }
                break;
            }

            if (direction != 0) {
                View focused = mView != null ? mView.findFocus() : null;
                if (focused != null) {
                    View v = focused.focusSearch(direction);
                    if (v != null && v != focused) {
                        // do the math the get the interesting rect
                        // of previous focused into the coord system of
                        // newly focused view
                        focused.getFocusedRect(mTempRect);
                        if (mView instanceof ViewGroup) {
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focused, mTempRect);
                            ((ViewGroup) mView).offsetRectIntoDescendantCoords(
                                    v, mTempRect);
                        }
                        if (v.requestFocus(direction, mTempRect)) {
                            playSoundEffect(
                                    SoundEffectConstants.getContantForFocusDirection(direction));
                            finishKeyEvent(event, sendDone, true);
                            return;
                        }
                    }

                    // Give the focused view a last chance to handle the dpad key.
                    if (mView.dispatchUnhandledMove(focused, direction)) {
                        finishKeyEvent(event, sendDone, true);
                        return;
                    }
                }
            }
        }

        // Key was unhandled.
        finishKeyEvent(event, sendDone, false);
    }

    private void finishKeyEvent(KeyEvent event, boolean sendDone, boolean handled) {
        if (sendDone) {
            finishInputEvent(handled);
        }
    }

    /* drag/drop */
    void setLocalDragState(Object obj) {
        mLocalDragState = obj;
    }

    private void handleDragEvent(DragEvent event) {
        // From the root, only drag start/end/location are dispatched.  entered/exited
        // are determined and dispatched by the viewgroup hierarchy, who then report
        // that back here for ultimate reporting back to the framework.
        if (mView != null && mAdded) {
            final int what = event.mAction;

            if (what == DragEvent.ACTION_DRAG_EXITED) {
                // A direct EXITED event means that the window manager knows we've just crossed
                // a window boundary, so the current drag target within this one must have
                // just been exited.  Send it the usual notifications and then we're done
                // for now.
                mView.dispatchDragEvent(event);
            } else {
                // Cache the drag description when the operation starts, then fill it in
                // on subsequent calls as a convenience
                if (what == DragEvent.ACTION_DRAG_STARTED) {
                    mCurrentDragView = null;    // Start the current-recipient tracking
                    mDragDescription = event.mClipDescription;
                } else {
                    event.mClipDescription = mDragDescription;
                }

                // For events with a [screen] location, translate into window coordinates
                if ((what == DragEvent.ACTION_DRAG_LOCATION) || (what == DragEvent.ACTION_DROP)) {
                    mDragPoint.set(event.mX, event.mY);
                    if (mTranslator != null) {
                        mTranslator.translatePointInScreenToAppWindow(mDragPoint);
                    }

                    if (mCurScrollY != 0) {
                        mDragPoint.offset(0, mCurScrollY);
                    }

                    event.mX = mDragPoint.x;
                    event.mY = mDragPoint.y;
                }

                // Remember who the current drag target is pre-dispatch
                final View prevDragView = mCurrentDragView;

                // Now dispatch the drag/drop event
                boolean result = mView.dispatchDragEvent(event);

                // If we changed apparent drag target, tell the OS about it
                if (prevDragView != mCurrentDragView) {
                    try {
                        if (prevDragView != null) {
                            sWindowSession.dragRecipientExited(mWindow);
                        }
                        if (mCurrentDragView != null) {
                            sWindowSession.dragRecipientEntered(mWindow);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to note drag target change");
                    }
                }

                // Report the drop result when we're done
                if (what == DragEvent.ACTION_DROP) {
                    mDragDescription = null;
                    try {
                        Log.i(TAG, "Reporting drop result: " + result);
                        sWindowSession.reportDropResult(mWindow, result);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to report drop result");
                    }
                }

                // When the drag operation ends, release any local state object
                // that may have been in use
                if (what == DragEvent.ACTION_DRAG_ENDED) {
                    setLocalDragState(null);
                }
            }
        }
        event.recycle();
    }

    public void handleDispatchSystemUiVisibilityChanged(int visibility) {
        if (mView == null) return;
        if (mAttachInfo != null) {
            mAttachInfo.mSystemUiVisibility = visibility;
        }
        mView.dispatchSystemUiVisibilityChanged(visibility);
    }

    public void getLastTouchPoint(Point outLocation) {
        outLocation.x = (int) mLastTouchPoint.x;
        outLocation.y = (int) mLastTouchPoint.y;
    }

    public void setDragFocus(View newDragTarget) {
        if (mCurrentDragView != newDragTarget) {
            mCurrentDragView = newDragTarget;
        }
    }

    private AudioManager getAudioManager() {
        if (mView == null) {
            throw new IllegalStateException("getAudioManager called when there is no mView");
        }
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mView.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    private int relayoutWindow(WindowManager.LayoutParams params, int viewVisibility,
            boolean insetsPending) throws RemoteException {

        float appScale = mAttachInfo.mApplicationScale;
        boolean restore = false;
        if (params != null && mTranslator != null) {
            restore = true;
            params.backup();
            mTranslator.translateWindowLayout(params);
        }
        if (params != null) {
            if (DBG) Log.d(TAG, "WindowLayout in layoutWindow:" + params);
        }
        mPendingConfiguration.seq = 0;
        //Log.d(TAG, ">>>>>> CALLING relayout");
        int relayoutResult = sWindowSession.relayout(
                mWindow, params,
                (int) (mView.getMeasuredWidth() * appScale + 0.5f),
                (int) (mView.getMeasuredHeight() * appScale + 0.5f),
                viewVisibility, insetsPending, mWinFrame,
                mPendingContentInsets, mPendingVisibleInsets,
                mPendingConfiguration, mSurface);
        //Log.d(TAG, "<<<<<< BACK FROM relayout");
        if (restore) {
            params.restore();
        }
        
        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWinFrame(mWinFrame);
            mTranslator.translateRectInScreenToAppWindow(mPendingContentInsets);
            mTranslator.translateRectInScreenToAppWindow(mPendingVisibleInsets);
        }
        return relayoutResult;
    }

    /**
     * {@inheritDoc}
     */
    public void playSoundEffect(int effectId) {
        checkThread();

        try {
            final AudioManager audioManager = getAudioManager();

            switch (effectId) {
                case SoundEffectConstants.CLICK:
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
                    return;
                case SoundEffectConstants.NAVIGATION_DOWN:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_DOWN);
                    return;
                case SoundEffectConstants.NAVIGATION_LEFT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_LEFT);
                    return;
                case SoundEffectConstants.NAVIGATION_RIGHT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
                    return;
                case SoundEffectConstants.NAVIGATION_UP:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP);
                    return;
                default:
                    throw new IllegalArgumentException("unknown effect id " + effectId +
                            " not defined in " + SoundEffectConstants.class.getCanonicalName());
            }
        } catch (IllegalStateException e) {
            // Exception thrown by getAudioManager() when mView is null
            Log.e(TAG, "FATAL EXCEPTION when attempting to play sound effect: " + e);
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean performHapticFeedback(int effectId, boolean always) {
        try {
            return sWindowSession.performHapticFeedback(mWindow, effectId, always);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public View focusSearch(View focused, int direction) {
        checkThread();
        if (!(mView instanceof ViewGroup)) {
            return null;
        }
        return FocusFinder.getInstance().findNextFocus((ViewGroup) mView, focused, direction);
    }

    public void debug() {
        mView.debug();
    }

    public void die(boolean immediate) {
        if (immediate) {
            doDie();
        } else {
            sendEmptyMessage(DIE);
        }
    }

    void doDie() {
        checkThread();
        if (LOCAL_LOGV) Log.v(TAG, "DIE in " + this + " of " + mSurface);
        synchronized (this) {
            if (mAdded && !mFirst) {
                destroyHardwareRenderer();

                int viewVisibility = mView.getVisibility();
                boolean viewVisibilityChanged = mViewVisibility != viewVisibility;
                if (mWindowAttributesChanged || viewVisibilityChanged) {
                    // If layout params have been changed, first give them
                    // to the window manager to make sure it has the correct
                    // animation info.
                    try {
                        if ((relayoutWindow(mWindowAttributes, viewVisibility, false)
                                & WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                            sWindowSession.finishDrawing(mWindow);
                        }
                    } catch (RemoteException e) {
                    }
                }

                mSurface.release();
            }
            if (mAdded) {
                mAdded = false;
                dispatchDetachedFromWindow();
            }
        }
    }

    public void requestUpdateConfiguration(Configuration config) {
        Message msg = obtainMessage(UPDATE_CONFIGURATION, config);
        sendMessage(msg);
    }

    private void destroyHardwareRenderer() {
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.destroy(true);
            mAttachInfo.mHardwareRenderer = null;
            mAttachInfo.mHardwareAccelerated = false;
        }
    }

    public void dispatchFinishedEvent(int seq, boolean handled) {
        Message msg = obtainMessage(FINISHED_EVENT);
        msg.arg1 = seq;
        msg.arg2 = handled ? 1 : 0;
        sendMessage(msg);
    }

    public void dispatchResized(int w, int h, Rect coveredInsets,
            Rect visibleInsets, boolean reportDraw, Configuration newConfig) {
        if (DEBUG_LAYOUT) Log.v(TAG, "Resizing " + this + ": w=" + w
                + " h=" + h + " coveredInsets=" + coveredInsets.toShortString()
                + " visibleInsets=" + visibleInsets.toShortString()
                + " reportDraw=" + reportDraw);
        Message msg = obtainMessage(reportDraw ? RESIZED_REPORT :RESIZED);
        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWindow(coveredInsets);
            mTranslator.translateRectInScreenToAppWindow(visibleInsets);
            w *= mTranslator.applicationInvertedScale;
            h *= mTranslator.applicationInvertedScale;
        }
        msg.arg1 = w;
        msg.arg2 = h;
        ResizedInfo ri = new ResizedInfo();
        ri.coveredInsets = new Rect(coveredInsets);
        ri.visibleInsets = new Rect(visibleInsets);
        ri.newConfig = newConfig;
        msg.obj = ri;
        sendMessage(msg);
    }
    
    private InputQueue.FinishedCallback mFinishedCallback;
    
    private final InputHandler mInputHandler = new InputHandler() {
        public void handleKey(KeyEvent event, InputQueue.FinishedCallback finishedCallback) {
            startInputEvent(finishedCallback);
            dispatchKey(event, true);
        }

        public void handleMotion(MotionEvent event, InputQueue.FinishedCallback finishedCallback) {
            startInputEvent(finishedCallback);
            dispatchMotion(event, true);
        }
    };

    public void dispatchKey(KeyEvent event) {
        dispatchKey(event, false);
    }

    private void dispatchKey(KeyEvent event, boolean sendDone) {
        //noinspection ConstantConditions
        if (false && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_CAMERA) {
                if (DBG) Log.d("keydisp", "===================================================");
                if (DBG) Log.d("keydisp", "Focused view Hierarchy is:");

                debug();

                if (DBG) Log.d("keydisp", "===================================================");
            }
        }

        Message msg = obtainMessage(DISPATCH_KEY);
        msg.obj = event;
        msg.arg1 = sendDone ? 1 : 0;

        if (LOCAL_LOGV) Log.v(
            TAG, "sending key " + event + " to " + mView);

        sendMessageAtTime(msg, event.getEventTime());
    }
    
    public void dispatchMotion(MotionEvent event) {
        dispatchMotion(event, false);
    }

    private void dispatchMotion(MotionEvent event, boolean sendDone) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            dispatchPointer(event, sendDone);
        } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
            dispatchTrackball(event, sendDone);
        } else {
            dispatchGenericMotion(event, sendDone);
        }
    }

    public void dispatchPointer(MotionEvent event) {
        dispatchPointer(event, false);
    }

    private void dispatchPointer(MotionEvent event, boolean sendDone) {
        Message msg = obtainMessage(DISPATCH_POINTER);
        msg.obj = event;
        msg.arg1 = sendDone ? 1 : 0;
        sendMessageAtTime(msg, event.getEventTime());
    }

    public void dispatchTrackball(MotionEvent event) {
        dispatchTrackball(event, false);
    }

    private void dispatchTrackball(MotionEvent event, boolean sendDone) {
        Message msg = obtainMessage(DISPATCH_TRACKBALL);
        msg.obj = event;
        msg.arg1 = sendDone ? 1 : 0;
        sendMessageAtTime(msg, event.getEventTime());
    }

    private void dispatchGenericMotion(MotionEvent event, boolean sendDone) {
        Message msg = obtainMessage(DISPATCH_GENERIC_MOTION);
        msg.obj = event;
        msg.arg1 = sendDone ? 1 : 0;
        sendMessageAtTime(msg, event.getEventTime());
    }

    public void dispatchAppVisibility(boolean visible) {
        Message msg = obtainMessage(DISPATCH_APP_VISIBILITY);
        msg.arg1 = visible ? 1 : 0;
        sendMessage(msg);
    }

    public void dispatchGetNewSurface() {
        Message msg = obtainMessage(DISPATCH_GET_NEW_SURFACE);
        sendMessage(msg);
    }

    public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
        Message msg = Message.obtain();
        msg.what = WINDOW_FOCUS_CHANGED;
        msg.arg1 = hasFocus ? 1 : 0;
        msg.arg2 = inTouchMode ? 1 : 0;
        sendMessage(msg);
    }

    public void dispatchCloseSystemDialogs(String reason) {
        Message msg = Message.obtain();
        msg.what = CLOSE_SYSTEM_DIALOGS;
        msg.obj = reason;
        sendMessage(msg);
    }

    public void dispatchDragEvent(DragEvent event) {
        final int what;
        if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
            what = DISPATCH_DRAG_LOCATION_EVENT;
            removeMessages(what);
        } else {
            what = DISPATCH_DRAG_EVENT;
        }
        Message msg = obtainMessage(what, event);
        sendMessage(msg);
    }

    public void dispatchSystemUiVisibilityChanged(int visibility) {
        sendMessage(obtainMessage(DISPATCH_SYSTEM_UI_VISIBILITY, visibility, 0));
    }

    /**
     * The window is getting focus so if there is anything focused/selected
     * send an {@link AccessibilityEvent} to announce that.
     */
    private void sendAccessibilityEvents() {
        if (!AccessibilityManager.getInstance(mView.getContext()).isEnabled()) {
            return;
        }
        mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        View focusedView = mView.findFocus();
        if (focusedView != null && focusedView != mView) {
            focusedView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        }
    }

    public boolean showContextMenuForChild(View originalView) {
        return false;
    }

    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
        return null;
    }

    public void createContextMenu(ContextMenu menu) {
    }

    public void childDrawableStateChanged(View child) {
    }

    void checkThread() {
        if (mThread != Thread.currentThread()) {
            throw new CalledFromWrongThreadException(
                    "Only the original thread that created a view hierarchy can touch its views.");
        }
    }

    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // ViewRoot never intercepts touch event, so this can be a no-op
    }

    public boolean requestChildRectangleOnScreen(View child, Rect rectangle,
            boolean immediate) {
        return scrollToRectOrFocus(rectangle, immediate);
    }

    class TakenSurfaceHolder extends BaseSurfaceHolder {
        @Override
        public boolean onAllowLockCanvas() {
            return mDrawingAllowed;
        }

        @Override
        public void onRelayoutContainer() {
            // Not currently interesting -- from changing between fixed and layout size.
        }

        public void setFormat(int format) {
            ((RootViewSurfaceTaker)mView).setSurfaceFormat(format);
        }

        public void setType(int type) {
            ((RootViewSurfaceTaker)mView).setSurfaceType(type);
        }
        
        @Override
        public void onUpdateSurface() {
            // We take care of format and type changes on our own.
            throw new IllegalStateException("Shouldn't be here");
        }

        public boolean isCreating() {
            return mIsCreating;
        }

        @Override
        public void setFixedSize(int width, int height) {
            throw new UnsupportedOperationException(
                    "Currently only support sizing from layout");
        }
        
        public void setKeepScreenOn(boolean screenOn) {
            ((RootViewSurfaceTaker)mView).setSurfaceKeepScreenOn(screenOn);
        }
    }
    
    static class InputMethodCallback extends IInputMethodCallback.Stub {
        private WeakReference<ViewRoot> mViewRoot;

        public InputMethodCallback(ViewRoot viewRoot) {
            mViewRoot = new WeakReference<ViewRoot>(viewRoot);
        }

        public void finishedEvent(int seq, boolean handled) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchFinishedEvent(seq, handled);
            }
        }

        public void sessionCreated(IInputMethodSession session) throws RemoteException {
            // Stub -- not for use in the client.
        }
    }

    static class W extends IWindow.Stub {
        private final WeakReference<ViewRoot> mViewRoot;

        W(ViewRoot viewRoot) {
            mViewRoot = new WeakReference<ViewRoot>(viewRoot);
        }

        public void resized(int w, int h, Rect coveredInsets, Rect visibleInsets,
                boolean reportDraw, Configuration newConfig) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchResized(w, h, coveredInsets, visibleInsets, reportDraw, newConfig);
            }
        }

        public void dispatchAppVisibility(boolean visible) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchAppVisibility(visible);
            }
        }

        public void dispatchGetNewSurface() {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchGetNewSurface();
            }
        }

        public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.windowFocusChanged(hasFocus, inTouchMode);
            }
        }

        private static int checkCallingPermission(String permission) {
            if (!Process.supportsProcesses()) {
                return PackageManager.PERMISSION_GRANTED;
            }

            try {
                return ActivityManagerNative.getDefault().checkPermission(
                        permission, Binder.getCallingPid(), Binder.getCallingUid());
            } catch (RemoteException e) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                final View view = viewRoot.mView;
                if (view != null) {
                    if (checkCallingPermission(Manifest.permission.DUMP) !=
                            PackageManager.PERMISSION_GRANTED) {
                        throw new SecurityException("Insufficient permissions to invoke"
                                + " executeCommand() from pid=" + Binder.getCallingPid()
                                + ", uid=" + Binder.getCallingUid());
                    }

                    OutputStream clientStream = null;
                    try {
                        clientStream = new ParcelFileDescriptor.AutoCloseOutputStream(out);
                        ViewDebug.dispatchCommand(view, command, parameters, clientStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (clientStream != null) {
                            try {
                                clientStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
        
        public void closeSystemDialogs(String reason) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchCloseSystemDialogs(reason);
            }
        }
        
        public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
                boolean sync) {
            if (sync) {
                try {
                    sWindowSession.wallpaperOffsetsComplete(asBinder());
                } catch (RemoteException e) {
                }
            }
        }
        
        public void dispatchWallpaperCommand(String action, int x, int y,
                int z, Bundle extras, boolean sync) {
            if (sync) {
                try {
                    sWindowSession.wallpaperCommandComplete(asBinder(), null);
                } catch (RemoteException e) {
                }
            }
        }

        /* Drag/drop */
        public void dispatchDragEvent(DragEvent event) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchDragEvent(event);
            }
        }

        @Override
        public void dispatchSystemUiVisibilityChanged(int visibility) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchSystemUiVisibilityChanged(visibility);
            }
        }
    }

    /**
     * Maintains state information for a single trackball axis, generating
     * discrete (DPAD) movements based on raw trackball motion.
     */
    static final class TrackballAxis {
        /**
         * The maximum amount of acceleration we will apply.
         */
        static final float MAX_ACCELERATION = 20;

        /**
         * The maximum amount of time (in milliseconds) between events in order
         * for us to consider the user to be doing fast trackball movements,
         * and thus apply an acceleration.
         */
        static final long FAST_MOVE_TIME = 150;

        /**
         * Scaling factor to the time (in milliseconds) between events to how
         * much to multiple/divide the current acceleration.  When movement
         * is < FAST_MOVE_TIME this multiplies the acceleration; when >
         * FAST_MOVE_TIME it divides it.
         */
        static final float ACCEL_MOVE_SCALING_FACTOR = (1.0f/40);

        float position;
        float absPosition;
        float acceleration = 1;
        long lastMoveTime = 0;
        int step;
        int dir;
        int nonAccelMovement;

        void reset(int _step) {
            position = 0;
            acceleration = 1;
            lastMoveTime = 0;
            step = _step;
            dir = 0;
        }

        /**
         * Add trackball movement into the state.  If the direction of movement
         * has been reversed, the state is reset before adding the
         * movement (so that you don't have to compensate for any previously
         * collected movement before see the result of the movement in the
         * new direction).
         *
         * @return Returns the absolute value of the amount of movement
         * collected so far.
         */
        float collect(float off, long time, String axis) {
            long normTime;
            if (off > 0) {
                normTime = (long)(off * FAST_MOVE_TIME);
                if (dir < 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to positive!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = 1;
            } else if (off < 0) {
                normTime = (long)((-off) * FAST_MOVE_TIME);
                if (dir > 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to negative!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = -1;
            } else {
                normTime = 0;
            }

            // The number of milliseconds between each movement that is
            // considered "normal" and will not result in any acceleration
            // or deceleration, scaled by the offset we have here.
            if (normTime > 0) {
                long delta = time - lastMoveTime;
                lastMoveTime = time;
                float acc = acceleration;
                if (delta < normTime) {
                    // The user is scrolling rapidly, so increase acceleration.
                    float scale = (normTime-delta) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc *= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " accelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc < MAX_ACCELERATION ? acc : MAX_ACCELERATION;
                } else {
                    // The user is scrolling slowly, so decrease acceleration.
                    float scale = (delta-normTime) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc /= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " deccelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc > 1 ? acc : 1;
                }
            }
            position += off;
            return (absPosition = Math.abs(position));
        }

        /**
         * Generate the number of discrete movement events appropriate for
         * the currently collected trackball movement.
         *
         * @param precision The minimum movement required to generate the
         * first discrete movement.
         *
         * @return Returns the number of discrete movements, either positive
         * or negative, or 0 if there is not enough trackball movement yet
         * for a discrete movement.
         */
        int generate(float precision) {
            int movement = 0;
            nonAccelMovement = 0;
            do {
                final int dir = position >= 0 ? 1 : -1;
                switch (step) {
                    // If we are going to execute the first step, then we want
                    // to do this as soon as possible instead of waiting for
                    // a full movement, in order to make things look responsive.
                    case 0:
                        if (absPosition < precision) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        step = 1;
                        break;
                    // If we have generated the first movement, then we need
                    // to wait for the second complete trackball motion before
                    // generating the second discrete movement.
                    case 1:
                        if (absPosition < 2) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        position += dir > 0 ? -2 : 2;
                        absPosition = Math.abs(position);
                        step = 2;
                        break;
                    // After the first two, we generate discrete movements
                    // consistently with the trackball, applying an acceleration
                    // if the trackball is moving quickly.  This is a simple
                    // acceleration on top of what we already compute based
                    // on how quickly the wheel is being turned, to apply
                    // a longer increasing acceleration to continuous movement
                    // in one direction.
                    default:
                        if (absPosition < 1) {
                            return movement;
                        }
                        movement += dir;
                        position += dir >= 0 ? -1 : 1;
                        absPosition = Math.abs(position);
                        float acc = acceleration;
                        acc *= 1.1f;
                        acceleration = acc < MAX_ACCELERATION ? acc : acceleration;
                        break;
                }
            } while (true);
        }
    }

    public static final class CalledFromWrongThreadException extends AndroidRuntimeException {
        public CalledFromWrongThreadException(String msg) {
            super(msg);
        }
    }

    private SurfaceHolder mHolder = new SurfaceHolder() {
        // we only need a SurfaceHolder for opengl. it would be nice
        // to implement everything else though, especially the callback
        // support (opengl doesn't make use of it right now, but eventually
        // will).
        public Surface getSurface() {
            return mSurface;
        }

        public boolean isCreating() {
            return false;
        }

        public void addCallback(Callback callback) {
        }

        public void removeCallback(Callback callback) {
        }

        public void setFixedSize(int width, int height) {
        }

        public void setSizeFromLayout() {
        }

        public void setFormat(int format) {
        }

        public void setType(int type) {
        }

        public void setKeepScreenOn(boolean screenOn) {
        }

        public Canvas lockCanvas() {
            return null;
        }

        public Canvas lockCanvas(Rect dirty) {
            return null;
        }

        public void unlockCanvasAndPost(Canvas canvas) {
        }
        public Rect getSurfaceFrame() {
            return null;
        }
    };

    static RunQueue getRunQueue() {
        RunQueue rq = sRunQueues.get();
        if (rq != null) {
            return rq;
        }
        rq = new RunQueue();
        sRunQueues.set(rq);
        return rq;
    }

    /**
     * @hide
     */
    static final class RunQueue {
        private final ArrayList<HandlerAction> mActions = new ArrayList<HandlerAction>();

        void post(Runnable action) {
            postDelayed(action, 0);
        }

        void postDelayed(Runnable action, long delayMillis) {
            HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;
            handlerAction.delay = delayMillis;

            synchronized (mActions) {
                mActions.add(handlerAction);
            }
        }

        void removeCallbacks(Runnable action) {
            final HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;

            synchronized (mActions) {
                final ArrayList<HandlerAction> actions = mActions;

                while (actions.remove(handlerAction)) {
                    // Keep going
                }
            }
        }

        void executeActions(Handler handler) {
            synchronized (mActions) {
                final ArrayList<HandlerAction> actions = mActions;
                final int count = actions.size();

                for (int i = 0; i < count; i++) {
                    final HandlerAction handlerAction = actions.get(i);
                    handler.postDelayed(handlerAction.action, handlerAction.delay);
                }

                actions.clear();
            }
        }

        private static class HandlerAction {
            Runnable action;
            long delay;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                HandlerAction that = (HandlerAction) o;
                return !(action != null ? !action.equals(that.action) : that.action != null);

            }

            @Override
            public int hashCode() {
                int result = action != null ? action.hashCode() : 0;
                result = 31 * result + (int) (delay ^ (delay >>> 32));
                return result;
            }
        }
    }

    private static native void nativeShowFPS(Canvas canvas, int durationMillis);
}
