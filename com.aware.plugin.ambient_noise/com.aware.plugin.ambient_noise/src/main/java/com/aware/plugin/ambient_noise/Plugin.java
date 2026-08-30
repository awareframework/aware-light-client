package com.aware.plugin.ambient_noise;

import android.Manifest;
import android.accounts.Account;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SyncRequest;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.utils.Aware_Plugin;
import com.aware.utils.PluginsManager;
import com.aware.utils.Scheduler;

import org.json.JSONException;
import org.json.JSONObject;

public class Plugin extends Aware_Plugin {

    public static final String SCHEDULER_PLUGIN_AMBIENT_NOISE = "SCHEDULER_PLUGIN_AMBIENT_NOISE";
    private static String TAG = "AWARE::Ambient Noise";
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String PLUGIN_PACKAGE_NAME = "com.aware.plugin.ambient_noise";
    private static AWARESensorObserver awareSensor;

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = Provider.getAuthority(this);
        TAG = "AWARE::Ambient Noise";
        REQUIRED_PERMISSIONS.add(Manifest.permission.RECORD_AUDIO);
        REQUIRED_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        // Runtime permission prompts belong to the visible consent flow. Launching an Activity
        // from this background plugin caused one more dialog/restart loop during all-sensor updates.
        initializePlugin();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {

            DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

            initializeSettings(getApplicationContext());

            setupScheduler();

            setupSync();
        } else {
            Log.e(TAG, "Required permissions not granted");
        }

        return START_STICKY;
    }

    private void initializePlugin() {
        if (Aware.getSetting(getApplicationContext(), Settings.STATUS_PLUGIN_AMBIENT_NOISE).isEmpty()) {
            Aware.setSetting(getApplicationContext(), Settings.STATUS_PLUGIN_AMBIENT_NOISE, true);
        }
    }



    /**
     * Ensures the plugin's own settings have a value, defaulting any that are empty. Shared with
     * AudioAnalyser (a separate IntentService triggered directly by the Scheduler, not through this
     * Service's onStartCommand()) so both read from the same single source of truth for defaults
     * instead of duplicating the literals.
     */
    static void initializeSettings(Context context) {
        if (Aware.getSetting(context, Settings.FREQUENCY_PLUGIN_AMBIENT_NOISE).isEmpty()) {
            Aware.setSetting(context, Settings.FREQUENCY_PLUGIN_AMBIENT_NOISE, 5);
        }
        if (Aware.getSetting(context, Settings.PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE).isEmpty()) {
            Aware.setSetting(context, Settings.PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE, 30);
        }
        if (Aware.getSetting(context, Settings.PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD).isEmpty()) {
            Aware.setSetting(context, Settings.PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD, 50);
        }
    }

    private void setupScheduler() {
        try {
            Scheduler.Schedule audioSampler = Scheduler.getSchedule(this, SCHEDULER_PLUGIN_AMBIENT_NOISE);
            if (audioSampler == null || audioSampler.getInterval() != Aware.getSettingAsLong(this, Settings.FREQUENCY_PLUGIN_AMBIENT_NOISE, 5)) {
                audioSampler = new Scheduler.Schedule(SCHEDULER_PLUGIN_AMBIENT_NOISE)
                        .setInterval(Aware.getSettingAsLong(this, Settings.FREQUENCY_PLUGIN_AMBIENT_NOISE, 5))
                        .setActionType(Scheduler.ACTION_TYPE_SERVICE)
                        .setActionClass(getPackageName() + "/" + AudioAnalyser.class.getName());
                Scheduler.saveSchedule(this, audioSampler);
            }
        } catch (JSONException e) {
            if (DEBUG) Log.e(TAG, "Error setting up scheduler: " + e.getMessage());
        }
    }

    private void setupSync() {

        try {
            Account aware_account = Aware.getAWAREAccount(this);
            String authority = Provider.getAuthority(this);

            if (aware_account != null) {
                ContentResolver.setIsSyncable(aware_account, authority, 1);

                ContentResolver.setSyncAutomatically(aware_account, authority, true);

                if (Aware.isStudy(this)) {
                    long frequency = Aware.getSettingAsLong(this, Aware_Preferences.FREQUENCY_WEBSERVICE, 30) * 60;

                    SyncRequest request = new SyncRequest.Builder()
                            .syncPeriodic(frequency, frequency / 3)
                            .setSyncAdapter(aware_account, authority)
                            .build();
                    ContentResolver.requestSync(request);
                }

            } else {
                Log.e(TAG, "Failed to setup sync - no AWARE account found");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up sync adapter: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        try {
            // Remove sync settings
            if (Aware.getAWAREAccount(this) != null) {
                ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Provider.getAuthority(this), false);
                ContentResolver.removePeriodicSync(
                        Aware.getAWAREAccount(this),
                        Provider.getAuthority(this),
                        Bundle.EMPTY
                );
            }

            Scheduler.removeSchedule(this, SCHEDULER_PLUGIN_AMBIENT_NOISE);
            // Call super.onDestroy() only once, at the end
            super.onDestroy();
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "Error during plugin cleanup: " + e.getMessage());
        }
    }

    public static void setSensorObserver(AWARESensorObserver observer) {
        awareSensor = observer;
    }

    public static AWARESensorObserver getSensorObserver() {
        return awareSensor;
    }

    public interface AWARESensorObserver {
        void onRecording(ContentValues data);
    }
}
