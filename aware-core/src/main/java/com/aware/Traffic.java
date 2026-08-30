
package com.aware;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SyncRequest;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.aware.providers.Traffic_Provider;
import com.aware.providers.Traffic_Provider.Traffic_Data;
import com.aware.utils.Aware_Sensor;
import com.aware.utils.SensorTimeUnits;

/**
 * Service that logs I/O traffic from WiFi & mobile network
 *
 * @author denzil
 */
public class Traffic extends Aware_Sensor {

    /**
     * Logging tag (default = "AWARE::Network traffic")
     */
    public static String TAG = "AWARE::Network traffic";

    /**
     * Broadcasted event: updated traffic information is available
     */
    public static final String ACTION_AWARE_NETWORK_TRAFFIC = "ACTION_AWARE_NETWORK_TRAFFIC";

    public static final int NETWORK_TYPE_MOBILE = 1;
    public static final int NETWORK_TYPE_WIFI = 2;

    private final Handler mHandler = new Handler();
    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            long currentMobileRxBytes = supportedCounter(TrafficStats.getMobileRxBytes());
            long currentMobileRxPackets = supportedCounter(TrafficStats.getMobileRxPackets());
            long currentMobileTxBytes = supportedCounter(TrafficStats.getMobileTxBytes());
            long currentMobileTxPackets = supportedCounter(TrafficStats.getMobileTxPackets());

            long currentWifiRxBytes = nonMobileCounter(
                    TrafficStats.getTotalRxBytes(), TrafficStats.getMobileRxBytes());
            long currentWifiRxPackets = nonMobileCounter(
                    TrafficStats.getTotalRxPackets(), TrafficStats.getMobileRxPackets());
            long currentWifiTxBytes = nonMobileCounter(
                    TrafficStats.getTotalTxBytes(), TrafficStats.getMobileTxBytes());
            long currentWifiTxPackets = nonMobileCounter(
                    TrafficStats.getTotalTxPackets(), TrafficStats.getMobileTxPackets());

            long d_mobileRxBytes = counterDelta(currentMobileRxBytes, mobileRxBytes);
            long d_mobileRxPackets = counterDelta(currentMobileRxPackets, mobileRxPackets);
            long d_mobileTxBytes = counterDelta(currentMobileTxBytes, mobileTxBytes);
            long d_mobileTxPackets = counterDelta(currentMobileTxPackets, mobileTxPackets);

            long d_wifiRxBytes = counterDelta(currentWifiRxBytes, wifiRxBytes);
            long d_wifiRxPackets = counterDelta(currentWifiRxPackets, wifiRxPackets);
            long d_wifiTxBytes = counterDelta(currentWifiTxBytes, wifiTxBytes);
            long d_wifiTxPackets = counterDelta(currentWifiTxPackets, wifiTxPackets);

            ContentValues wifi = new ContentValues();
            wifi.put(Traffic_Data.TIMESTAMP, System.currentTimeMillis());
            wifi.put(Traffic_Data.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
            wifi.put(Traffic_Data.NETWORK_TYPE, NETWORK_TYPE_WIFI);
            wifi.put(Traffic_Data.RECEIVED_BYTES, d_wifiRxBytes);
            wifi.put(Traffic_Data.SENT_BYTES, d_wifiTxBytes);
            wifi.put(Traffic_Data.RECEIVED_PACKETS, d_wifiRxPackets);
            wifi.put(Traffic_Data.SENT_PACKETS, d_wifiTxPackets);
            getContentResolver().insert(Traffic_Data.CONTENT_URI, wifi);

            if (awareSensor != null) awareSensor.onWiFiTraffic(wifi);

            if (Aware.DEBUG) Log.d(TAG, "Wifi:" + wifi.toString());

            ContentValues network = new ContentValues();
            network.put(Traffic_Data.TIMESTAMP, System.currentTimeMillis());
            network.put(Traffic_Data.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
            network.put(Traffic_Data.NETWORK_TYPE, NETWORK_TYPE_MOBILE);
            network.put(Traffic_Data.RECEIVED_BYTES, d_mobileRxBytes);
            network.put(Traffic_Data.SENT_BYTES, d_mobileTxBytes);
            network.put(Traffic_Data.RECEIVED_PACKETS, d_mobileRxPackets);
            network.put(Traffic_Data.SENT_PACKETS, d_mobileTxPackets);
            getContentResolver().insert(Traffic_Data.CONTENT_URI, network);

            if (awareSensor != null) awareSensor.onNetworkTraffic(network);
            if (Aware.DEBUG) Log.d(TAG, "Network: " + network.toString());

            Intent traffic = new Intent(ACTION_AWARE_NETWORK_TRAFFIC);
            sendBroadcast(traffic);

            if (awareSensor != null
                    && d_mobileRxBytes == 0 && d_mobileRxPackets == 0
                    && d_mobileTxBytes == 0 && d_mobileTxPackets == 0
                    && d_wifiRxBytes == 0 && d_wifiRxPackets == 0
                    && d_wifiTxBytes == 0 && d_wifiTxPackets == 0) {
                awareSensor.onIdleTraffic();
            }

            mobileRxBytes = currentMobileRxBytes;
            mobileRxPackets = currentMobileRxPackets;
            mobileTxBytes = currentMobileTxBytes;
            mobileTxPackets = currentMobileTxPackets;
            wifiRxBytes = currentWifiRxBytes;
            wifiTxBytes = currentWifiTxBytes;
            wifiRxPackets = currentWifiRxPackets;
            wifiTxPackets = currentWifiTxPackets;

            mHandler.postDelayed(this, getSamplingIntervalMillis());
        }
    };

    //Mobile stats
    private long mobileRxBytes = 0;
    private long mobileTxBytes = 0;
    private long mobileRxPackets = 0;
    private long mobileTxPackets = 0;

    //WiFi stats
    private long wifiRxBytes = 0;
    private long wifiTxBytes = 0;
    private long wifiRxPackets = 0;
    private long wifiTxPackets = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static Traffic.AWARESensorObserver awareSensor;

    public static void setSensorObserver(Traffic.AWARESensorObserver observer) {
        awareSensor = observer;
    }

    public static Traffic.AWARESensorObserver getSensorObserver() {
        return awareSensor;
    }

    public interface AWARESensorObserver {
        void onNetworkTraffic(ContentValues data);

        void onWiFiTraffic(ContentValues data);

        void onIdleTraffic();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = Traffic_Provider.getAuthority(this);
        resetTrafficBaselines();

        if (Aware.DEBUG) Log.d(TAG, "Traffic service created!");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {

            if (TrafficStats.getTotalRxBytes() == TrafficStats.UNSUPPORTED) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_NETWORK_TRAFFIC, false);
                if (Aware.DEBUG)
                    Log.d(TAG, "Device doesn't support traffic statistics! Disabling sensor...");
                Aware.stopTraffic(this);

            } else {

                DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");
                Aware.setSetting(this, Aware_Preferences.STATUS_NETWORK_TRAFFIC, true);

                // PhoneStateListener data-activity callbacks are permission-dependent and may
                // never fire for Wi-Fi-only traffic. Sample the system counters periodically
                // instead, using the study's configured traffic frequency (seconds).
                mHandler.removeCallbacks(mRunnable);
                resetTrafficBaselines();
                mHandler.postDelayed(mRunnable, getSamplingIntervalMillis());

                if (Aware.DEBUG) Log.d(TAG, "Traffic service active...");

                if (Aware.isStudy(this)) {
                    ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), Traffic_Provider.getAuthority(this), 1);
                    ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Traffic_Provider.getAuthority(this), true);
                    long frequency = Aware.getSettingAsLong(this, Aware_Preferences.FREQUENCY_WEBSERVICE, 30) * 60;
                    SyncRequest request = new SyncRequest.Builder()
                            .syncPeriodic(frequency, frequency / 3)
                            .setSyncAdapter(Aware.getAWAREAccount(this), Traffic_Provider.getAuthority(this))
                            .setExtras(new Bundle()).build();
                    ContentResolver.requestSync(request);
                }
            }
        }

        return START_STICKY;
    }

    private long getSamplingIntervalMillis() {
        int frequencySeconds = Math.max(1, Aware.getSettingAsInt(
                getApplicationContext(),
                Aware_Preferences.FREQUENCY_NETWORK_TRAFFIC,
                30));
        return SensorTimeUnits.secondsToMillis(frequencySeconds);
    }

    private void resetTrafficBaselines() {
        mobileRxBytes = supportedCounter(TrafficStats.getMobileRxBytes());
        mobileRxPackets = supportedCounter(TrafficStats.getMobileRxPackets());
        mobileTxBytes = supportedCounter(TrafficStats.getMobileTxBytes());
        mobileTxPackets = supportedCounter(TrafficStats.getMobileTxPackets());
        wifiRxBytes = nonMobileCounter(
                TrafficStats.getTotalRxBytes(), TrafficStats.getMobileRxBytes());
        wifiRxPackets = nonMobileCounter(
                TrafficStats.getTotalRxPackets(), TrafficStats.getMobileRxPackets());
        wifiTxBytes = nonMobileCounter(
                TrafficStats.getTotalTxBytes(), TrafficStats.getMobileTxBytes());
        wifiTxPackets = nonMobileCounter(
                TrafficStats.getTotalTxPackets(), TrafficStats.getMobileTxPackets());
    }

    static long supportedCounter(long counter) {
        return counter == TrafficStats.UNSUPPORTED ? 0 : Math.max(0, counter);
    }

    static long nonMobileCounter(long total, long mobile) {
        long supportedTotal = supportedCounter(total);
        if (mobile == TrafficStats.UNSUPPORTED) return supportedTotal;
        return Math.max(0, supportedTotal - supportedCounter(mobile));
    }

    static long counterDelta(long current, long previous) {
        return current >= previous ? current - previous : 0;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mHandler.removeCallbacks(mRunnable);

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Traffic_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                Traffic_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (Aware.DEBUG) Log.d(TAG, "Traffic service terminated...");
    }
}
