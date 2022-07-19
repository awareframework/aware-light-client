package com.aware.phone.utils;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;
import android.widget.Toast;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.providers.Aware_Provider;
import com.aware.utils.Http;
import com.aware.utils.Jdbc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for AWARE-specific processes.
 */
public class AwareUtil {
    private static final String[] REQUIRED_STUDY_CONFIG_KEYS = {"database", "questions",
            "schedules", "sensors", "study_info"};

    /**
     * Retrieves a study config from a file hosted online.
     *
     * @param studyUrl direct download link to the file or a link to the shared file (via Google
     *                 drive or Dropbox)
     * @return JSONObject representing the study config
     */
    public static JSONObject getStudyConfig(String studyUrl) throws JSONException {
        // Convert shared links from Google drive and Dropbox into direct download URLs
        if (studyUrl.contains("drive.google.com/file")) {
            Pattern pattern = Pattern.compile("(?<=\\/d\\/).*(?=\\/)");
            Matcher matcher = pattern.matcher(studyUrl);
            if (matcher.find()) {
                String fileId = matcher.group(0);
                studyUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
            }
        } else if (studyUrl.contains("www.dropbox.com")) {
            studyUrl = studyUrl.replace("www.dropbox.com", "dl.dropboxusercontent.com");
        }

        String request = new Http().dataGET(studyUrl, false);
        if (request != null) {
            return new JSONObject(request);
        }
        return null;
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
        // TODO: Add validation for certificate URLs
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

    /**
     * Returns the mapping of a sensor setting (from study config) to its sensor's name.
     * (e.g. status_accelerometer -> accelerometer)
     *
     * @param setting
     * @return
     */
    public static String getSensorType(String setting) {
        // TODO: Get a proper mapping
        return setting.replace("status_", "");
    }

//    public static void syncStudyConfig(Context context) {
//        if (!Aware.isStudy(context)) return;
//
//        boolean studyConfigChanged = false;
//        String studyUrl = Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER);
//        Cursor study = Aware.getStudy(context,
//                Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER));
//
//        if (study != null && study.moveToFirst()) {
//            try {
//                JSONObject localConfig = new JSONArray(study.getString(
//                        study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)))
//                        .getJSONObject(0);
////                studyConfigChanged =
//            } catch (JSONException e) {
//                e.printStackTrace();
//            }
//        }
//        try {
//            JSONObject config = getStudyConfig(studyUrl);
//            if (!validateStudyConfig(context, config)) {
//                Log.e(Aware.TAG, "Failed to sync study config, something is wrong with the config.");
//                return;
//            }
//
//        } catch (JSONException e) {
//            Log.e(Aware.TAG, "Failed to sync study config, error: " + e.getMessage());
//        }
//    }
//
//    /**
//     * Compares two versions of the study config and return true if there is any difference between
//     * them.
//     *
//     * @param oldConfig JSONObject representing the old study config
//     * @param newConfig JSONObject representing the new study config
//     * @return true if there are no changes between the old and new config, false otherwise.
//     */
//    private static boolean compareStudyConfig(JSONObject oldConfig, JSONObject newConfig) {
//        // Check database
//        // Check study info
//        // Check sensors
//        // Check schedulers
//        // Check ESM schedules
//        // Check ESM questions
//        try {
//            JSONObject oldDb = oldConfig.getJSONObject("database"),
//                    oldSensors = oldConfig.getJSONObject("sensors"),
//                    oldStudyInfo = oldConfig.getJSONObject("study_info"),
//                    oldEsms = oldConfig.getJSONObject("study_info"),
//                    oldSchedules = oldConfig.getJSONObject("study_info");
//            JSONObject newDb = newConfig.getJSONObject("database"),
//                    newSensors = newConfig.getJSONObject("sensors"),
//                    newStudyInfo = newConfig.getJSONObject("study_info"),
//                    newEsms = newConfig.getJSONObject("study_info"),
//                    newSchedules = newConfig.getJSONObject("study_info");
//
//            for (Iterator keys = oldDb.keys(); keys.hasNext();) {
//                String key = (String) keys.next();
//                if (!oldDb.getString(key).equals(newDb.getString(key))) return true;
//            }
//
//            for (Iterator keys = oldSensors.keys(); keys.hasNext();) {
//                String key = (String) keys.next();
//                if (!oldSensors.getString(key).equals(newSensors.getString(key))) return true;
//            }
//
//            for (Iterator keys = oldDb.keys(); keys.hasNext();) {
//                String key = (String) keys.next();
//                if (!oldDb.getString(key).equals(newDb.getString(key))) return true;
//            }
//
////            for (String key : oldConfig.getJSONObject("database")) {
////
////            }
//        } catch (JSONException e) {
//            e.printStackTrace();
//        }
//        return false;
//    }
}
