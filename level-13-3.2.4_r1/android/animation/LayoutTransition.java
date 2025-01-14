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

package android.animation;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This class enables automatic animations on layout changes in ViewGroup objects. To enable
 * transitions for a layout container, create a LayoutTransition object and set it on any
 * ViewGroup by calling {@link ViewGroup#setLayoutTransition(LayoutTransition)}. This will cause
 * default animations to run whenever items are added to or removed from that container. To specify
 * custom animations, use the {@link LayoutTransition#setAnimator(int, Animator)
 * setAnimator()} method.
 *
 * <p>One of the core concepts of these transition animations is that there are two types of
 * changes that cause the transition and four different animations that run because of
 * those changes. The changes that trigger the transition are items being added to a container
 * (referred to as an "appearing" transition) or removed from a container (also known as
 * "disappearing"). Setting the visibility of views (between GONE and VISIBLE) will trigger
 * the same add/remove logic. The animations that run due to those events are one that animates
 * items being added, one that animates items being removed, and two that animate the other
 * items in the container that change due to the add/remove occurrence. Users of
 * the transition may want different animations for the changing items depending on whether
 * they are changing due to an appearing or disappearing event, so there is one animation for
 * each of these variations of the changing event. Most of the API of this class is concerned
 * with setting up the basic properties of the animations used in these four situations,
 * or with setting up custom animations for any or all of the four.</p>
 *
 * <p>The animations specified for the transition, both the defaults and any custom animations
 * set on the transition object, are templates only. That is, these animations exist to hold the
 * basic animation properties, such as the duration, start delay, and properties being animated.
 * But the actual target object, as well as the start and end values for those properties, are
 * set automatically in the process of setting up the transition each time it runs. Each of the
 * animations is cloned from the original copy and the clone is then populated with the dynamic
 * values of the target being animated (such as one of the items in a layout container that is
 * moving as a result of the layout event) as well as the values that are changing (such as the
 * position and size of that object). The actual values that are pushed to each animation
 * depends on what properties are specified for the animation. For example, the default
 * CHANGE_APPEARING animation animates <code>left</code>, <code>top</code>, <code>right</code>,
 * and <code>bottom</code>. Values for these properties are updated with the pre- and post-layout
 * values when the transition begins. Custom animations will be similarly populated with
 * the target and values being animated, assuming they use ObjectAnimator objects with
 * property names that are known on the target object.</p>
 *
 * <p>This class, and the associated XML flag for containers, animateLayoutChanges="true",
 * provides a simple utility meant for automating changes in straightforward situations.
 * Using LayoutTransition at multiple levels of a nested view hierarchy may not work due to the
 * interrelationship of the various levels of layout. Also, a container that is being scrolled
 * at the same time as items are being added or removed is probably not a good candidate for
 * this utility, because the before/after locations calculated by LayoutTransition
 * may not match the actual locations when the animations finish due to the container
 * being scrolled as the animations are running. You can work around that
 * particular issue by disabling the 'changing' animations by setting the CHANGE_APPEARING
 * and CHANGE_DISAPPEARING animations to null, and setting the startDelay of the
 * other animations appropriately.</p>
 */
public class LayoutTransition {

    /**
     * A flag indicating the animation that runs on those items that are changing
     * due to a new item appearing in the container.
     */
    public static final int CHANGE_APPEARING = 0;

    /**
     * A flag indicating the animation that runs on those items that are changing
     * due to an item disappearing from the container.
     */
    public static final int CHANGE_DISAPPEARING = 1;

    /**
     * A flag indicating the animation that runs on those items that are appearing
     * in the container.
     */
    public static final int APPEARING = 2;

    /**
     * A flag indicating the animation that runs on those items that are disappearing
     * from the container.
     */
    public static final int DISAPPEARING = 3;

    /**
     * These variables hold the animations that are currently used to run the transition effects.
     * These animations are set to defaults, but can be changed to custom animations by
     * calls to setAnimator().
     */
    private Animator mDisappearingAnim = null;
    private Animator mAppearingAnim = null;
    private Animator mChangingAppearingAnim = null;
    private Animator mChangingDisappearingAnim = null;

    /**
     * These are the default animations, defined in the constructor, that will be used
     * unless the user specifies custom animations.
     */
    private static ObjectAnimator defaultChangeIn;
    private static ObjectAnimator defaultChangeOut;
    private static ObjectAnimator defaultFadeIn;
    private static ObjectAnimator defaultFadeOut;

    /**
     * The default duration used by all animations.
     */
    private static long DEFAULT_DURATION = 300;

    /**
     * The durations of the four different animations
     */
    private long mChangingAppearingDuration = DEFAULT_DURATION;
    private long mChangingDisappearingDuration = DEFAULT_DURATION;
    private long mAppearingDuration = DEFAULT_DURATION;
    private long mDisappearingDuration = DEFAULT_DURATION;

    /**
     * The start delays of the four different animations. Note that the default behavior of
     * the appearing item is the default duration, since it should wait for the items to move
     * before fading it. Same for the changing animation when disappearing; it waits for the item
     * to fade out before moving the other items.
     */
    private long mAppearingDelay = DEFAULT_DURATION;
    private long mDisappearingDelay = 0;
    private long mChangingAppearingDelay = 0;
    private long mChangingDisappearingDelay = DEFAULT_DURATION;

    /**
     * The inter-animation delays used on the two changing animations
     */
    private long mChangingAppearingStagger = 0;
    private long mChangingDisappearingStagger = 0;

    /**
     * The default interpolators used for the animations
     */
    private TimeInterpolator mAppearingInterpolator = new AccelerateDecelerateInterpolator();
    private TimeInterpolator mDisappearingInterpolator = new AccelerateDecelerateInterpolator();
    private TimeInterpolator mChangingAppearingInterpolator = new DecelerateInterpolator();
    private TimeInterpolator mChangingDisappearingInterpolator = new DecelerateInterpolator();

    /**
     * These hashmaps are used to store the animations that are currently running as part of
     * the transition. The reason for this is that a further layout event should cause
     * existing animations to stop where they are prior to starting new animations. So
     * we cache all of the current animations in this map for possible cancellation on
     * another layout event.
     */
    private final HashMap<View, Animator> pendingAnimations = new HashMap<View, Animator>();
    private final HashMap<View, Animator> currentChangingAnimations = new HashMap<View, Animator>();
    private final HashMap<View, Animator> currentAppearingAnimations =
            new HashMap<View, Animator>();
    private final HashMap<View, Animator> currentDisappearingAnimations =
            new HashMap<View, Animator>();

    /**
     * This hashmap is used to track the listeners that have been added to the children of
     * a container. When a layout change occurs, an animation is created for each View, so that
     * the pre-layout values can be cached in that animation. Then a listener is added to the
     * view to see whether the layout changes the bounds of that view. If so, the animation
     * is set with the final values and then run. If not, the animation is not started. When
     * the process of setting up and running all appropriate animations is done, we need to
     * remove these listeners and clear out the map.
     */
    private final HashMap<View, View.OnLayoutChangeListener> layoutChangeListenerMap =
            new HashMap<View, View.OnLayoutChangeListener>();

    /**
     * Used to track the current delay being assigned to successive animations as they are
     * started. This value is incremented for each new animation, then zeroed before the next
     * transition begins.
     */
    private long staggerDelay;

    /**
     * The set of listeners that should be notified when APPEARING/DISAPPEARING transitions
     * start and end.
     */
    private ArrayList<TransitionListener> mListeners;


    /**
     * Constructs a LayoutTransition object. By default, the object will listen to layout
     * events on any ViewGroup that it is set on and will run default animations for each
     * type of layout event.
     */
    public LayoutTransition() {
        if (defaultChangeIn == null) {
            // "left" is just a placeholder; we'll put real properties/values in when needed
            PropertyValuesHolder pvhLeft = PropertyValuesHolder.ofInt("left", 0, 1);
            PropertyValuesHolder pvhTop = PropertyValuesHolder.ofInt("top", 0, 1);
            PropertyValuesHolder pvhRight = PropertyValuesHolder.ofInt("right", 0, 1);
            PropertyValuesHolder pvhBottom = PropertyValuesHolder.ofInt("bottom", 0, 1);
            defaultChangeIn = ObjectAnimator.ofPropertyValuesHolder(this,
                    pvhLeft, pvhTop, pvhRight, pvhBottom);
            defaultChangeIn.setDuration(DEFAULT_DURATION);
            defaultChangeIn.setStartDelay(mChangingAppearingDelay);
            defaultChangeIn.setInterpolator(mChangingAppearingInterpolator);
            defaultChangeOut = defaultChangeIn.clone();
            defaultChangeOut.setStartDelay(mChangingDisappearingDelay);
            defaultChangeOut.setInterpolator(mChangingDisappearingInterpolator);
            defaultFadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f);
            defaultFadeIn.setDuration(DEFAULT_DURATION);
            defaultFadeIn.setStartDelay(mAppearingDelay);
            defaultFadeIn.setInterpolator(mAppearingInterpolator);
            defaultFadeOut = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f);
            defaultFadeOut.setDuration(DEFAULT_DURATION);
            defaultFadeOut.setStartDelay(mDisappearingDelay);
            defaultFadeOut.setInterpolator(mDisappearingInterpolator);
        }
        mChangingAppearingAnim = defaultChangeIn;
        mChangingDisappearingAnim = defaultChangeOut;
        mAppearingAnim = defaultFadeIn;
        mDisappearingAnim = defaultFadeOut;
    }

    /**
     * Sets the duration to be used by all animations of this transition object. If you want to
     * set the duration of just one of the animations in particular, use the
     * {@link #setDuration(int, long)} method.
     *
     * @param duration The length of time, in milliseconds, that the transition animations
     * should last.
     */
    public void setDuration(long duration) {
        mChangingAppearingDuration = duration;
        mChangingDisappearingDuration = duration;
        mAppearingDuration = duration;
        mDisappearingDuration = duration;
    }

    /**
     * Sets the start delay on one of the animation objects used by this transition. The
     * <code>transitionType</code> parameter determines the animation whose start delay
     * is being set.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose start
     * delay is being set.
     * @param delay The length of time, in milliseconds, to delay before starting the animation.
     * @see Animator#setStartDelay(long)
     */
    public void setStartDelay(int transitionType, long delay) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                mChangingAppearingDelay = delay;
                break;
            case CHANGE_DISAPPEARING:
                mChangingDisappearingDelay = delay;
                break;
            case APPEARING:
                mAppearingDelay = delay;
                break;
            case DISAPPEARING:
                mDisappearingDelay = delay;
                break;
        }
    }

    /**
     * Gets the start delay on one of the animation objects used by this transition. The
     * <code>transitionType</code> parameter determines the animation whose start delay
     * is returned.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose start
     * delay is returned.
     * @return long The start delay of the specified animation.
     * @see Animator#getStartDelay()
     */
    public long getStartDelay(int transitionType) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                return mChangingAppearingDuration;
            case CHANGE_DISAPPEARING:
                return mChangingDisappearingDuration;
            case APPEARING:
                return mAppearingDuration;
            case DISAPPEARING:
                return mDisappearingDuration;
        }
        // shouldn't reach here
        return 0;
    }

    /**
     * Sets the duration on one of the animation objects used by this transition. The
     * <code>transitionType</code> parameter determines the animation whose duration
     * is being set.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose
     * duration is being set.
     * @param duration The length of time, in milliseconds, that the specified animation should run.
     * @see Animator#setDuration(long)
     */
    public void setDuration(int transitionType, long duration) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                mChangingAppearingDuration = duration;
                break;
            case CHANGE_DISAPPEARING:
                mChangingDisappearingDuration = duration;
                break;
            case APPEARING:
                mAppearingDuration = duration;
                break;
            case DISAPPEARING:
                mDisappearingDuration = duration;
                break;
        }
    }

    /**
     * Gets the duration on one of the animation objects used by this transition. The
     * <code>transitionType</code> parameter determines the animation whose duration
     * is returned.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose
     * duration is returned.
     * @return long The duration of the specified animation.
     * @see Animator#getDuration()
     */
    public long getDuration(int transitionType) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                return mChangingAppearingDuration;
            case CHANGE_DISAPPEARING:
                return mChangingDisappearingDuration;
            case APPEARING:
                return mAppearingDuration;
            case DISAPPEARING:
                return mDisappearingDuration;
        }
        // shouldn't reach here
        return 0;
    }

    /**
     * Sets the length of time to delay between starting each animation during one of the
     * CHANGE animations.
     *
     * @param transitionType A value of {@link #CHANGE_APPEARING} or @link #CHANGE_DISAPPEARING}.
     * @param duration The length of time, in milliseconds, to delay before launching the next
     * animation in the sequence.
     */
    public void setStagger(int transitionType, long duration) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                mChangingAppearingStagger = duration;
                break;
            case CHANGE_DISAPPEARING:
                mChangingDisappearingStagger = duration;
                break;
            // noop other cases
        }
    }

    /**
     * Tets the length of time to delay between starting each animation during one of the
     * CHANGE animations.
     *
     * @param transitionType A value of {@link #CHANGE_APPEARING} or @link #CHANGE_DISAPPEARING}.
     * @return long The length of time, in milliseconds, to delay before launching the next
     * animation in the sequence.
     */
    public long getStagger(int transitionType) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                return mChangingAppearingStagger;
            case CHANGE_DISAPPEARING:
                return mChangingDisappearingStagger;
        }
        // shouldn't reach here
        return 0;
    }

    /**
     * Sets the interpolator on one of the animation objects used by this transition. The
     * <code>transitionType</code> parameter determines the animation whose interpolator
     * is being set.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose
     * duration is being set.
     * @param interpolator The interpolator that the specified animation should use.
     * @see Animator#setInterpolator(TimeInterpolator)
     */
    public void setInterpolator(int transitionType, TimeInterpolator interpolator) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                mChangingAppearingInterpolator = interpolator;
                break;
            case CHANGE_DISAPPEARING:
                mChangingDisappearingInterpolator = interpolator;
                break;
            case APPEARING:
                mAppearingInterpolator = interpolator;
                break;
            case DISAPPEARING:
                mDisappearingInterpolator = interpolator;
                break;
        }
    }

    /**
     * Gets the interpolator on one of the animation objects used by this transition. The
     * <code>transitionType</code> parameter determines the animation whose interpolator
     * is returned.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose
     * duration is being set.
     * @return TimeInterpolator The interpolator that the specified animation uses.
     * @see Animator#setInterpolator(TimeInterpolator)
     */
    public TimeInterpolator getInterpolator(int transitionType) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                return mChangingAppearingInterpolator;
            case CHANGE_DISAPPEARING:
                return mChangingDisappearingInterpolator;
            case APPEARING:
                return mAppearingInterpolator;
            case DISAPPEARING:
                return mDisappearingInterpolator;
        }
        // shouldn't reach here
        return null;
    }

    /**
     * Sets the animation used during one of the transition types that may run. Any
     * Animator object can be used, but to be most useful in the context of layout
     * transitions, the animation should either be a ObjectAnimator or a AnimatorSet
     * of animations including PropertyAnimators. Also, these ObjectAnimator objects
     * should be able to get and set values on their target objects automatically. For
     * example, a ObjectAnimator that animates the property "left" is able to set and get the
     * <code>left</code> property from the View objects being animated by the layout
     * transition. The transition works by setting target objects and properties
     * dynamically, according to the pre- and post-layoout values of those objects, so
     * having animations that can handle those properties appropriately will work best
     * for custom animation. The dynamic setting of values is only the case for the
     * CHANGE animations; the APPEARING and DISAPPEARING animations are simply run with
     * the values they have.
     *
     * <p>It is also worth noting that any and all animations (and their underlying
     * PropertyValuesHolder objects) will have their start and end values set according
     * to the pre- and post-layout values. So, for example, a custom animation on "alpha"
     * as the CHANGE_APPEARING animation will inherit the real value of alpha on the target
     * object (presumably 1) as its starting and ending value when the animation begins.
     * Animations which need to use values at the beginning and end that may not match the
     * values queried when the transition begins may need to use a different mechanism
     * than a standard ObjectAnimator object.</p>
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose
     * duration is being set.
     * @param animator The animation being assigned. A value of <code>null</code> means that no
     * animation will be run for the specified transitionType.
     */
    public void setAnimator(int transitionType, Animator animator) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                mChangingAppearingAnim = animator;
                break;
            case CHANGE_DISAPPEARING:
                mChangingDisappearingAnim = animator;
                break;
            case APPEARING:
                mAppearingAnim = animator;
                break;
            case DISAPPEARING:
                mDisappearingAnim = animator;
                break;
        }
    }

    /**
     * Gets the animation used during one of the transition types that may run.
     *
     * @param transitionType one of {@link #CHANGE_APPEARING}, {@link #CHANGE_DISAPPEARING},
     * {@link #APPEARING}, or {@link #DISAPPEARING}, which determines the animation whose
     * duration is being set.
     * @return Animator The animation being used for the given transition type.
     * @see #setAnimator(int, Animator)
     */
    public Animator getAnimator(int transitionType) {
        switch (transitionType) {
            case CHANGE_APPEARING:
                return mChangingAppearingAnim;
            case CHANGE_DISAPPEARING:
                return mChangingDisappearingAnim;
            case APPEARING:
                return mAppearingAnim;
            case DISAPPEARING:
                return mDisappearingAnim;
        }
        // shouldn't reach here
        return null;
    }

    /**
     * This function sets up runs animations on all of the views that change during layout.
     * For every child in the parent, we create a change animation of the appropriate
     * type (appearing or disappearing) and ask it to populate its start values from its
     * target view. We add layout listeners to all child views and listen for changes. For
     * those views that change, we populate the end values for those animations and start them.
     * Animations are not run on unchanging views.
     *
     * @param parent The container which is undergoing an appearing or disappearing change.
     * @param newView The view being added to or removed from the parent.
     * @param changeReason A value of APPEARING or DISAPPEARING, indicating whether the
     * transition is occuring because an item is being added to or removed from the parent.
     */
    private void runChangeTransition(final ViewGroup parent, View newView, final int changeReason) {

        Animator baseAnimator = (changeReason == APPEARING) ?
                mChangingAppearingAnim : mChangingDisappearingAnim;
        // If the animation is null, there's nothing to do
        if (baseAnimator == null) {
            return;
        }

        // reset the inter-animation delay, in case we use it later
        staggerDelay = 0;
        final long duration = (changeReason == APPEARING) ?
                mChangingAppearingDuration : mChangingDisappearingDuration;

        final ViewTreeObserver observer = parent.getViewTreeObserver(); // used for later cleanup
        if (!observer.isAlive()) {
            // If the observer's not in a good state, skip the transition
            return;
        }
        int numChildren = parent.getChildCount();

        for (int i = 0; i < numChildren; ++i) {
            final View child = parent.getChildAt(i);

            // only animate the views not being added or removed
            if (child != newView) {


                // Make a copy of the appropriate animation
                final Animator anim = baseAnimator.clone();

                // Set the target object for the animation
                anim.setTarget(child);

                // A ObjectAnimator (or AnimatorSet of them) can extract start values from
                // its target object
                anim.setupStartValues();

                // If there's an animation running on this view already, cancel it
                Animator currentAnimation = pendingAnimations.get(child);
                if (currentAnimation != null) {
                    currentAnimation.cancel();
                    pendingAnimations.remove(child);
                }
                // Cache the animation in case we need to cancel it later
                pendingAnimations.put(child, anim);

                // For the animations which don't get started, we have to have a means of
                // removing them from the cache, lest we leak them and their target objects.
                // We run an animator for the default duration+100 (an arbitrary time, but one
                // which should far surpass the delay between setting them up here and
                // handling layout events which start them.
                ValueAnimator pendingAnimRemover = ValueAnimator.ofFloat(0f, 1f).
                        setDuration(duration+100);
                pendingAnimRemover.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        pendingAnimations.remove(child);
                    }
                });
                pendingAnimRemover.start();

                // Add a listener to track layout changes on this view. If we don't get a callback,
                // then there's nothing to animate.
                final View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {

                        // Tell the animation to extract end values from the changed object
                        anim.setupEndValues();

                        long startDelay;
                        if (changeReason == APPEARING) {
                            startDelay = mChangingAppearingDelay + staggerDelay;
                            staggerDelay += mChangingAppearingStagger;
                        } else {
                            startDelay = mChangingDisappearingDelay + staggerDelay;
                            staggerDelay += mChangingDisappearingStagger;
                        }
                        anim.setStartDelay(startDelay);
                        anim.setDuration(duration);

                        Animator prevAnimation = currentChangingAnimations.get(child);
                        if (prevAnimation != null) {
                            prevAnimation.cancel();
                        }
                        Animator pendingAnimation = pendingAnimations.get(child);
                        if (pendingAnimation != null) {
                            pendingAnimations.remove(child);
                        }
                        // Cache the animation in case we need to cancel it later
                        currentChangingAnimations.put(child, anim);

                        if (anim instanceof ObjectAnimator) {
                            ((ObjectAnimator) anim).setCurrentPlayTime(0);
                        }
                        anim.start();

                        // this only removes listeners whose views changed - must clear the
                        // other listeners later
                        child.removeOnLayoutChangeListener(this);
                        layoutChangeListenerMap.remove(child);
                    }
                };
                // Remove the animation from the cache when it ends
                anim.addListener(new AnimatorListenerAdapter() {

                    @Override
                    public void onAnimationStart(Animator animator) {
                        if (mListeners != null) {
                            for (TransitionListener listener : mListeners) {
                                listener.startTransition(LayoutTransition.this, parent, child,
                                        changeReason == APPEARING ?
                                                CHANGE_APPEARING : CHANGE_DISAPPEARING);
                            }
                        }
                    }

                    @Override
                    public void onAnimationCancel(Animator animator) {
                        child.removeOnLayoutChangeListener(listener);
                        layoutChangeListenerMap.remove(child);
                    }

                    @Override
                    public void onAnimationEnd(Animator animator) {
                        currentChangingAnimations.remove(child);
                        if (mListeners != null) {
                            for (TransitionListener listener : mListeners) {
                                listener.endTransition(LayoutTransition.this, parent, child,
                                        changeReason == APPEARING ?
                                                CHANGE_APPEARING : CHANGE_DISAPPEARING);
                            }
                        }
                    }
                });

                child.addOnLayoutChangeListener(listener);
                // cache the listener for later removal
                layoutChangeListenerMap.put(child, listener);
            }
        }
        // This is the cleanup step. When we get this rendering event, we know that all of
        // the appropriate animations have been set up and run. Now we can clear out the
        // layout listeners.
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                parent.getViewTreeObserver().removeOnPreDrawListener(this);
                int numChildren = parent.getChildCount();
                for (int i = 0; i < numChildren; ++i) {
                    final View child = parent.getChildAt(i);
                    child.removeOnLayoutChangeListener(layoutChangeListenerMap.get(child));
                }
                layoutChangeListenerMap.clear();
                return true;
            }
        });
    }

    /**
     * Returns true if animations are running which animate layout-related properties. This
     * essentially means that either CHANGE_APPEARING or CHANGE_DISAPPEARING animations
     * are running, since these animations operate on layout-related properties.
     *
     * @return true if CHANGE_APPEARING or CHANGE_DISAPPEARING animations are currently
     * running.
     */
    public boolean isChangingLayout() {
        return (currentChangingAnimations.size() > 0);
    }

    /**
     * Returns true if any of the animations in this transition are currently running.
     *
     * @return true if any animations in the transition are running.
     */
    public boolean isRunning() {
        return (currentChangingAnimations.size() > 0 || currentAppearingAnimations.size() > 0 ||
                currentDisappearingAnimations.size() > 0);
    }

    /**
     * Cancels the currently running transition. Note that we cancel() the changing animations
     * but end() the visibility animations. This is because this method is currently called
     * in the context of starting a new transition, so we want to move things from their mid-
     * transition positions, but we want them to have their end-transition visibility.
     *
     * @hide
     */
    public void cancel() {
        if (currentChangingAnimations.size() > 0) {
            HashMap<View, Animator> currentAnimCopy =
                    (HashMap<View, Animator>) currentChangingAnimations.clone();
            for (Animator anim : currentAnimCopy.values()) {
                anim.cancel();
            }
            currentChangingAnimations.clear();
        }
        if (currentAppearingAnimations.size() > 0) {
            HashMap<View, Animator> currentAnimCopy =
                    (HashMap<View, Animator>) currentAppearingAnimations.clone();
            for (Animator anim : currentAnimCopy.values()) {
                anim.end();
            }
            currentAppearingAnimations.clear();
        }
        if (currentDisappearingAnimations.size() > 0) {
            HashMap<View, Animator> currentAnimCopy =
                    (HashMap<View, Animator>) currentDisappearingAnimations.clone();
            for (Animator anim : currentAnimCopy.values()) {
                anim.end();
            }
            currentDisappearingAnimations.clear();
        }
    }

    /**
     * Cancels the specified type of transition. Note that we cancel() the changing animations
     * but end() the visibility animations. This is because this method is currently called
     * in the context of starting a new transition, so we want to move things from their mid-
     * transition positions, but we want them to have their end-transition visibility.
     *
     * @hide
     */
    public void cancel(int transitionType) {
        switch (transitionType) {
            case CHANGE_APPEARING:
            case CHANGE_DISAPPEARING:
                if (currentChangingAnimations.size() > 0) {
                    HashMap<View, Animator> currentAnimCopy =
                            (HashMap<View, Animator>) currentChangingAnimations.clone();
                    for (Animator anim : currentAnimCopy.values()) {
                        anim.cancel();
                    }
                    currentChangingAnimations.clear();
                }
                break;
            case APPEARING:
                if (currentAppearingAnimations.size() > 0) {
                    HashMap<View, Animator> currentAnimCopy =
                            (HashMap<View, Animator>) currentAppearingAnimations.clone();
                    for (Animator anim : currentAnimCopy.values()) {
                        anim.end();
                    }
                    currentAppearingAnimations.clear();
                }
                break;
            case DISAPPEARING:
                if (currentDisappearingAnimations.size() > 0) {
                    HashMap<View, Animator> currentAnimCopy =
                            (HashMap<View, Animator>) currentDisappearingAnimations.clone();
                    for (Animator anim : currentAnimCopy.values()) {
                        anim.end();
                    }
                    currentDisappearingAnimations.clear();
                }
                break;
        }
    }

    /**
     * This method runs the animation that makes an added item appear.
     *
     * @param parent The ViewGroup to which the View is being added.
     * @param child The View being added to the ViewGroup.
     */
    private void runAppearingTransition(final ViewGroup parent, final View child) {
        Animator currentAnimation = currentDisappearingAnimations.get(child);
        if (currentAnimation != null) {
            currentAnimation.cancel();
        }
        if (mAppearingAnim == null) {
            if (mListeners != null) {
                for (TransitionListener listener : mListeners) {
                    listener.endTransition(LayoutTransition.this, parent, child, APPEARING);
                }
            }
            return;
        }
        Animator anim = mAppearingAnim.clone();
        anim.setTarget(child);
        anim.setStartDelay(mAppearingDelay);
        anim.setDuration(mAppearingDuration);
        if (anim instanceof ObjectAnimator) {
            ((ObjectAnimator) anim).setCurrentPlayTime(0);
        }
        if (mListeners != null) {
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator anim) {
                    currentAppearingAnimations.remove(child);
                    for (TransitionListener listener : mListeners) {
                        listener.endTransition(LayoutTransition.this, parent, child, APPEARING);
                    }
                }
            });
        }
        currentAppearingAnimations.put(child, anim);
        anim.start();
    }

    /**
     * This method runs the animation that makes a removed item disappear.
     *
     * @param parent The ViewGroup from which the View is being removed.
     * @param child The View being removed from the ViewGroup.
     */
    private void runDisappearingTransition(final ViewGroup parent, final View child) {
        Animator currentAnimation = currentAppearingAnimations.get(child);
        if (currentAnimation != null) {
            currentAnimation.cancel();
        }
        if (mDisappearingAnim == null) {
            if (mListeners != null) {
                for (TransitionListener listener : mListeners) {
                    listener.endTransition(LayoutTransition.this, parent, child, DISAPPEARING);
                }
            }
            return;
        }
        Animator anim = mDisappearingAnim.clone();
        anim.setStartDelay(mDisappearingDelay);
        anim.setDuration(mDisappearingDuration);
        anim.setTarget(child);
        if (mListeners != null) {
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator anim) {
                    currentDisappearingAnimations.remove(child);
                    for (TransitionListener listener : mListeners) {
                        listener.endTransition(LayoutTransition.this, parent, child, DISAPPEARING);
                    }
                }
            });
        }
        if (anim instanceof ObjectAnimator) {
            ((ObjectAnimator) anim).setCurrentPlayTime(0);
        }
        currentDisappearingAnimations.put(child, anim);
        anim.start();
    }

    /**
     * This method is called by ViewGroup when a child view is about to be added to the
     * container. This callback starts the process of a transition; we grab the starting
     * values, listen for changes to all of the children of the container, and start appropriate
     * animations.
     *
     * @param parent The ViewGroup to which the View is being added.
     * @param child The View being added to the ViewGroup.
     */
    public void addChild(ViewGroup parent, View child) {
        // Want disappearing animations to finish up before proceeding
        cancel(DISAPPEARING);
        // Also, cancel changing animations so that we start fresh ones from current locations
        cancel(CHANGE_APPEARING);
        if (mListeners != null) {
            for (TransitionListener listener : mListeners) {
                listener.startTransition(this, parent, child, APPEARING);
            }
        }
        runChangeTransition(parent, child, APPEARING);
        runAppearingTransition(parent, child);
    }

    /**
     * This method is called by ViewGroup when a child view is about to be added to the
     * container. This callback starts the process of a transition; we grab the starting
     * values, listen for changes to all of the children of the container, and start appropriate
     * animations.
     *
     * @param parent The ViewGroup to which the View is being added.
     * @param child The View being added to the ViewGroup.
     */
    public void showChild(ViewGroup parent, View child) {
        addChild(parent, child);
    }

    /**
     * This method is called by ViewGroup when a child view is about to be removed from the
     * container. This callback starts the process of a transition; we grab the starting
     * values, listen for changes to all of the children of the container, and start appropriate
     * animations.
     *
     * @param parent The ViewGroup from which the View is being removed.
     * @param child The View being removed from the ViewGroup.
     */
    public void removeChild(ViewGroup parent, View child) {
        // Want appearing animations to finish up before proceeding
        cancel(APPEARING);
        // Also, cancel changing animations so that we start fresh ones from current locations
        cancel(CHANGE_DISAPPEARING);
        if (mListeners != null) {
            for (TransitionListener listener : mListeners) {
                listener.startTransition(this, parent, child, DISAPPEARING);
            }
        }
        runChangeTransition(parent, child, DISAPPEARING);
        runDisappearingTransition(parent, child);
    }

    /**
     * This method is called by ViewGroup when a child view is about to be removed from the
     * container. This callback starts the process of a transition; we grab the starting
     * values, listen for changes to all of the children of the container, and start appropriate
     * animations.
     *
     * @param parent The ViewGroup from which the View is being removed.
     * @param child The View being removed from the ViewGroup.
     */
    public void hideChild(ViewGroup parent, View child) {
        removeChild(parent, child);
    }

    /**
     * Add a listener that will be called when the bounds of the view change due to
     * layout processing.
     *
     * @param listener The listener that will be called when layout bounds change.
     */
    public void addTransitionListener(TransitionListener listener) {
        if (mListeners == null) {
            mListeners = new ArrayList<TransitionListener>();
        }
        mListeners.add(listener);
    }

    /**
     * Remove a listener for layout changes.
     *
     * @param listener The listener for layout bounds change.
     */
    public void removeTransitionListener(TransitionListener listener) {
        if (mListeners == null) {
            return;
        }
        mListeners.remove(listener);
    }

    /**
     * Gets the current list of listeners for layout changes.
     * @return
     */
    public List<TransitionListener> getTransitionListeners() {
        return mListeners;
    }

    /**
     * This interface is used for listening to starting and ending events for transitions.
     */
    public interface TransitionListener {

        /**
         * This event is sent to listeners when any type of transition animation begins.
         *
         * @param transition The LayoutTransition sending out the event.
         * @param container The ViewGroup on which the transition is playing.
         * @param view The View object being affected by the transition animation.
         * @param transitionType The type of transition that is beginning,
         * {@link android.animation.LayoutTransition#APPEARING},
         * {@link android.animation.LayoutTransition#DISAPPEARING},
         * {@link android.animation.LayoutTransition#CHANGE_APPEARING}, or
         * {@link android.animation.LayoutTransition#CHANGE_DISAPPEARING}.
         */
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType);

        /**
         * This event is sent to listeners when any type of transition animation ends.
         *
         * @param transition The LayoutTransition sending out the event.
         * @param container The ViewGroup on which the transition is playing.
         * @param view The View object being affected by the transition animation.
         * @param transitionType The type of transition that is ending,
         * {@link android.animation.LayoutTransition#APPEARING},
         * {@link android.animation.LayoutTransition#DISAPPEARING},
         * {@link android.animation.LayoutTransition#CHANGE_APPEARING}, or
         * {@link android.animation.LayoutTransition#CHANGE_DISAPPEARING}.
         */
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType);
    }

}