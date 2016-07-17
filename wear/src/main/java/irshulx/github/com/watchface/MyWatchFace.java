/*
 * Copyright (C) 2014 The Android Open Source Project
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

package irshulx.github.com.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private Bitmap mBackground;
    private Bitmap mH0;
    private Bitmap mM0;
    private Bitmap battery;
    private Resources resources;
    private boolean isRounded;
    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mRegisteredPowerConnectionReceiver=false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        Date mDate;
        private int mBatteryPercent=100;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
                mDate=new Date();
                String x="20";
            }
        };

        final BroadcastReceiver mPowerConnectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;

                int chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                boolean usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                boolean acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;


               mBatteryPercent = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            }
        };

        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            battery = BitmapFactory.decodeResource(resources, R.drawable.battery);
            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);

            if (mRegisteredPowerConnectionReceiver) {
                return;
            }
            mRegisteredPowerConnectionReceiver=true;
            IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
          MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
            MyWatchFace.this.registerReceiver(mPowerConnectionReceiver, batteryFilter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
            if(!mRegisteredPowerConnectionReceiver)
                return;
            mRegisteredPowerConnectionReceiver=false;
            MyWatchFace.this.unregisterReceiver(mPowerConnectionReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            isRounded = insets.isRound();
            mXOffset = resources.getDimension(isRounded
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRounded
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = MyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.colorNormal));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            mDate=new Date();
            String x= mDate.toString();
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            drawBackground(canvas, bounds);
            drawTime(canvas, mTime);
            if(!isRounded) {
                drawBattery(canvas, bounds);
            }
            drawDate(canvas, mDate);
        }

        private void drawBackground(Canvas canvas, Rect bounds) {
            mBackground = BitmapFactory.decodeResource(resources,mBatteryPercent<30? R.drawable.bg_alert:R.drawable.bg);
            canvas.drawBitmap(mBackground, 0, 0, null);
        }

        private void drawDate(Canvas canvas, Date mDate) {
            float top= (canvas.getHeight()/2)+20;
            float left = 29.0f;
            String ddd= new SimpleDateFormat("EEE").format(mDate).toLowerCase();
            for(int i=0 ;i<ddd.length();i++){
                String character= String.valueOf(ddd.charAt(i));
                canvas.drawBitmap(BitmapFactory.decodeResource(resources, getResId(pullResource(character,true))), left, top, null);
                left+=24;
            }
            left+=1;
            canvas.drawBitmap(BitmapFactory.decodeResource(resources,getResId(pullResource("comma",true))), left, top+10f, null);
            left+=10;

            String dd= new SimpleDateFormat("dd").format(mDate);
            for(int i=0 ;i<dd.length();i++){
                String character= String.valueOf(dd.charAt(i));
                canvas.drawBitmap(BitmapFactory.decodeResource(resources,getResId(pullResource(character,true))), left, top, null);
                left+=24;
            }
            left+=1;
            canvas.drawBitmap(BitmapFactory.decodeResource(resources, getResId(pullResource("period",true))), left, top + 10f, null);
            left+=10;
            String mm= new SimpleDateFormat("MM").format(mDate);
            for(int i=0 ;i<mm.length();i++){
                String character= String.valueOf(mm.charAt(i));
                canvas.drawBitmap(BitmapFactory.decodeResource(resources, getResId(pullResource(character,true))), left, top, null);
                left+=24;
            }
            left+=1;
            canvas.drawBitmap(BitmapFactory.decodeResource(resources,getResId(pullResource("period",true))), left, top + 10f, null);
            left+=10;
            String yyyy= new SimpleDateFormat("yy").format(mDate);
            for(int i=0 ;i<yyyy.length();i++){
                String character= String.valueOf(yyyy.charAt(i));
                canvas.drawBitmap(BitmapFactory.decodeResource(resources, getResId(pullResource(character,true))), left, top, null);
                left+=24;
            }
        }

        private int getResId(String name){
            int resID = getResources().getIdentifier(name, "drawable", getPackageName());
            return resID;
        }

        private String pullResource(String letter, boolean isResourceSmall){
           if(mBatteryPercent<30){
            return isResourceSmall?"ltr_sm_danger_"+letter:"ltr_bg_danger_"+letter;
           }else{
               return isResourceSmall?"ltr_sm_safe_"+letter:"ltr_bg_safe_"+letter;
           }
        }


        private void drawBattery(Canvas canvas, Rect bounds) {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            float left= bounds.width()-80;
            canvas.drawBitmap(battery,left,23f,null);
            mTextPaint.setTextSize(20f);
            canvas.drawText(String.valueOf(mBatteryPercent) + "%", left + 28, 43f, mTextPaint);
        }


        private void drawTime(Canvas canvas, Time mTime) {
            float height= canvas.getHeight();
            float offsetLeft= 5.0f;
            float left=isRounded?7.0f: 2.0f;
            String hh= String.valueOf(String.format("%02d",mTime.hour));
            for(int i=0 ;i<hh.length();i++){
                String character= String.valueOf(hh.charAt(i));
                mH0= BitmapFactory.decodeResource(resources, getResId(pullResource(character,false)));
                canvas.drawBitmap(mH0, left, height/3, null);
                left+=70;
            }
            canvas.drawBitmap(BitmapFactory.decodeResource(resources,getResId(pullResource("column",false))), offsetLeft+140, height/3, null);
            left+=30;
            String mm= String.valueOf(String.format("%02d",mTime.minute));
            for(int i=0 ;i<mm.length();i++){
                String character= String.valueOf(mm.charAt(i));
                mM0= BitmapFactory.decodeResource(resources, getResId(pullResource(character,false)));
                canvas.drawBitmap(mM0, left, height/3, null);
                left+=70;
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
