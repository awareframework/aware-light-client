package com.aware.phone.ui;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.Toast;

import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.Notes;
import com.aware.phone.R;
import com.aware.phone.ui.prefs.TakeNotesPref;
import com.aware.phone.ui.dialogs.PermissionCheckDialog;
import com.aware.phone.utils.AwareUtil;
import com.aware.phone.utils.SensorPermissionMapper;
import com.aware.ui.PermissionsHandler;
import com.aware.ScreenShot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

import static com.aware.Aware.AWARE_NOTIFICATION_IMPORTANCE_GENERAL;
import static com.aware.Aware.TAG;
import static com.aware.Aware.setNotificationProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.os.Environment;
import androidx.documentfile.provider.DocumentFile;



/**
 *
 */
public class Aware_Client extends Aware_Activity {

    public static boolean permissions_ok;
    private static Hashtable<Integer, Boolean> listSensorType;
    private static SharedPreferences prefs;
    public static final int REQUEST_CODE_OPEN_DIRECTORY = 1000;
    public static final int REQUEST_CODE_SCREENSHOT = 1002;
    private static final ArrayList<String> REQUIRED_PERMISSIONS = new ArrayList<>();
    private static final Hashtable<String, Integer> optionalSensors = new Hashtable<>();
    private final Aware.AndroidPackageMonitor packageMonitor = new Aware.AndroidPackageMonitor();
    private TakeNotesPref originalTakeNotesPref = null;
    
    // Screenshot service restart tracking
    private int screenshotRestartCount = 0;
    private long lastScreenshotRestartTime = 0;
    private static final int MAX_SCREENSHOT_RESTART_ATTEMPTS = 3;
    private static final long SCREENSHOT_RESTART_RESET_INTERVAL = 60000; // 1 minute

    private BroadcastReceiver screenshotServiceStoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ScreenShot.ACTION_SCREENSHOT_SERVICE_STOPPED.equals(intent.getAction())) {
                checkAndStartScreenshotService();
            }
        }
    };

    private BroadcastReceiver noteStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Notes.ACTION_NOTE_STATUS.equals(intent.getAction())){
                updateTakeNotesVisibility();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("com.aware.phone", Context.MODE_PRIVATE);

        // Register preference change listener
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        // Initialize views
        if (Aware.isStudy(getApplicationContext())) {
            setContentView(R.layout.activity_aware_study);
            addPreferencesFromResource(R.xml.pref_aware_light);

            // Initialize plugin navigation
            setupPluginNavigation();
        } else {
            setContentView(R.layout.activity_aware);
            addPreferencesFromResource(R.xml.pref_aware_device);
        }
//        hideUnusedPreferences();

        // Initialize and check optional sensors and required permissions before starting AWARE service
        optionalSensors.put(Aware_Preferences.STATUS_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER);
        optionalSensors.put(Aware_Preferences.STATUS_SIGNIFICANT_MOTION, Sensor.TYPE_ACCELEROMETER);
        optionalSensors.put(Aware_Preferences.STATUS_BAROMETER, Sensor.TYPE_PRESSURE);
        optionalSensors.put(Aware_Preferences.STATUS_GRAVITY, Sensor.TYPE_GRAVITY);
        optionalSensors.put(Aware_Preferences.STATUS_GYROSCOPE, Sensor.TYPE_GYROSCOPE);
        optionalSensors.put(Aware_Preferences.STATUS_LIGHT, Sensor.TYPE_LIGHT);
        optionalSensors.put(Aware_Preferences.STATUS_LINEAR_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION);
        optionalSensors.put(Aware_Preferences.STATUS_MAGNETOMETER, Sensor.TYPE_MAGNETIC_FIELD);
        optionalSensors.put(Aware_Preferences.STATUS_PROXIMITY, Sensor.TYPE_PROXIMITY);
        optionalSensors.put(Aware_Preferences.STATUS_ROTATION, Sensor.TYPE_ROTATION_VECTOR);
        optionalSensors.put(Aware_Preferences.STATUS_TEMPERATURE, Sensor.TYPE_AMBIENT_TEMPERATURE);

        SensorManager manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ALL);
        listSensorType = new Hashtable<>();
        for (int i = 0; i < sensors.size(); i++) {
            listSensorType.put(sensors.get(i).getType(), true);
        }

        // Core permissions that are always needed for basic functionality
        REQUIRED_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            REQUIRED_PERMISSIONS.add(Manifest.permission.FOREGROUND_SERVICE);
        }

        // Check only core permissions needed to start AWARE service
        boolean CORE_PERMISSIONS_OK = true;
        ArrayList<String> corePermissions = new ArrayList<>();
        corePermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        corePermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            corePermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        for (String p : corePermissions) {
            if (PermissionChecker.checkSelfPermission(this, p) != PermissionChecker.PERMISSION_GRANTED) {
                CORE_PERMISSIONS_OK = false;
                break;
            }
        }
        
        if (CORE_PERMISSIONS_OK) {
            Intent aware = new Intent(this, Aware.class);
            startService(aware);
        }

        IntentFilter awarePackages = new IntentFilter();
        awarePackages.addAction(Intent.ACTION_PACKAGE_ADDED);
        awarePackages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        awarePackages.addDataScheme("package");
        registerReceiver(packageMonitor, awarePackages);

        Intent whitelisting = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        whitelisting.setData(Uri.parse("package:" + getPackageName()));
        startActivity(whitelisting);

        // Register the broadcast receiver
        registerReceiver(screenshotServiceStoppedReceiver, new IntentFilter(ScreenShot.ACTION_SCREENSHOT_SERVICE_STOPPED));
        registerReceiver(screenshotStatusReceiver, new IntentFilter(ScreenShot.ACTION_SCREENSHOT_STATUS));
        registerReceiver(noteStatusReceiver, new IntentFilter(Notes.ACTION_NOTE_STATUS));
        checkAndStartScreenshotService();
        checkAndStartPlugin();
    }

    private void setupPluginNavigation() {
        Preference pluginsManagerPref = findPreference("plugins_manager");
        if (pluginsManagerPref != null) {
            pluginsManagerPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent pluginsManager = new Intent(getApplicationContext(), Plugins_Manager.class);
                    startActivity(pluginsManager);
                    return true;
                }
            });
        }

        Preference streamUiPref = findPreference("stream_ui");
        if (streamUiPref != null) {
            streamUiPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent stream_ui = new Intent(getApplicationContext(), Stream_UI.class);
                    startActivity(stream_ui);
                    return true;
                }
            });
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, final Preference preference) {
        if (preference instanceof PreferenceScreen) {
            Dialog subpref = ((PreferenceScreen) preference).getDialog();
            ViewGroup root = (ViewGroup) subpref.findViewById(android.R.id.content).getParent();
            Toolbar toolbar = new Toolbar(this);
            toolbar.setBackgroundColor(ContextCompat.getColor(preferenceScreen.getContext(), R.color.primary));
            toolbar.setTitleTextColor(ContextCompat.getColor(preferenceScreen.getContext(), android.R.color.white));
            toolbar.setTitle(preference.getTitle());
            root.addView(toolbar, 0); //add to the top

            subpref.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    new SettingsSync().execute(preference);
                }
            });
        }
        
        // Handle check permissions preference click
        if (preference.getKey() != null && preference.getKey().equals("check_permissions")) {
            // Get current study config
            JSONObject studyConfig = Aware.getStudyConfig(getApplicationContext(), 
                Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));
            
            if (studyConfig != null) {
                JSONArray studyConfigs = new JSONArray().put(studyConfig);
                PermissionCheckDialog permissionDialog = new PermissionCheckDialog(this);
                permissionDialog.showPermissionCheck(studyConfigs, null);
            } else {
                Toast.makeText(this, "No study configuration found", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        String value = "";
        Map<String, ?> keys = sharedPreferences.getAll();
        if (keys.containsKey(key)) {
            Object entry = keys.get(key);
            if (entry instanceof Boolean)
                value = String.valueOf(sharedPreferences.getBoolean(key, false));
            else if (entry instanceof String)
                value = String.valueOf(sharedPreferences.getString(key, "error"));
            else if (entry instanceof Integer)
                value = String.valueOf(sharedPreferences.getInt(key, 0));
        }

        Aware.setSetting(getApplicationContext(), key, value);
        Preference pref = findPreference(key);
        if (CheckBoxPreference.class.isInstance(pref)) {
            CheckBoxPreference check = (CheckBoxPreference) findPreference(key);
            check.setChecked(Aware.getSetting(getApplicationContext(), key).equals("true"));

            // Check if this is a sensor being enabled that needs permissions
            if (key.contains("status_") && value.equals("true")) {
                List<String> sensorPermissions = SensorPermissionMapper.getRequiredPermissions(key);
                ArrayList<String> missingPermissions = new ArrayList<>();
                
                for (String permission : sensorPermissions) {
                    if (PermissionChecker.checkSelfPermission(this, permission) != PermissionChecker.PERMISSION_GRANTED) {
                        missingPermissions.add(permission);
                    }
                }
                
                if (!missingPermissions.isEmpty()) {
                    // Sensor needs permissions - disable it temporarily and show permission dialog
                    check.setChecked(false);
                    Aware.setSetting(getApplicationContext(), key, "false");
                    
                    // Show permission dialog for this specific sensor
                    showSensorPermissionDialog(key, missingPermissions);
                    return; // Don't proceed with sensor activation yet
                }
            }

            //update the parent to show active/inactive
            new SettingsSync().execute(pref);

            //Start/Stop sensor
            Aware.startAWARE(getApplicationContext());
        }
        if (EditTextPreference.class.isInstance(pref)) {
            EditTextPreference text = (EditTextPreference) findPreference(key);
            text.setText(Aware.getSetting(getApplicationContext(), key));
        }
        if (ListPreference.class.isInstance(pref)) {
            ListPreference list = (ListPreference) findPreference(key);
            list.setSummary(list.getEntry());
        }

        // Check if screenshot preference is changed
        if (key.equals(Aware_Preferences.STATUS_SCREENSHOT) ||
                key.equals(Aware_Preferences.STATUS_SCREENSHOT_LOCAL_STORAGE)) {
            handleScreenshotPreferenceChange(key, value);
        }

        if (key.equals(Aware_Preferences.STATUS_NOTES)) {
            updateTakeNotesVisibility();
        }


    }

    private void handleScreenshotPreferenceChange(String key, String value) {
        if (key.equals(Aware_Preferences.STATUS_SCREENSHOT)) {
            if (value.equals("true")) {
                checkAndStartScreenshotService();
            } else {
                stopScreenshotService();
            }
        } else if (key.equals(Aware_Preferences.STATUS_SCREENSHOT_LOCAL_STORAGE)) {
            // Restart the screenshot service if it's currently running
            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT).equals("true")) {
                stopScreenshotService();
                checkAndStartScreenshotService();
            }
        }
    }

    private class SettingsSync extends AsyncTask<Preference, Preference, Void> {
        @Override
        protected Void doInBackground(Preference... params) {
            for (Preference pref : params) {
                publishProgress(pref);
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Preference... values) {
            super.onProgressUpdate(values);

            Preference pref = values[0];


            if (pref != null) Log.i(TAG, "Syncing pref with key: " + pref.getKey());
            if (getPreferenceParent(pref) == null) return;

            if (CheckBoxPreference.class.isInstance(pref)) {
                CheckBoxPreference check = (CheckBoxPreference) findPreference(pref.getKey());
                check.setChecked(Aware.getSetting(getApplicationContext(), pref.getKey()).equals("true"));
                if (check.isChecked()) {
                    if (pref.getKey().equalsIgnoreCase(Aware_Preferences.STATUS_WEBSERVICE)) {
                        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER).length() == 0) {
                            Toast.makeText(getApplicationContext(), "Study URL missing...", Toast.LENGTH_SHORT).show();
                        } else if (!Aware.isStudy(getApplicationContext())) {
                            //Shows UI to allow the user to join study
                            Intent joinStudy = new Intent(getApplicationContext(), Aware_Join_Study.class);
                            joinStudy.putExtra(Aware_Join_Study.EXTRA_STUDY_URL, Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));
                            startActivity(joinStudy);
                        }
                    }
                    if (pref.getKey().equalsIgnoreCase(Aware_Preferences.FOREGROUND_PRIORITY)) {
                        sendBroadcast(new Intent(Aware.ACTION_AWARE_PRIORITY_FOREGROUND));
                    }
                } else {
                    if (pref.getKey().equalsIgnoreCase(Aware_Preferences.FOREGROUND_PRIORITY)) {
                        sendBroadcast(new Intent(Aware.ACTION_AWARE_PRIORITY_BACKGROUND));
                    }
                }
            }

            if (EditTextPreference.class.isInstance(pref)) {
                EditTextPreference text = (EditTextPreference) findPreference(pref.getKey());
                text.setText(Aware.getSetting(getApplicationContext(), pref.getKey()));
                text.setSummary(Aware.getSetting(getApplicationContext(), pref.getKey()));
            }

            if (ListPreference.class.isInstance(pref)) {
                ListPreference list = (ListPreference) findPreference(pref.getKey());
                list.setSummary(list.getEntry());
            }

            if (PreferenceScreen.class.isInstance(getPreferenceParent(pref))) {
                PreferenceScreen parent = (PreferenceScreen) getPreferenceParent(pref);

                boolean prefEnabled = Boolean.valueOf(Aware.getSetting(Aware_Client.this, Aware_Preferences.ENABLE_CONFIG_UPDATE));
                parent.setEnabled(prefEnabled);  // enabled/disabled based on config

                ListAdapter children = parent.getRootAdapter();
                boolean isActive = false;
                ArrayList sensorStatuses = new ArrayList<String>();
                for (int i = 0; i < children.getCount(); i++) {
                    Object obj = children.getItem(i);
                    if (CheckBoxPreference.class.isInstance(obj)) {
                        CheckBoxPreference child = (CheckBoxPreference) obj;
                        if (child.getKey().contains("status_")) {
                            sensorStatuses.add(child.getKey());
                            if (child.isChecked()) {
                                isActive = true;
                                break;
                            }
                        }
                    }
                }

                // Check if any of the status settings of a sensor (parent pref) is active in the study config
                JSONObject studyConfig = Aware.getStudyConfig(getApplicationContext(), Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));
                boolean isActiveInConfig = false;
                try {
                    JSONArray sensorsList = studyConfig.getJSONArray("sensors");
                    for (int i = 0; i < sensorsList.length(); i++) {
                        JSONObject sensorInfo = sensorsList.getJSONObject(i);
                        String sensorSetting = sensorInfo.getString("setting");

                        if (sensorStatuses.contains(sensorSetting)) {
                            sensorStatuses.remove(sensorSetting);
                            isActiveInConfig = sensorInfo.getBoolean("value");
                        }

                        if (isActiveInConfig || sensorStatuses.size() == 0) break;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                // Only show sensor if it is active in the study config
                if (isActiveInConfig) {
                    if (pref != null) Log.i(TAG, "Pref with key: " + pref.getKey() + " is active!");
                    try {
                        Class res = R.drawable.class;
                        Field field = res.getField("ic_action_" + parent.getKey());
                        int icon_id = field.getInt(null);
                        Drawable category_icon = ContextCompat.getDrawable(getApplicationContext(), icon_id);
                        if (category_icon != null) {
                            int colorId = isActive ? R.color.accent : R.color.lightGray;
                            category_icon.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(getApplicationContext(), colorId), PorterDuff.Mode.SRC_IN));
                            parent.setIcon(category_icon);
                            onContentChanged();
                        }
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        e.printStackTrace();
                    }
                } else {
                    PreferenceCategory rootSensorPref = (PreferenceCategory) getPreferenceParent(parent);
                    rootSensorPref.removePreference(parent);
                }
            }
        }
    }

    private BroadcastReceiver screenshotStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ScreenShot.ACTION_SCREENSHOT_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(ScreenShot.EXTRA_SCREENSHOT_STATUS);
                if (ScreenShot.STATUS_RETRY_COUNT_EXCEEDED.equals(status)) {
                    Log.d(TAG, "Screenshot service retry count exceeded. Restarting service...");
                    restartScreenshotService();
                } else if (ScreenShot.STATUS_SESSION_EXPIRED.equals(status)) {
                    Log.d(TAG, "Screenshot MediaProjection session expired. Requesting new permission...");
                    handleSessionExpired();
                }
            }
        }
    };

    private void restartScreenshotService() {
        long currentTime = System.currentTimeMillis();
        
        // Reset restart count if enough time has passed
        if (currentTime - lastScreenshotRestartTime > SCREENSHOT_RESTART_RESET_INTERVAL) {
            screenshotRestartCount = 0;
        }
        
        screenshotRestartCount++;
        lastScreenshotRestartTime = currentTime;
        
        if (screenshotRestartCount > MAX_SCREENSHOT_RESTART_ATTEMPTS) {
            Log.e(TAG, "Screenshot service exceeded maximum restart attempts");
            showScreenshotServiceError("Screenshot service is experiencing issues. Please check your settings and permissions.");
            // Reset the counter to allow future restarts
            screenshotRestartCount = 0;
            return;
        }
        
        stopScreenshotService();
        
        // Exponential backoff: 2s, 4s, 8s
        long restartDelay = (long) (2000 * Math.pow(2, screenshotRestartCount - 1));
        Log.d(TAG, "Restarting screenshot service in " + restartDelay + "ms (attempt " + screenshotRestartCount + ")");
        
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                checkAndStartScreenshotService();
            }
        }, restartDelay);
    }

    private void checkAndStartScreenshotService() {
        if (!isAccessibilityServiceEnabled(this, Applications.class)) {
            enableAccessibilityService();
            return;
        }
        
        // Check storage permissions before starting screenshot service
        if (!checkScreenshotPermissions()) {
            Log.e(TAG, "Missing required permissions for screenshot service");
            showScreenshotServiceError("Screenshot service requires storage permissions. Please grant them in settings.");
            return;
        }
        
        // Check battery optimization
        if (!isBatteryOptimizationIgnored(this, getPackageName())) {
            Log.w(TAG, "Battery optimization not ignored - screenshot service may be killed");
        }

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT).equals("true")) {
            if (ScreenShot.mediaProjectionResultCode != 0 && ScreenShot.mediaProjectionResultData != null) {
                if (!isScreenshotServiceRunning()) {
                    startScreenshotService(ScreenShot.mediaProjectionResultCode, ScreenShot.mediaProjectionResultData);
                }
            } else {
                MediaProjectionManager projectionManager = (MediaProjectionManager) this.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                Intent intent = projectionManager.createScreenCaptureIntent();
                startActivityForResult(intent, REQUEST_CODE_SCREENSHOT);
            }
        }
    }

    private void checkAndStartPlugin(){

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_PLUGIN_AMBIENT_NOISE).equals("true")) {
            Aware.startPlugin(getApplicationContext(), "com.aware.plugin.ambient_noise");
            } else {
            Aware.stopPlugin(getApplicationContext(), "com.aware.plugin.ambient_noise");
        }

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_PLUGIN_OPENWEATHER).equalsIgnoreCase("true")) {
            Aware.startPlugin(getApplicationContext(), "com.aware.plugin.openweather");
            } else {
            Aware.stopPlugin(getApplicationContext(), "com.aware.plugin.openweather");
        }
    }

    private void enableAccessibilityService() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        Toast.makeText(this, "Please enable the accessibility service.", Toast.LENGTH_LONG).show();
    }


    private boolean isScreenshotServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (ScreenShot.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void startScreenshotService(int resultCode, Intent resultData) {
        int defaultCaptureInterval = 5; // Default capture interval in seconds
        int defaultCompressRate = 20;   // Default compression rate
        boolean defaultSaveToLocal = true; // Default to not save to local storage

        String captureIntervalSetting = Aware.getSetting(getApplicationContext(), Aware_Preferences.CAPTURE_TIME_INTERVAL);
        String compressRateSetting = Aware.getSetting(getApplicationContext(), Aware_Preferences.COMPRESS_RATE);
        String saveToLocalSetting = Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT_LOCAL_STORAGE);

        int captureInterval = defaultCaptureInterval;
        int compressRate = defaultCompressRate;
        boolean saveToLocal = defaultSaveToLocal;

        if (!captureIntervalSetting.isEmpty()) {
            try {
                captureInterval = Integer.parseInt(captureIntervalSetting);
            } catch (NumberFormatException e) {
                captureInterval = defaultCaptureInterval;
            }
        }

        if (!compressRateSetting.isEmpty()) {
            try {
                compressRate = Integer.parseInt(compressRateSetting);
            } catch (NumberFormatException e) {
                compressRate = defaultCompressRate;
            }
        }

        if (!saveToLocalSetting.isEmpty()) {
            saveToLocal = Boolean.parseBoolean(saveToLocalSetting);
        }
        
        // Log service configuration for debugging
        Log.d(TAG, "Starting screenshot service with configuration:");
        Log.d(TAG, "  Capture interval: " + captureInterval + " seconds");
        Log.d(TAG, "  Compression rate: " + compressRate + "%");
        Log.d(TAG, "  Save to local: " + saveToLocal);
        Log.d(TAG, "  Battery optimization ignored: " + isBatteryOptimizationIgnored(this, getPackageName()));

        Intent serviceIntent = new Intent(this, ScreenShot.class);
        serviceIntent.putExtra(ScreenShot.MEDIA_PROJECTION_RESULT_CODE, resultCode);
        serviceIntent.putExtra(ScreenShot.MEDIA_PROJECTION_RESULT_DATA, resultData);
        serviceIntent.putExtra(ScreenShot.CAPTURE_TIME_INTERVAL, captureInterval * 1000); // Convert to milliseconds
        serviceIntent.putExtra(ScreenShot.COMPRESS_RATE, compressRate);
        serviceIntent.putExtra(ScreenShot.STATUS_SCREENSHOT_LOCAL_STORAGE, saveToLocal);
        
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
            
            // Store the start time for health monitoring
            prefs.edit().putLong("screenshot_service_start_time", System.currentTimeMillis()).apply();
            
            Toast.makeText(this, "Screenshot service started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start screenshot service", e);
            showScreenshotServiceError("Failed to start screenshot service: " + e.getMessage());
        }
    }


    private void stopScreenshotService() {
        Intent serviceIntent = new Intent(this, ScreenShot.class);
        stopService(serviceIntent);
        
        // Track service stop time for health monitoring
        long startTime = prefs.getLong("screenshot_service_start_time", 0);
        if (startTime > 0) {
            long runDuration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Screenshot service ran for " + (runDuration / 1000) + " seconds");
            prefs.edit().remove("screenshot_service_start_time").apply();
        }
    }

    /**
     * Checks if the application is running on an emulator.
     *
     * @return true if the application is running on an emulator, false otherwise.
     */
    public static boolean isEmulator() {
        // Check various properties to determine if the current environment is an emulator.
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }

    /**
     * Retrieves the appropriate directory for the application's database file storage based on
     * several conditions such as running environment (emulator or real device) and configuration settings.
     *
     * @return The File object pointing to the appropriate directory for database storage.
     */
    protected File getDatabaseFileFolder(){
        Context context = this;  // Use the current instance to access the context methods.

        File dataDirectory;

        if (context.getResources().getBoolean(com.aware.R.bool.internalstorage)) {
            // Use the internal storage directory assigned to the app which is private.
            dataDirectory = context.getFilesDir();
        } else if (!context.getResources().getBoolean(com.aware.R.bool.standalone)) {
            // Use a directory on the external storage that remains after the app is uninstalled.
            dataDirectory = new File(Environment.getExternalStoragePublicDirectory("AWARE").toString());
        } else {
            // Decide the storage location based on whether the environment is an emulator.
            if (isEmulator()) {
                // Use internal storage for emulators for simplicity.
                dataDirectory =  context.getFilesDir();
            } else {
                // Use the external app-specific directory that is removed when the app is uninstalled.
                dataDirectory = new File(ContextCompat.getExternalFilesDirs(context, null)[0] + "/AWARE");
            }
        }

        return dataDirectory;
    }

    /**
     * This method checks if the result corresponds to the REQUEST_CODE_OPEN_DIRECTORY and if
     * the result code indicates success. If so, it proceeds to export files from a specified
     * internal directory to the selected external directory.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Check if the result comes from the correct request and has a successful result code
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            Uri treeUri = data.getData();
            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);

            // Retrieve the directory for storing database files
            File externalAppDirectory = getDatabaseFileFolder();


            // Log.d("export_data", "External AWARE directory path: " + externalAppDirectory.getAbsolutePath());

            File[] files = externalAppDirectory.listFiles();

            if (files != null && files.length > 0) {
                for (final File file : files) {
                    // Log.d("export_data", "Processing file: " + file.getAbsolutePath());
                    try {
                        DocumentFile newFile = pickedDir.createFile("application/octet-stream", file.getName());
                        if (newFile != null) {
                            try (InputStream in = new FileInputStream(file);
                                 OutputStream out = getContentResolver().openOutputStream(newFile.getUri())) {
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = in.read(buffer)) > 0) {
                                    out.write(buffer, 0, length);
                                }
                                out.flush();
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(Aware_Client.this, "Exported: " + file.getName(), Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            throw new IOException("Failed to create document file for: " + file.getName());
                        }
                    } catch (final IOException e) {
                        // Log.e("export_data", "Failed to export: " + file.getName(), e);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(Aware_Client.this, "Failed to export: " + file.getName(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(Aware_Client.this, "All files exported successfully", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                // Log.d("export_data", "No files found to export.");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(Aware_Client.this, "No files available to export.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            // Log.d("export_data", "Output folder URI: " + pickedDir.getUri().toString());
        }

//        if (requestCode == ScreenshotCapturePref.REQUEST_CODE_SCREENSHOT && resultCode == RESULT_OK) {
//            Log.d(TAG, "Starting ScreenCaptureService with result data");
//            Intent intent = new Intent(this, ScreenshotCaptureService.class);
//            intent.putExtra(ScreenshotCaptureService.MEDIA_PROJECTION_RESULT_CODE, resultCode);
//            intent.putExtra(ScreenshotCaptureService.MEDIA_PROJECTION_RESULT_DATA, data);
//            ContextCompat.startForegroundService(this, intent);
//        } else {
//            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show();
//            Log.d(TAG, "Screen capture permission denied");
//        }

        if (requestCode == REQUEST_CODE_SCREENSHOT) {

            if (resultCode == RESULT_OK) {
                startScreenshotService(resultCode, data);
                // Reset restart count on successful permission grant
                screenshotRestartCount = 0;
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean isAccessibilityServiceEnabled(Context context, Class<?> accessibilityServiceClass) {
        int accessibilityEnabled = 0;
        final String service = context.getPackageName() + "/" + accessibilityServiceClass.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(context.getApplicationContext().getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Error finding setting, default accessibility to not found: " + e.getMessage());
        }

        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(context.getApplicationContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                colonSplitter.setString(settingValue);
                while (colonSplitter.hasNext()) {
                    String componentName = colonSplitter.next();

                    if (componentName.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }



    @Override
    protected void onResume() {
        super.onResume();

        // Only check for study-specific permissions if user has joined a study
        boolean isInStudy = Aware.isStudy(getApplicationContext());
        boolean hasShownPermissions = prefs.getBoolean("study_permissions_shown", false);
        
        if (isInStudy && !hasShownPermissions) {
            // Get study-specific permissions
            ArrayList<String> studyPermissions = getStudyRequiredPermissions();
            
            permissions_ok = true;
            ArrayList<String> missingPermissions = new ArrayList<>();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (String p : studyPermissions) {
                    if (PermissionChecker.checkSelfPermission(this, p) != PermissionChecker.PERMISSION_GRANTED) {
                        permissions_ok = false;
                        missingPermissions.add(p);
                    }
                }
            }

            if (!permissions_ok) {
                Log.d(TAG, "Missing study-specific permissions: " + missingPermissions.size());
                
                // Mark that we've shown permissions for this study
                prefs.edit().putBoolean("study_permissions_shown", true).apply();
                
                // Show permission check dialog first to explain what permissions are needed
                JSONObject studyConfig = Aware.getStudyConfig(getApplicationContext(), 
                    Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));
                
                if (studyConfig != null) {
                    JSONArray studyConfigs = new JSONArray().put(studyConfig);
                    PermissionCheckDialog permissionDialog = new PermissionCheckDialog(this);
                    permissionDialog.showPermissionCheck(studyConfigs, new Runnable() {
                        @Override
                        public void run() {
                            // After dialog is dismissed, proceed with permission request if needed
                            checkAndRequestPermissions();
                        }
                    });
                } else {
                    // No study config, just request missing permissions directly
                    requestMissingPermissions(missingPermissions);
                }
            }
        } else {
            // Not in a study or already shown permissions, check for core permissions only
            permissions_ok = checkCorePermissions();
        }

        if (permissions_ok) {
            // Check if there's a pending sensor to enable after permissions were granted
            String pendingSensor = prefs.getString("pending_sensor_enable", null);
            if (pendingSensor != null) {
                // Re-enable the sensor now that permissions are granted
                Aware.setSetting(getApplicationContext(), pendingSensor, "true");
                CheckBoxPreference sensorPref = (CheckBoxPreference) findPreference(pendingSensor);
                if (sensorPref != null) {
                    sensorPref.setChecked(true);
                }
                
                // Clear the pending sensor
                prefs.edit().remove("pending_sensor_enable").apply();
                
                // Start the sensor
                Aware.startAWARE(getApplicationContext());
                
                Toast.makeText(this, AwareUtil.getSensorType(pendingSensor) + " enabled", Toast.LENGTH_SHORT).show();
            }

            if (prefs.getAll().isEmpty() && Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID).length() == 0) {
                PreferenceManager.setDefaultValues(getApplicationContext(), "com.aware.phone", Context.MODE_PRIVATE, R.xml.aware_preferences, true);
                prefs.edit().commit();
            } else {
                PreferenceManager.setDefaultValues(getApplicationContext(), "com.aware.phone", Context.MODE_PRIVATE, R.xml.aware_preferences, false);
            }

            Map<String, ?> defaults = prefs.getAll();
            for (Map.Entry<String, ?> entry : defaults.entrySet()) {
                if (Aware.getSetting(getApplicationContext(), entry.getKey(), "com.aware.phone").length() == 0) {
                    Aware.setSetting(getApplicationContext(), entry.getKey(), entry.getValue(), "com.aware.phone"); //default AWARE settings
                }
            }

            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID).length() == 0) {
                UUID uuid = UUID.randomUUID();
                Aware.setSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID, uuid.toString(), "com.aware.phone");
            }

            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER).length() == 0) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER, "http://api.awareframework.com/index.php");
            }

            Set<String> keys = optionalSensors.keySet();
            for (String optionalSensor : keys) {
                Preference pref = findPreference(optionalSensor);
                PreferenceGroup parent = getPreferenceParent(pref);
                if (pref != null && parent != null && pref.getKey().equalsIgnoreCase(optionalSensor) && !listSensorType.containsKey(optionalSensors.get(optionalSensor)))
                    parent.setEnabled(false);
            }

            try {
                PackageInfo awareInfo = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), PackageManager.GET_ACTIVITIES);
                Aware.setSetting(getApplicationContext(), Aware_Preferences.AWARE_VERSION, awareInfo.versionName);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            //Check if AWARE is active on the accessibility services. Android Wear doesn't support accessibility services (no API yet...)
            if (!Aware.is_watch(this)) {
                Applications.isAccessibilityServiceActive(this);
            }

            //Check if AWARE is allowed to run on Doze
            //Aware.isBatteryOptimizationIgnored(this, getPackageName());

            prefs.registerOnSharedPreferenceChangeListener(this);

            new SettingsSync().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, //use all cores available to process UI faster
                    findPreference(Aware_Preferences.DEVICE_ID),
                    findPreference(Aware_Preferences.DEVICE_LABEL),
                    findPreference(Aware_Preferences.AWARE_VERSION),
                    findPreference(Aware_Preferences.STATUS_ACCELEROMETER),
                    findPreference(Aware_Preferences.STATUS_APPLICATIONS),
                    findPreference(Aware_Preferences.STATUS_BAROMETER),
                    findPreference(Aware_Preferences.STATUS_BATTERY),
                    findPreference(Aware_Preferences.STATUS_BLUETOOTH),
                    findPreference(Aware_Preferences.STATUS_CALLS),
                    findPreference(Aware_Preferences.STATUS_COMMUNICATION_EVENTS),
                    findPreference(Aware_Preferences.STATUS_CRASHES),
                    findPreference(Aware_Preferences.STATUS_ESM),
                    findPreference(Aware_Preferences.STATUS_GRAVITY),
                    findPreference(Aware_Preferences.STATUS_GYROSCOPE),
                    findPreference(Aware_Preferences.STATUS_INSTALLATIONS),
                    findPreference(Aware_Preferences.STATUS_KEYBOARD),
                    findPreference(Aware_Preferences.STATUS_SCREENTEXT),
                    findPreference(Aware_Preferences.STATUS_LIGHT),
                    findPreference(Aware_Preferences.STATUS_LINEAR_ACCELEROMETER),
                    findPreference(Aware_Preferences.STATUS_LOCATION_GPS),
                    findPreference(Aware_Preferences.STATUS_LOCATION_NETWORK),
                    findPreference(Aware_Preferences.STATUS_LOCATION_PASSIVE),
                    findPreference(Aware_Preferences.STATUS_MAGNETOMETER),
                    findPreference(Aware_Preferences.STATUS_MESSAGES),
                    findPreference(Aware_Preferences.STATUS_MQTT),
                    findPreference(Aware_Preferences.STATUS_NETWORK_EVENTS),
                    findPreference(Aware_Preferences.STATUS_NETWORK_TRAFFIC),
                    findPreference(Aware_Preferences.STATUS_NOTIFICATIONS),
                    findPreference(Aware_Preferences.STATUS_PROCESSOR),
                    findPreference(Aware_Preferences.STATUS_PROXIMITY),
                    findPreference(Aware_Preferences.STATUS_ROTATION),
                    findPreference(Aware_Preferences.STATUS_SCREEN),
                    findPreference(Aware_Preferences.STATUS_SIGNIFICANT_MOTION),
                    findPreference(Aware_Preferences.STATUS_TEMPERATURE),
                    findPreference(Aware_Preferences.STATUS_TELEPHONY),
                    findPreference(Aware_Preferences.STATUS_TIMEZONE),
                    findPreference(Aware_Preferences.STATUS_WIFI),
                    findPreference(Aware_Preferences.STATUS_WEBSERVICE),
                    findPreference(Aware_Preferences.MQTT_SERVER),
                    findPreference(Aware_Preferences.MQTT_PORT),
                    findPreference(Aware_Preferences.MQTT_USERNAME),
                    findPreference(Aware_Preferences.MQTT_PASSWORD),
                    findPreference(Aware_Preferences.MQTT_KEEP_ALIVE),
                    findPreference(Aware_Preferences.MQTT_QOS),
                    findPreference(Aware_Preferences.WEBSERVICE_SERVER),
                    findPreference(Aware_Preferences.FREQUENCY_WEBSERVICE),
                    findPreference(Aware_Preferences.FREQUENCY_CLEAN_OLD_DATA),
                    findPreference(Aware_Preferences.WEBSERVICE_CHARGING),
                    findPreference(Aware_Preferences.WEBSERVICE_SILENT),
                    findPreference(Aware_Preferences.WEBSERVICE_WIFI_ONLY),
                    findPreference(Aware_Preferences.WEBSERVICE_FALLBACK_NETWORK),
                    findPreference(Aware_Preferences.REMIND_TO_CHARGE),
                    findPreference(Aware_Preferences.WEBSERVICE_SIMPLE),
                    findPreference(Aware_Preferences.WEBSERVICE_REMOVE_DATA),
                    findPreference(Aware_Preferences.DEBUG_DB_SLOW),
                    findPreference(Aware_Preferences.FOREGROUND_PRIORITY),
                    findPreference(Aware_Preferences.STATUS_TOUCH),
                    findPreference(Aware_Preferences.STATUS_SCREENSHOT)

            );
        }
        updateTakeNotesVisibility();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    public static boolean isBatteryOptimizationIgnored(Context context, String package_name) {
        boolean is_ignored = true;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            PowerManager pm = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            is_ignored = pm.isIgnoringBatteryOptimizations(package_name);
        }

        if (!is_ignored) {
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL);
            mBuilder.setSmallIcon(com.aware.R.drawable.ic_stat_aware_recharge);
            mBuilder.setContentTitle(context.getApplicationContext().getResources().getString(com.aware.R.string.aware_activate_battery_optimize_ignore_title));
            mBuilder.setContentText(context.getApplicationContext().getResources().getString(com.aware.R.string.aware_activate_battery_optimize_ignore));
            mBuilder.setAutoCancel(true);
            mBuilder.setOnlyAlertOnce(true); //notify the user only once
            mBuilder.setDefaults(NotificationCompat.DEFAULT_ALL);
            mBuilder = setNotificationProperties(mBuilder, AWARE_NOTIFICATION_IMPORTANCE_GENERAL);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                mBuilder.setChannelId(Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL);

            Intent batteryIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            batteryIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent clickIntent = PendingIntent.getActivity(context, 0, batteryIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            mBuilder.setContentIntent(clickIntent);

            NotificationManager notManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notManager.notify(Aware.AWARE_BATTERY_OPTIMIZATION_ID, mBuilder.build());
        }

        Log.d(Aware.TAG, "Battery Optimizations: " + is_ignored);

        return is_ignored;
    }

    @Override
    protected void onStop() {
        // Check if the activity is finishing
        boolean isFinishing = this.isFinishing();

        // Handle based on whether it's system-initiated closure
        if (!isFinishing) {
            if (isBatteryOptimizationIgnored(this, "com.aware.phone")) {
                Log.d("AWARE-Client", "AWARE stopped from background: may be caused by battery optimization");
                Aware.debug(this, "AWARE stopped from background: may be caused by battery optimization");
            } else {
                Log.d("AWARE-Client", "AWARE stopped from background: may be caused by system settings");
                Aware.debug(this, "AWARE stopped from background: may be caused by system settings");
            }
        }
        super.onStop();
    }


    @Override
    protected void onDestroy() {
        // Check if the activity is finishing
        boolean isFinishing = this.isFinishing();

        // Handle based on whether it's user-initiated or system-initiated closure
        if (isFinishing) {
            // User initiated closure
            Aware.debug(this, "AWARE interface cleaned from the list of frequently used apps");
        }
        Log.d("AWARE_Client", "AWARE interface cleaned from the list of frequently used apps");
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(screenshotStatusReceiver);
        unregisterReceiver(packageMonitor);
        unregisterReceiver(screenshotServiceStoppedReceiver);
        unregisterReceiver(noteStatusReceiver);
    }

    private void hideUnusedPreferences() {
        Preference dataExchangePref = findPreference("data_exchange");
        if (dataExchangePref != null) {
            PreferenceScreen rootSensorPref = (PreferenceScreen) getPreferenceParent(dataExchangePref);
            rootSensorPref.removePreference(dataExchangePref);
        }
    }// Class member variable


    private void updateTakeNotesVisibility() {
        PreferenceScreen preferenceScreen = getPreferenceScreen();

        // First time, save the original preference
        if (originalTakeNotesPref == null) {
            originalTakeNotesPref = (TakeNotesPref) findPreference("take_notes_pref");
        }

        boolean isEnabled = Aware.getSetting(getApplicationContext(),
                Aware_Preferences.STATUS_NOTES).equals("true");

        PreferenceCategory studyCategory = (PreferenceCategory) findPreference("study_actions");

        if (studyCategory != null) {
            TakeNotesPref currentPref = (TakeNotesPref) findPreference("take_notes_pref");

            if (!isEnabled && currentPref != null) {
                Log.d("NOTET", "REMOVE NOTE");
                studyCategory.removePreference(currentPref);
            } else if (isEnabled && currentPref == null) {
                Log.d("NOTET", "add NOTE");
                studyCategory.addPreference(originalTakeNotesPref);
            }
        }
    }
    
    /**
     * Check if core permissions are granted
     */
    private boolean checkCorePermissions() {
        ArrayList<String> corePermissions = new ArrayList<>();
        corePermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        corePermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            corePermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        for (String permission : corePermissions) {
            if (PermissionChecker.checkSelfPermission(this, permission) != PermissionChecker.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get permissions required based on currently enabled sensors from study configuration
     */
    private ArrayList<String> getStudyRequiredPermissions() {
        ArrayList<String> requiredPermissions = new ArrayList<>();
        Set<String> uniquePermissions = new HashSet<>();
        
        // Always include core permissions
        uniquePermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        uniquePermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            uniquePermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        // Get study configuration
        JSONObject studyConfig = Aware.getStudyConfig(getApplicationContext(), 
            Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));
            
        if (studyConfig != null) {
            try {
                if (studyConfig.has("sensors")) {
                    JSONArray sensors = studyConfig.getJSONArray("sensors");
                    
                    for (int i = 0; i < sensors.length(); i++) {
                        JSONObject sensorConfig = sensors.getJSONObject(i);
                        String sensorSetting = sensorConfig.getString("setting");
                        
                        // Only add permissions for enabled sensors
                        // Check if sensor is enabled - value can be boolean, string, or numeric
                        boolean isEnabled = false;
                        Object value = sensorConfig.get("value");
                        if (value instanceof Boolean) {
                            isEnabled = (Boolean) value;
                        } else if (value instanceof String) {
                            isEnabled = "true".equalsIgnoreCase((String) value) || !"".equals(value);
                        } else if (value instanceof Number) {
                            // If it's a number (frequency), consider it enabled if > 0
                            isEnabled = ((Number) value).intValue() > 0;
                        }
                        
                        if (isEnabled) {
                            List<String> sensorPermissions = SensorPermissionMapper.getRequiredPermissions(sensorSetting);
                            uniquePermissions.addAll(sensorPermissions);
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing study configuration for permissions", e);
            }
        }
        
        // Convert set back to list
        requiredPermissions.addAll(uniquePermissions);
        return requiredPermissions;
    }
    
    /**
     * Check current permissions and request any missing ones
     */
    private void checkAndRequestPermissions() {
        ArrayList<String> studyPermissions = getStudyRequiredPermissions();
        ArrayList<String> missingPermissions = new ArrayList<>();
        
        for (String permission : studyPermissions) {
            if (PermissionChecker.checkSelfPermission(this, permission) != PermissionChecker.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        
        if (!missingPermissions.isEmpty()) {
            requestMissingPermissions(missingPermissions);
        }
    }
    
    /**
     * Request the specified missing permissions
     */
    private void requestMissingPermissions(ArrayList<String> missingPermissions) {
        Log.d(TAG, "Requesting " + missingPermissions.size() + " missing permissions");
        
        Intent permissionsHandler = new Intent(this, PermissionsHandler.class);
        permissionsHandler.putStringArrayListExtra(PermissionsHandler.EXTRA_REQUIRED_PERMISSIONS, missingPermissions);
        permissionsHandler.putExtra(PermissionsHandler.EXTRA_REDIRECT_ACTIVITY, getPackageName() + "/" + getClass().getName());
        permissionsHandler.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(permissionsHandler);
    }
    
    /**
     * Show a dialog explaining why a specific sensor needs permissions
     */
    private void handleSessionExpired() {
        // Clear the stored MediaProjection data
        ScreenShot.mediaProjectionResultCode = 0;
        ScreenShot.mediaProjectionResultData = null;
        
        // Reset restart count for session expiry
        screenshotRestartCount = 0;
        
        // Request new MediaProjection permission
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Aware_Client.this, 
                    "Screenshot permission expired. Please grant permission again.", 
                    Toast.LENGTH_LONG).show();
                
                // Request new permission after a short delay
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT).equals("true")) {
                            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                            Intent intent = projectionManager.createScreenCaptureIntent();
                            startActivityForResult(intent, REQUEST_CODE_SCREENSHOT);
                        }
                    }
                }, 1000);
            }
        });
    }
    
    private boolean checkScreenshotPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PermissionChecker.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED &&
                   PermissionChecker.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED;
        }
        return true;
    }
    
    private void showScreenshotServiceError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(Aware_Client.this)
                    .setTitle("Screenshot Service Error")
                    .setMessage(message)
                    .setPositiveButton("Settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Open app settings
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        }
                    })
                    .setNegativeButton("Disable", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Disable screenshot service
                            Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT, false);
                            CheckBoxPreference screenshotPref = (CheckBoxPreference) findPreference(Aware_Preferences.STATUS_SCREENSHOT);
                            if (screenshotPref != null) {
                                screenshotPref.setChecked(false);
                            }
                        }
                    })
                    .show();
            }
        });
    }
    
    private void showSensorPermissionDialog(final String sensorKey, final ArrayList<String> missingPermissions) {
        final String sensorName = AwareUtil.getSensorType(sensorKey);
        
        StringBuilder message = new StringBuilder();
        message.append(sensorName).append(" requires the following permissions:\n\n");
        
        for (String permission : missingPermissions) {
            String permissionName = permission.substring(permission.lastIndexOf('.') + 1);
            String description = SensorPermissionMapper.getPermissionDescription(permission);
            message.append("• ").append(permissionName).append("\n  ").append(description).append("\n\n");
        }
        
        message.append("Would you like to grant these permissions?");
        
        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message.toString())
            .setPositiveButton("Grant Permissions", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Store the sensor key so we can re-enable it after permissions are granted
                    prefs.edit().putString("pending_sensor_enable", sensorKey).apply();
                    requestMissingPermissions(missingPermissions);
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // User cancelled - sensor remains disabled
                    Toast.makeText(Aware_Client.this, sensorName + " will remain disabled", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }


}
