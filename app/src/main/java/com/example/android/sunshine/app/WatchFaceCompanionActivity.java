package com.example.android.sunshine.app;

import android.app.Activity;
import android.app.LoaderManager;
import android.content.ComponentName;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.wearable.companion.WatchFaceCompanion;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

public class WatchFaceCompanionActivity extends Activity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        ResultCallback<DataApi.DataItemResult>, LoaderManager.LoaderCallbacks<Cursor> {
    private static final String TAG = "WatchFaceCompanion";

    public static final String KEY_LOCATION = "LOCATION";
    public static final String KEY_DATETIME = "DATETIME";
    public static final String KEY_FORECAST = "FORECAST";
    public static final String KEY_MAXTEMP = "MAXTEMP";
    public static final String KEY_MINTEMP = "MINTEMP";

    private static final int FORECAST_LOADER = 1;

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.TABLE_NAME + "." + WeatherContract.WeatherEntry._ID,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    // These indices are tied to FORECAST_COLUMNS.  If FORECAST_COLUMNS changes, these must change.
    static final int COL_WEATHER_ID = 0;
    static final int COL_WEATHER_CONDITION_ID = 1;
    static final int COL_WEATHER_MAX_TEMP = 2;
    static final int COL_WEATHER_MIN_TEMP = 3;

    private GoogleApiClient mGoogleApiClient;
    private String mPeerId;
    private TextView mLocation;
    private TextView mDatetime;
    private Button mButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch_face_companion);

        mPeerId = getIntent().getStringExtra(WatchFaceCompanion.EXTRA_PEER_ID);
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        mLocation = (TextView) findViewById(R.id.location);
        mDatetime = (TextView) findViewById(R.id.datetime);
        mButton = (Button) findViewById(R.id.refresh);

        ComponentName name = getIntent().getParcelableExtra(
                WatchFaceCompanion.EXTRA_WATCH_FACE_COMPONENT);
        TextView label = (TextView) findViewById(R.id.component);
        if (name != null) {
            label.setText(name.getClassName());
        }

        getLoaderManager().initLoader(FORECAST_LOADER, null, this); // for testing
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onStop();
    }

    @Override // GoogleApiClient.ConnectionCallbacks
    public void onConnected(Bundle connectionHint) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnected: " + connectionHint);
        }

        if (mPeerId != null) {
            Uri.Builder builder = new Uri.Builder();
            Uri uri = builder.scheme("wear").path(Utility.PATH_WITH_FEATURE).authority(mPeerId).build();
            Wearable.DataApi.getDataItem(mGoogleApiClient, uri).setResultCallback(this);
        } else {
            displayNoConnectedDeviceDialog();
        }
    }

    @Override // ResultCallback<DataApi.DataItemResult>
    public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
        if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
            DataItem configDataItem = dataItemResult.getDataItem();
            DataMapItem dataMapItem = DataMapItem.fromDataItem(configDataItem);
            DataMap config = dataMapItem.getDataMap();
            setUpTextViews(config);
        } else {
            setUpTextViews(null);
        }
        getLoaderManager().initLoader(FORECAST_LOADER, null, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        String locationSetting = Utility.getPreferredLocation(this);
        String locationString = mLocation.getText().toString();
        if (!locationString.equals("") && !locationString.equals(locationSetting)) {
            locationSetting= locationString;
        }
        long date = System.currentTimeMillis();

        Uri uri = WeatherContract.WeatherEntry.
                buildWeatherLocationWithDate(locationSetting, date);

        mLocation.setText(locationSetting);
        mDatetime.setText(String.valueOf(date));

        return new CursorLoader(this, uri, FORECAST_COLUMNS, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (data.moveToNext()) {
            Log.d(TAG, "onLoadFinished: " + data.getInt(COL_WEATHER_ID));

            DataMap dataMap = new DataMap();
            dataMap.putString(KEY_LOCATION, mLocation.getText().toString());
            dataMap.putLong(KEY_DATETIME, Long.parseLong(mDatetime.getText().toString()));
            dataMap.putInt(KEY_FORECAST, data.getInt(COL_WEATHER_CONDITION_ID));
            dataMap.putFloat(KEY_MAXTEMP, data.getFloat(COL_WEATHER_MAX_TEMP));
            dataMap.putFloat(KEY_MINTEMP, data.getFloat(COL_WEATHER_MIN_TEMP));

            Utility.putConfigDataItem(mGoogleApiClient, dataMap);

            ((TextView) findViewById(R.id.forecast)).setText(String.valueOf(data.getInt(COL_WEATHER_CONDITION_ID)));
            ((TextView) findViewById(R.id.maxtemp)).setText(String.valueOf(data.getFloat(COL_WEATHER_MAX_TEMP)));
            ((TextView) findViewById(R.id.mintemp)).setText(String.valueOf(data.getFloat(COL_WEATHER_MIN_TEMP)));
        }
        mButton.setEnabled(true);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onLoaderReset: ");
        }
    }

    @Override // GoogleApiClient.ConnectionCallbacks
    public void onConnectionSuspended(int cause) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnectionSuspended: " + cause);
        }
    }

    @Override // GoogleApiClient.OnConnectionFailedListener
    public void onConnectionFailed(@NonNull ConnectionResult result) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnectionFailed: " + result);
        }
    }

    public void refresh(View view) {
        mButton.setEnabled(false);
        getLoaderManager().restartLoader(FORECAST_LOADER, null, this);
    }

    private void setUpTextViews(DataMap config) {
        setUpTextView(R.id.location, KEY_LOCATION, config, Utility.getPreferredLocation(this));
        setUpTextView(R.id.datetime, KEY_DATETIME, config, "");
        setUpTextView(R.id.forecast, KEY_FORECAST, config, "");
        setUpTextView(R.id.maxtemp, KEY_MAXTEMP, config, "");
        setUpTextView(R.id.mintemp, KEY_MINTEMP, config, "");
    }

    private void setUpTextView(int id, final String configKey, DataMap config, String defaultText) {
        TextView textview = (TextView) findViewById(id);
        if (config != null) {
            textview.setText(config.getString(configKey, defaultText));
        } else {
            textview.setText(defaultText);
        }
    }

    private void displayNoConnectedDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String messageText = getResources().getString(R.string.title_no_device_connected);
        String okText = getResources().getString(R.string.ok_no_device_connected);
        builder.setMessage(messageText)
                .setCancelable(false)
                .setPositiveButton(okText, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) { }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }
}
