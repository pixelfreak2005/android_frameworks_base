/*
 * Copyright (C) 2014-2017 SlimRoms Project
 * Author: Lars Greiss - email: kufikugel@googlemail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.systemui.slimrecent;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.android.cards.internal.CardExpand;
import com.android.systemui.R;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * This class handles our expanded card which shows the actual task screenshot.
 * The loader (#link:BitmapDownloaderTask) class is handled here as well and put
 * the loaded task screenshot for the time the task exists into the LRU cache.
 */
public class RecentExpandedCard extends CardExpand {

    private Context mContext;

    private Drawable mDefaultThumbnailBackground;

    private int mPersistentTaskId = -1;
    private String mLabel;
    private int mThumbnailWidth;
    private int mThumbnailHeight;
    private int mBottomPadding;
    private float mScaleFactor;
    private boolean mScaleFactorChanged;

    private int defaultCardBg;
    private int cardColor;

    private BitmapDownloaderTask mTask;

    private boolean mReload;
    private boolean mDoNotNullBitmap;

    final static BitmapFactory.Options sBitmapOptions;

    private TaskDescription mTaskDescription;

    static {
        sBitmapOptions = new BitmapFactory.Options();
        sBitmapOptions.inMutable = true;
    }

    public RecentExpandedCard(Context context, TaskDescription td, float scaleFactor) {
        this(context, R.layout.recent_inner_card_expand, td, scaleFactor);
    }

    // Main constructor. Set the important values we need.
    public RecentExpandedCard(Context context, int innerLayout,
            TaskDescription td, float scaleFactor) {
        super(context, innerLayout);
        mTaskDescription = td;
        mContext = context;
        mPersistentTaskId = td.persistentTaskId;
        mLabel = (String) td.getLabel();
        mScaleFactor = scaleFactor;

        defaultCardBg = mContext.getResources().getColor(
                R.color.recents_task_bar_default_background_color);
        cardColor = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.RECENT_CARD_BG_COLOR,
                defaultCardBg, UserHandle.USER_CURRENT);

        initDimensions();
    }

    // Update expanded card content.
    public void updateExpandedContent(TaskDescription td, float scaleFactor) {
        mTaskDescription = td;
        String label = (String) td.getLabel();
        if (label != null && label.equals(mLabel)) {
            mDoNotNullBitmap = true;
        }
        mLabel = label;
        mPersistentTaskId = td.persistentTaskId;
        mReload = true;

        if (scaleFactor != mScaleFactor) {
            mScaleFactorChanged = true;
            mScaleFactor = scaleFactor;
            initDimensions();
        }
    }

    /** Returns the activity's primary color. */
    public int getDefaultCardColorBg() {
        if (mTaskDescription != null && mTaskDescription.cardColor != 0) {
            return mTaskDescription.cardColor;
        }
        return defaultCardBg;
    }

    // Setup main dimensions we need.
    private void initDimensions() {
        final Resources res = mContext.getResources();
        // Render the default thumbnail background
        mThumbnailWidth = (int) (res.getDimensionPixelSize(
                R.dimen.recent_thumbnail_width) * mScaleFactor);
        mThumbnailHeight = (int) (res.getDimensionPixelSize(
                R.dimen.recent_thumbnail_height) * mScaleFactor);
        mBottomPadding = (int) (res.getDimensionPixelSize(
                R.dimen.recent_thumbnail_bottom_padding) * mScaleFactor);

        mDefaultThumbnailBackground = new ColorDrawableWithDimensions(
                res.getColor(R.color.card_backgroundExpand), mThumbnailWidth, mThumbnailHeight);
    }

    @Override
    public void setupInnerViewElements(ViewGroup parent, View view) {
        if (view == null || mPersistentTaskId == -1) {
            return;
        }

        // We use here a view holder to reduce expensive findViewById calls
        // when getView is called on the arrayadapter which calls setupInnerViewElements.
        // Simply just check if the given view was already tagged. If yes we know it has
        // the thumbnailView we want to have. If not we search it, give it to the viewholder
        // and tag the view for the next call to reuse the holded information later.
        ViewHolder holder;
        holder = (ViewHolder) view.getTag();

        if (holder == null) {
            holder = new ViewHolder();
            holder.thumbnailView = (RecentImageView) view.findViewById(R.id.thumbnail);
            // Take scale factor into account if it is different then default or it has changed.
            if (mScaleFactor != RecentController.DEFAULT_SCALE_FACTOR || mScaleFactorChanged) {
                mScaleFactorChanged = false;
                final ViewGroup.MarginLayoutParams layoutParams =
                        (ViewGroup.MarginLayoutParams) holder.thumbnailView.getLayoutParams();
                layoutParams.width = mThumbnailWidth;
                layoutParams.height = mThumbnailHeight;
                layoutParams.setMargins(0, 0, 0, mBottomPadding);
                holder.thumbnailView.setLayoutParams(layoutParams);
            }
            view.setTag(holder);
        }

        // Assign task bitmap to our view via async task loader. If it is just
        // a refresh of the view do not load it again
        // and use the allready present one from the LRU Cache.
        if (mTask == null || mReload) {
            if (!mDoNotNullBitmap) {
                holder.thumbnailView.setImageDrawable(mDefaultThumbnailBackground);
            }

            mReload = false;
            mDoNotNullBitmap = false;

            mTask = new BitmapDownloaderTask(holder.thumbnailView, mContext, mScaleFactor);
            mTask.executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, mPersistentTaskId);
        } else {
            if (mTask.isLoaded()) {
                // We may have lost our thumbnail in our cache.
                // Check for it. If it is not present reload it again.
                Bitmap bitmap = CacheController.getInstance(mContext)
                        .getBitmapFromMemCache(String.valueOf(mPersistentTaskId));

                if (bitmap == null) {
                    mTask = new BitmapDownloaderTask(holder.thumbnailView, mContext, mScaleFactor);
                    mTask.executeOnExecutor(
                            AsyncTask.THREAD_POOL_EXECUTOR, mPersistentTaskId);
                } else {
                    holder.thumbnailView.setImageBitmap(CacheController.getInstance(mContext)
                            .getBitmapFromMemCache(String.valueOf(mPersistentTaskId)));
                }
            }
        }

        // set custom background
        if (cardColor != 0x00ffffff) {
            parent.setBackgroundColor(cardColor);
        } else {
            parent.setBackgroundColor(getDefaultCardColorBg());
        }
    }

    static class ViewHolder {
        RecentImageView thumbnailView;
    }

    // Loads the actual task bitmap.
    private static Bitmap loadThumbnail(int persistentTaskId, Context context, float scaleFactor) {
        if (context == null) {
            return null;
        }
        final ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        return getResizedBitmap(getThumbnail(am, persistentTaskId), context, scaleFactor);
    }

    /**
     * Returns a task thumbnail from the activity manager
     */
    public static Bitmap getThumbnail(ActivityManager activityManager, int taskId) {
        ActivityManager.TaskThumbnail taskThumbnail = activityManager.getTaskThumbnail(taskId);
        if (taskThumbnail == null) return null;

        Bitmap thumbnail = taskThumbnail.mainThumbnail;
        ParcelFileDescriptor descriptor = taskThumbnail.thumbnailFileDescriptor;
        if (thumbnail == null && descriptor != null) {
            thumbnail = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(),
                    null, sBitmapOptions);
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
            }
        }
        return thumbnail;
    }

    // Resize and crop the task bitmap to the overlay values.
    private static Bitmap getResizedBitmap(Bitmap source, Context context, float scaleFactor) {
        if (source == null) {
            return null;
        }

        final Resources res = context.getResources();
        final int thumbnailWidth =
                (int) (res.getDimensionPixelSize(
                        R.dimen.recent_thumbnail_width) * scaleFactor);
        final int thumbnailHeight =
                (int) (res.getDimensionPixelSize(
                        R.dimen.recent_thumbnail_height) * scaleFactor);
        final float INITIAL_SCALE = 0.75f;
        int h = source.getHeight();
        int w = source.getWidth();
        Bitmap cropped = null;

        int mode = currentHandsMode(context);
        try {
            if (mode == 1) {
                cropped = Bitmap.createBitmap(source, 0, (int)(h * (1-INITIAL_SCALE)),
                        (int)(w * INITIAL_SCALE), (int)(h * INITIAL_SCALE));
                source.recycle();
                source = null;
            } else if (mode == 2) {
                cropped = Bitmap.createBitmap(source, (int)(w * (1-INITIAL_SCALE)), (int)(h * (1-INITIAL_SCALE)),
                        (int)(w * INITIAL_SCALE), (int)(h * INITIAL_SCALE));
                source.recycle();
                source = null;
            }
        } catch (Exception e) {
            cropped = source;
            source.recycle();
            source = null;
        }

        final int sourceWidth = mode != 0 ? cropped.getWidth() : w;
        final int sourceHeight = mode != 0 ? cropped.getHeight() : h;

        // Compute the scaling factors to fit the new height and width, respectively.
        // To cover the final image, the final scaling will be the bigger
        // of these two.
        final float xScale = (float) thumbnailWidth / sourceWidth;
        final float yScale = (float) thumbnailHeight / sourceHeight;
        final float scale = Math.max(xScale, yScale);

        // Now get the size of the source bitmap when scaled
        final float scaledWidth = scale * sourceWidth;
        final float scaledHeight = scale * sourceHeight;

        // Let's find out the left coordinates if the scaled bitmap
        // should be centered in the new size given by the parameters
        final float left = (thumbnailWidth - scaledWidth) / 2;

        // The target rectangle for the new, scaled version of the source bitmap
        final RectF targetRect = new RectF(left, 0.0f, left + scaledWidth, scaledHeight);

        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(true);

        // Finally, we create a new bitmap of the specified size and draw our new,
        // scaled bitmap onto it.
        final Bitmap dest = Bitmap.createBitmap(thumbnailWidth, thumbnailHeight, Config.ARGB_8888);
        final Canvas canvas = new Canvas(dest);
        canvas.drawBitmap(mode != 0 ? cropped : source, null, targetRect, paint);

        return dest;
    }

    private static int currentHandsMode(Context context) {
        int mode;
        String str = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.SINGLE_HAND_MODE);
        if (str != null && str.contains("left")) {
            mode = 1;
        } else if (str != null && str.contains("right")) {
            mode = 2;
        } else {
            mode = 0;
        }
        return mode;
    }

    // AsyncTask loader for the task bitmap.
    private static class BitmapDownloaderTask extends AsyncTask<Integer, Void, Bitmap> {

        private boolean mLoaded;

        private final WeakReference<RecentImageView> rImageViewReference;
        private final WeakReference<Context> rContext;

        //private int mOrigPri;
        private float mScaleFactor;

        private String mLRUCacheKey;

        public BitmapDownloaderTask(RecentImageView imageView,
                Context context, float scaleFactor) {
            rImageViewReference = new WeakReference<RecentImageView>(imageView);
            rContext = new WeakReference<Context>(context);
            mScaleFactor = scaleFactor;
        }

        @Override
        protected Bitmap doInBackground(Integer... params) {
            mLoaded = false;
            mLRUCacheKey = null;
            // Save current thread priority and set it during the loading
            // to background priority.
            //mOrigPri = Process.getThreadPriority(Process.myTid());
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
            if (isCancelled() || rContext == null) {
                return null;
            }
            mLRUCacheKey = String.valueOf(params[0]);
            // Load and return bitmap
            return loadThumbnail(params[0], rContext.get(), mScaleFactor);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (isCancelled()) {
                bitmap = null;
            }
            // Restore original thread priority.
            //Process.setThreadPriority(mOrigPri);

            // Assign image to the view.
            if (rImageViewReference != null) {
                mLoaded = true;
                final RecentImageView imageView = rImageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    if (bitmap != null && rContext != null) {
                        final Context context = rContext.get();
                        if (context != null) {
                            // Put the loaded bitmap into the LRU cache for later use.
                            CacheController.getInstance(context)
                                    .addBitmapToMemoryCache(mLRUCacheKey, bitmap);
                        }
                    }
                }
            }
        }

        public boolean isLoaded() {
            return mLoaded;
        }
    }
}
