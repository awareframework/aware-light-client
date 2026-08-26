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

    /** Whether a table is ordered by the instant its rows finished rather than by row id. */
    public static boolean pagesByCompletion(String[] columns) {
        return completionColumn(columns) != null;
    }

    /**
     * The column a table's rows are ordered by: the instant they finished where the table has one,
     * and the row id otherwise.
     */
    public static String orderColumn(String[] columns) {
        String completion = completionColumn(columns);
        return completion == null ? ROW_ID : completion;
    }

    /**
     * The rows one batch takes: those past the cursor, and finished where the table says so.
     *
     * A table whose rows finish after they are stored is ordered by the instant they finished, and
     * the cursor is the pair (that instant, row id). A row is past the cursor when it finished later,
     * or finished in the same millisecond and sits after it in insertion order. Every write of a
     * completion column stamps the moment of completion, so a row that finishes now finishes after
     * every row already uploaded and is offered on the next sync however long it ran.
     *
     * @param columns        the table's column names
     * @param cursorValue    the ordering value the cursor stands at
     * @param cursorId       row id of the last acknowledged row; 0 offers the table from its start
     * @param studyCondition additional clause restricting rows to the study, or null
     */
    public static String selection(String[] columns, long cursorValue, long cursorId,
                                   String studyCondition) {
        StringBuilder selection = new StringBuilder();
        String completion = completionColumn(columns);

        if (completion == null) {
            selection.append(ROW_ID).append(" > ").append(cursorId);
        } else {
            selection.append(completion).append(" != 0")
                    .append(" AND (").append(completion).append(" > ").append(cursorValue)
                    .append(" OR (").append(completion).append(" = ").append(cursorValue)
                    .append(" AND ").append(ROW_ID).append(" > ").append(cursorId).append("))");
        }

        if (studyCondition != null) selection.append(studyCondition);

        return selection.toString();
    }

    /**
     * One batch of rows, read from the cursor forward in the order the table is paged by.
     *
     * @param columns   the table's column names
     * @param batchSize rows the batch may carry
     */
    public static String order(String[] columns, int batchSize) {
        String completion = completionColumn(columns);
        if (completion == null) return ROW_ID + " ASC LIMIT " + batchSize;
        return completion + " ASC, " + ROW_ID + " ASC LIMIT " + batchSize;
    }

    /**
     * Whether a table's cursor is still to be derived from a timestamp marker.
     *
     * A marker recording only how far a table was uploaded by capture time names a position the row
     * id cursor can be seeded from, so an upload continues from there rather than offering the table
     * from its start again. The translation holds for a table paged by row id, where capture order
     * and insertion order agree. A table paged by completion is ordered on an axis that marker says
     * nothing about, so its cursor opens at the start of that axis and the rows already held are
     * offered once more.
     *
     * @param columns         the table's column names
     * @param cursorId        the stored row id cursor
     * @param markerTimestamp the stored timestamp marker
     */
    public static boolean needsSeeding(String[] columns, long cursorId, long markerTimestamp) {
        return !pagesByCompletion(columns) && cursorId <= 0 && markerTimestamp > 0;
    }

    private static boolean contains(String[] columns, String name) {
        if (columns == null) return false;
        for (String column : columns) {
            if (name.equals(column)) return true;
        }
        return false;
    }
}
