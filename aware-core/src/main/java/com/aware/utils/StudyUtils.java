package com.aware.utils;

/**
 * Created by denzilferreira on 16/02/16.
 */

import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.aware.Applications;
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.core.app.NotificationCompat;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Service that allows plugins/applications to send data to AWARE's dashboard study
 * Note: joins a study without requiring a QRCode, just the study URL
 *
 * TODO: fix parsing of the URL segments that may be missing
 */
public class StudyUtils extends IntentService {
    private static final String[] REQUIRED_STUDY_CONFIG_KEYS = {"database", "questions",
            "schedules", "sensors", "study_info"};

    /**
     * Received broadcast to join a study
     */
    public static final String EXTRA_JOIN_STUDY = "study_url";

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
                data.put(Aware_Preferences.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
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
                    Log.d(Aware.TAG, DatabaseUtils.dumpCursorToString(dbStudy));

                if (dbStudy == null || !dbStudy.moveToFirst()) {
                    ContentValues studyData = new ContentValues();
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
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
                        Log.d(Aware.TAG, "New study data: " + studyData.toString());
                    }
                } else {
                    //User rejoined a study he was already part of. Mark as abandoned.
                    ContentValues complianceEntry = new ContentValues();
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
                    complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "rejoined study. abandoning previous");

                    getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);

                    //Update the information to the latest
                    ContentValues studyData = new ContentValues();
                    studyData.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
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
                        Log.d(Aware.TAG, "Rejoined study data: " + studyData.toString());
                    }
                }

                if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                applySettings(getApplicationContext(), full_url, study_config);

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Sets first all the settings to the client.
     * If there are plugins, apply the same settings to them.
     * This allows us to add plugins to studies from the dashboard.
     *
     * @param context
     * @param configs
     */
    public static void applySettings(Context context, JSONArray configs) {
        applySettings(context, Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER), configs);
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
    public static void applySettings(Context context, String webserviceServer, JSONArray configs) {
        applySettings(context, webserviceServer, configs, false);
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
     */
    public static void applySettings(Context context, String webserviceServer, JSONArray configs, Boolean insertCompliance) {
        boolean is_developer = Aware.getSetting(context, Aware_Preferences.DEBUG_FLAG).equals("true");

        //First reset the client to default settings...
        Aware.reset(context);

        if (is_developer) Aware.setSetting(context, Aware_Preferences.DEBUG_FLAG, true);

        //Now apply the new settings
        try {
            Aware.setSetting(context, Aware_Preferences.WEBSERVICE_SERVER, webserviceServer);
            JSONObject studyConfig = configs.getJSONObject(0);  // use first config
            JSONObject studyInfo = studyConfig.getJSONObject("study_info");

            // Set database settings
            JSONObject dbConfig = studyConfig.getJSONObject("database");
            Aware.setSetting(context, Aware_Preferences.DB_HOST, dbConfig.getString("database_host"));
            Aware.setSetting(context, Aware_Preferences.DB_PORT, dbConfig.getInt("database_port"));
            Aware.setSetting(context, Aware_Preferences.DB_NAME, dbConfig.getString("database_name"));
            Aware.setSetting(context, Aware_Preferences.DB_USERNAME, dbConfig.getString("database_username"));
            Aware.setSetting(context, Aware_Preferences.DB_PASSWORD, dbConfig.getString("database_password"));

            // Set study information
            if (insertCompliance) {
                ContentValues studyData = new ContentValues();
                studyData.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(context, Aware_Preferences.DEVICE_ID));
                studyData.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                studyData.put(Aware_Provider.Aware_Studies.STUDY_API, "");
                studyData.put(Aware_Provider.Aware_Studies.STUDY_URL, webserviceServer);
                studyData.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, studyConfig.toString());
                studyData.put(Aware_Provider.Aware_Studies.STUDY_KEY, "0");
                studyData.put(Aware_Provider.Aware_Studies.STUDY_PI, studyInfo.getString("researcher_first") + " " + studyInfo.getString("researcher_last") + "\nContact: " + studyInfo.getString("researcher_contact"));
                studyData.put(Aware_Provider.Aware_Studies.STUDY_TITLE, studyInfo.getString("study_title"));
                studyData.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, studyInfo.getString("study_description"));
                studyData.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "updated study");
                studyData.put(Aware_Provider.Aware_Studies.STUDY_JOINED, System.currentTimeMillis());
                studyData.put(Aware_Provider.Aware_Studies.STUDY_EXIT, 0);
                context.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, studyData);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        JSONArray plugins = new JSONArray();
        JSONArray sensors = new JSONArray();
        JSONArray schedulers = new JSONArray();
        JSONArray esm_schedules = new JSONArray();
        JSONArray questions = new JSONArray();

        for (int i = 0; i < configs.length(); i++) {
            try {
                JSONObject element = configs.getJSONObject(i);
                if (element.has("plugins")) {
                    plugins = element.getJSONArray("plugins");
                }
                if (element.has("sensors")) {
                    sensors = element.getJSONArray("sensors");
                }
                if (element.has("schedulers")) {
                    schedulers = element.getJSONArray("schedulers");
                }
                if (element.has("schedules")) {
                    esm_schedules = element.getJSONArray("schedules");
                    if (element.has("questions")) questions = element.getJSONArray("questions");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        //Set the sensors' settings first
        for (int i = 0; i < sensors.length(); i++) {
            try {
                JSONObject sensor_config = sensors.getJSONObject(i);
                Aware.setSetting(context, sensor_config.getString("setting"), sensor_config.get("value"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        //Set the plugins' settings now
        ArrayList<String> active_plugins = new ArrayList<>();
        for (int i = 0; i < plugins.length(); i++) {
            try {
                JSONObject plugin_config = plugins.getJSONObject(i);

                String package_name = plugin_config.getString("plugin");
                active_plugins.add(package_name);

                JSONArray plugin_settings = plugin_config.getJSONArray("settings");
                for (int j = 0; j < plugin_settings.length(); j++) {
                    JSONObject plugin_setting = plugin_settings.getJSONObject(j);
                    Aware.setSetting(context, plugin_setting.getString("setting"), plugin_setting.get("value"), package_name);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // Set up ESM question objects
        HashMap<String, JSONObject> esm_questions = new HashMap<>();
        for (int i = 0; i < questions.length(); i++) {
            try {
                JSONObject questionJson = questions.getJSONObject(i);
                String questionId = questionJson.getString("id");
                esm_questions.put(questionId, new JSONObject().put("esm", questionJson));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // Set ESM schedules
        for (int i = 0; i < esm_schedules.length(); i++) {
            try {
                JSONObject scheduleJson = esm_schedules.getJSONObject(i);
                JSONArray questionIds = scheduleJson.getJSONArray("questions");
                JSONArray questionObjects = new JSONArray();

                for (int j = 0; j < questionIds.length(); j++) {
                    questionObjects.put(esm_questions.get(questionIds.getString(j)));
                }

                createEsmSchedule(context, scheduleJson, questionObjects);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        //Set other schedulers
        if (schedulers.length() > 0)
            Scheduler.setSchedules(context, schedulers);

        for (String package_name : active_plugins) {
            PackageInfo installed = PluginsManager.isInstalled(context, package_name);
            if (installed != null) {
                Aware.startPlugin(context, package_name);
            } else {
                Aware.downloadPlugin(context, package_name, null, false);
            }
        }

        Intent aware = new Intent(context, Aware.class);
        context.startService(aware);

        //Send data to server
        Intent sync = new Intent(Aware.ACTION_AWARE_SYNC_DATA);
        context.sendBroadcast(sync);
    }

    /**
     * Creates a schedule for triggering ESMs based on a JSON object that defines a schedule.
     * Examples of the schedule JSON:<p>
     * Interval:
     * Random:
     * Repeat:
     * </p>
     * @param context
     * @param scheduleJson JSONObject representing the schedule
     * @param esmsArray JSONArray representing the ESM questions
     */
    private static void createEsmSchedule(Context context, JSONObject scheduleJson,
                                          JSONArray esmsArray) {
        try {
            String type = scheduleJson.getString("type");
            String title = scheduleJson.getString("title");
            Scheduler.Schedule schedule = new Scheduler.Schedule(title);

            if (Aware.DEBUG) Log.d(Aware.TAG, "Creating ESM schedule: " + scheduleJson.toString());

            // Set schedule parameters
            switch (type) {
                case "interval":
                    JSONArray days = scheduleJson.getJSONArray("days");
                    for (int i = 0; i < days.length(); i ++) {
                        schedule.addWeekday(days.getString(i));
                    }
                    JSONArray hours = scheduleJson.getJSONArray("hours");
                    for (int i = 0; i < hours.length(); i ++) {
                        schedule.addHour(hours.getInt(i));
                    }
                    break;
                case "random":
                    schedule.addHour(scheduleJson.getInt("firsthour"))
                            .addHour(scheduleJson.getInt("lasthour"))
                            .random(scheduleJson.getInt("randomCount"),
                                    scheduleJson.getInt("randomInterval"));
                    break;
                case "repeat":
                    schedule.setInterval(scheduleJson.getInt("repeatInterval"));
                    break;
                default:
                    return;
            }

            // Set trigger for ESMs as the schedule's title
            for (int i = 0; i < esmsArray.length(); i ++) {
                esmsArray.getJSONObject(i).getJSONObject("esm").put(ESM_Question.esm_trigger, title);
            }

            schedule.setActionType(Scheduler.ACTION_TYPE_BROADCAST)
                    .setActionIntentAction(ESM.ACTION_AWARE_QUEUE_ESM)
                    .addActionExtra(ESM.EXTRA_ESM, esmsArray.toString());
            Scheduler.saveSchedule(context, schedule);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param context
     */
    public static void syncStudyConfig(Context context, Boolean toast) {
        if (!Aware.isStudy(context)) return;

        String studyUrl = Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER);
        Cursor study = Aware.getStudy(context,
                Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER));

        if (study != null && study.moveToFirst()) {
            try {
                JSONObject localConfig = new JSONObject(study.getString(
                        study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
                JSONObject newConfig = getStudyConfig(studyUrl);
                if (!validateStudyConfig(context, newConfig)) {
                    String msg = "Failed to sync study, something is wrong with the config.";
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
                if (jsonEquals(localConfig, newConfig, false)) {
                    String msg = "There are no study updates.";
                    if (Aware.DEBUG) Aware.debug(context, msg);
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
                applySettings(context, studyUrl, new JSONArray().put(newConfig), true);
                if (Aware.DEBUG) Aware.debug(context, "Updated study config: " + newConfig);
                if (toast) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, "Study was updated.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                // TODO RIO: Update last sync date

                // Notify the user that study config has been updated
                Intent intent = new Intent()
                        .setComponent(new ComponentName("com.aware.phone", "com.aware.phone.ui.Aware_Light_Client"))
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent clickIntent = PendingIntent.getActivity(context, 0, intent, 0);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL)
                        .setChannelId(Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL)
                        .setContentIntent(clickIntent)
                        .setSmallIcon(R.drawable.ic_stat_aware_accessibility)
                        .setAutoCancel(true)
                        .setContentTitle(context.getResources().getString(R.string.aware_notif_study_sync_title))
                        .setContentText(context.getResources().getString(R.string.aware_notif_study_sync));
                builder = Aware.setNotificationProperties(builder, Aware.AWARE_NOTIFICATION_IMPORTANCE_GENERAL);

                NotificationManager notManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                notManager.notify(Applications.ACCESSIBILITY_NOTIFICATION_ID, builder.build());
            } catch (JSONException e) {
                e.printStackTrace();
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

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(studyUrl).build();

        try (Response response = client.newCall(request).execute()) {
            String responseStr = response.body().string();
            JSONObject responseJson = new JSONObject(responseStr);
            return responseJson;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Validates that the study config has the correct JSON schema for AWARE.
     * It needs to have the keys: "database", "sensors" and "study_info".
     *
     * @param context application context
     * @param config JSON representing a study configuration
     * @return true if the study config is valid, false otherwise
     */
    public static boolean validateStudyConfig(Context context, JSONObject config) {
        for (String key: REQUIRED_STUDY_CONFIG_KEYS) {
            if (!config.has(key)) return false;
        }

        // Test database connection
        try {
            JSONObject dbInfo = config.getJSONObject("database");
            return Jdbc.testConnection(dbInfo.getString("database_host"),
                    dbInfo.getString("database_port"), dbInfo.getString("database_name"),
                    dbInfo.getString("database_username"), dbInfo.getString("database_password"));
        } catch (JSONException e) {
            return false;
        }
    }

    private static boolean jsonEquals(JSONObject obj1, JSONObject obj2, boolean strict) {
        try {
            JSONAssert.assertEquals(obj1, obj2, strict);
            return true;
        } catch (JSONException | AssertionError e) {
            return false;
        }
    }
}
