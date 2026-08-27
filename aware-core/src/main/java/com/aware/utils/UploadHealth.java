package com.aware.utils;

import android.content.Context;
import android.database.Cursor;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.R;
import com.aware.providers.Aware_Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Whether data is reaching the research database, and what the participant is told about it.
 *
 * Upload health is kept as state rather than as a stream of events, and **per table**.
 * {@code onPerformSync} runs once per table, so a single outage produces a failure for every table on
 * every tick; recording each one would fill aware_log with the same fact. Instead each table's outage
 * is recorded when it starts and cleared when that table is next acknowledged, so one outage leaves
 * one entry however long it lasts.
 *
 * Per table because the failures seen in the field were per table: a column the server lacked, and a
 * sensor that had silently stopped collecting. With one shared flag, the next table's success cleared
 * the broken table's outage, so both reported themselves healthy — for two hours and twenty-one hours
 * respectively.
 *
 * The participant is told two things. The app always shows how far delivery has reached, which costs
 * no attention. A notification is posted as soon as delivery starts failing, so a phone that has
 * stopped reaching the study is visible straight away rather than discovered later. It is posted once
 * per outage — the edge, not every failure — and cancelled when delivery recovers.
 */
public final class UploadHealth {

    /**
     * All sync adapters live in this application process and can complete concurrently. The outage
     * map and its derived settings form one logical record, so their complete read-modify-write and
     * notification transition must be serialized rather than locking individual preference calls.
     */
    private static final Object OUTAGE_STATE_LOCK = new Object();

    private UploadHealth() {
    }

    /**
     * Records that a table's batch was acknowledged: ends that table's outage, and clears the
     * notification once no table is failing.
     *
     * Clearing only the named table is the whole point. Roughly 30 sync adapters run in parallel and
     * each calls this for its own table, so a version that cleared everything let the next successful
     * table erase a broken one's outage — which is how a table with a missing server column reported
     * itself healthy for two hours.
     */
    public static void recordSuccess(Context context, String table) {
        synchronized (OUTAGE_STATE_LOCK) {
            Map<String, Long> outages = outages(context);
            if (!outages.containsKey(table)) return;

            Map<String, Long> remaining = withSuccess(outages, table);
            saveOutages(context, remaining);
            Aware.debug(context, Aware.LogType.SYNC,
                    "Upload recovered for " + table + "; its data is reaching the database again");

            if (remaining.isEmpty()) {
                StudyUtils.cancelStudyNotification(context, Aware.AWARE_UPLOAD_HEALTH_NOTIFICATION_ID);
                Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_NOTIFIED, "false");
                Aware.debug(context, Aware.LogType.SYNC,
                        "Upload recovered; every table is reaching the database again");
            }
        }
    }

    /**
     * Records that a table's batch was not acknowledged.
     *
     * @param table  the table that failed
     * @param reason short, non-sensitive description of why — never a connection string or password
     */
    public static void recordFailure(Context context, String table, String reason) {
        synchronized (OUTAGE_STATE_LOCK) {
            Map<String, Long> outages = outages(context);

            if (!outages.containsKey(table)) {
                // Edge-triggered per table: the first failure of this table's outage is the one worth
                // recording. Later failures of the same table say the same thing.
                outages = withFailure(outages, table, System.currentTimeMillis());
                saveOutages(context, outages);
                Aware.debug(context, Aware.LogType.SYNC, "Upload failing for " + table + ": " + reason);
            }

            if (shouldNotify(earliestOutage(outages), alreadyNotified(context))) {
                StudyUtils.postStudyNotification(context, Aware.AWARE_UPLOAD_HEALTH_NOTIFICATION_ID,
                        R.string.aware_notif_upload_stalled_title,
                        R.string.aware_notif_upload_stalled);
                Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_NOTIFIED, "true");
                Aware.debug(context, Aware.LogType.SYNC,
                        "Notified the participant that data is not reaching the database");
            }
        }
    }

    /** Whether this table specifically is failing to deliver. */
    public static boolean isFailing(Context context, String table) {
        synchronized (OUTAGE_STATE_LOCK) {
            return outages(context).containsKey(table);
        }
    }

    /** The tables currently failing to deliver, alphabetically; empty when delivery is healthy. */
    public static List<String> failingTables(Context context) {
        synchronized (OUTAGE_STATE_LOCK) {
            return new ArrayList<>(outages(context).keySet());
        }
    }

    private static Map<String, Long> outages(Context context) {
        return parseOutages(Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_TABLES));
    }

    private static void saveOutages(Context context, Map<String, Long> outages) {
        Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_TABLES, formatOutages(outages));
        // Kept in step for the screens and for any researcher reading the settings: the oldest
        // failing table's start time is when delivery stopped being wholly healthy.
        Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_SINCE, earliestOutage(outages));
        Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_REASON,
                outages.isEmpty() ? "" : describeFailing(new ArrayList<>(outages.keySet()))
                        + " not acknowledged");
    }

    // --- Pure outage-set operations. Separated from the Context work so the behaviour that actually
    // masked a broken table can be unit-tested rather than only reasoned about.

    /** Parses {@code table:sinceMs} pairs; unparseable or empty entries are skipped, not guessed. */
    static Map<String, Long> parseOutages(String raw) {
        Map<String, Long> outages = new TreeMap<>();
        if (raw == null || raw.trim().isEmpty()) return outages;
        for (String entry : raw.split(",")) {
            int split = entry.lastIndexOf(':');
            if (split <= 0 || split == entry.length() - 1) continue;
            try {
                long since = Long.parseLong(entry.substring(split + 1).trim());
                if (since > 0) outages.put(entry.substring(0, split).trim(), since);
            } catch (NumberFormatException ignored) {
            }
        }
        return outages;
    }

    static String formatOutages(Map<String, Long> outages) {
        StringBuilder formatted = new StringBuilder();
        for (Map.Entry<String, Long> outage : outages.entrySet()) {
            if (formatted.length() > 0) formatted.append(',');
            formatted.append(outage.getKey()).append(':').append(outage.getValue());
        }
        return formatted.toString();
    }

    /** Adds a table's outage, keeping the start time of one already recorded. */
    static Map<String, Long> withFailure(Map<String, Long> outages, String table, long now) {
        Map<String, Long> updated = new TreeMap<>(outages);
        if (!updated.containsKey(table)) updated.put(table, now);
        return updated;
    }

    /** Removes only the named table's outage, leaving every other table's untouched. */
    static Map<String, Long> withSuccess(Map<String, Long> outages, String table) {
        Map<String, Long> updated = new TreeMap<>(outages);
        updated.remove(table);
        return updated;
    }

    /** Uses the same critical section as production state updates for concurrency regression tests. */
    static void withOutageStateLock(Runnable operation) {
        synchronized (OUTAGE_STATE_LOCK) {
            operation.run();
        }
    }

    /** When delivery first stopped being wholly healthy, or 0 when no table is failing. */
    static long earliestOutage(Map<String, Long> outages) {
        long earliest = 0;
        for (long since : outages.values()) {
            if (since > 0 && (earliest == 0 || since < earliest)) earliest = since;
        }
        return earliest;
    }

    /** Names the failing tables for a participant: {@code "bluetooth"}, {@code "a and b"}, {@code "a, b and c"}. */
    static String describeFailing(List<String> tables) {
        if (tables == null || tables.isEmpty()) return "";
        if (tables.size() == 1) return tables.get(0);
        StringBuilder described = new StringBuilder();
        for (int i = 0; i < tables.size() - 1; i++) {
            if (i > 0) described.append(", ");
            described.append(tables.get(i));
        }
        return described.append(" and ").append(tables.get(tables.size() - 1)).toString();
    }

    /**
     * Whether a delivery-failure notification is due: delivery is failing and the participant has not
     * already been told about this outage.
     *
     * The second condition is what keeps one outage to one notification. Every table reports the same
     * outage on every sync tick, and re-posting the same notification id alerts again each time, so
     * without it the participant would be buzzed once per table per minute.
     *
     * Pure, so it can be unit-tested without a device.
     *
     * @param outageSince when the current outage began, or 0 if delivery is healthy
     */
    static boolean shouldNotify(long outageSince, boolean alreadyNotified) {
        return outageSince > 0 && !alreadyNotified;
    }

    /**
     * One line for the participant, describing how far delivery has reached.
     *
     * Pure and free of Android formatting: the caller passes a rendered relative time so this stays
     * unit-testable. A null {@code deliveredUpToRelative} means nothing has ever been delivered, which
     * reads differently from a delivery that has fallen behind — on a new enrolment there is no gap
     * to explain yet.
     */
    public static String statusLine(CharSequence deliveredUpToRelative, List<String> failingTables,
                                    int pendingRecords) {
        StringBuilder line = new StringBuilder();
        if (deliveredUpToRelative == null) {
            line.append("Nothing delivered yet");
        } else {
            line.append("Delivered up to ").append(deliveredUpToRelative);
        }
        if (failingTables != null && !failingTables.isEmpty()) {
            // Naming them is the point: "not delivering" alone reads as a whole-study outage, when
            // the common case is one sensor failing while the rest are fine.
            line.append(" — ").append(describeFailing(failingTables))
                    .append(failingTables.size() == 1 ? " is" : " are")
                    .append(" not delivering right now");
            if (pendingRecords > 0) {
                line.append("; ").append(pendingRecords).append(" record")
                        .append(pendingRecords == 1 ? "" : "s").append(" waiting");
            }
            line.append(". Your data is kept on the device until it can be sent.");
        }
        return line.toString();
    }

    /** When the current outage began, or 0 when delivery is healthy. */
    public static long outageSince(Context context) {
        synchronized (OUTAGE_STATE_LOCK) {
            return parseLong(Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_SINCE));
        }
    }

    /**
     * The point the research database has been brought up to: the newest row timestamp any table's
     * upload bookmark reports as delivered, or 0 when nothing has been.
     *
     * Read from the bookmarks rather than from the clock time of the last successful upload, because
     * those diverge exactly when it matters. Draining a backlog, an upload can succeed this minute
     * while the server is still only caught up to last week — reporting the upload time would claim
     * the data had arrived. This is the same quantity {@code SensorCollection.lastDeliveredMs} shows
     * per sensor, maximised across tables, so the two screens cannot disagree.
     */
    public static long deliveredUpToMs(Context context) {
        Cursor markers = context.getContentResolver().query(
                Aware_Provider.Aware_Sync_Markers.CONTENT_URI,
                new String[]{Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED},
                null, null, null);
        if (markers == null) return 0L;
        long newest = 0L;
        try {
            int column = markers.getColumnIndex(Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED);
            if (column < 0) return 0L;
            while (markers.moveToNext()) {
                long delivered = markers.getLong(column);
                if (delivered > newest) newest = delivered;
            }
        } finally {
            markers.close();
        }
        return newest;
    }

    /** Why delivery is currently failing, or an empty string when it is not. */
    public static String outageReason(Context context) {
        synchronized (OUTAGE_STATE_LOCK) {
            String reason = Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_REASON);
            return reason == null ? "" : reason;
        }
    }

    private static boolean alreadyNotified(Context context) {
        return "true".equals(Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_NOTIFIED));
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
