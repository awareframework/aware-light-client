package com.aware.utils;

import android.content.Context;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;

import org.json.JSONArray;

import java.util.Hashtable;

/**
 * Uploading through the study's webservice instead of opening the database.
 *
 * The counterpart to {@link Jdbc}, and deliberately the same contract: a boolean
 * that means "the server acknowledged these rows", so a caller can keep its rows
 * on false without knowing which path carried them. Which path a study uses is
 * {@link #enabled(Context)}, read from the config the phone already holds.
 *
 * What the server expects is the legacy AWARE webservice protocol the micro-server
 * implements: a form POST to {@code <webservice_server>/<table>/insert} carrying
 * {@code device_id} and {@code data}, where {@code data} is the same JSON array of
 * rows the JDBC path would have inserted. The response body is not parsed --- the
 * status code is the acknowledgement, and {@link Http#dataPOST} already returns
 * null for anything that is not 200.
 *
 * Why this exists on the server's terms rather than the phone's: the phone holds no
 * database credential on this path, so the address it is given is a study URL and
 * nothing else. That is what lets the database stay private, and it is the whole
 * reason for preferring this path where a network allows it.
 */
public class Webservice {

    private static final String TAG = "AWARE::Webservice";

    /** Matches Jdbc's fast-fail budget, so a study exit stays responsive either way. */
    private static final int FAST_FAIL_SECONDS = 10;

    /** The ordinary upload timeout, in milliseconds. */
    private static final int TIMEOUT_MS = 60 * 1000;

    private Webservice() {}

    /**
     * Whether this study uploads through the webservice rather than the database.
     *
     * Read from {@code status_webservice}, which the study config carries and the
     * server derives from the study's declared dataflow. A phone whose config
     * predates that field reads false and keeps using the database, which is what
     * it was already doing.
     */
    public static boolean enabled(Context context) {
        return "true".equals(Aware.getSetting(context, Aware_Preferences.STATUS_WEBSERVICE));
    }

    /**
     * The endpoint a table's rows are posted to.
     *
     * Built from {@code webservice_server}, which on this path is the study URL the
     * server hands out; the table and the operation are appended exactly as the
     * legacy protocol expects. Empty when no study URL is set, which callers treat
     * as "not configured" rather than guessing at a host.
     */
    public static String insertUrl(Context context, String table) {
        String server = Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER);
        if (server == null || server.trim().length() == 0) return "";
        String base = server.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + table + "/insert";
    }

    /**
     * Post a batch of rows, and say whether the server took them.
     *
     * @return true when the server acknowledged the batch; false when it did not, in
     *         which case the caller keeps its rows exactly as it would on a failed
     *         database insert.
     */
    public static boolean insertData(Context context, String table, JSONArray rows) {
        return post(context, table, rows, TIMEOUT_MS);
    }

    /**
     * The same upload on a short budget, for a caller that must not block.
     *
     * Mirrors {@link Jdbc#insertDataFastFail}: leaving a study has to stay
     * responsive even when the server cannot be reached, so this bounds the wait
     * rather than inheriting the default socket timeout.
     */
    public static boolean insertDataFastFail(Context context, String table, JSONArray rows,
                                             int timeoutSeconds) {
        int seconds = timeoutSeconds > 0 ? timeoutSeconds : FAST_FAIL_SECONDS;
        return post(context, table, rows, seconds * 1000);
    }

    /**
     * Whether the study's webservice answers at all.
     *
     * The counterpart to {@link Jdbc#probeConnection}, and necessarily coarser: a
     * database refuses a bad credential distinctly, while this path has no
     * credential to refuse. So reachable or not is the whole answer, and a caller
     * that wanted to tell "wrong password" from "wrong host" cannot on this path.
     */
    public static boolean reachable(Context context) {
        return reachable(Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SERVER));
    }

    /**
     * The same question about a URL that is not the stored one yet.
     *
     * Join-time validation needs this: the config is being checked before it is
     * adopted, so the setting still holds the previous study's address or none.
     */
    public static boolean reachable(String server) {
        if (server == null || server.trim().length() == 0) return false;
        Http http = new Http();
        http.setTimeout(FAST_FAIL_SECONDS * 1000);
        return http.dataGET(server.trim(), true) != null;
    }

    private static boolean post(Context context, String table, JSONArray rows, int timeoutMs) {
        if (rows == null || rows.length() == 0) return true;

        // Through the same resolver the sync adapter uses, so a device whose id
        // lives only in the mirror is not treated as having none.
        String deviceId = DeviceId.trimToEmpty(Aware.getDeviceID(context));
        if (deviceId.isEmpty()) {
            // The server refuses a batch that names no device, and it is right to:
            // rows stored against no device belong to nobody. Failing here keeps them
            // on the phone until the id resolves rather than spending a request to be
            // told the same thing.
            Log.w(TAG, "Not uploading '" + table + "': this install has no device_id yet.");
            return false;
        }

        String url = insertUrl(context, table);
        if (url.length() == 0) {
            Log.w(TAG, "Not uploading '" + table + "': no webservice_server is set.");
            return false;
        }

        Hashtable<String, String> form = new Hashtable<>();
        form.put("device_id", deviceId);
        form.put("data", rows.toString());

        Http http = new Http();
        http.setTimeout(timeoutMs);
        // The body is never logged here. It is the participant's data, and a log is
        // not where it belongs.
        String answer = http.dataPOST(url, form, true);
        if (answer == null) {
            Log.w(TAG, "Upload of '" + table + "' was not acknowledged (" + rows.length()
                    + " row(s)).");
            return false;
        }

        if (Aware.DEBUG) {
            Log.d(TAG, "Uploaded " + rows.length() + " row(s) to '" + table + "'.");
        }
        return true;
    }
}
