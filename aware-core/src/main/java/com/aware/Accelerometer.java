
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

import com.aware.providers.Accelerometer_Provider;
import com.aware.providers.Accelerometer_Provider.Accelerometer_Data;
import com.aware.providers.Accelerometer_Provider.Accelerometer_Sensor;
import com.aware.utils.Aware_Sensor;
import com.aware.utils.SensorDataBuffer;
import com.aware.utils.SensorTimeUnits;

/**
 * AWARE Accelerometer module
 * - Accelerometer raw data
 * - Accelerometer sensor information
 *
 * @author df
 */
public class Accelerometer extends Aware_Sensor implements SensorEventListener {

    public static String TAG = "AWARE::Accelerometer";

    private static SensorManager mSensorManager;
    private static Sensor mAccelerometer;

    private static HandlerThread sensorThread = null;
    private static Handler sensorHandler = null;
    private static PowerManager.WakeLock wakeLock = null;

    private static Float[] LAST_VALUES = null;
    private static long LAST_TS = 0;
    private static long LAST_SAVE = 0;

    private static int FREQUENCY = -1;
    private static double THRESHOLD = 0;
    private static boolean ENFORCE_FREQUENCY = false;

    public static final String ACTION_AWARE_ACCELEROMETER = "ACTION_AWARE_ACCELEROMETER";

    private SensorDataBuffer dataBuffer;

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        //We log current accuracy on the sensor changed event
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (SignificantMotion.isSignificantMotionActive && !SignificantMotion.CURRENT_SIGMOTION_STATE) {
            dataBuffer.flush(isDatabaseWriteSuppressed());
            return;
        }

        long TS = System.currentTimeMillis();
        if (ENFORCE_FREQUENCY && TS < LAST_TS + FREQUENCY / 1000)
            return;
        if (LAST_VALUES != null && THRESHOLD > 0 && Math.abs(event.values[0] - LAST_VALUES[0]) < THRESHOLD
                && Math.abs(event.values[1] - LAST_VALUES[1]) < THRESHOLD
                && Math.abs(event.values[2] - LAST_VALUES[2]) < THRESHOLD) {
            return;
        }

        LAST_VALUES = new Float[]{event.values[0], event.values[1], event.values[2]};

        ContentValues rowData = new ContentValues();
        rowData.put(Accelerometer_Data.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
        rowData.put(Accelerometer_Data.TIMESTAMP, TS);
        rowData.put(Accelerometer_Data.VALUES_0, event.values[0]);
        rowData.put(Accelerometer_Data.VALUES_1, event.values[1]);
        rowData.put(Accelerometer_Data.VALUES_2, event.values[2]);
        rowData.put(Accelerometer_Data.ACCURACY, event.accuracy);

        if (awareSensor != null) awareSensor.onAccelerometerChanged(rowData);

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

    private static AWARESensorObserver awareSensor;
    public static void setSensorObserver(AWARESensorObserver observer) {
        awareSensor = observer;
    }
    public static AWARESensorObserver getSensorObserver() {
        return awareSensor;
    }

    public interface AWARESensorObserver {
        void onAccelerometerChanged(ContentValues data);
    }

    /**
     * Calculates the sampling rate in Hz (i.e., how many samples did we collect in the past second)
     *
     * @param context
     * @return hz
     */
    public static int getFrequency(Context context) {
        int hz = 0;
        String[] columns = new String[]{"count(*) as frequency", "datetime(" + Accelerometer_Data.TIMESTAMP + "/1000, 'unixepoch','localtime') as sample_time"};
        Cursor qry = context.getContentResolver().query(Accelerometer_Data.CONTENT_URI, columns, "1) group by (sample_time", null, "sample_time DESC LIMIT 1 OFFSET 2");
        if (qry != null && qry.moveToFirst()) {
            hz = qry.getInt(0);
        }
        if (qry != null && !qry.isClosed()) qry.close();
        return hz;
    }

    private void saveAccelerometerDevice(Sensor acc) {
        if (acc == null) return;

        Cursor accelInfo = getContentResolver().query(Accelerometer_Sensor.CONTENT_URI, null, null, null, null);
        if (accelInfo == null || !accelInfo.moveToFirst()) {
            ContentValues rowData = new ContentValues();
            rowData.put(Accelerometer_Sensor.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
            rowData.put(Accelerometer_Sensor.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Accelerometer_Sensor.MAXIMUM_RANGE, acc.getMaximumRange());
            rowData.put(Accelerometer_Sensor.MINIMUM_DELAY, acc.getMinDelay());
            rowData.put(Accelerometer_Sensor.NAME, acc.getName());
            rowData.put(Accelerometer_Sensor.POWER_MA, acc.getPower());
            rowData.put(Accelerometer_Sensor.RESOLUTION, acc.getResolution());
            rowData.put(Accelerometer_Sensor.TYPE, acc.getType());
            rowData.put(Accelerometer_Sensor.VENDOR, acc.getVendor());
            rowData.put(Accelerometer_Sensor.VERSION, acc.getVersion());

            getContentResolver().insert(Accelerometer_Sensor.CONTENT_URI, rowData);

            if (Aware.DEBUG) Log.d(TAG, "Accelerometer device:" + rowData.toString());
        }
        if (accelInfo != null && !accelInfo.isClosed()) accelInfo.close();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = Accelerometer_Provider.getAuthority(this);
        dataBuffer = new SensorDataBuffer(this, Accelerometer_Data.CONTENT_URI, ACTION_AWARE_ACCELEROMETER, TAG);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        sensorThread = new HandlerThread(TAG);
        sensorThread.start();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        sensorHandler = new Handler(sensorThread.getLooper());

        if (Aware.DEBUG) Log.d(TAG, "Accelerometer service created!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        sensorHandler.removeCallbacksAndMessages(null);
        mSensorManager.unregisterListener(this, mAccelerometer);
        sensorThread.quit();
        dataBuffer.close(isDatabaseWriteSuppressed());
        wakeLock.release();

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Accelerometer_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                Accelerometer_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (Aware.DEBUG) Log.d(TAG, "Accelerometer service terminated...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {
            if (mAccelerometer == null) {
                if (Aware.DEBUG) Log.w(TAG, "This device does not have an accelerometer!");
                Aware.setSetting(this, Aware_Preferences.STATUS_ACCELEROMETER, false);
                stopSelf();
            } else {
                DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");
                Aware.setSetting(this, Aware_Preferences.STATUS_ACCELEROMETER, true);
                saveAccelerometerDevice(mAccelerometer);

                if (Aware.getSetting(this, Aware_Preferences.FREQUENCY_ACCELEROMETER).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.FREQUENCY_ACCELEROMETER, 20000);
                }

                if (Aware.getSetting(this, Aware_Preferences.THRESHOLD_ACCELEROMETER).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.THRESHOLD_ACCELEROMETER, 0.0);
                }

                int new_frequency = Aware.getSettingAsInt(getApplicationContext(), Aware_Preferences.FREQUENCY_ACCELEROMETER, 20000);
                double new_threshold = Aware.getSettingAsDouble(getApplicationContext(), Aware_Preferences.THRESHOLD_ACCELEROMETER, 0.0);
                boolean new_enforce_frequency = (Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_ACCELEROMETER_ENFORCE).equals("true")
                        || Aware.getSetting(getApplicationContext(), Aware_Preferences.ENFORCE_FREQUENCY_ALL).equals("true"));

                if (FREQUENCY != new_frequency
                        || THRESHOLD != new_threshold
                        || ENFORCE_FREQUENCY != new_enforce_frequency) {

                    sensorHandler.removeCallbacksAndMessages(null);
                    mSensorManager.unregisterListener(this, mAccelerometer);

                    FREQUENCY = new_frequency;
                    THRESHOLD = new_threshold;
                    ENFORCE_FREQUENCY = new_enforce_frequency;
                }

                mSensorManager.registerListener(this, mAccelerometer, SensorTimeUnits.samplingPeriodUs(new_frequency), sensorHandler);
                LAST_SAVE = System.currentTimeMillis();

                if (Aware.DEBUG) Log.d(TAG, "Accelerometer service active: " + FREQUENCY + " ms");

                if (Aware.isStudy(this)) {
                    ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), Accelerometer_Provider.getAuthority(this), 1);
                    ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Accelerometer_Provider.getAuthority(this), true);
                    long frequency = Aware.getSettingAsLong(this, Aware_Preferences.FREQUENCY_WEBSERVICE, 30) * 60;
                    SyncRequest request = new SyncRequest.Builder()
                            .syncPeriodic(frequency, frequency/3)
                            .setSyncAdapter(Aware.getAWAREAccount(this), Accelerometer_Provider.getAuthority(this))
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
