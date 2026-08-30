package com.aware.phone.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import com.aware.utils.Http;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small stateless helpers for the phone UI: fetching a hosted study config, mapping a sensor
 * setting key to its sensor name, and copying text to the clipboard.
 */
public class AwareUtil {

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

    /**
     * Copies text to the system clipboard and shows a short confirmation toast.
     *
     * @param context
     * @param label   clipboard label (not user-visible on most launchers)
     * @param text    the text to copy; no-op if null/empty
     */
    public static void copyToClipboard(Context context, String label, String text) {
        if (text == null || text.length() == 0) return;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show();
    }
}
