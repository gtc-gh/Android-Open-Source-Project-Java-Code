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

package com.android.internal.view.menu;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/**
 * @hide
 */
public class ActionMenuItemView extends LinearLayout
        implements MenuView.ItemView, View.OnClickListener {
    private static final String TAG = "ActionMenuItemView";

    private MenuItemImpl mItemData;
    private CharSequence mTitle;
    private MenuBuilder.ItemInvoker mItemInvoker;

    private ImageButton mImageButton;
    private Button mTextButton;

    public ActionMenuItemView(Context context) {
        this(context, null);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onFinishInflate() {
        mImageButton = (ImageButton) findViewById(com.android.internal.R.id.imageButton);
        mTextButton = (Button) findViewById(com.android.internal.R.id.textButton);
        mImageButton.setOnClickListener(this);
        mTextButton.setOnClickListener(this);
    }

    public MenuItemImpl getItemData() {
        return mItemData;
    }

    public void initialize(MenuItemImpl itemData, int menuType) {
        mItemData = itemData;

        setIcon(itemData.getIcon());
        setTitle(itemData.getTitleForItemView(this)); // Title only takes effect if there is no icon
        setId(itemData.getItemId());

        setVisibility(itemData.isVisible() ? View.VISIBLE : View.GONE);
        setEnabled(itemData.isEnabled());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mImageButton.setEnabled(enabled);
        mTextButton.setEnabled(enabled);
    }

    public void onClick(View v) {
        if (mItemInvoker != null) {
            mItemInvoker.invokeItem(mItemData);
        }
    }

    public void setItemInvoker(MenuBuilder.ItemInvoker invoker) {
        mItemInvoker = invoker;
    }

    public boolean prefersCondensedTitle() {
        return true;
    }

    public void setCheckable(boolean checkable) {
        // TODO Support checkable action items
    }

    public void setChecked(boolean checked) {
        // TODO Support checkable action items
    }

    public void setIcon(Drawable icon) {
        mImageButton.setImageDrawable(icon);
        if (icon != null) {
            mImageButton.setVisibility(VISIBLE);
        } else {
            mImageButton.setVisibility(GONE);
        }

        mTextButton.setVisibility(icon == null || mItemData.showsTextAsAction() ? VISIBLE : GONE);
    }
    
    public boolean hasText() {
        return mTextButton.getVisibility() != GONE;
    }

    public void setShortcut(boolean showShortcut, char shortcutKey) {
        // Action buttons don't show text for shortcut keys.
    }

    public void setTitle(CharSequence title) {
        mTitle = title;

        // populate accessibility description with title
        setContentDescription(title);

        if (mImageButton.getDrawable() == null || mItemData.showsTextAsAction()) {
            mTextButton.setText(mTitle);
            mTextButton.setVisibility(VISIBLE);
        }
    }

    public boolean showsIcon() {
        return true;
    }
}
