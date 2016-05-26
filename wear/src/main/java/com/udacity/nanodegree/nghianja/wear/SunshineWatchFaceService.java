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

package com.udacity.nanodegree.nghianja.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "CanvasWatchFaceService";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final String[] FORECAST_COLUMNS = {
            WearableWeatherContract.WeatherEntry.TABLE_NAME + "." + WearableWeatherContract.WeatherEntry._ID,
            WearableWeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WearableWeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WearableWeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    // These indices are tied to FORECAST_COLUMNS.  If FORECAST_COLUMNS changes, these must change.
    private static final int COL_WEATHER_ID = 0;
    private static final int COL_WEATHER_CONDITION_ID = 1;
    private static final int COL_WEATHER_MAX_TEMP = 2;
    private static final int COL_WEATHER_MIN_TEMP = 3;

    private static final String KEY_LOCATION = "LOCATION";
    private static final String KEY_DATETIME = "DATETIME";
    private static final String KEY_FORECAST = "FORECAST";
    private static final String KEY_MAXTEMP = "MAXTEMP";
    private static final String KEY_MINTEMP = "MINTEMP";

    private static int getWeatherIcon(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }
        return -1;
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Bitmap mIconBitmap;
        Paint mBackgroundPaint;
        Paint mHandPaint;
        Paint mSecondHandPaint;
        Paint mHighTextPaint;
        Paint mLowTextPaint;
        boolean mAmbient;
        GregorianCalendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar.setTimeZone(TimeZone.getTimeZone(tz));
            }
        };
        int mTapCount;
        String mLocationSetting;
        String mFormat;
        float mHighTemp;
        float mLowTemp;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        AsyncTask<String, Void, Cursor> mLoadWeatherTask;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.background_light));
            mIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear);

            mHandPaint = new Paint();
            mHandPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.analog_hands));
            mHandPaint.setStrokeWidth(SunshineWatchFaceService.this.getResources().getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mSecondHandPaint = new Paint();
            mSecondHandPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.second_hand));
            mSecondHandPaint.setStrokeWidth(SunshineWatchFaceService.this.getResources().getDimension(R.dimen.second_hand_stroke));
            mSecondHandPaint.setAntiAlias(true);
            mSecondHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mHighTextPaint = new Paint();
            mHighTextPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.white));
            mHighTextPaint.setTextAlign(Paint.Align.CENTER);
            mHighTextPaint.setTextSize(SunshineWatchFaceService.this.getResources().getDimensionPixelSize(R.dimen.temperature_text_size));
            mHighTextPaint.setAntiAlias(true);
            mHighTextPaint.setElegantTextHeight(true);

            mLowTextPaint = new Paint();
            mLowTextPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.grey));
            mLowTextPaint.setTextAlign(Paint.Align.CENTER);
            mLowTextPaint.setTextSize(SunshineWatchFaceService.this.getResources().getDimensionPixelSize(R.dimen.temperature_text_size));
            mLowTextPaint.setAntiAlias(true);
            mLowTextPaint.setElegantTextHeight(true);

            mCalendar = new GregorianCalendar();
            mLocationSetting = getResources().getString(R.string.pref_location_default);
            mFormat = SunshineWatchFaceService.this.getString(R.string.format_temperature);
            mHighTemp = 25f;
            mLowTemp = 16f;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
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
                    mHandPaint.setAntiAlias(!inAmbientMode);
                    mSecondHandPaint.setAntiAlias(inAmbientMode);
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
                    mBackgroundPaint.setColor(
                            ContextCompat.getColor(SunshineWatchFaceService.this,
                                    mTapCount % 2 == 0 ? R.color.background_light : R.color.background_dark));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Update the time
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                float canvasX4 = canvas.getWidth() / 4f;
                float canvasY2 = canvas.getHeight() / 2f;
                float iconX2 = mIconBitmap.getWidth() / 2f;
                float iconY2 = mIconBitmap.getHeight() / 2f;
                String high = String.format(mFormat, mHighTemp);
                String low = String.format(mFormat, mLowTemp);

                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
                canvas.drawBitmap(mIconBitmap, canvasX4 - iconX2, canvasY2 - iconY2, null);
                canvas.drawText(high, 0, high.length(), canvas.getWidth() - canvasX4, canvasY2 - iconY2, mHighTextPaint);
                canvas.drawText(low, 0, low.length(), canvas.getWidth() - canvasX4, canvasY2 + mIconBitmap.getHeight(), mLowTextPaint);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float secRot = mCalendar.get(Calendar.SECOND) / 30f * (float) Math.PI;
            int minutes = mCalendar.get(Calendar.MINUTE);
            float minRot = minutes / 30f * (float) Math.PI;
            float hrRot = ((mCalendar.get(Calendar.HOUR) + (minutes / 60f)) / 6f) * (float) Math.PI;

            float secLength = centerX - 20;
            float minLength = centerX - 40;
            float hrLength = centerX - 80;

            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mSecondHandPaint);
            }

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            canvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandPaint);

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            canvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHandPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mCalendar.setTimeInMillis(System.currentTimeMillis());
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

                cancelLoadWeatherTask();
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
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
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

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onDataChanged: " + dataEvents);
            }
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        SunshineWatchFaceUtility.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                updateWatchFace(config);
            }
        }

        private void updateWatchFace(final DataMap config) {
            boolean uiUpdated = false;
            if (config.containsKey(KEY_FORECAST) && config.getInt(KEY_FORECAST) != 0) {
                mIconBitmap = BitmapFactory.decodeResource(getResources(),
                        getWeatherIcon(config.getInt(KEY_FORECAST)));
                uiUpdated = true;
            }
            if (config.containsKey(KEY_MAXTEMP)) {
                mHighTemp = config.getFloat(KEY_MAXTEMP);
                uiUpdated = true;
            }
            if (config.containsKey(KEY_MINTEMP)) {
                mLowTemp = config.getFloat(KEY_MINTEMP);
                uiUpdated = true;
            }
            if (uiUpdated) {
                invalidate();
            }
        }

        private void updateWatchFaceOnStartUp() {
            SunshineWatchFaceUtility.fetchConfigDataMap(mGoogleApiClient,
                    new SunshineWatchFaceUtility.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            updateWatchFace(startupConfig);
                        }
                    }
            );
        }

        private void onWeatherLoaded(Cursor cursor) {
            if (cursor.moveToNext()) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onWeatherLoad: " + cursor.getInt(COL_WEATHER_ID));
                }
                mIconBitmap = BitmapFactory.decodeResource(getResources(),
                        getWeatherIcon(cursor.getInt(COL_WEATHER_CONDITION_ID)));
                mHighTemp = cursor.getFloat(COL_WEATHER_MAX_TEMP);
                mLowTemp = cursor.getFloat(COL_WEATHER_MIN_TEMP);
                invalidate();
            }
        }

        private void cancelLoadWeatherTask() {
            if (mLoadWeatherTask != null) {
                mLoadWeatherTask.cancel(true);
            }
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateWatchFaceOnStartUp();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(@NonNull ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }
    }
}
