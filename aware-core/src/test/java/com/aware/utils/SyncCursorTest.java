package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the position an upload resumes from and the rows the next batch takes.
 *
 * The properties pinned here are the ones that decide whether a table drains losslessly, and they
 * differ by table shape.
 *
 * A table whose rows are complete when stored is paged by row id. A capture timestamp is shared by
 * rows a high-frequency sensor writes in the same millisecond and moves backwards when a sensor
 * flushes a buffer, so resuming on it leaves rows behind; the row id has neither property.
 *
 * A table whose rows finish after they are stored is paged by the instant they finished, with the
 * row id separating rows that finished in the same millisecond. Resuming such a table on the row id
 * would strand every row that finished after the cursor passed it — a session that outlived the ones
 * started after it, or a prompt answered hours later.
 *
 * Reading either shape by offset shifts the window whenever a row is inserted mid-run, so neither
 * carries one.
 */
public class SyncCursorTest {

    private static final String[] SENSOR = {"_id", "timestamp", "device_id", "double_values_0"};
    private static final String[] SESSION = {"_id", "timestamp", "device_id", "double_end_timestamp"};
    private static final String[] ESM = {"_id", "timestamp", "double_esm_user_answer_timestamp"};

    // --- Shape routing -----------------------------------------------------

    @Test
    public void aSensorTableGatesNothingAndIsPagedByRowId() {
        assertNull(SyncCursor.completionColumn(SENSOR));
        assertFalse(SyncCursor.pagesByCompletion(SENSOR));
        assertEquals(SyncCursor.ROW_ID, SyncCursor.orderColumn(SENSOR));
    }

    @Test
    public void aSessionTableIsPagedByTheInstantASessionClosed() {
        assertEquals(SyncCursor.SESSION_END, SyncCursor.completionColumn(SESSION));
        assertTrue(SyncCursor.pagesByCompletion(SESSION));
        assertEquals(SyncCursor.SESSION_END, SyncCursor.orderColumn(SESSION));
    }

    @Test
    public void anEsmTableIsPagedByTheInstantAPromptWasAnswered() {
        assertEquals(SyncCursor.ESM_ANSWER, SyncCursor.completionColumn(ESM));
        assertTrue(SyncCursor.pagesByCompletion(ESM));
        assertEquals(SyncCursor.ESM_ANSWER, SyncCursor.orderColumn(ESM));
    }

    // --- Tables paged by row id -------------------------------------------

    @Test
    public void aBatchResumesOnTheRowAfterTheCursor() {
        assertEquals("_id > 4200", SyncCursor.selection(SENSOR, 0, 4200, null));
    }

    @Test
    public void theCursorIsNeverTheCaptureTimestamp() {
        // Rows a sensor writes inside one millisecond share a timestamp, so a timestamp predicate
        // hides every row but the last of that millisecond. The selection names the row id only.
        String selection = SyncCursor.selection(SENSOR, 1787764838289L, 4200, null);

        assertTrue(selection.startsWith(SyncCursor.ROW_ID + " >"));
        assertFalse(selection.contains("timestamp >"));
    }

    @Test
    public void anUnsyncedTableIsOfferedFromItsFirstRow() {
        // SQLite assigns row ids from 1, so a cursor of 0 admits the whole table.
        assertEquals("_id > 0", SyncCursor.selection(SENSOR, 0, 0, null));
    }

    @Test
    public void theStudyClauseIsCarriedIntoTheSelection() {
        assertEquals("_id > 7 AND timestamp >= 1000",
                SyncCursor.selection(SENSOR, 0, 7, " AND timestamp >= 1000"));
    }

    @Test
    public void aBatchIsReadInInsertionOrderWithoutAnOffset() {
        // An offset counts rows from the start of the result set, so a row inserted between two
        // batches of one run shifts every later window and a row is skipped. The cursor in the
        // selection already names where to resume, so the read carries a limit and no offset.
        String order = SyncCursor.order(SENSOR, 1000);

        assertEquals("_id ASC LIMIT 1000", order);
        assertFalse(order.contains(","));
    }

    // --- Tables paged by completion ---------------------------------------

    @Test
    public void aSessionTableResumesOnTheInstantItReachedWithARowIdTiebreak() {
        assertEquals("double_end_timestamp != 0 AND (double_end_timestamp > 100"
                        + " OR (double_end_timestamp = 100 AND _id > 5))",
                SyncCursor.selection(SESSION, 100, 5, null));
    }

    @Test
    public void anEsmTableResumesOnTheAnswerInstantItReached() {
        assertEquals("double_esm_user_answer_timestamp != 0"
                        + " AND (double_esm_user_answer_timestamp > 100"
                        + " OR (double_esm_user_answer_timestamp = 100 AND _id > 5))",
                SyncCursor.selection(ESM, 100, 5, null));
    }

    @Test
    public void aSessionFinishingAfterTheCursorIsStillOffered() {
        // The case a row-id cursor strands: a session stored at row 2 that outlived the sessions
        // stored after it, so it closes once the cursor already stands on row 5. It is admitted on
        // the completion axis, where it lies past the cursor, and its lower row id does not exclude
        // it — the row id appears only inside the equal-instant tiebreak.
        String selection = SyncCursor.selection(SESSION, 100, 5, null);

        assertTrue(selection.contains("double_end_timestamp > 100"));
        assertFalse(selection.startsWith("_id >"));
        assertEquals("_id > 5))", selection.substring(selection.length() - 9));
    }

    @Test
    public void anUnfinishedRowIsNeverOffered() {
        assertTrue(SyncCursor.selection(SESSION, 100, 5, null)
                .startsWith("double_end_timestamp != 0"));
        assertTrue(SyncCursor.selection(ESM, 100, 5, null)
                .startsWith("double_esm_user_answer_timestamp != 0"));
    }

    @Test
    public void aCompletionTableIsReadInCompletionOrderWithARowIdTiebreak() {
        assertEquals("double_end_timestamp ASC, _id ASC LIMIT 500",
                SyncCursor.order(SESSION, 500));
        assertEquals("double_esm_user_answer_timestamp ASC, _id ASC LIMIT 500",
                SyncCursor.order(ESM, 500));
    }

    @Test
    public void theStudyClauseIsCarriedIntoACompletionSelection() {
        assertTrue(SyncCursor.selection(SESSION, 100, 5, " AND timestamp >= 1000")
                .endsWith(" AND timestamp >= 1000"));
    }

    // --- Seeding from a timestamp marker ----------------------------------

    @Test
    public void aTimestampMarkerIsTranslatedIntoARowOnce() {
        assertTrue(SyncCursor.needsSeeding(SENSOR, 0, 1787764838289L));
    }

    @Test
    public void aTableAlreadyHoldingACursorIsLeftAlone() {
        assertFalse(SyncCursor.needsSeeding(SENSOR, 4200, 1787764838289L));
    }

    @Test
    public void aTableThatNeverSyncedNeedsNoSeeding() {
        assertFalse(SyncCursor.needsSeeding(SENSOR, 0, 0));
    }

    @Test
    public void aCompletionTableIsNotSeededFromACaptureTimestamp() {
        // Capture order and completion order are different axes, so a marker recorded on the first
        // names no position on the second. Such a table opens at the start of the completion axis.
        assertFalse(SyncCursor.needsSeeding(SESSION, 0, 1787764838289L));
        assertFalse(SyncCursor.needsSeeding(ESM, 0, 1787764838289L));
    }
}
