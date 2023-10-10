/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * A hardware layer can be used to render graphics operations into a hardware
 * friendly buffer. For instance, with an OpenGL backend, a hardware layer
 * would use a Frame Buffer Object (FBO.) The hardware layer can be used as
 * a drawing cache when a complex set of graphics operations needs to be
 * drawn several times.
 */
abstract class HardwareLayer {
    int mWidth;
    int mHeight;

    final boolean mOpaque;

    /**
     * Creates a new hardware layer at least as large as the supplied
     * dimensions.
     * 
     * @param width The minimum width of the layer
     * @param height The minimum height of the layer
     * @param isOpaque Whether the layer should be opaque or not
     */
    HardwareLayer(int width, int height, boolean isOpaque) {
        mWidth = width;
        mHeight = height;
        mOpaque = isOpaque;
    }

    /**
     * Returns the minimum width of the layer.
     * 
     * @return The minimum desired width of the hardware layer 
     */
    int getWidth() {
        return mWidth;
    }

    /**
     * Returns the minimum height of the layer.
     * 
     * @return The minimum desired height of the hardware layer 
     */
    int getHeight() {
        return mHeight;
    }

    /**
     * Returns whether or not this layer is opaque.
     * 
     * @return True if the layer is opaque, false otherwise
     */
    boolean isOpaque() {
        return mOpaque;
    }

    /**
     * Indicates whether this layer can be rendered.
     * 
     * @return True if the layer can be rendered into, false otherwise
     */
    abstract boolean isValid();

    /**
     * Resizes the layer, if necessary, to be at least as large
     * as the supplied dimensions.
     * 
     * @param width The new desired minimum width for this layer
     * @param height The new desired minimum height for this layer
     */
    abstract void resize(int width, int height);

    /**
     * Returns a hardware canvas that can be used to render onto
     * this layer.
     * 
     * @return A hardware canvas, or null if a canvas cannot be created
     */
    abstract HardwareCanvas getCanvas();

    /**
     * Destroys resources without waiting for a GC. 
     */
    abstract void destroy();

    /**
     * This must be invoked before drawing onto this layer.
     * @param currentCanvas
     */
    abstract HardwareCanvas start(Canvas currentCanvas);
    
    /**
     * This must be invoked after drawing onto this layer.
     * @param currentCanvas
     */
    abstract void end(Canvas currentCanvas);
}
