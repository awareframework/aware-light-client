package com.aware.utils;

/**
 * Where a table's upload resumes from, and which rows the next batch takes.
 *
 * The cursor is the row id, which SQLite assigns in insertion order and never reuses. That gives an
 * upload two properties a capture timestamp cannot: every row has a distinct position, and a row
 * buffered by a sensor and inserted later sits after the cursor rather than behind it. A batch
 * therefore resumes exactly where the last acknowledged one stopped, and the rows it takes are the
 * rows that have never been offered.
 *
 * Paging is by cursor position rather than by offset, so the window a batch reads is decided by the
 * last acknowledged row alone. Inserts arriving between two batches of the same run land after that
 * position and are read by a later batch.
 */
public final class SyncCursor {

    /** The row id, assigned in insertion order. */
    public static final String ROW_ID = "_id";

    /** Session tables carry the instant a session closed. */
    public static final String SESSION_END = "double_end_timestamp";

    /** ESM tables carry the instant the participant answered. */
    public static final String ESM_ANSWER = "double_esm_user_answer_timestamp";

    private SyncCursor() {
    }

    /**
     * The column a table gates a finished row on, or null when every stored row is ready to upload.
     *
     * @param columns the table's column names
     */
    public static String completionColumn(String[] columns) {
        if (contains(columns, SESSION_END)) return SESSION_END;
        if (contains(columns, ESM_ANSWER)) return ESM_ANSWER;
        return null;
    }

    /**
     * The rows one batch takes: those past the cursor, and finished where the table says so.
     *
     * @param columns        the table's column names
     * @param cursorId       row id of the last acknowledged row; 0 offers the table from its start
     * @param studyCondition additional clause restricting rows to the study, or null
     */
    public static String selection(String[] columns, long cursorId, String studyCondition) {
        StringBuilder selection = new StringBuilder(ROW_ID + " > " + cursorId);

        String completion = completionColumn(columns);
        if (completion != null) selection.append(" AND ").append(completion).append(" != 0");

        if (studyCondition != null) selection.append(studyCondition);

        return selection.toString();
    }

    /**
     * One batch of rows, read in insertion order from the cursor forward.
     *
     * @param batchSize rows the batch may carry
     */
    public static String order(int batchSize) {
        return ROW_ID + " ASC LIMIT " + batchSize;
    }

    /**
     * The cursor after a batch.
     *
     * Monotonic: the cursor holds the highest acknowledged row id, so a batch that carried fewer
     * rows than it read leaves the position where the acknowledged rows end.
     *
     * @param cursorId          the cursor the batch started from
     * @param acknowledgedMaxId highest row id the server acknowledged in that batch
     */
    public static long advance(long cursorId, long acknowledgedMaxId) {
        return Math.max(cursorId, acknowledgedMaxId);
    }

    /**
     * Whether a table's cursor is still to be derived from a timestamp marker.
     *
     * A marker recording only how far a table was uploaded by capture time names a position the row
     * id cursor can be seeded from, so an upload continues from there rather than offering the
     * table from its start again.
     *
     * @param cursorId        the stored row id cursor
     * @param markerTimestamp the stored timestamp marker
     */
    public static boolean needsSeeding(long cursorId, long markerTimestamp) {
        return cursorId <= 0 && markerTimestamp > 0;
    }

    private static boolean contains(String[] columns, String name) {
        if (columns == null) return false;
        for (String column : columns) {
            if (name.equals(column)) return true;
        }
        return false;
    }
}
