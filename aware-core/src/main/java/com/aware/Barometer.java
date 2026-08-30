
package com.aware;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncRequest;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.aware.providers.Barometer_Provider;
import com.aware.providers.Barometer_Provider.Barometer_Data;
import com.aware.providers.Barometer_Provider.Barometer_Sensor;
import com.aware.utils.Aware_Sensor;
import com.aware.utils.SensorDataBuffer;
import com.aware.utils.SensorTimeUnits;


/**
 * AWARE Barometer module
 * - Ambient pressure raw data, in mbar
 * - Ambient pressure sensor information
 *
 * @author df
 */
public class Barometer extends Aware_Sensor implements SensorEventListener {

    public static String TAG = "AWARE::Barometer";

    private static SensorManager mSensorManager;
    private static Sensor mPressure;
    private static HandlerThread sensorThread = null;
    private static Handler sensorHandler = null;

    private static PowerManager.WakeLock wakeLock = null;

    private static Float LAST_VALUE = null;
    private static long LAST_TS = 0;
    private static long LAST_SAVE = 0;

    private static int FREQUENCY = -1;
    private static double THRESHOLD = 0;
    // Reject any data points that come in more often than frequency
    private static boolean ENFORCE_FREQUENCY = false;

    public static final String ACTION_AWARE_BAROMETER = "ACTION_AWARE_BAROMETER";

    private SensorDataBuffer dataBuffer;

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        //We log current accuracy on the sensor changed event
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long TS = System.currentTimeMillis();
        if (ENFORCE_FREQUENCY && TS < LAST_TS + FREQUENCY / 1000)
            return;
        if (LAST_VALUE != null && THRESHOLD > 0 && Math.abs(event.values[0] - LAST_VALUE) < THRESHOLD) {
            return;
        }

        LAST_VALUE = event.values[0];

        // Proceed with saving as usual.
        ContentValues rowData = new ContentValues();
        rowData.put(Barometer_Data.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
        rowData.put(Barometer_Data.TIMESTAMP, TS);
        rowData.put(Barometer_Data.AMBIENT_PRESSURE, event.values[0]);
        rowData.put(Barometer_Data.ACCURACY, event.accuracy);

        if (awareSensor != null) awareSensor.onBarometerChanged(rowData);

        boolean buffered = dataBuffer.add(rowData);
        LAST_TS = TS;
        if (!buffered) return;

        if (dataBuffer.size() < SensorDataBuffer.BATCH_SIZE && TS < LAST_SAVE + 300000) {
            return;
        }

        if (dataBuffer.flush(isDatabaseWriteSuppressed())) {
            LAST_SAVE = TS;
        }
    }

    private boolean isDatabaseWriteSuppressed() {
        return Aware.getSetting(getApplicationContext(), Aware_Preferences.DEBUG_DB_SLOW).equals("true");
    }

    private static Barometer.AWARESensorObserver awareSensor;

    public static void setSensorObserver(Barometer.AWARESensorObserver observer) {
        awareSensor = observer;
    }

    public static Barometer.AWARESensorObserver getSensorObserver() {
        return awareSensor;
    }

    public interface AWARESensorObserver {
        void onBarometerChanged(ContentValues data);
    }

    /**
     * Calculates the sampling rate in Hz (i.e., how many samples did we collect in the past second)
     *
     * @param context
     * @return hz
     */
    public static int getFrequency(Context context) {
        int hz = 0;
        String[] columns = new String[]{"count(*) as frequency", "datetime(" + Barometer_Data.TIMESTAMP + "/1000, 'unixepoch','localtime') as sample_time"};
        Cursor qry = context.getContentResolver().query(Barometer_Data.CONTENT_URI, columns, "1) group by (sample_time", null, "sample_time DESC LIMIT 1 OFFSET 2");
        if (qry != null && qry.moveToFirst()) {
            hz = qry.getInt(0);
        }
        if (qry != null && !qry.isClosed()) qry.close();
        return hz;
    }

    private void saveSensorDevice(Sensor sensor) {
        if (sensor == null) return;

        Cursor sensorInfo = getContentResolver().query(Barometer_Sensor.CONTENT_URI, null, null, null, null);
        if (sensorInfo == null || !sensorInfo.moveToFirst()) {
            ContentValues rowData = new ContentValues();
            rowData.put(Barometer_Sensor.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
            rowData.put(Barometer_Sensor.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Barometer_Sensor.MAXIMUM_RANGE, sensor.getMaximumRange());
            rowData.put(Barometer_Sensor.MINIMUM_DELAY, sensor.getMinDelay());
            rowData.put(Barometer_Sensor.NAME, sensor.getName());
            rowData.put(Barometer_Sensor.POWER_MA, sensor.getPower());
            rowData.put(Barometer_Sensor.RESOLUTION, sensor.getResolution());
            rowData.put(Barometer_Sensor.TYPE, sensor.getType());
            rowData.put(Barometer_Sensor.VENDOR, sensor.getVendor());
            rowData.put(Barometer_Sensor.VERSION, sensor.getVersion());

            getContentResolver().insert(Barometer_Sensor.CONTENT_URI, rowData);

            if (Aware.DEBUG) Log.d(TAG, "Barometer sensor info: " + rowData.toString());
        }
        if (sensorInfo != null && !sensorInfo.isClosed()) sensorInfo.close();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = Barometer_Provider.getAuthority(this);
        dataBuffer = new SensorDataBuffer(this, Barometer_Data.CONTENT_URI, ACTION_AWARE_BAROMETER, TAG);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        mPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        sensorThread = new HandlerThread(TAG);
        sensorThread.start();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        sensorHandler = new Handler(sensorThread.getLooper());

        if (Aware.DEBUG) Log.d(TAG, "Barometer service created!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        sensorHandler.removeCallbacksAndMessages(null);
        mSensorManager.unregisterListener(this, mPressure);
        sensorThread.quit();
        dataBuffer.close(isDatabaseWriteSuppressed());

        wakeLock.release();

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Barometer_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                Barometer_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (Aware.DEBUG) Log.d(TAG, "Barometer service terminated...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {
            if (mPressure == null) {
                if (Aware.DEBUG) Log.w(TAG, "This device does not have a barometer sensor!");
                Aware.setSetting(this, Aware_Preferences.STATUS_BAROMETER, false);
                stopSelf();
            } else {
                DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

                Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_BAROMETER, true);
                saveSensorDevice(mPressure);

                if (Aware.getSetting(this, Aware_Preferences.FREQUENCY_BAROMETER).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.FREQUENCY_BAROMETER, 1000000);
                }

                if (Aware.getSetting(this, Aware_Preferences.THRESHOLD_BAROMETER).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.THRESHOLD_BAROMETER, 0.0);
                }

                int new_frequency = Aware.getSettingAsInt(getApplicationContext(), Aware_Preferences.FREQUENCY_BAROMETER, 1000000);
                double new_threshold = Aware.getSettingAsDouble(getApplicationContext(), Aware_Preferences.THRESHOLD_BAROMETER, 0.0);
                boolean new_enforce_frequency = (Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_BAROMETER_ENFORCE).equals("true")
                        || Aware.getSetting(getApplicationContext(), Aware_Preferences.ENFORCE_FREQUENCY_ALL).equals("true"));

                if (FREQUENCY != new_frequency
                        || THRESHOLD != new_threshold
                        || ENFORCE_FREQUENCY != new_enforce_frequency) {

                    sensorHandler.removeCallbacksAndMessages(null);
                    mSensorManager.unregisterListener(this, mPressure);

                    FREQUENCY = new_frequency;
                    THRESHOLD = new_threshold;
                    ENFORCE_FREQUENCY = new_enforce_frequency;
                }

                mSensorManager.registerListener(this, mPressure, SensorTimeUnits.samplingPeriodUs(new_frequency), sensorHandler);
                LAST_SAVE = System.currentTimeMillis();

                if (Aware.DEBUG) Log.d(TAG, "Barometer service active: " + FREQUENCY + "ms");

                if (Aware.isStudy(this)) {
                    ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), Barometer_Provider.getAuthority(this), 1);
                    ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Barometer_Provider.getAuthority(this), true);
                    long frequency = Aware.getSettingAsLong(this, Aware_Preferences.FREQUENCY_WEBSERVICE, 30) * 60;
                    SyncRequest request = new SyncRequest.Builder()
                            .syncPeriodic(frequency, frequency / 3)
                            .setSyncAdapter(Aware.getAWAREAccount(this), Barometer_Provider.getAuthority(this))
                            .setExtras(new Bundle()).build();
                    ContentResolver.requestSync(request);
                }
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
