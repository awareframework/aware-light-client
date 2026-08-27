package com.aware.utils;

/**
 * Created by denzilferreira on 16/02/16.
 */

import android.app.IntentService;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.preference.PreferenceManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.ESM;
import com.aware.R;
import com.aware.providers.Aware_Provider;
import com.aware.ui.esms.ESM_Question;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Service that allows plugins/applications to send data to AWARE's dashboard study
 * Note: joins a study without requiring a QRCode, just the study URL
 */
public class StudyUtils extends IntentService {
    // "database" is not here: a study whose data goes through the webservice ships no
    // database block at all, deliberately, so that the phone never holds a credential
    // for a database it does not contact. It is required on the direct path only --
    // see firstMissingRequirement.
    private static final String[] REQUIRED_STUDY_CONFIG_KEYS = {"questions",
            "schedules", "sensors", "study_info"};
    private static final long MAX_STUDY_CONFIG_BYTES = 5L * 1024L * 1024L;
    private static final OkHttpClient STUDY_CONFIG_HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * Received broadcast to join a study
     */
    public static final String EXTRA_JOIN_STUDY = "study_url";

    public static String input_password_ = "";

    public StudyUtils() {
        super("StudyUtils Service");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String full_url = intent.getStringExtra(EXTRA_JOIN_STUDY);

        if (Aware.DEBUG) Log.d(Aware.TAG, "Joining: " + full_url);

        Uri study_uri = Uri.parse(full_url);

        List<String> path_segments = study_uri.getPathSegments();
        String protocol = study_uri.getScheme();
        String study_api_key = path_segments.get(path_segments.size() - 1);
        String study_id = path_segments.get(path_segments.size() - 2);

        // TODO RIO: Replace GET to webserver a GET to study config URL
        String request;
        if (protocol.equals("https")) {
//            SSLManager.handleUrl(getApplicationContext(), full_url, true);

            try {
                request = new Https(SSLManager.getHTTPS(getApplicationContext(), full_url)).dataGET(full_url.substring(0, full_url.indexOf("/index.php")) + "/index.php/webservice/client_get_study_info/" + study_api_key, true);
            } catch (FileNotFoundException e) {
                request = null;
            }
        } else {
            request = new Http().dataGET(full_url.substring(0, full_url.indexOf("/index.php")) + "/index.php/webservice/client_get_study_info/" + study_api_key, true);
        }

        if (request != null) {

            if (request.equals("[]")) return;

            try {
                JSONObject studyInfo = new JSONObject(request);

                //Request study settings
                Hashtable<String, String> data = new Hashtable<>();
                data.put(Aware_Preferences.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
                data.put("platform", "android");
                try {
                    PackageInfo package_info = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0);
                    data.put("package_name", package_info.packageName);
                    data.put("package_version_code", String.valueOf(package_info.versionCode));
                    data.put("package_version_name", String.valueOf(package_info.versionName));
                } catch (PackageManager.NameNotFoundException e) {
                    Log.d(Aware.TAG, "Failed to put package info: " + e);
                    e.printStackTrace();
                }

                // TODO RIO: Replace POST to webserver with DB insert
                String answer;
                if (protocol.equals("https")) {
                    try {
                        answer = new Https(SSLManager.getHTTPS(getApplicationContext(), full_url)).dataPOST(full_url, data, true);
                    } catch (FileNotFoundException e) {
                        answer = null;
                    }
                } else {
                    answer = new Http().dataPOST(full_url, data, true);
                }

                if (answer == null) {
                    Toast.makeText(getApplicationContext(), "Failed to connect to server, try again.", Toast.LENGTH_SHORT).show();
                    return;
                }

                JSONArray study_config = new JSONArray(answer);

                if (study_config.getJSONObject(0).has("message")) {
                    Toast.makeText(getApplicationContext(), "This study is no longer available.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Cursor dbStudy = Aware.getStudy(getApplicationContext(), full_url);
                if (Aware.DEBUG)
                    Log.d(Aware.TAG, LogRedactor.redact(DatabaseUtils.dumpCursorToString(dbStudy)));

                if (dbStudy == null || !dbStudy.moveToFirst()) {
                    ContentValues studyData = new ContentValues();
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_JOINED, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_KEY, study_id);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_API, study_api_key);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_URL, full_url);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_PI, studyInfo.getString("researcher_first") + " " + studyInfo.getString("researcher_last") + "\nContact: " + studyInfo.getString("researcher_contact"));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, study_config.toString());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_TITLE, studyInfo.getString("study_name"));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, studyInfo.getString("study_description"));

                    getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, studyData);

                    if (Aware.DEBUG) {
                        Log.d(Aware.TAG, LogRedactor.redact("New study data: " + studyData.toString()));
                    }
                } else {
                    //User rejoined a study he was already part of. Mark as abandoned.
                    ContentValues complianceEntry = new ContentValues();
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "rejoined study. abandoning previous");

                    getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);

                    //Update the information to the latest
                    ContentValues studyData = new ContentValues();
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_JOINED, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_KEY, study_id);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_API, study_api_key);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_URL, full_url);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_PI, studyInfo.getString("researcher_first") + " " + studyInfo.getString("researcher_last") + "\nContact: " + studyInfo.getString("researcher_contact"));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, study_config.toString());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_TITLE, studyInfo.getString("study_name"));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, studyInfo.getString("study_description"));

                    getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, studyData);

                    if (Aware.DEBUG) {
                        Log.d(Aware.TAG, LogRedactor.redact("Rejoined study data: " + studyData.toString()));
                    }
                }

                if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                // This is a programmatic join with no consent UI, so a sensor that needs a runtime
                // permission or the Accessibility Service must not be silently switched on. Hold
                // every such sensor off (persisted, so a later config sync keeps honouring it);
                // permission-free base sensors still start, and the held ones await the participant.
                Set<String> declined = holdConsentSensorsUnlessAgreed(getApplicationContext(), study_config);
                applySettings(getApplicationContext(), full_url, study_config, false, input_password_, declined);

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Timeout for the best-effort study-exit notification. Kept short so "leave study" stays
     * responsive when the research database is unreachable (e.g. the study no longer exists).
     */
    private static final int STUDY_EXIT_UPLOAD_TIMEOUT_SECONDS = 8;

    /**
     * Best-effort, fast-failing upload of a single study-exit compliance row to the research
     * database, so the researcher is notified when the database is reachable.
     *
     * Leaving a study must never depend on this succeeding: callers un-enroll locally regardless of
     * the result. A {@code false} return means "could not notify" (e.g. the database is gone), not
     * "leave failed".
     *
     * @param context   application context
     * @param exitEntry the study-exit row (the same values written to the local studies provider)
     * @return true if the research database acknowledged the exit row, false otherwise
     */
    public static boolean uploadStudyExit(Context context, ContentValues exitEntry) {
        if (exitEntry == null) return false;
        try {
            JSONObject row = new JSONObject();
            for (Map.Entry<String, Object> value : exitEntry.valueSet()) {
                row.put(value.getKey(), value.getValue());
            }
            // Whichever path the study uses. The server derives every enrolment window
            // from `aware_studies`, so a study event that does not arrive leaves the
            // window open and the participant looking like they never left.
            JSONArray rows = new JSONArray().put(row);
            return Webservice.enabled(context)
                    ? Webservice.insertDataFastFail(context, "aware_studies", rows,
                            STUDY_EXIT_UPLOAD_TIMEOUT_SECONDS)
                    : Jdbc.insertDataFastFail(context, "aware_studies", rows,
                            STUDY_EXIT_UPLOAD_TIMEOUT_SECONDS);
        } catch (Exception e) {
            Log.e(Aware.TAG, "Study-exit notification could not be built", e);
            return false;
        }
    }

    /**
     * Timeout for a database credential probe. Short so a rotated-password check (or a re-auth
     * attempt) never hangs the UI when the server is slow or unreachable.
     */
    private static final int STUDY_PROBE_TIMEOUT_SECONDS = 8;

    /**
     * Attempts to re-authenticate the active study with a participant-entered password.
     *
     * On {@link Jdbc.ConnectionResult#OK} the password is stored, the pending re-auth flag is
     * cleared, and a config sync is triggered so collection resumes with no re-join. On any other
     * result nothing is changed and the outcome is returned so the UI can tell the participant
     * whether the password was wrong or the server was unreachable.
     *
     * @param context     application context
     * @param newPassword the password the participant entered
     * @return the probe result
     */
    /**
     * Builds a study compliance row (same columns the join/quit flows use) from the active-study
     * cursor, tagged with the given compliance reason.
     */
    private static ContentValues complianceRow(Context context, Cursor study, String compliance) {
        ContentValues cv = new ContentValues();
        cv.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID,
                Aware.getDeviceID(context));
        cv.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
        cv.put(Aware_Provider.Aware_Studies.STUDY_KEY,
                study.getInt(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_API,
                study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_URL,
                study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_PI,
                study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_JOINED,
                study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_EXIT,
                study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_EXIT)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_TITLE,
                study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION,
                study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
        cv.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, compliance);
        return cv;
    }

    public static Jdbc.ConnectionResult reauthenticateStudy(Context context, String newPassword) {
        JSONObject dbInfo = null;
        ContentValues resumedRow = null;
        Cursor study = Aware.getActiveStudy(context);
        if (study != null && study.moveToFirst()) {
            try {
                JSONObject config = new JSONObject(study.getString(
                        study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
                dbInfo = config.optJSONObject("database");
            } catch (JSONException e) {
                Log.e(Aware.TAG, "Re-auth: stored study config is unreadable");
            }
            resumedRow = complianceRow(context, study,
                    "collection resumed after password re-authentication");
        }
        if (study != null && !study.isClosed()) study.close();

        if (dbInfo == null) return Jdbc.ConnectionResult.UNREACHABLE;

        Jdbc.ConnectionResult result = Jdbc.probeConnection(context,
                dbInfo.optString("database_host", ""),
                dbInfo.optString("database_port", ""),
                dbInfo.optString("database_name", ""),
                dbInfo.optString("database_username", ""),
                newPassword,
                STUDY_PROBE_TIMEOUT_SECONDS);

        if (result == Jdbc.ConnectionResult.OK) {
            Aware.setSetting(context, Aware_Preferences.DB_PASSWORD, newPassword);
            Aware.setSetting(context, Aware_Preferences.PENDING_STUDY_REAUTH, "");
            // Re-authenticated inside the app, so the notification was never tapped and
            // setAutoCancel did not fire. Collection has resumed; clear it.
            cancelStudyNotification(context, Aware.AWARE_STUDY_REAUTH_NOTIFICATION_ID);
            // Audit the recovery for the researcher: the data gap was the live signal; this row is
            // the record, uploaded now that the credentials are valid again.
            if (resumedRow != null) {
                context.getContentResolver().insert(
                        Aware_Provider.Aware_Studies.CONTENT_URI, resumedRow);
                try {
                    JSONObject json = new JSONObject();
                    for (Map.Entry<String, Object> e : resumedRow.valueSet()) {
                        json.put(e.getKey(), e.getValue());
                    }
                    JSONArray audit = new JSONArray().put(json);
                    if (Webservice.enabled(context)) {
                        Webservice.insertData(context, "aware_studies", audit);
                    } else {
                        Jdbc.insertData(context, "aware_studies", audit);
                    }
                } catch (Exception e) {
                    Log.e(Aware.TAG, "Failed to upload re-auth audit row", e);
                }
            }
            // Resume collection/upload with no re-join.
            syncStudyConfig(context, false);
        }
        return result;
    }

    /**
     * Sets first all the settings to the client.
     * If there are plugins, apply the same settings to them.
     * This allows us to add plugins to studies from the dashboard.
     *
     * @param context
     * @param configs
     */
    public static void applySettings(Context context, JSONArray configs, String input_password) {
        applySettings(context, Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER), configs, input_password);
    }

    /**
     * Sets first all the settings to the client.
     * If there are plugins, apply the same settings to them.
     * This allows us to add plugins to studies from the dashboard.
     *
     * @param context
     * @param webserviceServer
     * @param configs
     */
    public static void applySettings(Context context, String webserviceServer, JSONArray configs, String input_password) {
        applySettings(context, webserviceServer, configs, false, input_password);
    }

    /**
     * Sets first all the settings to the client.
     * If there are plugins, apply the same settings to them.
     * This allows us to add plugins to studies from the dashboard.
     *
     * @param context
     * @param webserviceServer
     * @param configs
     * @param insertCompliance true to insert a new compliance record (i.e. when updating a study)
     * @param input_password password for database if required
     */
    public static void applySettings(Context context, String webserviceServer, JSONArray configs, Boolean insertCompliance, String input_password) {
        applySettings(context, webserviceServer, configs, insertCompliance, input_password, Collections.<String>emptySet());
    }

    /**
     * Sets first all the settings to the client.
     * If there are plugins, apply the same settings to them.
     * This allows us to add plugins to studies from the dashboard.
     *
     * @param context
     * @param webserviceServer
     * @param configs
     * @param insertCompliance true to insert a new compliance record (i.e. when updating a study)
     * @param input_password password for database if required
     * @param declinedSettings status_* setting keys the participant declined consent for — these are
     *                          forced to false regardless of what {@code configs} says, so a declined
     *                          sensor is never flipped on even momentarily.
     */
    public static void applySettings(Context context, String webserviceServer, JSONArray configs, Boolean insertCompliance, String input_password, Set<String> declinedSettings) {
        boolean is_developer = Aware.getSetting(context, Aware_Preferences.DEBUG_FLAG).equals("true");
        // Preserve across the reset below, same as DEBUG_FLAG: a config re-apply (join or background
        // sync) is not a consent event, so it must not erase the participant's recorded agreement.
        // Quitting a study calls Aware.reset() directly, NOT through here — that path intentionally
        // wipes the record so the next join asks for consent again.
        String consentRecord = Aware.getSetting(context, Aware_Preferences.STUDY_CONSENT_RECORD);
        // Preserve across the reset below for the same reason, and additionally fold it into the
        // declined set enforced below: a background sync passes no declined set, but the
        // participant's persisted declines must still be honoured (not re-enabled) on every apply.
        String persistedDeclined = Aware.getSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS);

        //First reset the client to default settings...
        Aware.reset(context);

        input_password_ = input_password;
        if (is_developer) Aware.setSetting(context, Aware_Preferences.DEBUG_FLAG, true);
        if (consentRecord.length() > 0) Aware.setSetting(context, Aware_Preferences.STUDY_CONSENT_RECORD, consentRecord);
        if (persistedDeclined.length() > 0) Aware.setSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS, persistedDeclined);

        // Effective declined set = whatever the caller passed ∪ whatever the participant previously
        // persisted, so both a fresh decline (consent screen) and a standing one (background sync)
        // keep the sensor off.
        Set<String> effectiveDeclined = new HashSet<>();
        if (declinedSettings != null) effectiveDeclined.addAll(declinedSettings);
        for (String key : persistedDeclined.split(",")) {
            if (key.trim().length() > 0) effectiveDeclined.add(key.trim());
        }
        effectiveDeclined = expandGroupedConsentDeclines(effectiveDeclined);
        Aware.setSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS,
                joinSettings(effectiveDeclined));

        //Now apply the new settings
        try {
            Aware.setSetting(context, Aware_Preferences.WEBSERVICE_SERVER, webserviceServer);
            JSONObject studyConfig = configs.getJSONObject(0);  // use first config
            JSONObject studyInfo = studyConfig.optJSONObject("study_info");

            if (studyInfo == null) {
                Log.e(Aware.TAG, "Study info is missing or invalid in configuration");
                return;
            }

            // Set database settings
            try {
                JSONObject dbConfig = studyConfig.optJSONObject("database");
                if (dbConfig != null) {
                    Aware.setSetting(context, Aware_Preferences.DB_HOST, dbConfig.optString("database_host", ""));
                    Aware.setSetting(context, Aware_Preferences.DB_PORT, dbConfig.optInt("database_port", 3306));
                    Aware.setSetting(context, Aware_Preferences.DB_NAME, dbConfig.optString("database_name", ""));
                    Aware.setSetting(context, Aware_Preferences.DB_USERNAME, dbConfig.optString("database_username", ""));
                    // The authority that signed the research database's certificate, when the study
                    // publishes one. It belongs to the study rather than to the app, so it arrives
                    // with the rest of the study's database settings.
                    Aware.setSetting(context, Aware_Preferences.DB_CA,
                            dbConfig.optString(Aware_Preferences.DB_CA, ""));

                    boolean configWithoutPassword = dbConfig.optBoolean("config_without_password", false);
                    if (!configWithoutPassword) {
                        Aware.setSetting(context, Aware_Preferences.DB_PASSWORD, dbConfig.optString("database_password", ""));
                    } else {
                        Aware.setSetting(context, Aware_Preferences.DB_PASSWORD, input_password);
                    }
                } else {
                    Log.e(Aware.TAG, "Database configuration is missing");
                }
            } catch (Exception e) {
                Log.e(Aware.TAG, "Error setting database configuration: " + e.getMessage());
            }

            // Set study information
            if (insertCompliance) {
                try {
                    ContentValues studyData = new ContentValues();
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getDeviceID(context));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_API, "");
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_URL, webserviceServer);
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, studyConfig.toString());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_KEY, "0");
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_PI,
                            studyInfo.optString("researcher_first", "") + " " +
                                    studyInfo.optString("researcher_last", "") + "\nContact: " +
                                    studyInfo.optString("researcher_contact", ""));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_TITLE,
                            studyInfo.optString("study_title", studyInfo.optString("study_name", "Unknown Study")));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION,
                            studyInfo.optString("study_description", ""));
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "updated study");
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_UPDATED, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_JOINED, System.currentTimeMillis());
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_EXIT, 0);
                    context.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, studyData);
                } catch (Exception e) {
                    Log.e(Aware.TAG, "Error inserting study compliance: " + e.getMessage());
                }
            }
        } catch (JSONException e) {
            Log.e(Aware.TAG, "Error parsing study configuration: " + e.getMessage());
            return;
        }

        // Initialize arrays for different configuration types
        JSONArray plugins = new JSONArray();
        JSONArray sensors = new JSONArray();
        JSONArray schedulers = new JSONArray();
        JSONArray esm_schedules = new JSONArray();
        JSONArray questions = new JSONArray();

        // Extract configuration elements
        for (int i = 0; i < configs.length(); i++) {
            try {
                JSONObject element = configs.getJSONObject(i);

                // Extract plugins
                if (element.has("plugins") && !element.isNull("plugins")) {
                    plugins = element.getJSONArray("plugins");
                }

                // Extract sensors
                if (element.has("sensors") && !element.isNull("sensors")) {
                    sensors = element.getJSONArray("sensors");
                }

                // Extract schedulers
                if (element.has("schedulers") && !element.isNull("schedulers")) {
                    schedulers = element.getJSONArray("schedulers");
                }

                // Extract ESM schedules and questions
                if (element.has("schedules") && !element.isNull("schedules")) {
                    esm_schedules = element.getJSONArray("schedules");
                }

                if (element.has("questions") && !element.isNull("questions")) {
                    questions = element.getJSONArray("questions");
                }
            } catch (JSONException e) {
                Log.e(Aware.TAG, "Error parsing configuration element: " + e.getMessage());
            }
        }

        // Set the sensors' settings first
        processSensorSettings(context, sensors, effectiveDeclined);

        // Set the plugins' settings and prepare for activation
        ArrayList<String> active_plugins = processPluginSettings(context, plugins);

        // Process ESM settings if available
        if (questions.length() > 0 || esm_schedules.length() > 0) {
            try {
                processEsmSettings(context, questions, esm_schedules);
            } catch (Exception e) {
                Log.e(Aware.TAG, "Error processing ESM settings: " + e.getMessage(), e);
                // Continue with other settings even if ESM fails
            }
        }

        // Set other schedulers
        if (schedulers.length() > 0) {
            try {
                Scheduler.setSchedules(context, schedulers);
            } catch (Exception e) {
                Log.e(Aware.TAG, "Error setting schedulers: " + e.getMessage());
            }
        }

        // Activate plugins
        for (String package_name : active_plugins) {
            try {
                PackageInfo installed = PluginsManager.isInstalled(context, package_name);
                if (installed != null) {
                    Aware.startPlugin(context, package_name);
                } else {
                    Aware.downloadPlugin(context, package_name, null, false);
                }
            } catch (Exception e) {
                Log.e(Aware.TAG, "Error activating plugin " + package_name + ": " + e.getMessage());
            }
        }

        // Log the health of every study-enabled sensor (including unavailable/blocked/disabled,
        // frequency-based delayed data, and event-driven waiting) into aware_log, which already
        // syncs to the researcher's database — covers both the join flow and every config update,
        // since this is the one place both paths funnel through.
        try {
            SensorDiagnostics.logSensorStatus(context, sensors);
        } catch (Exception e) {
            Log.e(Aware.TAG, "Error logging sensor diagnostics: " + e.getMessage());
        }

        // Start Aware service and sync data
        Intent aware = new Intent(context, Aware.class);
        context.startService(aware);

        Intent sync = new Intent(Aware.ACTION_AWARE_SYNC_DATA);
        context.sendBroadcast(sync);
    }

    /**
     * Process and apply plugin settings from configuration
     *
     * @param context Application context
     * @param plugins JSONArray of plugin configurations
     * @return ArrayList of active plugin package names
     */
    private static ArrayList<String> processPluginSettings(Context context, JSONArray plugins) {
        ArrayList<String> active_plugins = new ArrayList<>();
        if (plugins == null) return active_plugins;

        for (int i = 0; i < plugins.length(); i++) {
            try {
                JSONObject plugin_config = plugins.getJSONObject(i);

                if (plugin_config.has("plugin")) {
                    String package_name = plugin_config.getString("plugin");
                    active_plugins.add(package_name);

                    // Apply plugin-specific settings if available
                    if (plugin_config.has("settings") && !plugin_config.isNull("settings")) {
                        JSONArray plugin_settings = plugin_config.getJSONArray("settings");
                        for (int j = 0; j < plugin_settings.length(); j++) {
                            try {
                                JSONObject plugin_setting = plugin_settings.getJSONObject(j);
                                if (plugin_setting.has("setting") && plugin_setting.has("value")) {
                                    String setting = plugin_setting.getString("setting");
                                    Object value = plugin_setting.get("value");

                                    // Apply setting based on value type
                                    if (value instanceof Boolean) {
                                        Aware.setSetting(context, setting, (Boolean) value, package_name);
                                    } else if (value instanceof Integer) {
                                        Aware.setSetting(context, setting, (Integer) value, package_name);
                                    } else if (value instanceof Double) {
                                        Aware.setSetting(context, setting, (Double) value, package_name);
                                    } else if (value instanceof String) {
                                        Aware.setSetting(context, setting, (String) value, package_name);
                                    }
                                }
                            } catch (JSONException e) {
                                Log.e(Aware.TAG, "Error processing plugin setting: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(Aware.TAG, "Error processing plugin: " + e.getMessage());
            }
        }

        return active_plugins;
    }

    /**
     * Process and apply sensor settings from configuration with extensive debug logging
     *
     * @param context Application context
     * @param sensors JSONArray of sensor configurations
     * @param declinedSettings status_* keys to force to false regardless of {@code sensors}' own value
     *                         — the participant declined consent for these, so they must never be
     *                         written true in the first place.
     */
    private static void processSensorSettings(Context context, JSONArray sensors, Set<String> declinedSettings) {
        if (sensors == null) {
            Log.d(Aware.TAG, "processSensorSettings: sensors array is null");
            return;
        }

        Log.d(Aware.TAG, "processSensorSettings: Processing " + sensors.length() + " sensor settings");

        // Track all settings to verify they're applied correctly
        HashMap<String, String> appliedSettings = new HashMap<>();

        // Mirror each setting into the UI SharedPreferences too. AWARE keeps sensor state in two
        // stores: the aware_settings provider (read by startAWARE to actually run sensors) and the
        // preference-screen SharedPreferences (what the checkboxes show and persist). Writing only
        // the provider left the checkboxes OFF and let onSharedPreferenceChanged overwrite the
        // provider back to the (false) UI default — so config-enabled sensors never activated until
        // the participant tapped them. Keeping the two stores in sync fixes that.
        SharedPreferences.Editor uiPrefs = PreferenceManager.getDefaultSharedPreferences(context).edit();

        for (int i = 0; i < sensors.length(); i++) {
            try {
                JSONObject sensor_config = sensors.getJSONObject(i);
                Log.d(Aware.TAG, "processSensorSettings: Sensor #" + i + ": " + sensor_config.toString());

                if (sensor_config.has("setting") && sensor_config.has("value")) {
                    String setting = sensor_config.getString("setting");
                    Object value = sensor_config.get("value");

                    // Declined settings are forced false here rather than written true and unflipped
                    // later, so a declined sensor never runs even momentarily.
                    if (declinedSettings.contains(setting)) {
                        Log.d(Aware.TAG, "processSensorSettings: " + setting +
                                " declined by participant, forcing value to false");
                        value = Boolean.FALSE;
                    }

                    // A server may enable every sensor generically, including hardware/platform
                    // features this phone cannot provide. Persist those status flags as false up
                    // front instead of briefly starting a doomed service. In particular Processor
                    // is blocked on Android N+, and its former start-disable-toast cycle caused
                    // config drift reconciliation to reapply the whole study repeatedly.
                    if (Boolean.TRUE.equals(value)
                            && setting.startsWith("status_")
                            && !SensorAvailability.isHardwareAvailable(context, setting)) {
                        Log.d(Aware.TAG, "processSensorSettings: " + setting
                                + " unavailable on this device, forcing value to false");
                        value = Boolean.FALSE;
                    }

                    String valueType = value.getClass().getSimpleName();

                    // WEBSERVICE_SERVER doubles as the join URL that Aware.getStudy()/isStudy()
                    // match against aware_studies.study_url. Some study configs carry a
                    // "webservice_server" sensor entry for the classic PHP-webservice upload
                    // path (a different URL, e.g. per-platform); applying it here silently
                    // overwrote the join URL and broke every getStudy() lookup from then on.
                    if (setting.equals(Aware_Preferences.WEBSERVICE_SERVER)) {
                        Log.d(Aware.TAG, "processSensorSettings: Skipping " + setting +
                                " from sensors config (owned by the join/sync URL, not overridable here)");
                        continue;
                    }

                    Log.d(Aware.TAG, "processSensorSettings: Processing setting: " + setting +
                            " with value: " + value + " (type: " + valueType + ")");

                    // Apply setting based on value type
                    try {
                        if (value instanceof Boolean) {
                            Aware.setSetting(context, setting, (Boolean) value);
                        } else if (value instanceof Integer) {
                            Aware.setSetting(context, setting, (Integer) value);
                        } else if (value instanceof Double) {
                            Aware.setSetting(context, setting, (Double) value);
                        } else if (value instanceof Long) {
                            Aware.setSetting(context, setting, ((Long) value).intValue());
                        } else if (value instanceof String) {
                            Aware.setSetting(context, setting, (String) value);
                        } else {
                            // For any other type, convert to string
                            Aware.setSetting(context, setting, value.toString());
                        }
                        // Mirror to the UI store with the type the preference persists as:
                        // CheckBoxPreference persists a Boolean; EditText/List persist a String.
                        if (value instanceof Boolean) {
                            uiPrefs.putBoolean(setting, (Boolean) value);
                        } else {
                            uiPrefs.putString(setting, String.valueOf(value));
                        }

                        appliedSettings.put(setting, value.toString());
                        Log.d(Aware.TAG, "processSensorSettings: Successfully applied setting: " + setting);
                    } catch (Exception e) {
                        Log.e(Aware.TAG, "processSensorSettings: Error applying setting " + setting +
                                ": " + e.getMessage());
                    }
                } else {
                    Log.d(Aware.TAG, "processSensorSettings: Sensor config missing required fields");
                }
            } catch (JSONException e) {
                Log.e(Aware.TAG, "processSensorSettings: JSONException: " + e.getMessage());
            }
        }

        // Commit the mirrored UI settings so the preference screen reflects the study config.
        uiPrefs.apply();

        // Verify all settings were successfully applied
        Log.d(Aware.TAG, "processSensorSettings: Verifying " + appliedSettings.size() + " applied settings");
        for (Map.Entry<String, String> entry : appliedSettings.entrySet()) {
            String key = entry.getKey();
            String expectedValue = entry.getValue();
            String actualValue = Aware.getSetting(context, key);

            if (actualValue.equals(expectedValue)) {
                Log.d(Aware.TAG, "processSensorSettings: Verified setting: " + key +
                        " = " + actualValue + " ✓");
            } else {
                Log.e(Aware.TAG, "processSensorSettings: Setting verification failed for " + key +
                        ": expected=" + expectedValue + ", actual=" + actualValue + " ✗");
            }
        }
    }

    /**
     * Process and apply ESM settings from configuration with debug logging
     *
     * @param context Application context
     * @param questions JSONArray of ESM questions
     * @param schedules JSONArray of ESM schedules
     */
    private static void processEsmSettings(Context context, JSONArray questions, JSONArray schedules) {
        Log.d(Aware.TAG, "processEsmSettings: Starting ESM processing");

        if (questions == null) {
            Log.e(Aware.TAG, "processEsmSettings: questions array is null");
            return;
        }

        if (schedules == null) {
            Log.e(Aware.TAG, "processEsmSettings: schedules array is null");
            return;
        }

        Log.d(Aware.TAG, "processEsmSettings: Found " + questions.length() + " questions and " +
                schedules.length() + " schedules");

        // Log details about each question
        for (int i = 0; i < questions.length(); i++) {
            try {
                JSONObject questionJson = questions.getJSONObject(i);
                String questionId = questionJson.optString("id", "unknown");
                String questionType = questionJson.optString("esm_type", "unknown");
                String questionTitle = questionJson.optString("esm_title", "unknown");

                Log.d(Aware.TAG, "processEsmSettings: Question #" + i +
                        ": id=" + questionId +
                        ", type=" + questionType +
                        ", title=" + questionTitle);

                Log.d(Aware.TAG, "processEsmSettings: Full question: " + questionJson.toString());
            } catch (JSONException e) {
                Log.e(Aware.TAG, "processEsmSettings: Error processing question #" + i + ": " + e.getMessage());
            }
        }

        // Log details about each schedule
        for (int i = 0; i < schedules.length(); i++) {
            try {
                JSONObject scheduleJson = schedules.getJSONObject(i);
                String scheduleTitle = scheduleJson.optString("title", "unknown");
                String scheduleType = scheduleJson.optString("type", "unknown");

                Log.d(Aware.TAG, "processEsmSettings: Schedule #" + i +
                        ": title=" + scheduleTitle +
                        ", type=" + scheduleType);

                // Log the question IDs for this schedule
                if (scheduleJson.has("questions")) {
                    Object questionsObj = scheduleJson.get("questions");
                    if (questionsObj instanceof JSONArray) {
                        JSONArray questionIds = (JSONArray) questionsObj;
                        StringBuilder idsStr = new StringBuilder();
                        for (int j = 0; j < questionIds.length(); j++) {
                            idsStr.append(questionIds.get(j)).append(", ");
                        }
                        Log.d(Aware.TAG, "processEsmSettings: Schedule questions: " + idsStr);
                    } else {
                        Log.d(Aware.TAG, "processEsmSettings: Schedule has single question: " + questionsObj);
                    }
                } else {
                    Log.e(Aware.TAG, "processEsmSettings: Schedule missing questions field");
                }

                Log.d(Aware.TAG, "processEsmSettings: Full schedule: " + scheduleJson.toString());
            } catch (JSONException e) {
                Log.e(Aware.TAG, "processEsmSettings: Error processing schedule #" + i + ": " + e.getMessage());
            }
        }

        // Create a map of question objects by their IDs
        HashMap<String, JSONObject> esm_questions = new HashMap<>();
        for (int i = 0; i < questions.length(); i++) {
            try {
                JSONObject questionJson = questions.getJSONObject(i);
                if (questionJson.has("id")) {
                    String questionId = questionJson.getString("id");
                    JSONObject esmWrapper = new JSONObject().put("esm", questionJson);
                    esm_questions.put(questionId, esmWrapper);
                    Log.d(Aware.TAG, "processEsmSettings: Mapped question ID " + questionId +
                            " to object: " + esmWrapper.toString());
                } else {
                    Log.e(Aware.TAG, "processEsmSettings: Question without ID: " + questionJson.toString());
                }
            } catch (JSONException e) {
                Log.e(Aware.TAG, "processEsmSettings: Error mapping question: " + e.getMessage());
            }
        }

        // Process each schedule
        for (int i = 0; i < schedules.length(); i++) {
            try {
                JSONObject scheduleJson = schedules.getJSONObject(i);
                Log.d(Aware.TAG, "processEsmSettings: Processing schedule: " + scheduleJson.optString("title", "unknown"));

                // Skip if no questions array or not of the expected type
                if (!scheduleJson.has("questions") || scheduleJson.isNull("questions")) {
                    Log.e(Aware.TAG, "processEsmSettings: Schedule missing questions array");
                    continue;
                }

                // Get question IDs from the schedule
                JSONArray questionIds;
                try {
                    questionIds = scheduleJson.getJSONArray("questions");
                    Log.d(Aware.TAG, "processEsmSettings: Found questions array with " +
                            questionIds.length() + " items");
                } catch (JSONException e) {
                    // Try to handle case where questions might be a single value instead of array
                    try {
                        Object questionObj = scheduleJson.get("questions");
                        questionIds = new JSONArray();
                        questionIds.put(questionObj);
                        Log.d(Aware.TAG, "processEsmSettings: Converted single question to array");
                    } catch (Exception ex) {
                        Log.e(Aware.TAG, "processEsmSettings: Invalid questions format: " + e.getMessage());
                        continue;
                    }
                }

                // Build question objects array for this schedule
                JSONArray questionObjects = new JSONArray();
                for (int j = 0; j < questionIds.length(); j++) {
                    try {
                        String questionId = String.valueOf(questionIds.get(j));
                        JSONObject questionObj = esm_questions.get(questionId);

                        if (questionObj != null) {
                            questionObjects.put(questionObj);
                            Log.d(Aware.TAG, "processEsmSettings: Added question ID " + questionId +
                                    " to schedule");
                        } else {
                            Log.e(Aware.TAG, "processEsmSettings: Question ID " + questionId +
                                    " not found in questions map");
                        }
                    } catch (JSONException e) {
                        Log.e(Aware.TAG, "processEsmSettings: Error adding question to schedule: " + e.getMessage());
                    }
                }

                // Create schedule if there are questions to add
                if (questionObjects.length() > 0) {
                    Log.d(Aware.TAG, "processEsmSettings: Creating schedule with " +
                            questionObjects.length() + " questions");
                    createEsmSchedule(context, scheduleJson, questionObjects);
                } else {
                    Log.e(Aware.TAG, "processEsmSettings: No valid questions for schedule");
                }
            } catch (JSONException e) {
                Log.e(Aware.TAG, "processEsmSettings: Error processing schedule: " + e.getMessage());
            }
        }

        Log.d(Aware.TAG, "processEsmSettings: Completed ESM processing");
    }

    /**
     * Creates a schedule for triggering ESMs with debug logging
     *
     * @param context Application context
     * @param scheduleJson JSONObject representing the schedule
     * @param esmsArray JSONArray representing the ESM questions
     */
    private static void createEsmSchedule(Context context, JSONObject scheduleJson, JSONArray esmsArray) {
        try {
            String scheduleTitle = scheduleJson.optString("title", "unnamed");
            Log.d(Aware.TAG, "createEsmSchedule: Creating schedule: " + scheduleTitle);

            // Get required schedule properties with defaults
            String type = scheduleJson.optString("type", "");
            Log.d(Aware.TAG, "createEsmSchedule: Schedule type: " + type);

            // Handle different types of "esm_keep" field (could be string or boolean)
            String keep;
            try {
                boolean keepBool = scheduleJson.getBoolean("esm_keep");
                keep = String.valueOf(keepBool);
                Log.d(Aware.TAG, "createEsmSchedule: Found esm_keep as boolean: " + keep);
            } catch (Exception e) {
                keep = scheduleJson.optString("esm_keep", "false");
                Log.d(Aware.TAG, "createEsmSchedule: Found esm_keep as string: " + keep);

                // Handle potential typo in the field name (seen in some configs)
                if (keep.equals("false") && scheduleJson.has("esm_kepp")) {
                    keep = scheduleJson.optString("esm_kepp", "false");
                    Log.d(Aware.TAG, "createEsmSchedule: Found esm_kepp (typo): " + keep);
                }
            }

            // Create schedule object
            Scheduler.Schedule schedule = new Scheduler.Schedule(scheduleTitle);

            // Set schedule parameters based on type
            switch (type.toLowerCase()) {
                case "interval":
                    Log.d(Aware.TAG, "createEsmSchedule: Configuring interval schedule");
                    try {
                        // Add days if available
                        if (scheduleJson.has("days") && !scheduleJson.isNull("days")) {
                            JSONArray days = scheduleJson.getJSONArray("days");
                            for (int i = 0; i < days.length(); i++) {
                                String day = days.getString(i);
                                schedule.addWeekday(day);
                                Log.d(Aware.TAG, "createEsmSchedule: Added day: " + day);
                            }
                        } else {
                            Log.d(Aware.TAG, "createEsmSchedule: No days specified");
                        }

                        // Add hours if available
                        if (scheduleJson.has("hours") && !scheduleJson.isNull("hours")) {
                            JSONArray hours = scheduleJson.getJSONArray("hours");
                            for (int i = 0; i < hours.length(); i++) {
                                int hour = hours.getInt(i);
                                schedule.addHour(hour);
                                Log.d(Aware.TAG, "createEsmSchedule: Added hour: " + hour);
                            }
                        } else {
                            Log.d(Aware.TAG, "createEsmSchedule: No hours specified");
                        }
                    } catch (Exception e) {
                        Log.e(Aware.TAG, "createEsmSchedule: Error setting interval: " + e.getMessage());
                    }
                    break;

                case "random":
                    Log.d(Aware.TAG, "createEsmSchedule: Configuring random schedule");
                    try {
                        // Parse first hour
                        String firstHourString = scheduleJson.optString("firsthour", "9:00");
                        Log.d(Aware.TAG, "createEsmSchedule: First hour string: " + firstHourString);

                        int firstHour;
                        try {
                            firstHour = Integer.parseInt(firstHourString.split(":")[0]);
                        } catch (Exception e) {
                            firstHour = 9; // Default to 9 AM if parsing fails
                            Log.e(Aware.TAG, "createEsmSchedule: Error parsing firsthour, using default: " + e.getMessage());
                        }

                        // Parse last hour
                        String lastHourString = scheduleJson.optString("lasthour", "21:00");
                        Log.d(Aware.TAG, "createEsmSchedule: Last hour string: " + lastHourString);

                        int lastHour;
                        try {
                            lastHour = Integer.parseInt(lastHourString.split(":")[0]);
                        } catch (Exception e) {
                            lastHour = 21; // Default to 9 PM if parsing fails
                            Log.e(Aware.TAG, "createEsmSchedule: Error parsing lasthour, using default: " + e.getMessage());
                        }

                        int randomCount = scheduleJson.optInt("randomCount", 1);
                        int randomInterval = scheduleJson.optInt("randomInterval", 30);

                        Log.d(Aware.TAG, "createEsmSchedule: Random parameters - firstHour: " + firstHour +
                                ", lastHour: " + lastHour +
                                ", count: " + randomCount +
                                ", interval: " + randomInterval);

                        // Add hours and random parameters
                        schedule.addHour(firstHour)
                                .addHour(lastHour)
                                .random(randomCount, randomInterval);
                    } catch (Exception e) {
                        Log.e(Aware.TAG, "createEsmSchedule: Error setting random schedule: " + e.getMessage());
                    }
                    break;

                case "repeat":
                    Log.d(Aware.TAG, "createEsmSchedule: Configuring repeat schedule");
                    try {
                        int repeatInterval = scheduleJson.optInt("repeatInterval", 60);
                        Log.d(Aware.TAG, "createEsmSchedule: Repeat interval: " + repeatInterval);
                        schedule.setInterval(repeatInterval);
                    } catch (Exception e) {
                        Log.e(Aware.TAG, "createEsmSchedule: Error setting repeat interval: " + e.getMessage());
                    }
                    break;

                default:
                    Log.e(Aware.TAG, "createEsmSchedule: Unknown schedule type: " + type);
                    return;
            }

            // Set trigger for ESMs as the schedule's title
            Log.d(Aware.TAG, "createEsmSchedule: Setting trigger and keep for " + esmsArray.length() + " ESMs");
            for (int i = 0; i < esmsArray.length(); i++) {
                try {
                    JSONObject esmObj = esmsArray.getJSONObject(i);
                    JSONObject esm = esmObj.getJSONObject("esm");

                    esm.put(ESM_Question.esm_trigger, scheduleTitle);
                    esm.put(ESM_Question.esm_keep, keep);

                    Log.d(Aware.TAG, "createEsmSchedule: Set trigger=" + scheduleTitle +
                            " and keep=" + keep + " for ESM #" + i);
                } catch (JSONException e) {
                    Log.e(Aware.TAG, "createEsmSchedule: Error setting ESM trigger: " + e.getMessage());
                }
            }

            // Save the schedule
            try {
                schedule.setActionType(Scheduler.ACTION_TYPE_BROADCAST)
                        .setActionIntentAction(ESM.ACTION_AWARE_QUEUE_ESM)
                        .addActionExtra(ESM.EXTRA_ESM, esmsArray.toString());

                Scheduler.saveSchedule(context, schedule);
                Log.d(Aware.TAG, "createEsmSchedule: Successfully saved schedule: " + scheduleTitle);
            } catch (Exception e) {
                Log.e(Aware.TAG, "createEsmSchedule: Error saving schedule: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            Log.e(Aware.TAG, "createEsmSchedule: Error creating ESM schedule: " + e.getMessage(), e);
        }
    }

    /**
     * Synchronizes the study configuration with the server
     *
     * @param context Application context
     * @param toast Whether to show toast messages
     */
    public static void syncStudyConfig(Context context, Boolean toast) {
        syncStudyConfig(context, toast, false, false);
    }

    /**
     * Synchronizes the study configuration with the server.
     *
     * @param manual true only when the participant explicitly tapped "Check for study updates"
     */
    public static void syncStudyConfig(Context context, Boolean toast, boolean manual) {
        syncStudyConfig(context, toast, manual, false);
    }

    /**
     * Synchronizes or previews the study configuration.
     *
     * @param approved true after the participant accepted the exact pending server configuration
     */
    public static void syncStudyConfig(
            Context context, Boolean toast, boolean manual, boolean approved) {
        if (!Aware.isStudy(context)) return;
        // Awaiting password re-authentication: don't re-probe/re-apply on every sync — it would keep
        // hammering the database with the rejected password. The pending prompt drives recovery, and
        // reauthenticateStudy() clears this flag (then re-runs this sync) once the password is fixed.
        if (Aware.getSetting(context, Aware_Preferences.PENDING_STUDY_REAUTH).trim().length() > 0) return;
        boolean editable = Boolean.parseBoolean(Aware.getSetting(
                context, Aware_Preferences.ENABLE_CONFIG_UPDATE));
        if (shouldSkipAutomaticConfigSync(editable, manual)) {
            if (Aware.DEBUG) {
                Aware.debug(context, Aware.LogType.STUDY,
                        "Skipping automatic study-config update while participant editing is enabled");
            }
            return;
        }

        // Aware.getActiveStudy() instead of Aware.getStudy(context, webservice_server): the latter
        // does "WHERE study_url LIKE '<webservice_server>%'", which silently finds nothing (and
        // makes this whole method a no-op) the moment webservice_server and the row's own
        // study_url text ever diverge — e.g. joining via a short link that differs from the
        // resolved config URL the row actually stored. getActiveStudy() matches on "currently
        // joined, not exited" instead, with no URL comparison at all.
        Cursor study = Aware.getActiveStudy(context);

        if (study != null && study.moveToFirst()) {
            try {
                String studyUrl = study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL));
                JSONObject localConfig = new JSONObject(study.getString(
                        study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
                JSONObject newConfig = getStudyConfig(studyUrl);
                StudyConfigValidation validation = validateStudyConfigDetailed(
                        context, newConfig, Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));
                if (!configIsApplicable(validation)) {
                    // A password-join study whose stored password is rejected or missing means the
                    // researcher rotated the password: flag the participant for re-authentication
                    // instead of reporting a dead-end config error, and skip applying the config
                    // until they re-authenticate.
                    if (needsParticipantReauth(validation) && requiresParticipantPassword(newConfig)) {
                        Aware.setSetting(context, Aware_Preferences.PENDING_STUDY_REAUTH, studyUrl);
                        Log.w(Aware.TAG, "Study database password rejected; participant re-authentication required.");
                        // Nudge an open UI to prompt immediately instead of only on next app open.
                        context.sendBroadcast(new Intent(Aware.ACTION_AWARE_STUDY_REAUTH_REQUIRED));
                        // The broadcast only reaches a running Aware_Client, and syncs run on their
                        // own schedule with the app closed. The notification is what tells the
                        // participant collection is paused while they are not in the app.
                        postStudyNotification(context, Aware.AWARE_STUDY_REAUTH_NOTIFICATION_ID,
                                R.string.aware_notif_study_reauth_title,
                                R.string.aware_notif_study_reauth);
                        Aware.debug(context, Aware.LogType.STUDY,
                                "Notified the participant that the study password is required");
                        return;
                    }

                    String msg = validation == StudyConfigValidation.AUTH_FAILED
                            ? "Failed to sync study, the database rejected the study's credentials."
                            : "Failed to sync study, something is wrong with the config.";
                    Log.e(Aware.TAG, msg);
                    if (toast) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    return;
                }

                if (validation == StudyConfigValidation.UNREACHABLE) {
                    // Config retrieval and upload connectivity are separate operations, so an
                    // out-of-reach database does not stop the config applying; the sync adapter
                    // retries the upload on its own schedule. Recorded as upload health, not as a
                    // config failure, and deliberately not toasted — the sync runs every minute.
                    Aware.debug(context, Aware.LogType.STUDY,
                            "Study config applied while the upload database is unreachable");
                }

                boolean configsEqual = jsonEquals(localConfig, newConfig);
                // A server change the participant can never act on — one confined to sensors whose
                // hardware this device lacks — must not keep re-triggering the manual "study update
                // available" preview. The sensor's checkbox is disabled and it can never collect, so
                // "Keep my settings" can never reconcile the difference and the dialog would reappear
                // on every check. Treat a config that differs ONLY in such sensors as "no actionable
                // update", exactly like an identical config (the same reasoning liveDriftSignature()
                // already applies to drift). Editable mode only: locked mode has no preview and just
                // adopts the server config, so it never loops.
                boolean noActionableDiff = configsEqual
                        || (editable && configsDifferOnlyByUnavailableSensors(
                                context, localConfig, newConfig));
                boolean approvalMatches = approved
                        && pendingApprovalMatches(context, newConfig);
                if (shouldPreviewManualConfigUpdate(
                        editable, manual, noActionableDiff, approvalMatches)) {
                    publishConfigUpdatePreview(context, localConfig, newConfig);
                    if (toast) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(
                                        context,
                                        "Study update available for review.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    return;
                }

                if (noActionableDiff) {
                    Aware.setSetting(
                            context, Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL, "");
                    // The server config hasn't changed, but that alone doesn't guarantee the
                    // device's live settings still match it — Aware.reset() and an interrupted
                    // apply can both leave aware_settings drifted while the stored config blob
                    // still reads the same as the server. Comparing blobs alone let that drift go
                    // undetected forever: the participant would look "configured" while a sensor
                    // was silently off. Check live settings too and self-heal if they've drifted.
                    String drift = liveDriftSignature(context, newConfig);
                    if (drift.isEmpty()) {
                        String msg = "There are no study updates.";
                        if (Aware.DEBUG) Aware.debug(context, Aware.LogType.STUDY, msg);
                        if (toast) {
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        Aware.setSetting(context, Aware_Preferences.LAST_DRIFT_SIGNATURE, "");
                        return;
                    }

                    String lastDrift = Aware.getSetting(context, Aware_Preferences.LAST_DRIFT_SIGNATURE);
                    long lastReconcileTs = Aware.getSettingAsLong(context, Aware_Preferences.LAST_DRIFT_RECONCILE_TS, 0);
                    boolean sameDriftTriedRecently = drift.equals(lastDrift)
                            && (System.currentTimeMillis() - lastReconcileTs) < DRIFT_RECONCILE_BACKOFF_MS;
                    if (sameDriftTriedRecently) {
                        // Same mismatch we already tried to fix recently — most likely a sensor
                        // the device can't actually satisfy (e.g. no hardware), where re-applying
                        // would restart every sensor service again on every ~1 min sync poll for
                        // no benefit. Back off and retry later in case the cause was transient.
                        if (Aware.DEBUG)
                            Aware.debug(context, Aware.LogType.STUDY, "Live settings drifted from study config but a fix was already attempted recently, skipping: " + drift);
                        return;
                    }

                    if (Aware.DEBUG)
                        Aware.debug(context, Aware.LogType.STUDY, "Live settings drifted from study config, self-healing: " + drift);
                    // insertCompliance=false: this is a silent local self-heal, not a real config
                    // change, so it shouldn't log an "updated study" compliance row or notify the
                    // participant the way an actual server-side edit does below.
                    applySettings(context, studyUrl, new JSONArray().put(newConfig), false, Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));
                    // applySettings() resets the settings provider before rebuilding it, so persist
                    // the backoff marker only after the apply. Writing it before apply meant reset()
                    // immediately erased it; the next sync retried seconds later, recreated ESM
                    // schedules, restarted every sensor, and could continue until the phone failed.
                    Aware.setSetting(context, Aware_Preferences.LAST_DRIFT_SIGNATURE, drift);
                    Aware.setSetting(context, Aware_Preferences.LAST_DRIFT_RECONCILE_TS, System.currentTimeMillis());
                    return;
                }

                // Real config change from the server — clear any stale drift bookkeeping, since
                // the full re-apply below re-syncs every setting from scratch anyway.
                Aware.setSetting(context, Aware_Preferences.LAST_DRIFT_SIGNATURE, "");
                Aware.setSetting(
                        context, Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL, "");

                // Consent gate for mid-study changes (F2): a sensor the researcher newly enabled that
                // needs a participant grant must NOT start collecting just because the config says so —
                // the participant hasn't agreed to it. Hold every such sensor off by adding it to the
                // persisted declined set before applying; the "study updated" prompt then lets the
                // participant agree (which un-declines + enables it). Base sensors that need no grant
                // are not held. Sensors dropped from the config are cleared from the declined set so
                // stale entries don't accumulate across edits.
                holdNewlyAddedConsentSensors(context, localConfig, newConfig);

                applySettings(context, studyUrl, new JSONArray().put(newConfig), true, Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));
                if (Aware.DEBUG) Aware.debug(context, Aware.LogType.STUDY, "Updated study config: " + newConfig);

                // Tell any open UI to rebuild (e.g. show newly enabled sensors) without a re-join,
                // and report which sensors were added / removed so it can notify the participant.
                ArrayList<String> added = new ArrayList<>();
                ArrayList<String> removed = new ArrayList<>();
                diffActiveSensors(localConfig, newConfig, added, removed,
                        unavailableStatusSettings(context, localConfig, newConfig));
                Boolean configUpdateAllowedNewValue = enableConfigUpdateChanged(localConfig, newConfig);

                // Persist the curated, participant-meaningful part of this diff so it can still be
                // shown next time the app is opened even if no UI was around to receive the live
                // broadcast below (syncs run on their own schedule regardless of whether the app is
                // open). Cleared once shown by whichever path (live or catch-up) shows it first.
                boolean hasCuratedChanges = !added.isEmpty() || !removed.isEmpty() || configUpdateAllowedNewValue != null;
                if (hasCuratedChanges) {
                    try {
                        JSONObject notice = new JSONObject();
                        notice.put("added", new JSONArray(added));
                        notice.put("removed", new JSONArray(removed));
                        if (configUpdateAllowedNewValue != null) {
                            notice.put("cfgChanged", true);
                            notice.put("cfgNewValue", configUpdateAllowedNewValue);
                        }
                        notice.put("manual", manual);
                        Aware.setSetting(context, Aware_Preferences.PENDING_STUDY_UPDATE_NOTICE, notice.toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                Intent configUpdated = new Intent(Aware.ACTION_AWARE_STUDY_CONFIG_UPDATED);
                configUpdated.putStringArrayListExtra(Aware.EXTRA_SENSORS_ADDED, added);
                configUpdated.putStringArrayListExtra(Aware.EXTRA_SENSORS_REMOVED, removed);
                if (configUpdateAllowedNewValue != null) {
                    configUpdated.putExtra(Aware.EXTRA_CONFIG_UPDATE_ALLOWED_CHANGED, true);
                    configUpdated.putExtra(Aware.EXTRA_CONFIG_UPDATE_ALLOWED_NEW_VALUE, configUpdateAllowedNewValue);
                }
                configUpdated.putExtra(Aware.EXTRA_CONFIG_UPDATE_MANUAL, manual);
                context.sendBroadcast(configUpdated);
                if (toast) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, "Study was updated.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                // TODO RIO: Update last sync date

                // Notify only for a change the participant can act on. Frequency- and
                // threshold-only updates produce no entry in the curated diff, so a notification
                // about them would open an empty summary. Newly added consent sensors get their own
                // wording: they stay uncollected until the participant agrees.
                if (hasCuratedChanges) {
                    postStudyNotification(context, Aware.AWARE_STUDY_UPDATE_NOTIFICATION_ID,
                            R.string.aware_notif_study_update_title,
                            added.isEmpty()
                                    ? R.string.aware_notif_study_update_sensors
                                    : R.string.aware_notif_study_update_consent);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            } finally {
                study.close();
            }
        }
    }

    /**
     * Posts a study notification that opens the app when tapped. Shared by every participant-facing
     * study alert so channel, importance and tap target stay consistent; each caller passes its own
     * notification id. {@code setAutoCancel} clears it on tap — an alert resolved inside the app is
     * cleared by {@link #cancelStudyNotification} instead.
     */
    static void postStudyNotification(Context context, int notificationId,
                                      int titleRes, int textRes) {
        Aware.postGeneralNotification(
                context,
                notificationId,
                context.getResources().getString(titleRes),
                context.getResources().getString(textRes));
    }

    /** Removes a study notification whose condition has been resolved inside the app. */
    static void cancelStudyNotification(Context context, int notificationId) {
        NotificationManager notManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notManager != null) notManager.cancel(notificationId);
    }

    static boolean shouldSkipAutomaticConfigSync(boolean editable, boolean manual) {
        return editable && !manual;
    }

    static boolean shouldPreviewManualConfigUpdate(
            boolean editable, boolean manual, boolean configsEqual, boolean approvalMatches) {
        return editable && manual && !configsEqual && !approvalMatches;
    }

    private static boolean pendingApprovalMatches(Context context, JSONObject serverConfig) {
        String pending = Aware.getSetting(
                context, Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL);
        if (pending == null || pending.trim().length() == 0) return false;
        try {
            return jsonEquals(new JSONObject(pending), serverConfig);
        } catch (JSONException e) {
            return false;
        }
    }

    private static void publishConfigUpdatePreview(
            Context context, JSONObject localConfig, JSONObject serverConfig) {
        Aware.setSetting(
                context,
                Aware_Preferences.PENDING_STUDY_CONFIG_APPROVAL,
                serverConfig.toString());

        ArrayList<String> added = new ArrayList<>();
        ArrayList<String> removed = new ArrayList<>();
        diffActiveSensors(localConfig, serverConfig, added, removed,
                unavailableStatusSettings(context, localConfig, serverConfig));
        Boolean configUpdateAllowedNewValue =
                enableConfigUpdateChanged(localConfig, serverConfig);

        Intent available = new Intent(Aware.ACTION_AWARE_STUDY_CONFIG_UPDATE_AVAILABLE);
        available.putStringArrayListExtra(Aware.EXTRA_SENSORS_ADDED, added);
        available.putStringArrayListExtra(Aware.EXTRA_SENSORS_REMOVED, removed);
        if (configUpdateAllowedNewValue != null) {
            available.putExtra(Aware.EXTRA_CONFIG_UPDATE_ALLOWED_CHANGED, true);
            available.putExtra(
                    Aware.EXTRA_CONFIG_UPDATE_ALLOWED_NEW_VALUE,
                    configUpdateAllowedNewValue);
        }
        context.sendBroadcast(available);
    }

    /**
     * Persists a participant's editable-mode sensor change into the active study config and emits
     * a compliance row carrying that effective config. This makes the configuration received by
     * the researcher match the settings that actually produced the uploaded sensor data.
     */
    public static boolean persistEditableSensorSetting(
            Context context, String setting, String value) {
        if (setting == null
                || setting.length() == 0
                || Aware_Preferences.ENABLE_CONFIG_UPDATE.equals(setting)
                || !Aware.isStudy(context)
                || !Boolean.parseBoolean(Aware.getSetting(
                        context, Aware_Preferences.ENABLE_CONFIG_UPDATE))) {
            return false;
        }

        Cursor study = Aware.getActiveStudy(context);
        if (study == null || !study.moveToFirst()) {
            if (study != null) study.close();
            return false;
        }

        boolean updated = false;
        try {
            int studyId = study.getInt(
                    study.getColumnIndex(Aware_Provider.Aware_Studies._ID));
            String stored = study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG));
            JSONObject current = parseStoredStudyConfig(stored);
            JSONObject effective = withSensorSetting(current, setting, value);
            if (jsonEquals(current, effective)) return false;

            ContentValues values = new ContentValues();
            values.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, effective.toString());
            values.put(Aware_Provider.Aware_Studies.STUDY_UPDATED, System.currentTimeMillis());
            updated = context.getContentResolver().update(
                    Aware_Provider.Aware_Studies.CONTENT_URI,
                    values,
                    Aware_Provider.Aware_Studies._ID + "=?",
                    new String[]{String.valueOf(studyId)}) > 0;
        } catch (JSONException e) {
            Log.e(Aware.TAG, "Failed to persist editable sensor setting: " + e.getMessage());
        } finally {
            study.close();
        }

        if (updated) {
            Aware.logStudyCompliance(
                    context, "participant setting changed: " + setting + "=" + value);
        }
        return updated;
    }

    private static JSONObject parseStoredStudyConfig(String stored) throws JSONException {
        if (stored == null || stored.trim().length() == 0) return new JSONObject();
        String trimmed = stored.trim();
        if (!trimmed.startsWith("[")) return new JSONObject(trimmed);
        JSONArray configs = new JSONArray(trimmed);
        return configs.length() == 0 ? new JSONObject() : configs.getJSONObject(0);
    }

    /**
     * Returns a deep copy of {@code config} with one sensors[] setting replaced or appended.
     * Existing JSON value types are retained; new values infer booleans and numbers before
     * falling back to a string.
     */
    static JSONObject withSensorSetting(
            JSONObject config, String setting, String displayedValue) throws JSONException {
        JSONObject copy = new JSONObject(config.toString());
        JSONArray sensors = copy.optJSONArray("sensors");
        if (sensors == null) {
            sensors = new JSONArray();
            copy.put("sensors", sensors);
        }

        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null || !setting.equals(sensor.optString("setting", ""))) continue;
            sensor.put("value", typedConfigValue(displayedValue, sensor.opt("value")));
            return copy;
        }

        JSONObject sensor = new JSONObject();
        sensor.put("setting", setting);
        sensor.put("value", typedConfigValue(displayedValue, null));
        sensors.put(sensor);
        return copy;
    }

    private static Object typedConfigValue(String value, Object existingValue) {
        if (existingValue instanceof Boolean) return Boolean.parseBoolean(value);
        if (existingValue instanceof Float || existingValue instanceof Double) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (existingValue instanceof Number) {
            try {
                long number = Long.parseLong(value);
                return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                        ? (int) number : number;
            } catch (NumberFormatException ignored) {
                return value;
            }
        }

        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            long number = Long.parseLong(value);
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                    ? (int) number : number;
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException alsoIgnored) {
                return value;
            }
        }
    }

    /**
     * Retrieves a study config from a file hosted online.
     *
     * @param studyUrl direct download link to the file or a link to the shared file (via Google
     *                 drive or Dropbox)
     * @return JSONObject representing the study config
     */
    public static JSONObject getStudyConfig(String studyUrl) throws JSONException {
        // Convert shared links from Google drive and Dropbox into direct download URLs
        if (studyUrl.contains("drive.google.com")) {
            String patternStr = studyUrl.contains("drive.google.com/file") ?
                    "(?<=\\/d\\/).*(?=\\/)" : "(?<=id=).*";
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(studyUrl);
            if (matcher.find()) {
                String fileId = matcher.group(0);
                studyUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
            }
        } else if (studyUrl.contains("www.dropbox.com")) {
            studyUrl = studyUrl.replace("www.dropbox.com", "dl.dropboxusercontent.com");
        }

        // Always fetch fresh so researcher edits are picked up. no-store rather than no-cache: the
        // latter only asks caches to revalidate before reuse, no-store tells them not to keep a
        // copy at all — a stronger guarantee against intermediary proxies serving something stale.
        Request request = new Request.Builder()
                .url(studyUrl)
                .header("Cache-Control", "no-store")
                .build();

        try (Response response = STUDY_CONFIG_HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            long contentLength = response.body().contentLength();
            if (contentLength > MAX_STUDY_CONFIG_BYTES) {
                Log.e(Aware.TAG, "Study configuration is too large: " + contentLength);
                return null;
            }
            String responseStr = response.body().string();
            if (responseStr.length() > MAX_STUDY_CONFIG_BYTES) {
                Log.e(Aware.TAG, "Study configuration exceeded size limit while reading");
                return null;
            }
            JSONObject responseJson = new JSONObject(responseStr);
            return responseJson;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Why a study configuration was rejected. Both callers — the join screen and the config sync —
     * need to tell the failures apart: the join screen has to say what the participant should do
     * about it (re-type the password, try again later, contact the researcher), and config sync has
     * to decide whether the configuration is still applicable.
     */
    public enum StudyConfigValidation {
        /** Required schema is present and the database credentials authenticate. */
        OK,
        /** Absent, incomplete, or malformed configuration. The participant cannot fix this. */
        INVALID_CONFIG,
        /** The study expects a participant-supplied password and none was given. */
        PASSWORD_REQUIRED,
        /** The database rejected the password (access denied). */
        AUTH_FAILED,
        /** The database could not be reached — down, blocked, or too slow. Not a credential problem. */
        UNREACHABLE
    }

    /**
     * Validates a study configuration's schema and database credentials, classifying the failure.
     *
     * Credentials are checked with {@link Jdbc#probeConnection}, which reports a rejected password
     * separately from an unreachable host, is bounded by {@link #STUDY_PROBE_TIMEOUT_SECONDS} so a
     * dead host cannot stall a join indefinitely, and works on its own short-lived connection.
     *
     * @param context        application context, which the probe needs to verify the server's certificate
     * @param config         study configuration to validate; null is {@link StudyConfigValidation#INVALID_CONFIG}
     * @param input_password password typed by the participant, used only by
     *                       {@code config_without_password=true} studies. Never logged.
     * @return the classified outcome
     */
    public static StudyConfigValidation validateStudyConfigDetailed(Context context, JSONObject config, String input_password) {
        String missing = firstMissingRequirement(config);
        if (missing != null) {
            Log.e(Aware.TAG, "Study configuration is missing: " + missing);
            return StudyConfigValidation.INVALID_CONFIG;
        }

        if (usesWebservice(config)) {
            // No credential to verify, so reachability is the whole question. A study
            // URL that does not answer now is the same problem a rejected password
            // would be: the participant cannot join, and telling them so at the QR
            // code is better than a phone that collects and never delivers.
            return Webservice.reachable(settingValue(config, "webservice_server"))
                    ? StudyConfigValidation.OK
                    : StudyConfigValidation.UNREACHABLE;
        }

        JSONObject dbInfo = config.optJSONObject("database");
        // config_without_password=true means the config deliberately ships no password and the
        // participant supplies it; false means the config carries its own.
        boolean participantSuppliesPassword = requiresParticipantPassword(config);
        String password = participantSuppliesPassword
                ? (input_password == null ? "" : input_password)
                : dbInfo.optString("database_password", "");
        if (participantSuppliesPassword && password.isEmpty()) {
            Log.e(Aware.TAG, "Study requires a participant-supplied password, none was given");
            return StudyConfigValidation.PASSWORD_REQUIRED;
        }

        Jdbc.ConnectionResult probe = Jdbc.probeConnection(context,
                dbInfo.optString("database_host", ""),
                dbInfo.optString("database_port", ""),
                dbInfo.optString("database_name", ""),
                dbInfo.optString("database_username", ""),
                password,
                STUDY_PROBE_TIMEOUT_SECONDS);
        switch (probe) {
            case OK:
                return StudyConfigValidation.OK;
            case AUTH_FAILED:
                return StudyConfigValidation.AUTH_FAILED;
            default:
                return StudyConfigValidation.UNREACHABLE;
        }
    }

    /**
     * Whether a downloaded configuration can be applied despite this validation outcome.
     * {@link StudyConfigValidation#UNREACHABLE} concerns the upload database, not the configuration,
     * so it does not block. Pure and Context-free so it can be unit-tested without a device.
     */
    static boolean configIsApplicable(StudyConfigValidation validation) {
        return validation == StudyConfigValidation.OK
                || validation == StudyConfigValidation.UNREACHABLE;
    }

    /**
     * Whether this outcome means the participant has to supply a password again, as opposed to a
     * failure they cannot act on. Both values are reported only after the schema checked out; the
     * caller still confirms the study expects a participant-supplied password.
     */
    static boolean needsParticipantReauth(StudyConfigValidation validation) {
        return validation == StudyConfigValidation.AUTH_FAILED
                || validation == StudyConfigValidation.PASSWORD_REQUIRED;
    }

    /** Database fields that must be present before a connection attempt is even worth making. */
    private static final String[] REQUIRED_DATABASE_FIELDS = {
            "database_host", "database_port", "database_name", "database_username"};

    /**
     * Returns the name of the first required study-config field that is absent or empty, or null when
     * the configuration carries everything needed to attempt a database connection.
     *
     * Package-private and free of logging, network and Context so it can be unit-tested directly —
     * same reasoning as {@link Jdbc#classify}. Checking the database fields here rather than letting
     * a connection attempt fail on them keeps "the researcher's config is broken" distinguishable
     * from "the server is down", which the participant-facing message depends on.
     */
    static String firstMissingRequirement(JSONObject config) {
        if (config == null) return "study configuration";

        for (String key: REQUIRED_STUDY_CONFIG_KEYS) {
            if (!config.has(key)) return key;
        }

        if (usesWebservice(config)) {
            // The study URL is this path's whole address: the phone posts its rows to
            // it and fetches its config from it, and there is nothing else to check.
            return settingValue(config, "webservice_server").isEmpty()
                    ? "webservice_server"
                    : null;
        }

        JSONObject dbInfo = config.optJSONObject("database");
        if (dbInfo == null) return "database";

        for (String field: REQUIRED_DATABASE_FIELDS) {
            if (dbInfo.optString(field, "").isEmpty()) return field;
        }
        return null;
    }

    /**
     * A named setting's value from a study config's sensors list, or "" when absent.
     * Pure, so it is unit-testable.
     */
    static String settingValue(JSONObject config, String setting) {
        if (config == null) return "";
        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return "";
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject entry = sensors.optJSONObject(i);
            if (entry != null && setting.equals(entry.optString("setting", ""))) {
                return entry.optString("value", "");
            }
        }
        return "";
    }

    /**
     * Whether this configuration uploads through the webservice rather than opening
     * the database.
     *
     * The declared field first, then the channel setting the server derives from the
     * same choice -- so a config written before the field existed still reads
     * correctly, which is every config an already-enrolled phone is holding. Pure, so
     * it is unit-testable.
     */
    static boolean usesWebservice(JSONObject config) {
        if (config == null) return false;
        String declared = config.optString("dataflow", "");
        if ("webservice".equalsIgnoreCase(declared)) return true;
        if ("direct".equalsIgnoreCase(declared)) return false;
        return "true".equalsIgnoreCase(settingValue(config, "status_webservice"));
    }

    /**
     * True when the study's config deliberately ships no database password and the participant is
     * expected to supply it ({@code config_without_password=true}). Pure, so it is unit-testable.
     */
    static boolean requiresParticipantPassword(JSONObject config) {
        JSONObject dbInfo = config == null ? null : config.optJSONObject("database");
        return dbInfo != null && dbInfo.optBoolean("config_without_password", false);
    }

    /**
     * Compares two JSON objects for equality.
     *
     * Uses NON_EXTENSIBLE rather than LENIENT: LENIENT treats arrays as one-directional subset
     * checks, so a config edit that only adds entries to the sensors/schedulers arrays (rather
     * than toggling an existing entry's value) compared equal to the old config and was silently
     * ignored on sync. NON_EXTENSIBLE requires both arrays to contain exactly the same elements
     * (order-independent), which catches additions and removals alike.
     *
     * @param obj1 First JSON object
     * @param obj2 Second JSON object
     * @return true if the objects are equal, false otherwise
     */
    // Package-private rather than private so StudyUtilsTest (same package, src/test) can call this
    // directly and lock in the NON_EXTENSIBLE regression above without reflection.
    static boolean jsonEquals(JSONObject obj1, JSONObject obj2) {
        try {
            JSONAssert.assertEquals(obj1, obj2, JSONCompareMode.NON_EXTENSIBLE);
            return true;
        } catch (JSONException | AssertionError e) {
            return false;
        }
    }

    /**
     * How long to wait before retrying a self-heal for the same detected live-settings drift.
     * Prevents an unfixable drift (e.g. a sensor whose hardware is missing, so it can never
     * actually match the config) from re-triggering applySettings() — and restarting every sensor
     * service — on every ~1 minute sync poll. Long enough to stop the hot loop, short enough that
     * a transient cause (e.g. a permission the participant grants later) still self-heals same-day.
     */
    private static final long DRIFT_RECONCILE_BACKOFF_MS = 60 * 60 * 1000; // 1 hour

    /**
     * Compares every status_* sensor setting in {@code config} against its live value in the
     * aware_settings provider (what actually controls whether a sensor service runs) and returns a
     * stable, sorted signature of any mismatches — empty string if live settings already match.
     *
     * Only status_* (on/off) settings are checked, not every setting (frequency/threshold etc.):
     * those don't affect whether data collection is happening at all, just its granularity, so a
     * mismatch there isn't the "participant thinks they're compliant but a sensor is off" failure
     * mode this exists to catch — and checking them would make the signature (and the reapply
     * cadence below) noisier without covering a materially worse bug.
     *
     * Settings whose sensor hardware this device doesn't have (per SensorAvailability) are excluded
     * entirely rather than left for the 1-hour reconcile backoff to suppress: a missing sensor is a
     * permanent, known fact about the device, not a transient failure that might self-heal, so it
     * shouldn't ever count as "drift" or occupy a slot in the backoff-tracked signature at all.
     */
    private static String liveDriftSignature(Context context, JSONObject config) {
        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return "";

        // Read every candidate setting's live value (and hardware availability) up front so the
        // comparison itself (below) is a pure function of data, not of Context/ContentResolver —
        // makes it directly unit-testable without Robolectric, same reasoning as
        // Aware.parseLongOrDefault being split out from Aware.getSettingAsLong().
        Map<String, String> liveValues = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;
            String setting = sensor.optString("setting", "");
            if (!setting.startsWith("status_") || !sensor.has("value")) continue;
            liveValues.put(setting, Aware.getSetting(context, setting));
            if (!SensorAvailability.isHardwareAvailable(context, setting)) {
                excluded.add(setting);
            }
        }
        // Also exclude sensors the participant declined: like a missing-hardware sensor, a decline is
        // a deliberate, known reason the live value differs from the config — not drift to self-heal.
        // Without this the ~1-minute sync would re-enable every declined sensor, undoing the decline.
        String persistedDeclined = Aware.getSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS);
        for (String key : persistedDeclined.split(",")) {
            if (key.trim().length() > 0) excluded.add(key.trim());
        }
        return driftSignature(config, liveValues, excluded);
    }

    /**
     * Context-free core of {@link #liveDriftSignature(Context, JSONObject)}: compares each
     * status_* sensor setting in {@code config} against its value in {@code liveValues} (missing
     * from the map is treated the same as an empty/unset live setting) and returns a stable, sorted
     * signature of any mismatches — empty string if everything matches. Settings named in
     * {@code hardwareUnavailable} are skipped regardless of their live value: the device can never
     * satisfy them, so they're not "drift" in the sense this method exists to catch. Split out so
     * it's unit-testable without a Context.
     *
     * Only status_* (on/off) settings are checked, not every setting (frequency/threshold etc.):
     * those don't affect whether data collection is happening at all, just its granularity, so a
     * mismatch there isn't the "participant thinks they're compliant but a sensor is off" failure
     * mode this exists to catch — and checking them would make the signature (and the reapply
     * cadence below) noisier without covering a materially worse bug.
     */
    static String driftSignature(JSONObject config, Map<String, String> liveValues, Set<String> excludedSettings) {
        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return "";

        TreeMap<String, String> mismatches = new TreeMap<>();
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;

            String setting = sensor.optString("setting", "");
            if (!setting.startsWith("status_") || !sensor.has("value")) continue;
            if (excludedSettings.contains(setting)) continue;

            String expected = String.valueOf(sensor.opt("value"));
            String live = liveValues.containsKey(setting) ? liveValues.get(setting) : "";
            if (!expected.equalsIgnoreCase(live)) {
                mismatches.put(setting, expected + "!=" + live);
            }
        }

        if (mismatches.isEmpty()) return "";
        StringBuilder signature = new StringBuilder();
        for (Map.Entry<String, String> mismatch : mismatches.entrySet()) {
            signature.append(mismatch.getKey()).append('=').append(mismatch.getValue()).append(';');
        }
        return signature.toString();
    }

    /**
     * Computes which sensors became active/inactive between two study configs, as human-readable
     * names, into {@code added} and {@code removed}. Settings in {@code excludedStatusSettings}
     * (e.g. sensors whose hardware this device lacks) are left out entirely — the participant can't
     * act on them, so listing them as "to activate" / "no longer collecting" would be misleading.
     */
    private static void diffActiveSensors(JSONObject oldConfig, JSONObject newConfig,
                                          List<String> added, List<String> removed,
                                          Set<String> excludedStatusSettings) {
        Set<String> before = activeSensorNames(oldConfig, excludedStatusSettings);
        Set<String> after = activeSensorNames(newConfig, excludedStatusSettings);
        for (String s : after) if (!before.contains(s)) added.add(s);
        for (String s : before) if (!after.contains(s)) removed.add(s);
    }

    /**
     * Human-readable names of sensors whose status_* setting is enabled (true) in a config,
     * skipping any setting named in {@code excludedStatusSettings}.
     */
    private static Set<String> activeSensorNames(JSONObject config, Set<String> excludedStatusSettings) {
        Set<String> active = new HashSet<>();
        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return active;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;
            String setting = sensor.optString("setting", "");
            if (excludedStatusSettings.contains(setting)) continue;
            if (setting.startsWith("status_") && sensor.optBoolean("value", false)) {
                active.add(setting.substring("status_".length()).replace('_', ' '));
            }
        }
        return active;
    }

    /**
     * The status_* settings in {@code configs} whose sensor hardware this device doesn't have (per
     * {@link SensorAvailability}). Such sensors can never collect regardless of the config, so a
     * difference confined to them isn't an actionable study update — see
     * {@link #configsDifferOnlyByUnavailableSensors} and the preview gate in {@link #syncStudyConfig}.
     */
    private static Set<String> unavailableStatusSettings(Context context, JSONObject... configs) {
        Set<String> unavailable = new HashSet<>();
        for (JSONObject config : configs) {
            JSONArray sensors = config == null ? null : config.optJSONArray("sensors");
            if (sensors == null) continue;
            for (int i = 0; i < sensors.length(); i++) {
                JSONObject sensor = sensors.optJSONObject(i);
                if (sensor == null) continue;
                String setting = sensor.optString("setting", "");
                if (setting.startsWith("status_")
                        && !SensorAvailability.isHardwareAvailable(context, setting)) {
                    unavailable.add(setting);
                }
            }
        }
        return unavailable;
    }

    /**
     * True when {@code local} and {@code server} are not identical, yet become identical once the
     * status_* settings for sensors this device can't support (no hardware) are ignored — i.e. the
     * only thing the server changed is a sensor the participant could never turn on anyway.
     */
    private static boolean configsDifferOnlyByUnavailableSensors(
            Context context, JSONObject local, JSONObject server) {
        Set<String> unavailable = unavailableStatusSettings(context, local, server);
        if (unavailable.isEmpty()) return false;
        return configsEqualIgnoringSensors(local, server, unavailable);
    }

    /**
     * True if {@code a} and {@code b} are equal (per {@link #jsonEquals}) after removing every
     * sensors[] entry whose "setting" is in {@code ignoredStatusSettings} from both. Split out as a
     * pure, Context-free function so it's directly unit-testable, same reasoning as
     * {@link #driftSignature}. Only the named status_* entries are dropped, not sibling frequency /
     * threshold entries for the same sensor — AWARE study edits toggle a sensor's status_* value in
     * place, so a status-only compare matches how the codebase already defines a sensor being on/off
     * ({@link #driftSignature}, {@link #diffActiveSensors}).
     */
    static boolean configsEqualIgnoringSensors(
            JSONObject a, JSONObject b, Set<String> ignoredStatusSettings) {
        try {
            return jsonEquals(
                    withoutSensorSettings(a, ignoredStatusSettings),
                    withoutSensorSettings(b, ignoredStatusSettings));
        } catch (JSONException e) {
            return false;
        }
    }

    /** Deep copy of {@code config} with every sensors[] entry named in {@code settingsToRemove} dropped. */
    private static JSONObject withoutSensorSettings(
            JSONObject config, Set<String> settingsToRemove) throws JSONException {
        JSONObject copy = new JSONObject(config.toString());
        JSONArray sensors = copy.optJSONArray("sensors");
        if (sensors == null) return copy;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor != null && settingsToRemove.contains(sensor.optString("setting", ""))) continue;
            kept.put(sensors.get(i));
        }
        copy.put("sensors", kept);
        return copy;
    }

    /** The raw status_* setting keys enabled (value true) in a config. */
    private static Set<String> enabledStatusSettings(JSONObject config) {
        Set<String> out = new HashSet<>();
        if (config == null) return out;
        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return out;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;
            String setting = sensor.optString("setting", "");
            if (setting.startsWith("status_") && sensor.optBoolean("value", false)) out.add(setting);
        }
        return out;
    }

    /**
     * After a server-side config change, hold every newly-enabled sensor that needs a participant
     * grant OFF until they agree, by adding it to the persisted declined set (F2). "Newly enabled" =
     * enabled in {@code newConfig} but not in {@code oldConfig}; "needs a grant" per
     * {@link SensorDiagnostics#requiresConsent}. Also drops from the declined set any setting the new
     * config no longer enables, so stale entries don't accumulate across successive edits.
     */
    private static void holdNewlyAddedConsentSensors(Context context, JSONObject oldConfig, JSONObject newConfig) {
        Set<String> oldEnabled = enabledStatusSettings(oldConfig);
        Set<String> newEnabled = enabledStatusSettings(newConfig);

        Set<String> declined = new HashSet<>();
        for (String key : Aware.getSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")) {
            if (key.trim().length() > 0) declined.add(key.trim());
        }

        // Keep only declines for sensors the config still enables — drop the rest as stale.
        declined.retainAll(newEnabled);

        // Hold each newly-enabled, consent-requiring sensor off until the participant agrees.
        for (String setting : newEnabled) {
            if (!oldEnabled.contains(setting) && SensorDiagnostics.requiresConsent(setting)) {
                declined.add(setting);
            }
        }

        StringBuilder joined = new StringBuilder();
        for (String setting : declined) {
            if (joined.length() > 0) joined.append(',');
            joined.append(setting);
        }
        Aware.setSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS, joined.toString());
    }

    /**
     * The consent-requiring status_* settings enabled across {@code configs} — every sensor that
     * gates on a runtime permission or the Accessibility Service (per
     * {@link SensorDiagnostics#requiresConsent}). Permission-free base sensors are excluded. Pure
     * (no Context) so it's unit-testable, mirroring {@link #driftSignature}'s split from
     * {@link #liveDriftSignature}.
     */
    static Set<String> consentRequiringEnabledSettings(JSONArray configs) {
        Set<String> out = new HashSet<>();
        if (configs == null) return out;
        for (int i = 0; i < configs.length(); i++) {
            for (String setting : enabledStatusSettings(configs.optJSONObject(i))) {
                if (SensorDiagnostics.requiresConsent(setting)) out.add(setting);
            }
        }
        return out;
    }

    /**
     * Installation events use package broadcasts and do not need Accessibility at the Android API
     * level. In the participant-facing consent, however, they are disclosed as part of Applications
     * usage. Preserve that product-level consent boundary: declining the Applications choice also
     * declines installation events.
     */
    static Set<String> expandGroupedConsentDeclines(Set<String> declined) {
        Set<String> expanded = new HashSet<>();
        if (declined != null) expanded.addAll(declined);
        if (expanded.contains(Aware_Preferences.STATUS_APPLICATIONS)
                || expanded.contains(Aware_Preferences.STATUS_NOTIFICATIONS)
                || expanded.contains(Aware_Preferences.STATUS_CRASHES)) {
            expanded.add(Aware_Preferences.STATUS_INSTALLATIONS);
        }
        return expanded;
    }

    private static String joinSettings(Set<String> settings) {
        StringBuilder joined = new StringBuilder();
        for (String setting : settings) {
            if (joined.length() > 0) joined.append(',');
            joined.append(setting);
        }
        return joined.toString();
    }

    /**
     * Migrates existing enrolments made before installation events were tied to Applications
     * consent, and mirrors the forced-off value into both setting stores before services start.
     */
    public static void enforceGroupedConsent(Context context) {
        Set<String> declined = new HashSet<>();
        for (String key : Aware.getSetting(
                context, Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")) {
            if (key.trim().length() > 0) declined.add(key.trim());
        }
        Set<String> expanded = expandGroupedConsentDeclines(declined);
        if (!expanded.contains(Aware_Preferences.STATUS_INSTALLATIONS)) {
            return;
        }
        if (!declined.contains(Aware_Preferences.STATUS_INSTALLATIONS)) {
            Aware.setSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS,
                    joinSettings(expanded));
        }
        Aware.setSetting(context, Aware_Preferences.STATUS_INSTALLATIONS, false);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(Aware_Preferences.STATUS_INSTALLATIONS, false)
                .apply();
    }

    /**
     * Holds every consent-requiring sensor enabled in {@code configs} OFF, by persisting it into the
     * declined set (unioned with anything already there), and returns the resulting declined set.
     * The programmatic join entry points ({@link Aware#joinStudy} / the {@link StudyUtils} service)
     * have no consent UI, so a sensor needing a runtime permission or the Accessibility Service must
     * not be silently enabled — this keeps it off, and keeps it off across later config syncs via the
     * persisted set. Permission-free base sensors are untouched and still start; a held sensor can be
     * enabled later once the participant consents (the consent screen, or the per-sensor Enable
     * action).
     */
    public static Set<String> holdConsentSensorsUnlessAgreed(Context context, JSONArray configs) {
        Set<String> declined = new HashSet<>();
        for (String key : Aware.getSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")) {
            if (key.trim().length() > 0) declined.add(key.trim());
        }
        declined.addAll(consentRequiringEnabledSettings(configs));

        StringBuilder joined = new StringBuilder();
        for (String setting : declined) {
            if (joined.length() > 0) joined.append(',');
            joined.append(setting);
        }
        Aware.setSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS, joined.toString());
        return declined;
    }

    /**
     * Value of a given setting key in a config's sensors array, or null if not present.
     * Boolean-only (unlike processSensorSettings()'s multi-type handling elsewhere in this file) —
     * fine for enable_config_update, but don't reuse this for a non-boolean setting.
     */
    private static Boolean sensorSettingValue(JSONObject config, String key) {
        JSONArray sensors = config.optJSONArray("sensors");
        if (sensors == null) return null;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;
            if (key.equals(sensor.optString("setting", ""))) {
                return sensor.optBoolean("value", false);
            }
        }
        return null;
    }

    /**
     * Null if the study's <em>effective</em> editability didn't change; otherwise its new value, for
     * the participant-facing "study updated" notice. Absent {@code enable_config_update} means the
     * default, researcher-controlled (locked) state, so it is normalised to {@code false} before
     * comparing: a config that merely starts or stops spelling the setting out (absent ↔ false) is NOT
     * a change and must not tell participants their edit access changed. Only a real flip
     * (locked ↔ editable) returns non-null.
     */
    // Package-private so StudyUtilsTest can lock in the absent-means-false normalisation.
    static Boolean enableConfigUpdateChanged(JSONObject oldConfig, JSONObject newConfig) {
        Boolean before = sensorSettingValue(oldConfig, Aware_Preferences.ENABLE_CONFIG_UPDATE);
        Boolean after = sensorSettingValue(newConfig, Aware_Preferences.ENABLE_CONFIG_UPDATE);
        boolean effectiveBefore = before != null && before;
        boolean effectiveAfter = after != null && after;
        if (effectiveBefore == effectiveAfter) return null;
        return effectiveAfter;
    }
}
