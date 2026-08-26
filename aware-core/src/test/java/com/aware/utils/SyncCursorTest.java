package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the position an upload resumes from and the rows the next batch takes.
 *
 * The properties pinned here are the ones that decide whether a table drains losslessly. A capture
 * timestamp is shared by rows a high-frequency sensor writes in the same millisecond and moves
 * backwards when a sensor flushes a buffer, so resuming on it leaves rows behind. Reading a batch by
 * offset shifts the window whenever a row is inserted mid-run. The row id has neither property, and
 * these tests hold the queries to it.
 */
public class SyncCursorTest {

    private static final String[] SENSOR = {"_id", "timestamp", "device_id", "double_values_0"};
    private static final String[] SESSION = {"_id", "timestamp", "device_id", "double_end_timestamp"};
    private static final String[] ESM = {"_id", "timestamp", "double_esm_user_answer_timestamp"};

    @Test
    public void aBatchResumesOnTheRowAfterTheCursor() {
        assertEquals("_id > 4200", SyncCursor.selection(SENSOR, 4200, null));
    }

    @Test
    public void theCursorIsNeverTheCaptureTimestamp() {
        // Rows a sensor writes inside one millisecond share a timestamp, so a timestamp predicate
        // hides every row but the last of that millisecond. The selection names the row id only.
        String selection = SyncCursor.selection(SENSOR, 4200, null);

        assertTrue(selection.startsWith(SyncCursor.ROW_ID + " >"));
        assertFalse(selection.contains("timestamp >"));
    }

    @Test
    public void anUnsyncedTableIsOfferedFromItsFirstRow() {
        // SQLite assigns row ids from 1, so a cursor of 0 admits the whole table.
        assertEquals("_id > 0", SyncCursor.selection(SENSOR, 0, null));
    }

    @Test
    public void theStudyClauseIsCarriedIntoTheSelection() {
        assertEquals("_id > 7 AND timestamp >= 1000",
                SyncCursor.selection(SENSOR, 7, " AND timestamp >= 1000"));
    }

    @Test
    public void aSessionTableOffersOnlyClosedSessions() {
        assertEquals("_id > 9 AND double_end_timestamp != 0",
                SyncCursor.selection(SESSION, 9, null));
    }

    @Test
    public void anEsmTableOffersOnlyAnsweredPrompts() {
        assertEquals("_id > 9 AND double_esm_user_answer_timestamp != 0",
                SyncCursor.selection(ESM, 9, null));
    }

    @Test
    public void aSensorTableGatesNothing() {
        assertNull(SyncCursor.completionColumn(SENSOR));
        assertEquals(SyncCursor.SESSION_END, SyncCursor.completionColumn(SESSION));
        assertEquals(SyncCursor.ESM_ANSWER, SyncCursor.completionColumn(ESM));
    }

    @Test
    public void aBatchIsReadInInsertionOrderWithoutAnOffset() {
        // An offset counts rows from the start of the result set, so a row inserted between two
        // batches of one run shifts every later window and a row is skipped. The cursor in the
        // selection already names where to resume, so the read carries a limit and no offset.
        String order = SyncCursor.order(1000);

        assertEquals("_id ASC LIMIT 1000", order);
        assertFalse(order.contains(","));
    }

    @Test
    public void theCursorMovesToTheLastAcknowledgedRow() {
        assertEquals(880, SyncCursor.advance(500, 880));
    }

    @Test
    public void theCursorNeverMovesBackwards() {
        // A batch reporting a lower id than the cursor already holds would offer acknowledged rows
        // again, which is what puts duplicate copies of one row on the server.
        assertEquals(500, SyncCursor.advance(500, 120));
        assertEquals(500, SyncCursor.advance(500, 0));
    }

    @Test
    public void aTimestampMarkerIsTranslatedIntoARowOnce() {
        assertTrue(SyncCursor.needsSeeding(0, 1787764838289L));
    }

    @Test
    public void aTableAlreadyHoldingACursorIsLeftAlone() {
        assertFalse(SyncCursor.needsSeeding(4200, 1787764838289L));
    }

    @Test
    public void aTableThatNeverSyncedNeedsNoSeeding() {
        assertFalse(SyncCursor.needsSeeding(0, 0));
    }
}
