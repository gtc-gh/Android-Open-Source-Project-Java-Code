/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

/**
 * Interface for rendering a ViewRoot using hardware acceleration.
 * 
 * @hide
 */
public abstract class HardwareRenderer {
    static final String LOG_TAG = "HardwareRenderer";

    /**
     * Turn on to only refresh the parts of the screen that need updating.
     * When turned on the property defined by {@link #RENDER_DIRTY_REGIONS_PROPERTY}
     * must also have the value "true". 
     */
    public static final boolean RENDER_DIRTY_REGIONS = true;

    /**
     * System property used to enable or disable dirty regions invalidation.
     * This property is only queried if {@link #RENDER_DIRTY_REGIONS} is true.
     * The default value of this property is assumed to be true.
     * 
     * Possible values:
     * "true", to enable partial invalidates
     * "false", to disable partial invalidates
     */
    static final String RENDER_DIRTY_REGIONS_PROPERTY = "hwui.render_dirty_regions";

    /**
     * Turn on to draw dirty regions every other frame.
     */
    private static final boolean DEBUG_DIRTY_REGION = false;
    
    /**
     * A process can set this flag to false to prevent the use of hardware
     * rendering.
     * 
     * @hide
     */
    public static boolean sRendererDisabled = false;

    private boolean mEnabled;
    private boolean mRequested = true;

    /**
     * Invoke this method to disable hardware rendering in the current process.
     * 
     * @hide
     */
    public static void disable() {
        sRendererDisabled = true;
    }

    /**
     * Indicates whether hardware acceleration is available under any form for
     * the view hierarchy.
     * 
     * @return True if the view hierarchy can potentially be hardware accelerated,
     *         false otherwise
     */
    public static boolean isAvailable() {
        return GLES20Canvas.isAvailable();
    }

    /**
     * Destroys the hardware rendering context.
     * 
     * @param full If true, destroys all associated resources.
     */
    abstract void destroy(boolean full);

    /**
     * Initializes the hardware renderer for the specified surface.
     * 
     * @param holder The holder for the surface to hardware accelerate.
     * 
     * @return True if the initialization was successful, false otherwise.
     */
    abstract boolean initialize(SurfaceHolder holder) throws Surface.OutOfResourcesException;
    
    /**
     * Updates the hardware renderer for the specified surface.
     * 
     * @param holder The holder for the surface to hardware accelerate.
     */
    abstract void updateSurface(SurfaceHolder holder) throws Surface.OutOfResourcesException;

    /**
     * Setup the hardware renderer for drawing. This is called for every
     * frame to draw.
     * 
     * @param width Width of the drawing surface.
     * @param height Height of the drawing surface.
     */
    abstract void setup(int width, int height);

    /**
     * Interface used to receive callbacks whenever a view is drawn by
     * a hardware renderer instance.
     */
    interface HardwareDrawCallbacks {
        /**
         * Invoked before a view is drawn by a hardware renderer.
         * 
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePreDraw(Canvas canvas);

        /**
         * Invoked after a view is drawn by a hardware renderer.
         * 
         * @param canvas The Canvas used to render the view.
         */
        void onHardwarePostDraw(Canvas canvas);
    }

    /**
     * Draws the specified view.
     *
     * @param view The view to draw.
     * @param attachInfo AttachInfo tied to the specified view.
     * @param callbacks Callbacks invoked when drawing happens.
     * @param dirty The dirty rectangle to update, can be null.
     */
    abstract void draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
            Rect dirty);

    /**
     * Creates a new display list that can be used to record batches of
     * drawing operations.
     * 
     * @return A new display list.
     */
    abstract DisplayList createDisplayList(View v);

    /**
     * Creates a new hardware layer.
     * 
     * @param width The minimum width of the layer
     * @param height The minimum height of the layer
     * @param isOpaque Whether the layer should be opaque or not
     * 
     * @return A hardware layer
     */
    abstract HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque);
    
    /**
     * Initializes the hardware renderer for the specified surface and setup the
     * renderer for drawing, if needed. This is invoked when the ViewRoot has
     * potentially lost the hardware renderer. The hardware renderer should be
     * reinitialized and setup when the render {@link #isRequested()} and
     * {@link #isEnabled()}.
     * 
     * @param width The width of the drawing surface.
     * @param height The height of the drawing surface.
     * @param attachInfo The 
     * @param holder
     */
    void initializeIfNeeded(int width, int height, View.AttachInfo attachInfo,
            SurfaceHolder holder) throws Surface.OutOfResourcesException {
        if (isRequested()) {
            // We lost the gl context, so recreate it.
            if (!isEnabled()) {
                if (initialize(holder)) {
                    setup(width, height);
                }
            }
        }        
    }

    /**
     * Creates a hardware renderer using OpenGL.
     * 
     * @param glVersion The version of OpenGL to use (1 for OpenGL 1, 11 for OpenGL 1.1, etc.)
     * @param translucent True if the surface is translucent, false otherwise
     * 
     * @return A hardware renderer backed by OpenGL.
     */
    static HardwareRenderer createGlRenderer(int glVersion, boolean translucent) {
        switch (glVersion) {
            case 2:
                return Gl20Renderer.create(translucent);
        }
        throw new IllegalArgumentException("Unknown GL version: " + glVersion);
    }

    /**
     * Indicates whether hardware acceleration is currently enabled.
     * 
     * @return True if hardware acceleration is in use, false otherwise.
     */
    boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Indicates whether hardware acceleration is currently enabled.
     * 
     * @param enabled True if the hardware renderer is in use, false otherwise.
     */
    void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Indicates whether hardware acceleration is currently request but not
     * necessarily enabled yet.
     * 
     * @return True if requested, false otherwise.
     */
    boolean isRequested() {
        return mRequested;
    }

    /**
     * Indicates whether hardware acceleration is currently requested but not
     * necessarily enabled yet.
     * 
     * @return True to request hardware acceleration, false otherwise.
     */
    void setRequested(boolean requested) {
        mRequested = requested;
    }

    @SuppressWarnings({"deprecation"})
    static abstract class GlRenderer extends HardwareRenderer {
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        private static final int EGL_SURFACE_TYPE = 0x3033;
        private static final int EGL_SWAP_BEHAVIOR_PRESERVED_BIT = 0x0400;

        private static final int SURFACE_STATE_ERROR = 0;
        private static final int SURFACE_STATE_SUCCESS = 1;
        private static final int SURFACE_STATE_UPDATED = 2;
        
        static EGLContext sEglContext;
        static EGL10 sEgl;
        static EGLDisplay sEglDisplay;
        static EGLConfig sEglConfig;

        private static Thread sEglThread;

        EGLSurface mEglSurface;
        
        GL mGl;
        HardwareCanvas mCanvas;
        int mFrameCount;
        Paint mDebugPaint;

        boolean mDirtyRegions;

        final int mGlVersion;
        final boolean mTranslucent;

        private boolean mDestroyed;
        
        private final Rect mRedrawClip = new Rect();

        GlRenderer(int glVersion, boolean translucent) {
            mGlVersion = glVersion;
            mTranslucent = translucent;
            final String dirtyProperty = System.getProperty(RENDER_DIRTY_REGIONS_PROPERTY, "true");
            //noinspection PointlessBooleanExpression,ConstantConditions
            mDirtyRegions = RENDER_DIRTY_REGIONS && "true".equalsIgnoreCase(dirtyProperty);
        }

        /**
         * Return a string for the EGL error code, or the hex representation
         * if the error is unknown.
         * 
         * @param error The EGL error to convert into a String.
         * 
         * @return An error string correponding to the EGL error code.
         */
        static String getEGLErrorString(int error) {
            switch (error) {
                case EGL10.EGL_SUCCESS:
                    return "EGL_SUCCESS";
                case EGL10.EGL_NOT_INITIALIZED:
                    return "EGL_NOT_INITIALIZED";
                case EGL10.EGL_BAD_ACCESS:
                    return "EGL_BAD_ACCESS";
                case EGL10.EGL_BAD_ALLOC:
                    return "EGL_BAD_ALLOC";
                case EGL10.EGL_BAD_ATTRIBUTE:
                    return "EGL_BAD_ATTRIBUTE";
                case EGL10.EGL_BAD_CONFIG:
                    return "EGL_BAD_CONFIG";
                case EGL10.EGL_BAD_CONTEXT:
                    return "EGL_BAD_CONTEXT";
                case EGL10.EGL_BAD_CURRENT_SURFACE:
                    return "EGL_BAD_CURRENT_SURFACE";
                case EGL10.EGL_BAD_DISPLAY:
                    return "EGL_BAD_DISPLAY";
                case EGL10.EGL_BAD_MATCH:
                    return "EGL_BAD_MATCH";
                case EGL10.EGL_BAD_NATIVE_PIXMAP:
                    return "EGL_BAD_NATIVE_PIXMAP";
                case EGL10.EGL_BAD_NATIVE_WINDOW:
                    return "EGL_BAD_NATIVE_WINDOW";
                case EGL10.EGL_BAD_PARAMETER:
                    return "EGL_BAD_PARAMETER";
                case EGL10.EGL_BAD_SURFACE:
                    return "EGL_BAD_SURFACE";
                case EGL11.EGL_CONTEXT_LOST:
                    return "EGL_CONTEXT_LOST";
                default:
                    return "0x" + Integer.toHexString(error);
            }
        }

        /**
         * Checks for OpenGL errors. If an error has occured, {@link #destroy(boolean)}
         * is invoked and the requested flag is turned off. The error code is
         * also logged as a warning.
         */
        void checkEglErrors() {
            if (isEnabled()) {
                int error = sEgl.eglGetError();
                if (error != EGL10.EGL_SUCCESS) {
                    // something bad has happened revert to
                    // normal rendering.
                    fallback(error != EGL11.EGL_CONTEXT_LOST);
                    Log.w(LOG_TAG, "EGL error: " + getEGLErrorString(error));
                }
            }
        }

        private void fallback(boolean fallback) {
            destroy(true);
            if (fallback) {
                // we'll try again if it was context lost
                setRequested(false);
                Log.w(LOG_TAG, "Mountain View, we've had a problem here. " 
                        + "Switching back to software rendering.");
            }
        }

        @Override
        boolean initialize(SurfaceHolder holder) throws Surface.OutOfResourcesException {
            if (isRequested() && !isEnabled()) {
                initializeEgl();
                mGl = createEglSurface(holder);
                mDestroyed = false;

                if (mGl != null) {
                    int err = sEgl.eglGetError();
                    if (err != EGL10.EGL_SUCCESS) {
                        destroy(true);
                        setRequested(false);
                    } else {
                        if (mCanvas == null) {
                            mCanvas = createCanvas();
                        }
                        if (mCanvas != null) {
                            setEnabled(true);
                        } else {
                            Log.w(LOG_TAG, "Hardware accelerated Canvas could not be created");
                        }
                    }

                    return mCanvas != null;
                }
            }
            return false;
        }
        
        @Override
        void updateSurface(SurfaceHolder holder) throws Surface.OutOfResourcesException {
            if (isRequested() && isEnabled()) {
                createEglSurface(holder);
            }
        }

        abstract GLES20Canvas createCanvas();

        void initializeEgl() {
            if (sEglContext != null) return;

            sEglThread = Thread.currentThread();
            sEgl = (EGL10) EGLContext.getEGL();
            
            // Get to the default display.
            sEglDisplay = sEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            
            if (sEglDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay failed "
                        + getEGLErrorString(sEgl.eglGetError()));
            }
            
            // We can now initialize EGL for that display
            int[] version = new int[2];
            if (!sEgl.eglInitialize(sEglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed " +
                        getEGLErrorString(sEgl.eglGetError()));
            }

            sEglConfig = getConfigChooser(mGlVersion).chooseConfig(sEgl, sEglDisplay);
            if (sEglConfig == null) {
                // We tried to use EGL_SWAP_BEHAVIOR_PRESERVED_BIT, try again without
                if (mDirtyRegions) {
                    mDirtyRegions = false;

                    sEglConfig = getConfigChooser(mGlVersion).chooseConfig(sEgl, sEglDisplay);
                    if (sEglConfig == null) {
                        throw new RuntimeException("eglConfig not initialized");
                    }
                } else {
                    throw new RuntimeException("eglConfig not initialized");
                }
            }
            
            /*
            * Create an EGL context. We want to do this as rarely as we can, because an
            * EGL context is a somewhat heavy object.
            */
            sEglContext = createContext(sEgl, sEglDisplay, sEglConfig);
        }

        GL createEglSurface(SurfaceHolder holder) throws Surface.OutOfResourcesException {
            // Check preconditions.
            if (sEgl == null) {
                throw new RuntimeException("egl not initialized");
            }
            if (sEglDisplay == null) {
                throw new RuntimeException("eglDisplay not initialized");
            }
            if (sEglConfig == null) {
                throw new RuntimeException("eglConfig not initialized");
            }
            if (Thread.currentThread() != sEglThread) {
                throw new IllegalStateException("HardwareRenderer cannot be used " 
                        + "from multiple threads");
            }

            /*
             *  The window size has changed, so we need to create a new
             *  surface.
             */
            if (mEglSurface != null && mEglSurface != EGL10.EGL_NO_SURFACE) {
                /*
                 * Unbind and destroy the old EGL surface, if
                 * there is one.
                 */
                sEgl.eglMakeCurrent(sEglDisplay, EGL10.EGL_NO_SURFACE,
                        EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                sEgl.eglDestroySurface(sEglDisplay, mEglSurface);
            }

            // Create an EGL surface we can render into.
            mEglSurface = sEgl.eglCreateWindowSurface(sEglDisplay, sEglConfig, holder, null);

            if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
                int error = sEgl.eglGetError();
                if (error == EGL10.EGL_BAD_NATIVE_WINDOW) {
                    Log.e(LOG_TAG, "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return null;
                }
                throw new RuntimeException("createWindowSurface failed "
                        + getEGLErrorString(error));
            }

            /*
             * Before we can issue GL commands, we need to make sure
             * the context is current and bound to a surface.
             */
            if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, sEglContext)) {
                throw new Surface.OutOfResourcesException("eglMakeCurrent failed "
                        + getEGLErrorString(sEgl.eglGetError()));
            }
            
            if (mDirtyRegions) {
                if (!GLES20Canvas.preserveBackBuffer()) {
                    Log.w(LOG_TAG, "Backbuffer cannot be preserved");
                }
            }

            return sEglContext.getGL();
        }

        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, mGlVersion, EGL10.EGL_NONE };

            return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT,
                    mGlVersion != 0 ? attrib_list : null);            
        }

        @Override
        void initializeIfNeeded(int width, int height, View.AttachInfo attachInfo,
                SurfaceHolder holder) throws Surface.OutOfResourcesException {
            if (isRequested()) {
                checkEglErrors();
                super.initializeIfNeeded(width, height, attachInfo, holder);
            }
        }
        
        @Override
        void destroy(boolean full) {
            if (full && mCanvas != null) {
                mCanvas = null;
            }
            
            if (!isEnabled() || mDestroyed) return;

            mDestroyed = true;

            sEgl.eglMakeCurrent(sEglDisplay, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            sEgl.eglDestroySurface(sEglDisplay, mEglSurface);

            mEglSurface = null;
            mGl = null;

            setEnabled(false);
        }

        @Override
        void setup(int width, int height) {
            mCanvas.setViewport(width, height);
        }

        boolean canDraw() {
            return mGl != null && mCanvas != null;
        }        
        
        void onPreDraw(Rect dirty) {
        }

        void onPostDraw() {
        }
        
        /**
         * Defines the EGL configuration for this renderer.
         * 
         * @return An {@link android.view.HardwareRenderer.GlRenderer.EglConfigChooser}.
         */
        EglConfigChooser getConfigChooser(int glVersion) {
            return new ComponentSizeChooser(glVersion, 8, 8, 8, 8, 0, 0, mDirtyRegions);
        }

        @Override
        void draw(View view, View.AttachInfo attachInfo, HardwareDrawCallbacks callbacks,
                Rect dirty) {
            if (canDraw()) {
                if (!mDirtyRegions) {
                    dirty = null;
                }

                attachInfo.mDrawingTime = SystemClock.uptimeMillis();
                attachInfo.mIgnoreDirtyState = true;
                view.mPrivateFlags |= View.DRAWN;
                
                long startTime;
                if (ViewDebug.DEBUG_PROFILE_DRAWING) {
                    startTime = SystemClock.elapsedRealtime();
                }

                final int surfaceState = checkCurrent();
                if (surfaceState != SURFACE_STATE_ERROR) {
                    // We had to change the current surface and/or context, redraw everything
                    if (surfaceState == SURFACE_STATE_UPDATED) {
                        dirty = null;
                    }

                    onPreDraw(dirty);

                    HardwareCanvas canvas = mCanvas;
                    attachInfo.mHardwareCanvas = canvas;

                    int saveCount = canvas.save();
                    callbacks.onHardwarePreDraw(canvas);

                    try {
                        view.mRecreateDisplayList =
                                (view.mPrivateFlags & View.INVALIDATED) == View.INVALIDATED;
                        view.mPrivateFlags &= ~View.INVALIDATED;

                        DisplayList displayList = view.getDisplayList();
                        if (displayList != null) {
                            if (canvas.drawDisplayList(displayList, view.getWidth(),
                                    view.getHeight(), mRedrawClip)) {
                                if (mRedrawClip.isEmpty() || view.getParent() == null) {
                                    view.invalidate();
                                } else {
                                    view.getParent().invalidateChild(view, mRedrawClip);
                                }
                                mRedrawClip.setEmpty();
                            }
                        } else {
                            // Shouldn't reach here
                            view.draw(canvas);
                        }

                        if (DEBUG_DIRTY_REGION) {
                            if (mDebugPaint == null) {
                                mDebugPaint = new Paint();
                                mDebugPaint.setColor(0x7fff0000);
                            }
                            if (dirty != null && (mFrameCount++ & 1) == 0) {
                                canvas.drawRect(dirty, mDebugPaint);
                            }
                        }
                    } finally {
                        callbacks.onHardwarePostDraw(canvas);
                        canvas.restoreToCount(saveCount);
                        view.mRecreateDisplayList = false;
                    }

                    onPostDraw();

                    if (ViewDebug.DEBUG_PROFILE_DRAWING) {
                        EventLog.writeEvent(60000, SystemClock.elapsedRealtime() - startTime);
                    }
    
                    attachInfo.mIgnoreDirtyState = false;
    
                    sEgl.eglSwapBuffers(sEglDisplay, mEglSurface);
                    checkEglErrors();
                }
            }
        }
        
        private int checkCurrent() {
            // TODO: Don't check the current context when we have one per UI thread
            // TODO: Use a threadlocal flag to know whether the surface has changed
            if (!sEglContext.equals(sEgl.eglGetCurrentContext()) ||
                    !mEglSurface.equals(sEgl.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                if (!sEgl.eglMakeCurrent(sEglDisplay, mEglSurface, mEglSurface, sEglContext)) {
                    fallback(true);
                    Log.e(LOG_TAG, "eglMakeCurrent failed " +
                            getEGLErrorString(sEgl.eglGetError()));
                    return SURFACE_STATE_ERROR;
                } else {
                    return SURFACE_STATE_UPDATED;
                }
            }
            return SURFACE_STATE_SUCCESS;
        }

        static abstract class EglConfigChooser {
            final int[] mConfigSpec;
            private final int mGlVersion;

            EglConfigChooser(int glVersion, int[] configSpec) {
                mGlVersion = glVersion;
                mConfigSpec = filterConfigSpec(configSpec);
            }

            EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
                int[] index = new int[1];
                if (!egl.eglChooseConfig(display, mConfigSpec, null, 0, index)) {
                    throw new IllegalArgumentException("eglChooseConfig failed "
                            + getEGLErrorString(egl.eglGetError()));
                }

                int numConfigs = index[0];
                if (numConfigs <= 0) {
                    throw new IllegalArgumentException("No configs match configSpec");
                }

                EGLConfig[] configs = new EGLConfig[numConfigs];
                if (!egl.eglChooseConfig(display, mConfigSpec, configs, numConfigs, index)) {
                    throw new IllegalArgumentException("eglChooseConfig failed "
                            + getEGLErrorString(egl.eglGetError()));
                }

                EGLConfig config = chooseConfig(egl, display, configs);
                if (config == null) {
                    throw new IllegalArgumentException("No config chosen");
                }

                return config;
            }

            abstract EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs);

            private int[] filterConfigSpec(int[] configSpec) {
                if (mGlVersion != 2) {
                    return configSpec;
                }
                /* We know none of the subclasses define EGL_RENDERABLE_TYPE.
                 * And we know the configSpec is well formed.
                 */
                int len = configSpec.length;
                int[] newConfigSpec = new int[len + 2];
                System.arraycopy(configSpec, 0, newConfigSpec, 0, len - 1);
                newConfigSpec[len - 1] = EGL10.EGL_RENDERABLE_TYPE;
                newConfigSpec[len] = 4; /* EGL_OPENGL_ES2_BIT */
                newConfigSpec[len + 1] = EGL10.EGL_NONE;
                return newConfigSpec;
            }
        }

        /**
         * Choose a configuration with exactly the specified r,g,b,a sizes,
         * and at least the specified depth and stencil sizes.
         */
        static class ComponentSizeChooser extends EglConfigChooser {
            private int[] mValue;

            private final int mRedSize;
            private final int mGreenSize;
            private final int mBlueSize;
            private final int mAlphaSize;
            private final int mDepthSize;
            private final int mStencilSize;
            private final boolean mDirtyRegions;

            ComponentSizeChooser(int glVersion, int redSize, int greenSize, int blueSize,
                    int alphaSize, int depthSize, int stencilSize, boolean dirtyRegions) {
                super(glVersion, new int[] {
                        EGL10.EGL_RED_SIZE, redSize,
                        EGL10.EGL_GREEN_SIZE, greenSize,
                        EGL10.EGL_BLUE_SIZE, blueSize,
                        EGL10.EGL_ALPHA_SIZE, alphaSize,
                        EGL10.EGL_DEPTH_SIZE, depthSize,
                        EGL10.EGL_STENCIL_SIZE, stencilSize,
                        EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT |
                                (dirtyRegions ? EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0),
                        EGL10.EGL_NONE });
                mValue = new int[1];
                mRedSize = redSize;
                mGreenSize = greenSize;
                mBlueSize = blueSize;
                mAlphaSize = alphaSize;
                mDepthSize = depthSize;
                mStencilSize = stencilSize;
                mDirtyRegions = dirtyRegions;
            }

            @Override
            EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs) {
                for (EGLConfig config : configs) {
                    int d = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE, 0);
                    int s = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE, 0);
                    if (d >= mDepthSize && s >= mStencilSize) {
                        int r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE, 0);
                        int g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE, 0);
                        int b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE, 0);
                        int a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE, 0);
                        boolean backBuffer;
                        if (mDirtyRegions) {
                            int surfaceType = findConfigAttrib(egl, display, config,
                                    EGL_SURFACE_TYPE, 0);
                            backBuffer = (surfaceType & EGL_SWAP_BEHAVIOR_PRESERVED_BIT) != 0;
                        } else {
                            backBuffer = true;
                        }
                        if (r >= mRedSize && g >= mGreenSize && b >= mBlueSize && a >= mAlphaSize
                                && backBuffer) {
                            return config;
                        }
                    }
                }
                return null;
            }

            private int findConfigAttrib(EGL10 egl, EGLDisplay display, EGLConfig config,
                    int attribute, int defaultValue) {
                if (egl.eglGetConfigAttrib(display, config, attribute, mValue)) {
                    return mValue[0];
                }

                return defaultValue;
            }
        }
    }

    /**
     * Hardware renderer using OpenGL ES 2.0.
     */
    static class Gl20Renderer extends GlRenderer {
        private GLES20Canvas mGlCanvas;

        Gl20Renderer(boolean translucent) {
            super(2, translucent);
        }

        @Override
        GLES20Canvas createCanvas() {
            return mGlCanvas = new GLES20Canvas(mTranslucent);
        }
        
        @Override
        boolean canDraw() {
            return super.canDraw() && mGlCanvas != null;
        }                

        @Override
        void onPreDraw(Rect dirty) {
            mGlCanvas.onPreDraw(dirty);
        }

        @Override
        void onPostDraw() {
            mGlCanvas.onPostDraw();
        }

        @Override
        void destroy(boolean full) {
            try {
                super.destroy(full);
            } finally {
                if (full && mGlCanvas != null) {
                    mGlCanvas = null;
                }
            }
        }

        @Override
        DisplayList createDisplayList(View v) {
            return new GLES20DisplayList(v);
        }
        
        @Override
        HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque) {
            return new GLES20Layer(width, height, isOpaque);
        }

        static HardwareRenderer create(boolean translucent) {
            if (GLES20Canvas.isAvailable()) {
                return new Gl20Renderer(translucent);
            }
            return null;
        }
    }
}
