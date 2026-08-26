package com.aware.syncadapters;

import android.accounts.Account;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.R;
import com.aware.providers.Aware_Provider;
import com.aware.utils.DeviceId;
import com.aware.utils.Http;
import com.aware.utils.Https;
import com.aware.utils.Jdbc;
import com.aware.utils.SSLManager;
import com.aware.utils.SyncBatchBudget;
import com.aware.utils.SyncCursor;
import com.aware.utils.Webservice;
import com.aware.utils.UploadHealth;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Hashtable;

/**
 * Created by denzilferreira on 19/07/2017.
 */
public class AwareSyncAdapter extends AbstractThreadedSyncAdapter {

    private String[] DATABASE_TABLES;
    private String[] TABLES_FIELDS;
    private Uri[] CONTEXT_URIS;

    private Context mContext;
    private NotificationManager notManager;

    private final ArrayList<String> highFrequencySensors = new ArrayList<>();

    private int notificationID = 99990;

    public void init(String[] DATABASE_TABLES, String[] TABLES_FIELDS, Uri[] CONTEXT_URIS) {
        this.DATABASE_TABLES = DATABASE_TABLES;
        this.TABLES_FIELDS = TABLES_FIELDS;
        this.CONTEXT_URIS = CONTEXT_URIS;
    }

    public AwareSyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        this.mContext = context;

        highFrequencySensors.add("accelerometer");
        highFrequencySensors.add("gyroscope");
        highFrequencySensors.add("barometer");
        highFrequencySensors.add("gravity");
        highFrequencySensors.add("linear_accelerometer");
        highFrequencySensors.add("magnetometer");
        highFrequencySensors.add("rotation");
        highFrequencySensors.add("temperature");
        highFrequencySensors.add("proximity");
        highFrequencySensors.add("screentext");
        highFrequencySensors.add("screenshot");
        highFrequencySensors.add("plugin_ambient_noise");
    }

    /**
     * Sends the data to AWARE server of the study
     *
     * @param account
     * @param extras
     * @param authority
     * @param provider
     * @param syncResult
     */
    @Override
    public void onPerformSync(
        Account account,
        Bundle extras,
        String authority,
        ContentProviderClient provider,
        SyncResult syncResult
    ) {
        Log.i(Aware.TAG, "Performing sync for " + Arrays.toString(DATABASE_TABLES));
        
        // Pause uploads while a study is awaiting password re-authentication: the stored password is
        // rejected, so every upload attempt would be a failed login hammering the research database.
        if (Aware.getSetting(mContext, Aware_Preferences.PENDING_STUDY_REAUTH).trim().length() > 0) {
            Log.i(Aware.TAG, "Skipping data sync: study awaiting password re-authentication.");
            return;
        }

        // Nothing uploads without an identity to attribute it to. syncBatch() repairs a row stored
        // before the UUID resolved, but only when it has one to repair it with -- so this is the case
        // that repair cannot cover, refused here rather than left to the payload. An install with no
        // UUID has also never joined a study, so in practice offloadData() already returns on the
        // empty webservice address; stating the rule here is what stops that staying true by
        // coincidence.
        if (DeviceId.trimToEmpty(Aware.getDeviceID(mContext)).isEmpty()) {
            Log.w(Aware.TAG, "Skipping data sync: this install has no device_id yet, so no row can be attributed.");
            return;
        }

        if (!Aware.getSetting(mContext, Aware_Preferences.WEBSERVICE_SILENT).equals("true"))
            notManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (DATABASE_TABLES != null && TABLES_FIELDS != null && CONTEXT_URIS != null) {
            for (int i = 0; i < DATABASE_TABLES.length; i++) {
                offloadData(mContext, DATABASE_TABLES[i], Aware.getSetting(getContext(), Aware_Preferences.WEBSERVICE_SERVER), TABLES_FIELDS[i], CONTEXT_URIS[i]);
            }
        }
    }

/// Send data to the database
    private void offloadData(Context context, String database_table, String web_server, String table_fields, Uri CONTENT_URI) {

        //Fixed: not part of a study, do nothing
        if (web_server.length() == 0 || web_server.equalsIgnoreCase("https://api.awareframework.com/index.php")) {
            return;
        }

        //Do we need to be charging?
        if (Aware.getSetting(context, Aware_Preferences.WEBSERVICE_CHARGING).equals("true")) {
            Intent batt = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            boolean isCharging = (plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB);

            if (!isCharging) {
                if (Aware.DEBUG) Log.d(Aware.TAG, "Only sync data if charging...");
                return;
            }
        }

        //Do we need WiFi?
        if (!isWifiNeededAndConnected()) {
            if (!isForce3G(database_table)) {
                if (Aware.DEBUG)
                    Log.d(Aware.TAG, "Sync data only over Wi-Fi. Will try again later...");
                return;
            }
        }

        Aware.debug(mContext, Aware.LogType.SYNC, "STUDY-SYNC: " + database_table);

        boolean web_service_simple = Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SIMPLE).equals("true");
        boolean web_service_remove_data = Aware.getSetting(context, Aware_Preferences.WEBSERVICE_REMOVE_DATA).equals("true");

        /**
         * Max number of rows to place on the HTTP(s) post
         */
        int MAX_POST_SIZE = getBatchSize();
        if (MAX_POST_SIZE == 0) {
            Log.d(Aware.TAG, "Device without available memory left for sync.");
            return;
        }

        if (Aware.is_watch(context)) {
            MAX_POST_SIZE = 100; //default for Android Wear (we have a limit of 100KB of data packet size (Message API restrictions)
        }

        if (Aware.DEBUG)
            Log.d(Aware.TAG, "Syncing " + database_table + " to: " + web_server + " in batches of " + MAX_POST_SIZE);

        String device_id = Aware.getDeviceID(context);
        boolean DEBUG = Aware.getSetting(context, Aware_Preferences.DEBUG_FLAG).equals("true");

        try {
            String[] columnsStr = getTableColumnsNames(CONTENT_URI, context);

            String study_condition = getStudySyncCondition(context, database_table);

            /**
             * The cursor is read from the local aware_sync_markers table. The server is never
             * queried for it: one round trip per table per sync event does not scale.
             */
            Position cursor = getSyncCursor(database_table, CONTENT_URI, columnsStr, study_condition);

            int total_records = getNumberOfRecordsToSync(CONTENT_URI, columnsStr, cursor, study_condition, context);
            boolean allow_table_maintenance = isTableAllowedForMaintenance(database_table);

            if (Aware.DEBUG) {
                Log.d(Aware.TAG, "Syncing table: " + database_table);
                Log.d(Aware.TAG, "Upload resumes after " + SyncCursor.orderColumn(columnsStr)
                        + " " + cursor.value + ", row id " + cursor.rowId);
                Log.d(Aware.TAG, "Joined study since: " + study_condition);
                Log.d(Aware.TAG, "Rows remaining to sync: " + total_records);
            }

            // If we have records to sync
            if (total_records > 0) {
                long start = System.currentTimeMillis();
                int uploaded_records = 0;
                int batches = (int) Math.ceil(total_records / (double) MAX_POST_SIZE);

                long deleteThroughId = 0;

                do {
                    if (!Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SILENT).equals("true"))
                        notifyUser(context, "Table: " + database_table + " syncing batch " + (uploaded_records + MAX_POST_SIZE) / MAX_POST_SIZE + " of " + batches, false, true, notificationID);

                    Cursor sync_data = getSyncData(CONTENT_URI, study_condition, columnsStr, cursor, context, MAX_POST_SIZE);
                    BatchOutcome batch = syncBatch(sync_data, database_table, device_id, context, DEBUG, cursor);
                    if (!batch.acknowledged) {
                        Log.d(Aware.TAG, "Batch for " + database_table + " was not acknowledged by the database. Will try again later.");
                        break;
                    }

                    // A batch that read no rows has drained the table: the cursor already stands at
                    // the last row, so there is nothing to advance it past.
                    if (batch.rows == 0) break;

                    // The cursor moves to the last row the server took in the order the table is
                    // paged by, and is stored before the next batch is read. An interrupted run
                    // therefore resumes from the last acknowledged row rather than from where this
                    // run began. Cleanup follows the highest row id instead, which on a table paged
                    // by completion is a different row from the one the cursor stands on.
                    cursor = batch.cursor;
                    deleteThroughId = Math.max(deleteThroughId, batch.maxRowId);
                    setSyncCursor(database_table, cursor);

                    uploaded_records += batch.rows;
                }
                while (uploaded_records < total_records && isWifiNeededAndConnected());

                //Are we performing database space maintenance?
                if (deleteThroughId > 0 && allow_table_maintenance)
                    performDatabaseSpaceMaintenance(CONTENT_URI, cursor.value, deleteThroughId, columnsStr, web_service_remove_data, context, database_table, DEBUG);

                if (DEBUG)
                    Log.d(Aware.TAG, database_table + " sync time: " + DateUtils.formatElapsedTime((System.currentTimeMillis() - start) / 1000));

                if (!Aware.getSetting(context, Aware_Preferences.WEBSERVICE_SILENT).equals("true")) {
                    notifyUser(context, "Finished syncing " + database_table + ". Thanks!", true, false, notificationID);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyUser(Context mContext, String message, boolean dismiss, boolean indetermined, int id) {
        if (!dismiss) {
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(mContext, Aware.AWARE_NOTIFICATION_CHANNEL_DATASYNC);
            mBuilder.setSmallIcon(R.drawable.ic_stat_aware_sync);
            mBuilder.setContentTitle(mContext.getResources().getString(R.string.app_name));
            mBuilder.setContentText(message);
            mBuilder.setAutoCancel(true);
            mBuilder.setOnlyAlertOnce(true); //notify the user only once
            mBuilder.setDefaults(NotificationCompat.DEFAULT_LIGHTS); //we only blink the LED, nothing else.
            mBuilder.setProgress(100, 100, indetermined);

            mBuilder = Aware.setNotificationProperties(mBuilder, Aware.AWARE_NOTIFICATION_IMPORTANCE_DATASYNC);

            PendingIntent clickIntent = PendingIntent.getActivity(mContext, 0, new Intent(), PendingIntent.FLAG_UPDATE_CURRENT);
            mBuilder.setContentIntent(clickIntent);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                mBuilder.setChannelId(Aware.AWARE_NOTIFICATION_CHANNEL_DATASYNC);

            try {
                notManager.notify(id, mBuilder.build());
            } catch (NullPointerException e) {
                if (Aware.DEBUG) Log.d(Aware.TAG, "Notification exception: " + e);
            }
        } else {
            try {
                notManager.cancel(id);
            } catch (NullPointerException e) {
                if (Aware.DEBUG) Log.d(Aware.TAG, "Notification exception: " + e);
            }
        }
    }

    /**
     * Upper bound on the number of rows a batch reads at once, scaled to the device's total RAM.
     *
     * This is a row count, so it says nothing about how many bytes those rows carry. The byte
     * ceiling is {@link SyncBatchBudget#MAX_PAYLOAD_BYTES}, applied while a batch is being built;
     * whichever of the two binds first ends the batch. Returns 0 when the device is under memory
     * pressure, which skips the sync entirely.
     */
    private int getBatchSize() {
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        ActivityManager actManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        actManager.getMemoryInfo(memInfo);

        if (memInfo.lowMemory) {
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getContext(), Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL);
            mBuilder.setSmallIcon(R.drawable.ic_stat_aware_plugin_dependency);
            mBuilder.setContentTitle("Low memory detected...");
            mBuilder.setContentText("Tap and swipe to clear recently used applications.");
            mBuilder.setAutoCancel(true);
            mBuilder.setOnlyAlertOnce(true);
            mBuilder.setDefaults(NotificationCompat.DEFAULT_ALL);
            mBuilder = Aware.setNotificationProperties(mBuilder, Aware.AWARE_NOTIFICATION_IMPORTANCE_GENERAL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                mBuilder.setChannelId(Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL);

            Intent intent = new Intent("com.android.systemui.recent.action.TOGGLE_RECENTS");
            intent.setComponent(new ComponentName("com.android.systemui", "com.android.systemui.recent.RecentsActivity"));

            PendingIntent clickIntent = PendingIntent.getActivity(getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            mBuilder.setContentIntent(clickIntent);

            NotificationManager notificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(Applications.ACCESSIBILITY_NOTIFICATION_ID, mBuilder.build());

            return 0;
        }

        double availableRam = memInfo.totalMem / 1048576000.0;
        if (availableRam <= 1.0) return 500;
        if (availableRam <= 2.0) return 1500;
        if (availableRam <= 4.0) return 5000;
        return 10000;
    }


    /**
     * Check the current connection is WiFi and we are connected
     *
     * @return
     */
    public boolean isWifiNeededAndConnected() {
        if (Aware.getSetting(mContext, Aware_Preferences.WEBSERVICE_WIFI_ONLY).equals("true")) {
            ConnectivityManager connManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connManager.getActiveNetworkInfo();
            return (activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI && activeNetwork.isConnected());
        }
        return true;
    }

    /**
     * Fallback to 3G if no wifi for x hours
     */
    public boolean isForce3G(String database_table) {
        if (Aware.getSetting(mContext, Aware_Preferences.WEBSERVICE_FALLBACK_NETWORK).length() > 0 && !Aware.getSetting(mContext, Aware_Preferences.WEBSERVICE_FALLBACK_NETWORK).equals("0")) {
            Cursor lastSynched = mContext.getContentResolver().query(Aware_Provider.Aware_Log.CONTENT_URI, null, Aware_Provider.Aware_Log.LOG_MESSAGE + " LIKE 'STUDY-SYNC: " + database_table + "'", null, Aware_Provider.Aware_Log.LOG_TIMESTAMP + " DESC LIMIT 1");
            if (lastSynched != null && lastSynched.moveToFirst()) {
                long synched = lastSynched.getLong(lastSynched.getColumnIndex(Aware_Provider.Aware_Log.LOG_TIMESTAMP));

                Log.d(Aware.TAG, "Checking forced sync over 3G...");
                Log.d(Aware.TAG, "Last sync: " + synched + " elapsed: " + (System.currentTimeMillis() - synched) + " force: " + (System.currentTimeMillis() - synched >= Aware.getSettingAsInt(mContext, Aware_Preferences.WEBSERVICE_FALLBACK_NETWORK, 0) * 60 * 60 * 1000));

                lastSynched.close();
                return (System.currentTimeMillis() - synched >= Aware.getSettingAsInt(mContext, Aware_Preferences.WEBSERVICE_FALLBACK_NETWORK, 0) * 60 * 60 * 1000);
            } else
                return true; //first time synching.
        }
        return false;
    }

    private String[] getTableColumnsNames(Uri CONTENT_URI, Context mContext) {
        String[] columnsStr = new String[]{};
        Cursor columnsDB = mContext.getContentResolver().query(CONTENT_URI, null, null, null, null);
        if (columnsDB != null) {
            columnsStr = columnsDB.getColumnNames();
        }
        if (columnsDB != null && !columnsDB.isClosed()) columnsDB.close();
        return columnsStr;
    }

    /**
     * Where a table's upload stands: the ordering value it reached, and the row it stopped on.
     *
     * The pair is the cursor. On a table paged by row id the value carries the last row's capture
     * time and only the id decides what comes next; on a table paged by completion both halves do,
     * the value ordering the rows and the id separating rows that finished in the same millisecond.
     */
    private static final class Position {
        long value;
        long rowId;

        Position(long value, long rowId) {
            this.value = value;
            this.rowId = rowId;
        }
    }

    /** What became of one offered batch. */
    private static final class BatchOutcome {
        boolean acknowledged;
        int rows;
        /** Highest row id the batch carried, which cleanup deletes through. */
        long maxRowId;
        /** The position the last row of the batch leaves the cursor at. */
        Position cursor;

        BatchOutcome(Position cursor) {
            this.cursor = cursor;
        }
    }

    /**
     * The position {@code database_table} resumes its upload from, opening at the start of the table
     * for one whose rows have never been offered.
     *
     * The marker is keyed by table name in its own table, so finding it is an equality match on one
     * row rather than a pattern match over log text.
     *
     * A marker holding a timestamp alone names a position on a table paged by row id, so it is
     * translated once into the id of the newest row at or before it. Everything at that instant is
     * treated as delivered, which is the reading that offers no row twice.
     */
    private Position getSyncCursor(String database_table, Uri CONTENT_URI, String[] columnsStr,
                                   String study_condition) {
        long cursorId = 0;
        long markerValue = 0;

        Cursor marker = mContext.getContentResolver().query(
                Aware_Provider.Aware_Sync_Markers.CONTENT_URI, null,
                Aware_Provider.Aware_Sync_Markers.MARKER_TABLE + "=?",
                new String[]{database_table}, null);

        if (marker != null && marker.moveToFirst()) {
            int idIndex = marker.getColumnIndex(Aware_Provider.Aware_Sync_Markers.MARKER_LAST_ID);
            if (idIndex >= 0) cursorId = marker.getLong(idIndex);
            markerValue = marker.getLong(
                    marker.getColumnIndex(Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED));
        }
        if (marker != null && !marker.isClosed()) marker.close();

        Position cursor = new Position(markerValue, cursorId);

        if (SyncCursor.needsSeeding(columnsStr, cursorId, markerValue)) {
            cursor.rowId = highestRowIdUpTo(CONTENT_URI, markerValue, study_condition);
            setSyncCursor(database_table, cursor);
            if (Aware.DEBUG)
                Log.d(Aware.TAG, database_table + ": upload resumes from row id " + cursor.rowId);
        }

        return cursor;
    }

    /** The newest row captured at or before {@code timestamp}, as its row id. */
    private long highestRowIdUpTo(Uri CONTENT_URI, long timestamp, String study_condition) {
        long rowId = 0;
        Cursor newest = mContext.getContentResolver().query(CONTENT_URI,
                new String[]{SyncCursor.ROW_ID},
                "timestamp <= " + timestamp + (study_condition == null ? "" : study_condition),
                null, SyncCursor.ROW_ID + " DESC LIMIT 1");
        if (newest != null && newest.moveToFirst()) {
            rowId = newest.getLong(newest.getColumnIndex(SyncCursor.ROW_ID));
        }
        if (newest != null && !newest.isClosed()) newest.close();
        return rowId;
    }

    /**
     * Records how far {@code database_table} has been uploaded.
     *
     * Written after the server acknowledges a batch, and read by the next sync of that table to pick
     * up where this one stopped. The provider replaces the table's previous marker, so this holds one
     * row per synced table. The value half is the instant the table is current to, which is what the
     * upload-health and diagnostics screens report.
     */
    private void setSyncCursor(String database_table, Position cursor) {
        ContentValues marker = new ContentValues();
        marker.put(Aware_Provider.Aware_Sync_Markers.MARKER_TABLE, database_table);
        marker.put(Aware_Provider.Aware_Sync_Markers.MARKER_LAST_ID, cursor.rowId);
        marker.put(Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED, cursor.value);
        mContext.getContentResolver().insert(Aware_Provider.Aware_Sync_Markers.CONTENT_URI, marker);
    }

    private String getStudySyncCondition(Context mContext, String DATABASE_TABLE) {
        //If in a study, get only data from joined date onwards
        String study_condition = "";
        if (Aware.isStudy(mContext)) {
            Cursor study = Aware.getStudy(mContext, Aware.getSetting(mContext, Aware_Preferences.WEBSERVICE_SERVER));
            if (study != null && study.moveToFirst()) {
                study_condition += " AND timestamp >= " + study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP));
            }
            if (study != null && !study.isClosed()) study.close();
        }

        //We always want to sync the device's profile and hardware sensor profiles for any study, no matter when we joined the study
        if (DATABASE_TABLE.equalsIgnoreCase("aware_device")
                || DATABASE_TABLE.matches("sensor_.*"))
            study_condition = "";

        return study_condition;
    }

    /**
     * Rows of {@code CONTENT_URI} still to upload: those past the cursor, and finished where the
     * table gates a row on a completion column.
     *
     * Counted rather than estimated, so the batch loop knows when it has drained the table.
     */
    private int getNumberOfRecordsToSync(Uri CONTENT_URI, String[] columnsStr, Position cursor,
                                         String study_condition, Context mContext) {
        int total = 0;
        String selection = SyncCursor.selection(columnsStr, cursor.value, cursor.rowId, study_condition);
        Cursor counter = mContext.getContentResolver().query(CONTENT_URI,
                new String[]{SyncCursor.ROW_ID}, selection, null, null);
        if (counter != null) {
            total = counter.getCount();
            counter.close();
        }
        if (Aware.DEBUG) Log.d(Aware.TAG, "Rows to sync where " + selection + ": " + total);
        return total;
    }

    /**
     * One batch, read from the cursor forward in insertion order.
     *
     * The window is decided by the cursor alone rather than by an offset into the result set, so a
     * row inserted while this run is in flight lands after the cursor and is read by a later batch
     * instead of shifting the rows this one takes.
     */
    private Cursor getSyncData(Uri CONTENT_URI, String study_condition, String[] columnsStr,
                               Position cursor, Context mContext, int MAX_POST_SIZE) {
        return mContext.getContentResolver().query(CONTENT_URI, null,
                SyncCursor.selection(columnsStr, cursor.value, cursor.rowId, study_condition), null,
                SyncCursor.order(columnsStr, MAX_POST_SIZE));
    }

    /**
     * Frees local space once the server holds the rows.
     *
     * @param lastId  highest row id the server acknowledged in this run
     * @param lastRun instant that acknowledged row was captured, which dates the retention windows
     */
    private void performDatabaseSpaceMaintenance(Uri CONTENT_URI, long lastRun, long lastId, String[] columnsStr, Boolean WEBSERVICE_REMOVE_DATA, Context mContext, String DATABASE_TABLE, Boolean DEBUG) {
        // keep records when contain end_timestamp (session-based entries), only remove the rows where the end_timestamp > 0
        String deleteSessionBasedSensors = "";
        if (exists(columnsStr, "double_end_timestamp")) {
            deleteSessionBasedSensors = " and double_end_timestamp > 0";
        }

        // A row still to finish is a row still to upload, whichever column the table finishes on, so
        // the deletion leaves it where an unanswered prompt and an open session are alike.
        String keepUnfinished = "";
        String completion = SyncCursor.completionColumn(columnsStr);
        if (completion != null) keepUnfinished = " AND " + completion + " != 0";

        if (WEBSERVICE_REMOVE_DATA) {
            // Keyed on _id (insertion order) rather than timestamp (capture order), so the rows
            // removed are the rows this run had acknowledged. A sample a sensor buffered and
            // inserted after the batch was read carries a higher _id and an older capture time, and
            // it stays until it has been uploaded and acknowledged itself.
            if (lastId > 0)
                mContext.getContentResolver().delete(CONTENT_URI,
                        SyncCursor.ROW_ID + " <= " + lastId + keepUnfinished, null);

        } else if (Aware.getSetting(mContext, Aware_Preferences.FREQUENCY_CLEAN_OLD_DATA).length() > 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(lastRun);
            int rowsDeleted = 0;
            switch (Aware.getSettingAsInt(mContext, Aware_Preferences.FREQUENCY_CLEAN_OLD_DATA, 0)) {
                case 1: //Weekly
                    cal.add(Calendar.DAY_OF_YEAR, -7);
                    if (Aware.DEBUG)
                        Log.d(Aware.TAG, " Cleaning locally any data older than last week (yyyy/mm/dd): " + cal.get(Calendar.YEAR) + '/' + (cal.get(Calendar.MONTH) + 1) + '/' + cal.get(Calendar.DAY_OF_MONTH));
                    rowsDeleted = mContext.getContentResolver().delete(CONTENT_URI, "timestamp < " + cal.getTimeInMillis() + deleteSessionBasedSensors, null);
                    break;
                case 2: //Monthly
                    cal.add(Calendar.MONTH, -1);
                    if (Aware.DEBUG)
                        Log.d(Aware.TAG, " Cleaning locally any data older than last month (yyyy/mm/dd): " + cal.get(Calendar.YEAR) + '/' + (cal.get(Calendar.MONTH) + 1) + '/' + cal.get(Calendar.DAY_OF_MONTH));
                    rowsDeleted = mContext.getContentResolver().delete(CONTENT_URI, "timestamp < " + cal.getTimeInMillis() + deleteSessionBasedSensors, null);
                    break;
                case 3: //Daily
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    if (Aware.DEBUG)
                        Log.d(Aware.TAG, "Cleaning locally any data older than today (yyyy/mm/dd): " + cal.get(Calendar.YEAR) + '/' + (cal.get(Calendar.MONTH) + 1) + '/' + cal.get(Calendar.DAY_OF_MONTH) + " from " + CONTENT_URI.toString());
                    rowsDeleted = mContext.getContentResolver().delete(CONTENT_URI, "timestamp < " + cal.getTimeInMillis() + deleteSessionBasedSensors, null);
                    break;
                case 4: //Always — remove acknowledged rows only.
                    // Key the deletion on _id (insertion order), not timestamp (capture order): a
                    // sample a sensor buffered and inserted after this sync read gets a higher _id,
                    // so it is never deleted before it has itself been uploaded and acknowledged.
                    if (highFrequencySensors.contains(DATABASE_TABLE) && lastId > 0)
                        rowsDeleted = mContext.getContentResolver().delete(CONTENT_URI, "_id <= " + lastId + keepUnfinished, null);
                    break;
            }

            if (DEBUG && rowsDeleted > 0)
                Log.d(Aware.TAG, "Cleaned " + rowsDeleted + " from " + CONTENT_URI.toString());
        }
    }

    /**
     * Offers one batch to the server and reports what became of it.
     *
     * Acknowledged with a row count of 0 is a batch that read no rows, which is a drained table
     * rather than a refusal.
     */
    private BatchOutcome syncBatch(Cursor context_data, String DATABASE_TABLE, String DEVICE_ID, Context mContext, Boolean DEBUG, Position from) throws JSONException {
        JSONArray rows = new JSONArray();
        BatchOutcome outcome = new BatchOutcome(from);
        // A read that produced no cursor offered the server nothing, so nothing was refused: the
        // caller ends the run on the row count rather than reporting the server turned a batch away.
        if (context_data == null) {
            outcome.acknowledged = true;
            return outcome;
        }
        String orderColumn = SyncCursor.orderColumn(getColumnNames(context_data));
        long lastSynced = 0;
        long lastOrderValue = from.value;
        long lastRowId = from.rowId;
        long maxId = 0;
        long payloadBytes = 0;
        boolean cappedByPayload = false;

        // A read that found no rows still holds a cursor, and this runs once per table per sync
        // event on every table that is up to date.
        if (context_data != null && !context_data.moveToFirst()) {
            context_data.close();
            outcome.acknowledged = true;
            return outcome;
        }

        if (context_data != null) {
            do {
                JSONObject row = new JSONObject();
                long rowBytes = 0;
                long rowId = 0;
                String[] columns = context_data.getColumnNames();
                for (String c_name : columns) {
                    if (c_name.equals("_id")) {
                        // Track the highest local row id in this batch so acknowledged rows can be
                        // deleted by _id (insertion order). A sample a sensor buffers and inserts
                        // after this read gets a higher _id, so it is never deleted before it has
                        // itself been uploaded.
                        rowId = context_data.getLong(context_data.getColumnIndex("_id"));
                        continue; // still skip the local id from the uploaded payload
                    }
                    if (c_name.equals("timestamp") || c_name.contains("double")) {
                        row.put(c_name, context_data.getDouble(context_data.getColumnIndex(c_name)));
                        rowBytes += SyncBatchBudget.columnBytes(c_name, SyncBatchBudget.NUMERIC_VALUE_BYTES);
                    } else if (c_name.contains("float")) {
                        row.put(c_name, context_data.getFloat(context_data.getColumnIndex(c_name)));
                        rowBytes += SyncBatchBudget.columnBytes(c_name, SyncBatchBudget.NUMERIC_VALUE_BYTES);
                    } else if (c_name.contains("long")) {
                        row.put(c_name, context_data.getLong(context_data.getColumnIndex(c_name)));
                        rowBytes += SyncBatchBudget.columnBytes(c_name, SyncBatchBudget.NUMERIC_VALUE_BYTES);
                    } else if (c_name.contains("blob") || c_name.contains("image_data")) {
                        byte[] blob = context_data.getBlob(context_data.getColumnIndex(c_name));
                        String encoded = blob == null ? "" : Base64.encodeToString(blob, Base64.DEFAULT);
                        row.put(c_name, encoded);
                        rowBytes += SyncBatchBudget.columnBytes(c_name, encoded.length());
                    } else if (c_name.contains("integer")) {
                        row.put(c_name, context_data.getInt(context_data.getColumnIndex(c_name)));
                        rowBytes += SyncBatchBudget.columnBytes(c_name, SyncBatchBudget.NUMERIC_VALUE_BYTES);
                    } else {
                        String str = "";
                        if (!context_data.isNull(context_data.getColumnIndex(c_name))) { // Fixes nulls and batch inserts not being possible
                            str = context_data.getString(context_data.getColumnIndex(c_name));
                        }
                        // Last line of defence for a row stored before device_id could be resolved.
                        // Uploading it blank puts a row on the server that no participant can be
                        // matched to, and the stored copy is the only place left to repair it from.
                        if (c_name.equals("device_id") && DeviceId.trimToEmpty(str).isEmpty()
                                && !DeviceId.trimToEmpty(DEVICE_ID).isEmpty()) {
                            str = DEVICE_ID;
                            if (DEBUG) Log.d(Aware.TAG, DATABASE_TABLE
                                    + ": stamped a row that had no device_id before uploading it");
                        }
                        row.put(c_name, str);
                        rowBytes += SyncBatchBudget.columnBytes(c_name, str.length());
                    }
                }

                // Stop before the payload outgrows what the phone can hold and the server will
                // accept. The caller resumes from the rows actually taken, so a held-back row is the
                // first row of the next batch rather than a skipped one.
                if (SyncBatchBudget.holdForNextBatch(rows.length(), payloadBytes, rowBytes,
                        SyncBatchBudget.MAX_PAYLOAD_BYTES)) {
                    cappedByPayload = true;
                    break;
                }

                rows.put(row);
                payloadBytes += rowBytes;
                if (rowId > maxId) maxId = rowId;
                // The cursor stands on the last row the batch read in the order the table is paged
                // by, which on a table ordered by completion is not the row with the highest id.
                lastRowId = rowId;
                lastOrderValue = orderColumn.equals(SyncCursor.ROW_ID)
                        ? rowId
                        : (long) context_data.getDouble(context_data.getColumnIndex(orderColumn));
            } while (context_data.moveToNext());

            context_data.close(); // Clear phone's memory immediately

            if (rows.length() == 0) {
                outcome.acknowledged = true;
                return outcome;
            }

            if (DEBUG && cappedByPayload)
                Log.d(Aware.TAG, DATABASE_TABLE + " batch capped at " + rows.length()
                        + " row(s) / ~" + (payloadBytes / 1024) + " KB by the payload budget");

            lastSynced = rows.getJSONObject(rows.length() - 1).getLong("timestamp"); // Last record to be synced

            // Which path carries the rows is the study's choice, and both answer the same
            // question: did the server take them. On the webservice path the phone holds
            // no database credential at all, which is what lets the database stay private.
            boolean viaWebservice = Webservice.enabled(mContext);
            boolean dataInserted = viaWebservice
                    ? Webservice.insertData(mContext, DATABASE_TABLE, rows)
                    : Jdbc.insertData(mContext, DATABASE_TABLE, rows);

            // The server did not acknowledge the batch. The path taken has already logged the
            // underlying cause; unreachable server, rejected credentials and a rejected
            // statement all arrive here alike, so this line does not guess between them.
            if (!dataInserted) {
                if (DEBUG) Log.d(Aware.TAG, DATABASE_TABLE + ": batch of " + rows.length()
                        + " row(s) / ~" + (payloadBytes / 1024) + " KB was not acknowledged. See the "
                        + (viaWebservice ? "webservice" : "JDBC") + " log above.");
                // Records the outage once rather than once per table per tick, and notifies the
                // participant only if it lasts. The table name is the reason: which one first failed
                // is the useful part, and it carries no credentials.
                UploadHealth.recordFailure(mContext, DATABASE_TABLE, "batch not acknowledged");
                return outcome;
            } else {
                // The batch was committed (acknowledged) by the database: report the position its
                // last row leaves the cursor at, the highest id so cleanup deletes through exactly
                // these rows and no further, and the row count so the caller resumes from what
                // actually went rather than from the row-count cap it asked for.
                outcome.acknowledged = true;
                outcome.rows = rows.length();
                outcome.maxRowId = maxId;
                boolean byRowId = orderColumn.equals(SyncCursor.ROW_ID);
                outcome.cursor = new Position(byRowId ? lastSynced : lastOrderValue,
                        byRowId ? maxId : lastRowId);
                UploadHealth.recordSuccess(mContext, DATABASE_TABLE);

                if (DEBUG)
                    Log.d(Aware.TAG, "Sync OK into " + DATABASE_TABLE + " [ " + rows.length() + " rows ]");
            }
        }

        return outcome;
    }


    /**
     * Tables holding device state rather than a stream of observations are kept locally after
     * upload: the study enrolment, the defined schedulers, and the device profile.
     *
     * The device profile has to survive because the phone compares against its stored row to decide
     * whether the device's facts or participant-facing label have changed (see
     * {@code Aware.get_device_info()}). A locally deleted row makes the comparison find nothing and
     * label updates match nothing.
     */
    private boolean isTableAllowedForMaintenance(String table_name) {
        return !table_name.equalsIgnoreCase("aware_studies")
                && !table_name.equalsIgnoreCase("scheduler")
                && !table_name.equalsIgnoreCase("aware_device");
    }

    /** A cursor's column names, or an empty list when the read produced no cursor. */
    private static String[] getColumnNames(Cursor cursor) {
        return cursor == null ? new String[0] : cursor.getColumnNames();
    }

    private static boolean exists(String[] array, String find) {
        for (String a : array) {
            if (a.equals(find)) return true;
        }
        return false;
    }
}
