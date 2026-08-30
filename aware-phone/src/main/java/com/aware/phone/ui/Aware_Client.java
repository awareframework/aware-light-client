package com.aware.phone.ui;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
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
import android.os.SystemClock;
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
import android.text.format.DateUtils;
import android.util.Log;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.EditText;
import android.widget.Toast;

import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.ESM;
import com.aware.Notes;
import com.aware.phone.R;
import com.aware.phone.ui.dialogs.JoinStudyDialog;
import com.aware.phone.ui.dialogs.QuitStudyDialog;
import com.aware.phone.ui.prefs.SensorCollection;
import com.aware.ui.ESM_Queue;
import com.aware.phone.ui.prefs.StudyCard;
import com.aware.phone.ui.prefs.TakeNotesPref;
import com.aware.phone.utils.AwareUtil;
import com.aware.providers.Aware_Provider;
import com.aware.ui.PermissionsHandler;
import com.aware.utils.SensorAvailability;
import com.aware.utils.Jdbc;
import com.aware.utils.StudyUtils;
import com.aware.utils.UploadHealth;
import com.aware.ScreenShot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
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
    // Generated "previously joined studies" rows (device mode); tracked so we can refresh them.
    private final ArrayList<Preference> studyHistoryPrefs = new ArrayList<>();
    // Keep the originally inflated sensor screens even when locked mode temporarily removes them.
    // This lets a study-config update add/remove only affected rows without recreating the Activity.
    private final Map<String, PreferenceScreen> sensorPreferenceScreens = new LinkedHashMap<>();

    private BroadcastReceiver screenshotServiceStoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ScreenShot.ACTION_SCREENSHOT_SERVICE_STOPPED.equals(intent.getAction())) {
                // A stopped/invalid MediaProjection cannot safely be restarted with the old token.
                // Re-entering here created a stop -> broadcast -> restart loop.
                Log.w(TAG, "Screenshot capture stopped; waiting for a visible user-initiated restart");
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

    // Rebuild the screen when a study config update is applied, so newly enabled/disabled sensors
    // appear immediately after "Sync config" — no re-join needed.
    private BroadcastReceiver studyConfigUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Aware.ACTION_AWARE_STUDY_CONFIG_UPDATE_AVAILABLE.equals(intent.getAction())) {
                showStudyConfigUpdatePreview(
                        intent.getStringArrayListExtra(Aware.EXTRA_SENSORS_ADDED),
                        intent.getStringArrayListExtra(Aware.EXTRA_SENSORS_REMOVED),
                        intent.getBooleanExtra(
                                Aware.EXTRA_CONFIG_UPDATE_ALLOWED_CHANGED, false)
                                ? intent.getBooleanExtra(
                                        Aware.EXTRA_CONFIG_UPDATE_ALLOWED_NEW_VALUE, false)
                                : null);
            } else if (Aware.ACTION_AWARE_STUDY_CONFIG_UPDATED.equals(intent.getAction())) {
                ArrayList<String> added = intent.getStringArrayListExtra(Aware.EXTRA_SENSORS_ADDED);
                ArrayList<String> removed = intent.getStringArrayListExtra(Aware.EXTRA_SENSORS_REMOVED);
                Boolean configUpdateAllowedNewValue = intent.getBooleanExtra(Aware.EXTRA_CONFIG_UPDATE_ALLOWED_CHANGED, false)
                        ? intent.getBooleanExtra(Aware.EXTRA_CONFIG_UPDATE_ALLOWED_NEW_VALUE, false) : null;
                boolean manual = intent.getBooleanExtra(Aware.EXTRA_CONFIG_UPDATE_MANUAL, false);
                // Don't clear the pending notice here: this receiver stays registered (and keeps
                // receiving broadcasts) even while the Activity is merely stopped/backgrounded, not
                // just while visible — so a dialog "shown" here may never actually be seen. Only
                // notifyStudyConfigUpdated()'s own dismiss handler, which only fires once the
                // participant has actually interacted with a visible dialog, clears it.
                notifyStudyConfigUpdated(added, removed, configUpdateAllowedNewValue, manual);
            }
        }
    };

    private boolean studyConfigPreviewOpen = false;

    private void showStudyConfigUpdatePreview(
            ArrayList<String> added,
            ArrayList<String> removed,
            Boolean configUpdateAllowedNewValue) {
        if (studyConfigPreviewOpen || isFinishing()) return;
        studyConfigPreviewOpen = true;
        dismissOpenSubPrefDialogIfAny();

        StringBuilder message = new StringBuilder(
                "The server has a different sensor configuration.\n"
                        + "Review the changes before replacing your current settings.");
        if (added != null && !added.isEmpty()) {
            message.append("\n\nServer sensors to activate:\n• ")
                    .append(TextUtils.join("\n• ", added));
        }
        if (removed != null && !removed.isEmpty()) {
            message.append("\n\nYour active sensors to deactivate:\n• ")
                    .append(TextUtils.join("\n• ", removed));
        }
        if ((added == null || added.isEmpty()) && (removed == null || removed.isEmpty())) {
            message.append("\n\nThe update changes sensor frequencies or other study settings.");
        }
        if (configUpdateAllowedNewValue != null) {
            message.append(configUpdateAllowedNewValue
                    ? "\n\nAfter this update you can adjust the sensor settings for this study yourself."
                    : "\n\nAfter this update, the researcher manages the sensor settings for this study.");
        }
        message.append("\n\nAgreeing replaces your local sensor configuration. "
                + "If new sensors need permission, you will review consent next.");

        new AlertDialog.Builder(this)
                .setTitle("Study update available")
                .setMessage(message.toString())
                .setPositiveButton("Agree and update", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent approved = new Intent(Aware.ACTION_AWARE_SYNC_CONFIG);
                        approved.putExtra(Aware.SYNC_CONFIG_EXTRA_TOAST, true);
                        approved.putExtra(Aware.SYNC_CONFIG_EXTRA_MANUAL, true);
                        approved.putExtra(Aware.SYNC_CONFIG_EXTRA_APPROVED, true);
                        sendBroadcast(approved);
                    }
                })
                .setNegativeButton("Keep my settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        keepParticipantStudySettings();
                    }
                })
                .setNeutralButton("Leave study", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Aware.setSetting(
                                getApplicationContext(),
                                Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL,
                                "");
                        new QuitStudyDialog(Aware_Client.this).showDialog();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        keepParticipantStudySettings();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        studyConfigPreviewOpen = false;
                    }
                })
                .show();
    }

    private void keepParticipantStudySettings() {
        Aware.setSetting(
                getApplicationContext(),
                Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL,
                "");
        Aware.logStudyCompliance(
                getApplicationContext(),
                "participant declined server config update and kept local settings");
        Toast.makeText(
                getApplicationContext(),
                "Your current sensor settings were kept.",
                Toast.LENGTH_SHORT).show();
    }

    private void showPendingStudyConfigApprovalIfAny() {
        if (!Aware.isStudy(getApplicationContext()) || isStudySettingsLocked()) return;
        String pending = Aware.getSetting(
                getApplicationContext(),
                Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL);
        if (pending == null || pending.trim().length() == 0) return;
        try {
            JSONObject local = Aware.getActiveStudyConfig(getApplicationContext());
            JSONObject server = new JSONObject(pending);
            Set<String> localSensors = activeSensorNames(local);
            Set<String> serverSensors = activeSensorNames(server);

            ArrayList<String> added = new ArrayList<>();
            for (String sensor : serverSensors) {
                if (!localSensors.contains(sensor)) added.add(sensor);
            }
            ArrayList<String> removed = new ArrayList<>();
            for (String sensor : localSensors) {
                if (!serverSensors.contains(sensor)) removed.add(sensor);
            }
            showStudyConfigUpdatePreview(
                    added,
                    removed,
                    editableModeValueChanged(local, server));
        } catch (JSONException e) {
            Aware.setSetting(
                    getApplicationContext(),
                    Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL,
                    "");
        }
    }

    private Set<String> activeSensorNames(JSONObject config) {
        Set<String> active = new HashSet<>();
        JSONArray sensors = config == null ? null : config.optJSONArray("sensors");
        if (sensors == null) return active;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;
            String setting = sensor.optString("setting", "");
            // Skip sensors whose hardware this device lacks: the participant can never turn them on,
            // so they must not appear in the "activate/deactivate" preview as an actionable change.
            // Mirrors StudyUtils, which now excludes the same sensors from the update decision.
            if (setting.startsWith("status_")
                    && !SensorAvailability.isHardwareAvailable(getApplicationContext(), setting)) {
                continue;
            }
            if (setting.startsWith("status_") && sensor.optBoolean("value", false)) {
                active.add(setting.substring("status_".length()).replace('_', ' '));
            }
        }
        return active;
    }

    private static Boolean editableModeValueChanged(
            JSONObject localConfig, JSONObject serverConfig) {
        Boolean local = sensorBooleanValue(
                localConfig, Aware_Preferences.ENABLE_CONFIG_UPDATE);
        Boolean server = sensorBooleanValue(
                serverConfig, Aware_Preferences.ENABLE_CONFIG_UPDATE);
        return server == null || server.equals(local) ? null : server;
    }

    private static Boolean sensorBooleanValue(JSONObject config, String settingName) {
        JSONArray sensors = config == null ? null : config.optJSONArray("sensors");
        if (sensors == null) return null;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor != null
                    && settingName.equals(sensor.optString("setting", ""))) {
                return sensor.optBoolean("value", false);
            }
        }
        return null;
    }

    /**
     * If a study config update was applied while no UI was around to receive the live broadcast
     * (the sync runs on its own schedule regardless of whether the app is open), show it now.
     */
    private void showPendingStudyUpdateNoticeIfAny() {
        String pending = Aware.getSetting(getApplicationContext(), Aware_Preferences.PENDING_STUDY_UPDATE_NOTICE);
        if (pending == null || pending.trim().length() == 0) return;

        try {
            JSONObject notice = new JSONObject(pending);
            ArrayList<String> added = new ArrayList<>();
            JSONArray addedJson = notice.optJSONArray("added");
            if (addedJson != null) {
                for (int i = 0; i < addedJson.length(); i++) added.add(addedJson.getString(i));
            }
            ArrayList<String> removed = new ArrayList<>();
            JSONArray removedJson = notice.optJSONArray("removed");
            if (removedJson != null) {
                for (int i = 0; i < removedJson.length(); i++) removed.add(removedJson.getString(i));
            }
            Boolean configUpdateAllowedNewValue = notice.optBoolean("cfgChanged", false)
                    ? notice.optBoolean("cfgNewValue", false) : null;
            notifyStudyConfigUpdated(
                    added, removed, configUpdateAllowedNewValue,
                    notice.optBoolean("manual", false));
        } catch (JSONException e) {
            e.printStackTrace();
            Aware.setSetting(getApplicationContext(), Aware_Preferences.PENDING_STUDY_UPDATE_NOTICE, "");
        }
    }

    /** Guards against stacking the re-auth dialog on repeated onResume() calls. */
    private boolean reauthDialogShowing = false;

    /** Live trigger: shows the re-auth prompt as soon as a background sync detects a rotated password. */
    private final BroadcastReceiver reauthRequiredReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showPendingReauthIfAny();
        }
    };

    /**
     * If a password-join study's stored database password was rejected during a background sync
     * (the researcher rotated it), prompt the participant to re-enter it now. Background sync sets
     * {@link Aware_Preferences#PENDING_STUDY_REAUTH} but cannot prompt, so the request waits here
     * until the app is open. A successful re-auth resumes collection with no re-join.
     */
    private void showPendingReauthIfAny() {
        String studyUrl = Aware.getSetting(getApplicationContext(), Aware_Preferences.PENDING_STUDY_REAUTH);
        if (studyUrl == null || studyUrl.trim().length() == 0) return;
        if (reauthDialogShowing) return;

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Study password");

        reauthDialogShowing = true;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Study password required")
                .setMessage("This study now requires you to enter its password to keep contributing "
                        + "data. Please enter the password provided by the researcher.\n\n"
                        + "If you tap Later, data collection is paused until you enter the password. "
                        + "You'll be asked again the next time the study updates or you open the app. "
                        + "You can also leave the study at any time.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Submit", null) // overridden in onShow so a wrong password keeps the dialog open
                .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        reauthDialogShowing = false;
                        d.dismiss();
                        Toast.makeText(Aware_Client.this,
                                "Data collection paused until you enter the study password.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String entered = input.getText().toString();
                        if (entered.length() == 0) {
                            input.setError("Enter a password");
                            return;
                        }
                        new ReauthTask(dialog, entered).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                });
            }
        });
        dialog.show();
    }

    /** Verifies a participant-entered study password off the main thread and reports the outcome. */
    private class ReauthTask extends AsyncTask<Void, Void, Jdbc.ConnectionResult> {
        private final AlertDialog dialog;
        private final String password;

        ReauthTask(AlertDialog dialog, String password) {
            this.dialog = dialog;
            this.password = password;
        }

        @Override
        protected void onPreExecute() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }

        @Override
        protected Jdbc.ConnectionResult doInBackground(Void... voids) {
            return StudyUtils.reauthenticateStudy(getApplicationContext(), password);
        }

        @Override
        protected void onPostExecute(Jdbc.ConnectionResult result) {
            if (isFinishing()) {
                reauthDialogShowing = false;
                return;
            }
            if (result == Jdbc.ConnectionResult.OK) {
                reauthDialogShowing = false;
                dialog.dismiss();
                Toast.makeText(Aware_Client.this, "Study password updated.", Toast.LENGTH_LONG).show();
            } else if (result == Jdbc.ConnectionResult.AUTH_FAILED) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                Toast.makeText(Aware_Client.this, "Password still incorrect.", Toast.LENGTH_LONG).show();
            } else {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                Toast.makeText(Aware_Client.this, "Can't reach the server. Try again later.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Tracks the currently-open nested PreferenceScreen dialog (e.g. "AWARE Study"). */
    private Dialog openSubPrefDialog = null;

    private void dismissOpenSubPrefDialogIfAny() {
        if (openSubPrefDialog != null && openSubPrefDialog.isShowing()) {
            openSubPrefDialog.dismiss();
        }
        openSubPrefDialog = null;
    }

    /**
     * Tells the participant the study changed — sensors added/removed, and/or whether they can now
     * edit their own settings — then applies a targeted sensor-list diff. If nothing curated changed
     * (e.g. a threshold/frequency tweak the participant doesn't need to know about), there's nothing
     * to show and nothing worth refreshing.
     */
    private void notifyStudyConfigUpdated(ArrayList<String> added, ArrayList<String> removed,
                                          Boolean configUpdateAllowedNewValue,
                                          boolean manual) {
        boolean hasChanges = (added != null && !added.isEmpty()) || (removed != null && !removed.isEmpty())
                || configUpdateAllowedNewValue != null;
        if (!hasChanges || isFinishing()) {
            return;
        }

        dismissOpenSubPrefDialogIfAny();

        // Some newly-added sensors need the participant's permission before they can collect and were
        // held off until agreed. Offer a "Review" action when any are pending, and word the "added"
        // line so it doesn't claim those are already collecting.
        JSONObject activeConfig = Aware.getActiveStudyConfig(getApplicationContext());
        JSONArray activeConfigs = new JSONArray();
        if (activeConfig != null) activeConfigs.put(activeConfig);
        final boolean hasHeld = SensorCollection.hasHeldConsents(getApplicationContext(), activeConfigs);

        // An explicit check is the participant's request to adopt the server configuration now.
        // If that introduces sensors requiring consent, continue directly into the consent screen
        // instead of making them acknowledge one dialog merely to open the next one.
        if (manual && hasHeld) {
            Aware.setSetting(
                    getApplicationContext(), Aware_Preferences.PENDING_STUDY_UPDATE_NOTICE, "");
            refreshSensorPreferencesForCurrentMode();
            Toast.makeText(
                    getApplicationContext(),
                    "Study updated. Review the permissions required by its sensors.",
                    Toast.LENGTH_LONG).show();
            Intent consent = new Intent(getApplicationContext(), SensorConsentActivity.class);
            consent.putExtra(SensorConsentActivity.EXTRA_UPDATE_MODE, true);
            startActivity(consent);
            return;
        }
        if (manual) {
            // The participant already approved these exact changes in the preview dialog.
            // Refresh the list without asking them to acknowledge the same update a second time.
            Aware.setSetting(
                    getApplicationContext(), Aware_Preferences.PENDING_STUDY_UPDATE_NOTICE, "");
            refreshSensorPreferencesForCurrentMode();
            return;
        }

        StringBuilder msg = new StringBuilder("The study was updated by the researcher.\n");
        if (added != null && !added.isEmpty()) {
            msg.append("\nAdded to the study:\n• ").append(TextUtils.join("\n• ", added));
        }
        if (removed != null && !removed.isEmpty()) {
            msg.append("\n\nNo longer collecting:\n• ").append(TextUtils.join("\n• ", removed));
        }
        if (configUpdateAllowedNewValue != null) {
            msg.append("\n\n").append(configUpdateAllowedNewValue
                    ? "You can now adjust the sensor settings for this study yourself."
                    : "The researcher now manages the sensor settings for this study.");
        }
        if (hasHeld) {
            msg.append("\n\nSome added sensors need your permission before they can collect. Review them now?");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Study updated")
                .setMessage(msg.toString())
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        // Only clear here — once the participant has actually seen and dismissed a
                        // visible dialog — not eagerly when merely attempting to show one (see the
                        // comment on studyConfigUpdatedReceiver for why that was unsafe).
                        Aware.setSetting(getApplicationContext(), Aware_Preferences.PENDING_STUDY_UPDATE_NOTICE, "");
                        // Add/remove only affected sensor rows; keep the Activity and surrounding UI.
                        if (!isFinishing()) refreshSensorPreferencesForCurrentMode();
                    }
                });
        if (hasHeld) {
            builder.setPositiveButton("Review", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent consent = new Intent(getApplicationContext(), SensorConsentActivity.class);
                    consent.putExtra(SensorConsentActivity.EXTRA_UPDATE_MODE, true);
                    consent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(consent);
                }
            });
            builder.setNegativeButton("Not now", null);
        } else {
            builder.setPositiveButton("OK", null);
        }
        builder.show();
    }

    /** Saves the full XML-defined sensor list before locked-mode filtering removes any rows. */
    private void cacheSensorPreferenceScreens() {
        sensorPreferenceScreens.clear();
        Preference sensors = findPreference("sensors");
        if (!(sensors instanceof PreferenceCategory)) return;
        PreferenceCategory category = (PreferenceCategory) sensors;
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            Preference child = category.getPreference(i);
            if (child instanceof PreferenceScreen && SensorCollection.isSensor(child.getKey())) {
                sensorPreferenceScreens.put(child.getKey(), (PreferenceScreen) child);
            }
        }
    }

    /**
     * Reconciles cached sensor screens with the current mode/config, relying on PreferenceGroup's
     * own hierarchy notifications. Then refreshes only status preferences in rows that are visible.
     */
    private void refreshSensorPreferencesForCurrentMode() {
        Preference sensors = findPreference("sensors");
        if (!(sensors instanceof PreferenceCategory)) return;
        PreferenceCategory category = (PreferenceCategory) sensors;
        JSONObject config = Aware.getActiveStudyConfig(getApplicationContext());
        boolean showAll = !isStudySettingsLocked();
        ArrayList<Preference> visibleStatuses = new ArrayList<>();

        for (PreferenceScreen screen : sensorPreferenceScreens.values()) {
            boolean shouldShow = showAll || isSensorActiveInConfig(screen, config);
            boolean attached = getPreferenceParent(screen) == category;
            if (shouldShow && !attached) {
                category.addPreference(screen);
            } else if (!shouldShow && attached) {
                category.removePreference(screen);
            }
            if (shouldShow) {
                collectStatusPreferences(screen, visibleStatuses);
            }
        }

        if (!visibleStatuses.isEmpty()) {
            new SettingsSync().executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR,
                    visibleStatuses.toArray(new Preference[visibleStatuses.size()]));
        }
    }

    private static void collectStatusPreferences(
            PreferenceGroup group, ArrayList<Preference> destination) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child.getKey() != null && child.getKey().startsWith("status_")) {
                destination.add(child);
            }
            if (child instanceof PreferenceGroup) {
                collectStatusPreferences((PreferenceGroup) child, destination);
            }
        }
    }

    private static boolean isSensorActiveInConfig(PreferenceScreen screen, JSONObject config) {
        if (config == null) return false;
        HashSet<String> statusKeys = new HashSet<>();
        ArrayList<Preference> statuses = new ArrayList<>();
        collectStatusPreferences(screen, statuses);
        for (Preference status : statuses) statusKeys.add(status.getKey());

        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return false;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor != null
                    && sensor.optBoolean("value", false)
                    && statusKeys.contains(sensor.optString("setting"))) {
                return true;
            }
        }
        return false;
    }

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
            cacheSensorPreferenceScreens();

            // Initialize plugin navigation
            setupPluginNavigation();
        } else {
            setContentView(R.layout.activity_aware);
            addPreferencesFromResource(R.xml.pref_aware_device);

            // Device mode: list previously joined studies below "Join a study".
            populateStudyHistory();
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

        // Only permissions the AWARE core itself needs to start are requested up front.
        // Sensor-specific permissions (location, phone state, bluetooth scanning, etc.) are requested on demand by each sensor's Service (see Aware_Sensor.onStartCommand)

        // Core sync framework (account creation + SyncAdapters). GET_ACCOUNTS is only
        // needed below API 26: from Android 8.0 onward, Account Visibility lets an app
        // see/manage an account it created itself (ours, via Aware_Accounts' own
        // AbstractAccountAuthenticator) without this permission -- requesting it anyway
        // is what put a Contacts-labelled prompt in front of participants for no reason.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            REQUIRED_PERMISSIONS.add(Manifest.permission.GET_ACCOUNTS);
        REQUIRED_PERMISSIONS.add(Manifest.permission.WRITE_SYNC_SETTINGS);
        REQUIRED_PERMISSIONS.add(Manifest.permission.READ_SYNC_SETTINGS);
        REQUIRED_PERMISSIONS.add(Manifest.permission.READ_SYNC_STATS);

        // Core storage (local database, data export, certificates)
        REQUIRED_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);

        // Background survival, can ask enabling additional Accesibility settings
        REQUIRED_PERMISSIONS.add(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) REQUIRED_PERMISSIONS.add(Manifest.permission.FOREGROUND_SERVICE);

        boolean PERMISSIONS_OK = true;
        for (String p : REQUIRED_PERMISSIONS) {
            if (PermissionChecker.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                PERMISSIONS_OK = false;
                break;
            }
        }
        if (PERMISSIONS_OK) {
            Intent aware = new Intent(this, Aware.class);
            startService(aware);
        }

        IntentFilter awarePackages = new IntentFilter();
        awarePackages.addAction(Intent.ACTION_PACKAGE_ADDED);
        awarePackages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        awarePackages.addDataScheme("package");
        registerReceiver(packageMonitor, awarePackages);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1
                || !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent whitelisting = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            whitelisting.setData(Uri.parse("package:" + getPackageName()));
            startActivity(whitelisting);
        }

        // Register the broadcast receiver
        registerReceiver(screenshotServiceStoppedReceiver, new IntentFilter(ScreenShot.ACTION_SCREENSHOT_SERVICE_STOPPED));
        registerReceiver(screenshotStatusReceiver, new IntentFilter(ScreenShot.ACTION_SCREENSHOT_STATUS));
        registerReceiver(noteStatusReceiver, new IntentFilter(Notes.ACTION_NOTE_STATUS));
        IntentFilter studyConfigUpdates =
                new IntentFilter(Aware.ACTION_AWARE_STUDY_CONFIG_UPDATED);
        studyConfigUpdates.addAction(Aware.ACTION_AWARE_STUDY_CONFIG_UPDATE_AVAILABLE);
        registerReceiver(studyConfigUpdatedReceiver, studyConfigUpdates);
        registerReceiver(reauthRequiredReceiver,
                new IntentFilter(Aware.ACTION_AWARE_STUDY_REAUTH_REQUIRED));
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
        // In a study, tapping a sensor shows its data-collection status instead of the locked settings
        // — unless the researcher opted in to participant edits via enable_config_update.
        if (isStudySettingsLocked()
                && preference instanceof PreferenceScreen
                && SensorCollection.isSensor(preference.getKey())) {
            showSensorCollectionDialog((PreferenceScreen) preference);
            return true;
        }
        if (preference instanceof PreferenceScreen) {
            // Editable mode opens the sensor's settings screen; surface the same collection status the
            // locked view shows in a dialog as a row at the top of that screen.
            if (SensorCollection.isSensor(preference.getKey())) {
                showSensorStatusRow((PreferenceScreen) preference);
            }
            Dialog subpref = ((PreferenceScreen) preference).getDialog();
            ViewGroup root = (ViewGroup) subpref.findViewById(android.R.id.content).getParent();
            Toolbar toolbar = new Toolbar(this);
            toolbar.setBackgroundColor(ContextCompat.getColor(preferenceScreen.getContext(), R.color.primary));
            toolbar.setTitleTextColor(ContextCompat.getColor(preferenceScreen.getContext(), android.R.color.white));
            toolbar.setTitle(preference.getTitle());
            root.addView(toolbar, 0); //add to the top

            openSubPrefDialog = subpref;
            subpref.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    if (openSubPrefDialog == dialog) openSubPrefDialog = null;
                    new SettingsSync().execute(preference);
                }
            });
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    /**
     * A millisecond timestamp as time elapsed since it, or {@code absent} when there is no such
     * timestamp. Android time formatting lives here so the status-text helpers stay pure.
     *
     * @param absent what to render for a missing timestamp; null lets the caller's own wording apply
     */
    private static CharSequence relativeTimeOr(long timestampMs, String absent) {
        if (timestampMs <= 0) return absent;
        return DateUtils.getRelativeTimeSpanString(
                timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    }

    /** Shows whether the given sensor is currently collecting data, and if not, why + what to do. */
    private void showSensorCollectionDialog(PreferenceScreen sensor) {
        boolean accessibilityOn = isAccessibilityServiceEnabled(this, Applications.class);
        SensorCollection.Status status =
                SensorCollection.getStatus(getApplicationContext(), sensor.getKey(), accessibilityOn);

        JSONObject activeConfig = Aware.getActiveStudyConfig(getApplicationContext());
        final JSONArray activeConfigs = new JSONArray();
        if (activeConfig != null) activeConfigs.put(activeConfig);
        final List<SensorCollection.ConsentItem> heldForCategory =
                SensorCollection.heldConsentsForCategory(
                        getApplicationContext(), activeConfigs, sensor.getKey());

        StringBuilder msg = new StringBuilder(SensorCollection.statusSummary(
                status,
                relativeTimeOr(status.lastDataMs, "never"),
                relativeTimeOr(SensorCollection.lastDeliveredMs(
                        getApplicationContext(), sensor.getKey()), null)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(sensor.getTitle())
                .setMessage(msg.toString());

        // A category can already be collecting while one of its other consent choices is still off
        // (Applications contains both app usage and masked keyboard text). Keep re-consent reachable
        // in that case instead of hiding it merely because the category has recent application data.
        final SensorCollection.ConsentItem consent = SensorCollection.consentItemForCategory(sensor.getKey());
        if (!heldForCategory.isEmpty()) {
            msg.append("\n\nWaiting for your consent: ");
            List<String> heldLabels = new ArrayList<>();
            for (SensorCollection.ConsentItem held : heldForCategory) heldLabels.add(held.label);
            msg.append(TextUtils.join(", ", heldLabels));
            builder.setMessage(msg.toString());
            builder.setPositiveButton("Review consent", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent review = new Intent(getApplicationContext(), SensorConsentActivity.class);
                    review.putExtra(SensorConsentActivity.EXTRA_UPDATE_MODE, true);
                    startActivity(review);
                }
            });
            builder.setNegativeButton("Close", null);
        } else if (!status.collecting && consent != null) {
            builder.setPositiveButton("Enable", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    enableConsentSensor(consent);
                }
            });
            builder.setNegativeButton("Close", null);
        } else {
            builder.setPositiveButton("OK", null);
        }
        builder.show();
    }

    /**
     * Inserts (or refreshes) a non-selectable row at the top of a sensor's settings screen showing the
     * same collecting / why / last-data information the locked view presents in a dialog, so the
     * participant sees a sensor's live status while editing it.
     */
    private void showSensorStatusRow(PreferenceScreen screen) {
        String rowKey = screen.getKey() + "_collection_status";
        Preference row = screen.findPreference(rowKey);
        if (row == null) {
            row = new Preference(this);
            row.setKey(rowKey);
            row.setSelectable(false);
            row.setOrder(-1); // above the sensor's own settings
            screen.addPreference(row);
        }

        boolean accessibilityOn = isAccessibilityServiceEnabled(this, Applications.class);
        SensorCollection.Status status =
                SensorCollection.getStatus(getApplicationContext(), screen.getKey(), accessibilityOn);
        row.setTitle(SensorCollection.statusHeadline(status));
        row.setSummary(SensorCollection.statusDetail(
                status.reason,
                relativeTimeOr(status.lastDataMs, "never"),
                relativeTimeOr(SensorCollection.lastDeliveredMs(
                        getApplicationContext(), screen.getKey()), null),
                status.fixHint));

        // A physical sensor that does not exist can never collect, so its Activate checkbox must not
        // imply otherwise. Keep the screen open for the explanatory status row and other information,
        // but disable only its activation control and clear any stale enabled value.
        if (!SensorCollection.isHardwareAvailable(getApplicationContext(), screen.getKey())) {
            Preference activation = screen.findPreference("status_" + screen.getKey());
            if (activation instanceof CheckBoxPreference) {
                CheckBoxPreference checkbox = (CheckBoxPreference) activation;
                checkbox.setEnabled(false);
                checkbox.setSummary("Unavailable on this device");
                if (checkbox.isChecked()) {
                    revertingUnavailablePreference = true;
                    try {
                        Aware.setSetting(getApplicationContext(), activation.getKey(), false);
                        checkbox.setChecked(false);
                    } finally {
                        revertingUnavailablePreference = false;
                    }
                    Aware.startAWARE(getApplicationContext());
                }
            }
        }
    }

    private static final int RC_ENABLE_SENSOR = 47001;

    // The consent group whose permission request is in flight from enableConsentSensor(), so the
    // result callback can follow up (e.g. nudge for background location once Location is granted).
    private String pendingEnableConsentKey;

    /**
     * Participant-initiated enable of a study sensor they hadn't consented to: undo the decline, turn
     * on the sub-settings the study actually wants, start collection, and route to whatever grant is
     * still missing (runtime permission dialog, or the accessibility / Location-services screens).
     */
    private void enableConsentSensor(SensorCollection.ConsentItem consent) {
        // Un-decline this consent group so the config sync won't force it back off.
        Set<String> declined = new HashSet<>(Arrays.asList(
                Aware.getSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")));
        List<String> controlled = SensorCollection.controlledSettings(consent);
        declined.removeAll(controlled);
        declined.remove("");
        Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS,
                TextUtils.join(",", declined));

        // Turn on only the sub-settings the study config enables (fall back to the whole group if the
        // config can't be read), so enabling "Calls & messages" doesn't switch on more than the study wants.
        List<String> toEnable = SensorCollection.configEnabledSettings(
                Aware.getActiveStudyConfig(getApplicationContext()),
                controlled.toArray(new String[controlled.size()]));
        if (toEnable.isEmpty()) toEnable = Arrays.asList(consent.statusSettings);
        for (String setting : toEnable) {
            Aware.setSetting(getApplicationContext(), setting, true);
        }

        JSONArray configs = new JSONArray();
        JSONObject activeConfig = Aware.getActiveStudyConfig(getApplicationContext());
        if (activeConfig != null) configs.put(activeConfig);
        Aware.logStudyCompliance(getApplicationContext(),
                "consent updated: " + SensorCollection.consentStateSummary(configs, declined));

        Aware.startAWARE(getApplicationContext());

        promptGrantsFor(consent);
    }

    /**
     * Route the participant to whatever grant a just-enabled sensor still needs — the accessibility
     * service for app/keyboard/screen sensors, or its runtime permissions — skipping anything already
     * in place. Shared by the study "Enable" path and by a direct checkbox toggle in editable mode, so
     * enabling a sensor always surfaces the same consent prompts. No-op for sensors that need neither.
     */
    private void promptGrantsFor(SensorCollection.ConsentItem consent) {
        if (consent == null) return;
        if (consent.needsAccessibility) {
            // The accessibility service is a single shared toggle, so if it's already on (enabled for
            // another accessibility sensor) there's nothing to send them to.
            if (!SensorCollection.isAccessibilityServiceEnabled(getApplicationContext())) {
                enableAccessibilityService();
            }
        } else if (consent.permissions.length > 0) {
            pendingEnableConsentKey = consent.key;
            ActivityCompat.requestPermissions(this, consent.permissions, RC_ENABLE_SENSOR);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_ENABLE_SENSOR) {
            // Whatever was granted, (re)start AWARE so the just-enabled sensor comes up now.
            Aware.startAWARE(getApplicationContext());
            // Foreground location alone stops logging when AWARE isn't open — nudge for "Allow all
            // the time" so location collected from here is complete, just like the consent screen does.
            if ("locations".equals(pendingEnableConsentKey)
                    && !SensorCollection.hasBackgroundLocation(getApplicationContext())) {
                promptAlwaysLocation();
            }
            pendingEnableConsentKey = null;
        }
    }

    private void promptAlwaysLocation() {
        new AlertDialog.Builder(this)
                .setTitle("Set location to \"Allow all the time\"")
                .setMessage("To record the places you visit continuously — even when AWARE isn't open — " +
                        "set this app's Location permission to \"Allow all the time\". With only " +
                        "\"While using the app\", location is recorded just while AWARE is open, so the data " +
                        "will be incomplete.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    // Guards the revert below from re-triggering itself: reverting a preference re-persists it,
    // which fires this listener again for the same key.
    private boolean revertingStudyPreference = false;
    private boolean revertingUnavailablePreference = false;

    /**
     * Settings stay researcher-controlled while enrolled in a study, unless the researcher opted
     * in to participant edits via the study config's enable_config_update setting.
     */
    private boolean isStudySettingsLocked() {
        boolean inStudy = Aware.isStudy(getApplicationContext());
        boolean editsAllowed = Boolean.valueOf(Aware.getSetting(getApplicationContext(), Aware_Preferences.ENABLE_CONFIG_UPDATE));
        return inStudy && !editsAllowed;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (revertingUnavailablePreference) return;

        // onPreferenceTreeClick only stops navigating INTO a sensor's screen — it doesn't stop a
        // change from taking effect once a checkbox is visible and tapped. Enforce it here too, at
        // the point the value actually gets written, so a participant can never actually flip a
        // researcher-controlled setting while in a study (unless enable_config_update allows it).
        if (!revertingStudyPreference && isStudySettingsLocked()) {
            revertingStudyPreference = true;
            try {
                String currentValue = Aware.getSetting(getApplicationContext(), key);
                Preference pref = findPreference(key);
                if (CheckBoxPreference.class.isInstance(pref)) {
                    ((CheckBoxPreference) pref).setChecked(currentValue.equals("true"));
                } else if (EditTextPreference.class.isInstance(pref)) {
                    ((EditTextPreference) pref).setText(currentValue);
                } else if (ListPreference.class.isInstance(pref)) {
                    ((ListPreference) pref).setValue(currentValue);
                }
            } finally {
                revertingStudyPreference = false;
            }
            return;
        }

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

        // Defense in depth for unavailable physical sensors. The editable screen disables the
        // checkbox, but reject the write as well in case another preference/UI path attempts it.
        if ("true".equals(value)
                && key.startsWith("status_")
                && !SensorAvailability.isHardwareAvailable(getApplicationContext(), key)) {
            revertingUnavailablePreference = true;
            try {
                Aware.setSetting(getApplicationContext(), key, false);
                Preference unavailable = findPreference(key);
                if (unavailable instanceof CheckBoxPreference) {
                    ((CheckBoxPreference) unavailable).setChecked(false);
                }
            } finally {
                revertingUnavailablePreference = false;
            }
            Aware.startAWARE(getApplicationContext());
            Toast.makeText(getApplicationContext(),
                    "This sensor is unavailable on this device", Toast.LENGTH_SHORT).show();
            return;
        }

        Aware.setSetting(getApplicationContext(), key, value);
        Preference pref = findPreference(key);

        // In editable study mode, the participant's sensor choices are the effective study
        // configuration rather than temporary drift from the server JSON. Persist the typed value
        // into the active study row and append a compliance event so the researcher sees the config
        // that actually produced this device's uploaded data.
        if (isPreferenceInsideSensorScreen(pref)) {
            StudyUtils.persistEditableSensorSetting(getApplicationContext(), key, value);
        }

        if (CheckBoxPreference.class.isInstance(pref)) {
            CheckBoxPreference check = (CheckBoxPreference) findPreference(key);
            check.setChecked(Aware.getSetting(getApplicationContext(), key).equals("true"));

            //update the parent to show active/inactive
            new SettingsSync().execute(pref);

            //Start/Stop sensor
            Aware.startAWARE(getApplicationContext());

            // Turning a sensor on directly (editable mode) still needs its grants: the accessibility
            // service for app/keyboard/screen sensors, or runtime permissions. Prompt the same way the
            // study "Enable" path does, so the consent dialogs show up here too.
            if (value.equals("true")) {
                promptGrantsFor(SensorCollection.consentItemForSetting(key));
            }
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

    private boolean isPreferenceInsideSensorScreen(Preference preference) {
        Preference current = preference;
        while (current != null) {
            PreferenceGroup parent = getPreferenceParent(current);
            if (parent == null) return false;
            if (SensorCollection.isSensor(parent.getKey())) return true;
            current = parent;
        }
        return false;
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
        // Several status_* preferences can belong to the same sensor screen. Refresh that parent
        // only once per sync pass; each individual checkbox/value is still reconciled above.
        private final Set<String> refreshedSensorParents = new HashSet<>();

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
                if (!refreshedSensorParents.add(parent.getKey())) return;

                boolean inStudy = Aware.isStudy(getApplicationContext());
                boolean prefEnabled = Boolean.valueOf(Aware.getSetting(Aware_Client.this, Aware_Preferences.ENABLE_CONFIG_UPDATE));
                // In a study the settings stay researcher-controlled, but keep the row tappable so
                // the participant can open the data-collection status dialog (click is intercepted
                // in onPreferenceTreeClick, so the editable sub-screen never opens).
                parent.setEnabled(inStudy || prefEnabled);
                boolean shouldDisableView = !inStudy;
                if (parent.getShouldDisableView() != shouldDisableView) {
                    parent.setShouldDisableView(shouldDisableView);
                }

                ListAdapter children = parent.getRootAdapter();
                ArrayList sensorStatuses = new ArrayList<String>();
                for (int i = 0; i < children.getCount(); i++) {
                    Object obj = children.getItem(i);
                    if (CheckBoxPreference.class.isInstance(obj)) {
                        CheckBoxPreference child = (CheckBoxPreference) obj;
                        if (child.getKey().contains("status_")) {
                            sensorStatuses.add(child.getKey());
                        }
                    }
                }

                // Check if any of the status settings of a sensor (parent pref) is active in the study config
                JSONObject studyConfig = Aware.getActiveStudyConfig(getApplicationContext());
                boolean isActiveInConfig = false;
                ArrayList<String> activeSensorStatuses = new ArrayList<String>();
                try {
                    JSONArray sensorsList = studyConfig.getJSONArray("sensors");
                    for (int i = 0; i < sensorsList.length(); i++) {
                        JSONObject sensorInfo = sensorsList.getJSONObject(i);
                        String sensorSetting = sensorInfo.getString("setting");

                        if (sensorStatuses.contains(sensorSetting)) {
                            boolean sensorEnabled = sensorInfo.getBoolean("value");
                            if (sensorEnabled) {
                                isActiveInConfig = true;
                                activeSensorStatuses.add(sensorSetting);
                            }
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                // Locked study view lists only the sensors the study actually collects. In editable
                // mode (not in a study, or the study allowed edits via enable_config_update) show every
                // sensor so the participant can enable/disable any of them, not just the study's set.
                boolean showAllSensors = !isStudySettingsLocked();
                if (isActiveInConfig || showAllSensors) {
                    if (pref != null) Log.i(TAG, "Pref with key: " + pref.getKey() + " is shown");
                    try {
                        Class res = R.drawable.class;
                        Field field = res.getField("ic_action_" + parent.getKey());
                        int icon_id = field.getInt(null);
                        Drawable category_icon = ContextCompat.getDrawable(getApplicationContext(), icon_id);
                        if (category_icon != null) {
                            // Blue if AWARE is actually collecting this sensor's data (recent rows
                            // in its provider), grey otherwise. Tap the sensor for the reason.
                            boolean accessibilityOn = isAccessibilityServiceEnabled(getApplicationContext(), Applications.class);
                            SensorCollection.Status collectionStatus =
                                    SensorCollection.getStatus(getApplicationContext(), parent.getKey(), accessibilityOn);
                            int colorId = collectionStatus.collecting ? R.color.accent : R.color.lightGray;
                            category_icon.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(getApplicationContext(), colorId), PorterDuff.Mode.SRC_IN));
                            parent.setIcon(category_icon);
                            // Editable mode deliberately lists hardware-backed sensors even when this
                            // phone cannot provide them. Make that permanent limitation explicit in
                            // the list itself; opening the row shows the fuller status and last-data
                            // detail added by showSensorStatusRow().
                            if (showAllSensors
                                    && !SensorCollection.isHardwareAvailable(
                                            getApplicationContext(), parent.getKey())) {
                                parent.setSummary(collectionStatus.reason);
                            }
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
                    Log.w(TAG, "Screenshot capture retry limit reached; not auto-restarting");
                }
            }
        }
    };

    private void checkAndStartScreenshotService() {
        // Only act when the active study actually enabled screenshot. Outside a study, or in a
        // study that doesn't use screenshot, do nothing — and never prompt for accessibility on
        // plain app start / device mode. Screenshot is the only sensor started here and the only
        // reason this path needs accessibility, so gating on it also gates the accessibility ask.
        if (!Aware.isStudy(this)) return;
        if (!Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT).equals("true")) return;

        if (!isAccessibilityServiceEnabled(this, Applications.class)) {
            enableAccessibilityService();
            return;
        }

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

private AlertDialog accessibilityDialog;

// Whether each prompt has already been shown this session (this Activity's lifetime), so neither
// is re-shown on every subsequent onResume once the participant has dismissed it.
private boolean accessibilityPromptedThisSession = false;
private boolean locationServicesPromptedThisSession = false;

private void enableAccessibilityService() {
    enableAccessibilityService(null);
}

/**
 * @param onResolved run when the dialog is dismissed, whichever button the participant chose;
 *                   pass null for no follow-up action.
 */
private void enableAccessibilityService(final Runnable onResolved) {
    if (accessibilityDialog != null && accessibilityDialog.isShowing()) {
        return; // already prompting; don't stack dialogs on repeated onResume
    }
    accessibilityPromptedThisSession = true;
    final ComponentName service = new ComponentName(this, Applications.class);

    accessibilityDialog = new AlertDialog.Builder(this)
        .setTitle("Enable AWARE accessibility")
        .setMessage("AWARE needs the Accessibility service to record app usage and screen content. On the next screen, find \"AWARE\", open it, and turn the switch ON.")
        .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                        details.putExtra("android.intent.extra.COMPONENT_NAME", service.flattenToString());
                        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(details);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "Accessibility detail settings unavailable, falling back to list", e);
                    }
                }
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Toast.makeText(Aware_Client.this,
                            "Please open Settings > Accessibility and enable AWARE.",
                            Toast.LENGTH_LONG).show();
                }
            }
        })
        .setNegativeButton("Not now", null)
        .setCancelable(true)
        .setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (onResolved != null) onResolved.run();
            }
        })
        .show();
    }

    private AlertDialog locationServicesDialog;

    /**
     * WiFi scanning requires the OS-level Location toggle on system-wide (Android blocks
     * WifiManager.startScan() with a SecurityException otherwise, regardless of granted
     * permissions) — prompt the participant to enable it, mirroring enableAccessibilityService().
     */
    private void enableLocationServices() {
        if (locationServicesDialog != null && locationServicesDialog.isShowing()) {
            return; // already prompting; don't stack dialogs on repeated onResume
        }
        locationServicesPromptedThisSession = true;
        locationServicesDialog = new AlertDialog.Builder(this)
            .setTitle("Enable Location services")
            .setMessage("This study collects WiFi data, which requires Location services to be turned on for the whole phone (Android requires this even though AWARE doesn't use your location for WiFi scanning). On the next screen, turn Location ON.")
            .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(Aware_Client.this,
                                "Please open Settings > Location and turn it on.",
                                Toast.LENGTH_LONG).show();
                    }
                }
            })
            .setNegativeButton("Not now", null)
            .setCancelable(true)
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    locationServicesDialog = null;
                }
            })
            .show();
    }

    /**
     * Fills in the Device section's delivery line: how far the research database has been brought up
     * to, and whether delivery is currently failing.
     *
     * Shown here because the per-sensor delivery detail is one tap into each sensor, so a phone that
     * has stopped delivering everything looks normal from the list. The pending-record count is not
     * passed: it would mean counting rows in every provider on the main thread, and the line reads
     * correctly without it.
     */
    private void showDataDeliveryStatus() {
        Preference delivery = findPreference("data_delivery");
        if (delivery == null) return;

        long deliveredUpTo = UploadHealth.deliveredUpToMs(this);
        CharSequence relative = deliveredUpTo > 0
                ? DateUtils.getRelativeTimeSpanString(deliveredUpTo, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS)
                : null;
        delivery.setSummary(UploadHealth.statusLine(
                relative, UploadHealth.failingTables(this), 0));
    }

    /**
     * Prompts for the accessibility service and the OS-level Location toggle the joined study needs,
     * one dialog at a time. When both are needed the accessibility prompt is shown first and the
     * Location prompt follows only once it is dismissed, so the two non-cancelable dialogs are never
     * shown together. Each prompt is shown at most once per session.
     */
    private void promptForRequiredServices() {
        if (Aware.is_watch(this) || !Aware.isStudy(this)) return;

        boolean needsAccessibility = studyNeedsAccessibility()
                && !isAccessibilityServiceEnabled(this, Applications.class);

        if (needsAccessibility && !accessibilityPromptedThisSession) {
            enableAccessibilityService(new Runnable() {
                @Override
                public void run() {
                    promptForLocationServicesIfNeeded();
                }
            });
        } else if (accessibilityDialog == null || !accessibilityDialog.isShowing()) {
            promptForLocationServicesIfNeeded();
        }
    }

    /**
     * Shows the Location-services prompt when the joined study needs WiFi, the OS Location toggle is
     * off, and it hasn't already been shown this session.
     */
    private void promptForLocationServicesIfNeeded() {
        if (Aware.is_watch(this) || !Aware.isStudy(this)) return;
        if (locationServicesPromptedThisSession) return;
        if (studyNeedsWifi() && !SensorCollection.isLocationServicesEnabled(this)) {
            enableLocationServices();
        }
    }

    /**
     * True if the joined study currently has WiFi scanning enabled, i.e. Location services are
     * actually needed right now. Used to avoid prompting participants in studies that don't use it.
     */
    private boolean studyNeedsWifi() {
        return "true".equalsIgnoreCase(Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_WIFI));
    }

    /**
     * Device mode only: lists previously joined studies in the "study_actions" category, below
     * the Join button. Each row opens a details dialog with re-join / copy link / delete actions.
     * Safe to call again to refresh (e.g. after a delete).
     */
    private void populateStudyHistory() {
        PreferenceCategory studyActions = (PreferenceCategory) findPreference("study_actions");
        if (studyActions == null) return;

        for (Preference p : studyHistoryPrefs) studyActions.removePreference(p);
        studyHistoryPrefs.clear();

        List<ContentValues> studies = Aware.getJoinedStudies(getApplicationContext());
        for (final ContentValues study : studies) {
            Preference row = new Preference(this);
            String title = study.getAsString(Aware_Provider.Aware_Studies.STUDY_TITLE);
            row.setTitle((title == null || title.trim().length() == 0) ? "(untitled study)" : title);
            row.setSummary(studyHistorySummary(study));
            row.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showStudyHistoryDialog(study);
                    return true;
                }
            });
            studyActions.addPreference(row);
            studyHistoryPrefs.add(row);
        }
    }

    private String studyHistorySummary(ContentValues study) {
        Double joined = study.getAsDouble(Aware_Provider.Aware_Studies.STUDY_JOINED);
        Double exit = study.getAsDouble(Aware_Provider.Aware_Studies.STUDY_EXIT);
        String status = (exit == null || exit == 0) ? "Enrolled" : "Left";
        if (joined != null && joined > 0)
            return "Joined " + DateFormat.getDateInstance().format(new Date(joined.longValue())) + " · " + status;
        return status;
    }

    /** Details + management dialog for a past study: view (the card), re-join, copy link, delete. */
    private void showStudyHistoryDialog(final ContentValues study) {
        View card = getLayoutInflater().inflate(R.layout.study_card, null);
        StudyCard.bind(this, card, study);
        final String url = study.getAsString(Aware_Provider.Aware_Studies.STUDY_URL);

        new AlertDialog.Builder(this)
                .setView(card)
                .setPositiveButton("Re-join", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        rejoinStudy(url);
                    }
                })
                .setNeutralButton("Copy link", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AwareUtil.copyToClipboard(Aware_Client.this, "AWARE study link", url);
                    }
                })
                .setNegativeButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmDeleteStudy(study);
                    }
                })
                .show();
    }

    private void rejoinStudy(String url) {
        if (url == null || url.length() == 0) return;
        // Reuse the standard join dialog (properly shown/attached) with the URL pre-filled,
        // rather than the direct Aware_Join_Study URL path, which crashes (unattached fragment).
        new JoinStudyDialog(this).setStudyUrl(url).showDialog();
    }

    private void confirmDeleteStudy(final ContentValues study) {
        final String url = study.getAsString(Aware_Provider.Aware_Studies.STUDY_URL);

        // Guard: never delete the study we're currently enrolled in.
        if (url != null && Aware.isStudy(getApplicationContext())) {
            Cursor active = Aware.getActiveStudy(getApplicationContext());
            boolean isActive = false;
            if (active != null) {
                if (active.moveToFirst()) {
                    isActive = url.equals(active.getString(
                            active.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                }
                active.close();
            }
            if (isActive) {
                Toast.makeText(this, "You can't delete the study you're currently in.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete from history")
                .setMessage("Remove this study from your history? This does not affect any data already uploaded to the server.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (url != null && url.length() > 0) {
                            getContentResolver().delete(Aware_Provider.Aware_Studies.CONTENT_URI,
                                    Aware_Provider.Aware_Studies.STUDY_URL + "=?", new String[]{url});
                        }
                        populateStudyHistory();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

        Intent serviceIntent = new Intent(this, ScreenShot.class);
        serviceIntent.putExtra(ScreenShot.MEDIA_PROJECTION_RESULT_CODE, resultCode);
        serviceIntent.putExtra(ScreenShot.MEDIA_PROJECTION_RESULT_DATA, resultData);
        serviceIntent.putExtra(ScreenShot.CAPTURE_TIME_INTERVAL, captureInterval * 1000); // Convert to milliseconds
        serviceIntent.putExtra(ScreenShot.COMPRESS_RATE, compressRate);
        serviceIntent.putExtra(ScreenShot.STATUS_SCREENSHOT_LOCAL_STORAGE, saveToLocal);
        ContextCompat.startForegroundService(this, serviceIntent);
    }


    private void stopScreenshotService() {
        Intent serviceIntent = new Intent(this, ScreenShot.class);
        stopService(serviceIntent);
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
                updateDeclinedSensor(Aware_Preferences.STATUS_SCREENSHOT, false);
                startScreenshotService(resultCode, data);
            } else {
                // Persist this as a participant decline. Otherwise the study drift reconciler sees
                // server=true/local=false and repeatedly re-enables screenshot, causing the capture
                // consent Activity to return on later launches.
                Aware.setSetting(getApplicationContext(),
                        Aware_Preferences.STATUS_SCREENSHOT, false);
                updateDeclinedSensor(Aware_Preferences.STATUS_SCREENSHOT, true);
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateDeclinedSensor(String statusSetting, boolean declined) {
        Set<String> settings = new HashSet<>();
        String raw = Aware.getSetting(
                getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS);
        if (raw != null) {
            for (String setting : raw.split(",")) {
                if (!setting.trim().isEmpty()) settings.add(setting.trim());
            }
        }
        if (declined) settings.add(statusSetting);
        else settings.remove(statusSetting);
        Aware.setSetting(getApplicationContext(),
                Aware_Preferences.STUDY_DECLINED_SENSORS,
                TextUtils.join(",", settings));
    }

    private boolean isAccessibilityServiceEnabled(Context context, Class<?> accessibilityServiceClass) {
        final ComponentName expected = new ComponentName(context, accessibilityServiceClass);
        final String settingValue = Settings.Secure.getString(
                context.getApplicationContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue == null) {
            return false;
        }

        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(settingValue);
        while (colonSplitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(colonSplitter.next());
            if (expected.equals(enabled)) {
                return true;
            }
        }

        return false;
    }

    private boolean isCollectionAvailable(ArrayList<String> activeSensorStatuses) {
        boolean accessibilityEnabled = true;
        for (String setting : activeSensorStatuses) {
            if (requiresAccessibility(setting)) {
                if (accessibilityEnabled) {
                    accessibilityEnabled = isAccessibilityServiceEnabled(this, Applications.class);
                }
                if (!accessibilityEnabled) return false;
            }
        }
        return true;
    }

    private boolean requiresAccessibility(String setting) {
        return Aware_Preferences.STATUS_APPLICATIONS.equals(setting)
                || Aware_Preferences.STATUS_NOTIFICATIONS.equals(setting)
                || Aware_Preferences.STATUS_CRASHES.equals(setting)
                || Aware_Preferences.STATUS_SCREENTEXT.equals(setting)
                || Aware_Preferences.STATUS_KEYBOARD.equals(setting)
                || Aware_Preferences.STATUS_TOUCH.equals(setting);
    }

    /**
     * True if any accessibility-backed sensor is currently enabled in settings, i.e. the
     * accessibility service is actually needed for data collection right now. Used to avoid
     * prompting participants in studies that don't rely on accessibility.
     */
    private boolean studyNeedsAccessibility() {
        final String[] accessibilitySensors = {
                Aware_Preferences.STATUS_APPLICATIONS,
                Aware_Preferences.STATUS_NOTIFICATIONS,
                Aware_Preferences.STATUS_CRASHES,
                Aware_Preferences.STATUS_SCREENTEXT,
                Aware_Preferences.STATUS_KEYBOARD,
                Aware_Preferences.STATUS_TOUCH
        };
        for (String setting : accessibilitySensors) {
            if ("true".equalsIgnoreCase(Aware.getSetting(getApplicationContext(), setting))) {
                return true;
            }
        }
        return false;
    }



    // Debounces the onResume() sync trigger below: rapid app-switching or configuration changes can
    // fire onResume() several times in quick succession. Each one spawns its own background thread
    // in Aware's receiver (syncStudyConfig -> a network fetch + a DB-credential check that can
    // block), so without this, those can stack up concurrently for no benefit — the config can't
    // meaningfully have changed again within a few seconds of the last check. static + elapsedRealtime
    // so it survives this Activity being recreated and isn't affected by wall-clock changes.
    private static volatile long lastSyncConfigBroadcastAtMs = 0;
    private static final long SYNC_CONFIG_DEBOUNCE_MS = 10_000;

    /**
     * Put an unanswered question back on screen when the participant opens the app.
     *
     * Skipped while one is already being answered: ESM_Queue is then the activity
     * in front, and starting it again would restart the participant's answering.
     */
    private void showOpenESMIfAny() {
        try {
            if (ESM.isESMVisible(getApplicationContext())) return;
            if (!ESM.hasOpenESM(getApplicationContext())) return;
            Intent queue = new Intent(this, ESM_Queue.class);
            queue.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(queue);
        } catch (Exception e) {
            // A question that cannot be shown must not stop the app from opening.
            if (Aware.DEBUG) Log.d("Aware_Client", "Could not bring an open ESM forward: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Locked studies keep reconciling on app open. Editable studies intentionally retain the
        // participant's local configuration until they explicitly tap "Check for study updates".
        if (Aware.isStudy(getApplicationContext()) && isStudySettingsLocked()) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSyncConfigBroadcastAtMs >= SYNC_CONFIG_DEBOUNCE_MS) {
                lastSyncConfigBroadcastAtMs = now;
                sendBroadcast(new Intent(Aware.ACTION_AWARE_SYNC_CONFIG));
            }
        }

        // Restore an editable-mode update proposal if the Activity was stopped/recreated while the
        // participant was deciding. Nothing is applied until they explicitly agree.
        showPendingStudyConfigApprovalIfAny();

        // Catch up on any already-applied study update that was received while this Activity wasn't
        // alive to receive the live broadcast.
        showPendingStudyUpdateNoticeIfAny();

        // A password-join study may have had its password rotated; background sync flags it but
        // cannot prompt, so ask for the new password here while the app is open.
        showPendingReauthIfAny();

        // A question the participant has not answered is brought forward here. Until
        // now the only way back to one was the notification, so a swipe or a cleared
        // shade lost it for good --- the question stayed in the queue and nothing
        // ever put it on screen again. Opening the app is the one moment they are
        // certainly looking at the phone, and an expired question is left alone: its
        // moment has passed and answering it would describe a different one.
        showOpenESMIfAny();

        permissions_ok = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String p : REQUIRED_PERMISSIONS) {
                if (PermissionChecker.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    permissions_ok = false;
                    break;
                }
            }
        }

        if (!permissions_ok) {
            Log.d(TAG, "Requesting permissions...");

            Intent permissionsHandler = new Intent(this, PermissionsHandler.class);
            permissionsHandler.putStringArrayListExtra(PermissionsHandler.EXTRA_REQUIRED_PERMISSIONS, REQUIRED_PERMISSIONS);
            permissionsHandler.putExtra(PermissionsHandler.EXTRA_REDIRECT_ACTIVITY, getPackageName() + "/" + getClass().getName());
            permissionsHandler.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permissionsHandler);

        } else {

            if (prefs.getAll().isEmpty() && Aware.getDeviceID(getApplicationContext()).length() == 0) {
                PreferenceManager.setDefaultValues(getApplicationContext(), "com.aware.phone", Context.MODE_PRIVATE, R.xml.aware_preferences, true);
                prefs.edit().commit();
            } else {
                PreferenceManager.setDefaultValues(getApplicationContext(), "com.aware.phone", Context.MODE_PRIVATE, R.xml.aware_preferences, false);
            }

            Map<String, ?> defaults = prefs.getAll();
            for (Map.Entry<String, ?> entry : defaults.entrySet()) {
                // Skip webservice_server: see the matching comment in Aware.onStartCommand()'s copy
                // of this loop — the cached "com.aware.phone" SharedPreferences default is a stale
                // placeholder URL, and copying it in here whenever the real setting is momentarily
                // empty is the same landmine as the fallback removed below, just reached via the
                // defaults cache instead of a literal.
                if (entry.getKey().equals(Aware_Preferences.WEBSERVICE_SERVER)) continue;
                if (Aware.getSetting(getApplicationContext(), entry.getKey(), "com.aware.phone").length() == 0) {
                    Aware.setSetting(getApplicationContext(), entry.getKey(), entry.getValue(), "com.aware.phone"); //default AWARE settings
                }
            }

            if (Aware.getDeviceID(getApplicationContext()).length() == 0) {
                UUID uuid = UUID.randomUUID();
                Aware.setSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID, uuid.toString(), "com.aware.phone");
            }

            // Deliberately no "if empty, default to the public AWARE demo server" fallback here
            // anymore — see the matching comment in Aware.onStartCommand(). This ran on every
            // onResume(), unsynchronized, and permanently overwrote a legitimate join URL with this
            // placeholder the instant it observed the setting momentarily empty (e.g. mid-reset()),
            // breaking study lookup with no way to self-correct afterward.

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

            showDataDeliveryStatus();

            // Prompt for the accessibility service and the OS-level Location toggle the joined study
            // needs. Shown as in-app dialogs (no tray notification) so the request is contextual and
            // actionable, one at a time and at most once per session — see the helper below.
            promptForRequiredServices();

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
                Aware.debug(this, Aware.LogType.LIFECYCLE, "AWARE stopped from background: may be caused by battery optimization");
            } else {
                Log.d("AWARE-Client", "AWARE stopped from background: may be caused by system settings");
                Aware.debug(this, Aware.LogType.LIFECYCLE, "AWARE stopped from background: may be caused by system settings");
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
            Aware.debug(this, Aware.LogType.LIFECYCLE, "AWARE interface cleaned from the list of frequently used apps");
        }
        Log.d("AWARE_Client", "AWARE interface cleaned from the list of frequently used apps");
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(screenshotStatusReceiver);
        unregisterReceiver(packageMonitor);
        unregisterReceiver(screenshotServiceStoppedReceiver);
        unregisterReceiver(noteStatusReceiver);
        unregisterReceiver(studyConfigUpdatedReceiver);
        unregisterReceiver(reauthRequiredReceiver);
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


}
