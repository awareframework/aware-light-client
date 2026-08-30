package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the payload budget that bounds an upload batch by bytes rather than by row count
 * alone.
 *
 * The row-count cap is payload-blind, so a screenshot backlog produced batches of gigabytes: too
 * large for the phone's heap to hold as JSON and past the server's max_allowed_packet. Such a batch
 * is retried unchanged forever — the sync marker never advances, local cleanup never runs, and the
 * next batch is larger still — so the properties pinned here are the ones that decide whether a
 * backlog drains or wedges.
 */
public class SyncBatchBudgetTest {

    private static final long CAP = 1000;

    @Test
    public void aRowFittingInsideTheBudgetIsTaken() {
        assertFalse(SyncBatchBudget.holdForNextBatch(5, 400, 100, CAP));
    }

    @Test
    public void aRowExactlyFillingTheBudgetIsTaken() {
        // Reaching the cap is fine; only passing it is not.
        assertFalse(SyncBatchBudget.holdForNextBatch(5, 900, 100, CAP));
    }

    @Test
    public void aRowPassingTheBudgetIsHeldBack() {
        assertTrue(SyncBatchBudget.holdForNextBatch(5, 901, 100, CAP));
    }

    @Test
    public void theFirstRowIsAlwaysTakenHoweverLarge() {
        // The wedge guard. A batch of zero rows reports nothing uploaded, so the marker never
        // advances and the same row is offered again on every sync, forever. Taking it means an
        // unsendable row fails against the server once instead of stalling the table for good.
        assertFalse(SyncBatchBudget.holdForNextBatch(0, 0, CAP * 1000, CAP));
    }

    @Test
    public void anEmptyBatchNeverHoldsBack() {
        assertFalse(SyncBatchBudget.holdForNextBatch(0, 0, 1, CAP));
        assertFalse(SyncBatchBudget.holdForNextBatch(0, 0, 0, CAP));
    }

    @Test
    public void columnBytesCountsTheNameTheValueAndTheOverhead() {
        assertEquals("ts".length() + SyncBatchBudget.COLUMN_OVERHEAD_BYTES + 12,
                SyncBatchBudget.columnBytes("ts", 12));
    }

    @Test
    public void columnBytesIsDominatedByALargeValue() {
        // A base64 screenshot is hundreds of thousands of characters; the column name is noise beside
        // it, and the estimate must not lose it.
        long imageBytes = 400_000;
        assertTrue(SyncBatchBudget.columnBytes("image_data", (int) imageBytes) >= imageBytes);
    }

    @Test
    public void aSensorTableStillGetsLargeRowBatches() {
        // An accelerometer row: timestamp, device_id and three double columns. Ten thousand of them
        // must stay comfortably inside the budget, or the byte cap would have quietly shrunk the
        // batches of the tables that were never the problem.
        long rowBytes = SyncBatchBudget.columnBytes("timestamp", SyncBatchBudget.NUMERIC_VALUE_BYTES)
                + SyncBatchBudget.columnBytes("device_id", 36)
                + SyncBatchBudget.columnBytes("double_values_0", SyncBatchBudget.NUMERIC_VALUE_BYTES)
                + SyncBatchBudget.columnBytes("double_values_1", SyncBatchBudget.NUMERIC_VALUE_BYTES)
                + SyncBatchBudget.columnBytes("double_values_2", SyncBatchBudget.NUMERIC_VALUE_BYTES)
                + SyncBatchBudget.columnBytes("accuracy", SyncBatchBudget.NUMERIC_VALUE_BYTES)
                + SyncBatchBudget.columnBytes("label", 0);

        assertFalse("10,000 accelerometer rows should not hit the payload cap",
                SyncBatchBudget.holdForNextBatch(9_999, rowBytes * 9_999, rowBytes,
                        SyncBatchBudget.MAX_PAYLOAD_BYTES));
    }

    @Test
    public void aScreenshotBacklogIsSplitIntoManyBatches() {
        // Walks the accumulation the way syncBatch does, over more screenshots than a single batch
        // can hold, and checks every batch stays inside the budget and every row is eventually
        // taken. This is the case that used to build one multi-gigabyte batch.
        long rowBytes = SyncBatchBudget.columnBytes("image_data", 500_000)
                + SyncBatchBudget.columnBytes("timestamp", SyncBatchBudget.NUMERIC_VALUE_BYTES)
                + SyncBatchBudget.columnBytes("device_id", 36);

        int pending = 200;
        int taken = 0;
        int batches = 0;
        while (taken < pending) {
            int rowsInBatch = 0;
            long bytesInBatch = 0;
            while (taken < pending && !SyncBatchBudget.holdForNextBatch(
                    rowsInBatch, bytesInBatch, rowBytes, SyncBatchBudget.MAX_PAYLOAD_BYTES)) {
                rowsInBatch++;
                bytesInBatch += rowBytes;
                taken++;
            }
            batches++;
            assertTrue("batch " + batches + " carried " + bytesInBatch + " bytes",
                    bytesInBatch <= SyncBatchBudget.MAX_PAYLOAD_BYTES);
            assertTrue("a batch must make progress", rowsInBatch > 0);
        }

        assertEquals("every pending row is eventually taken", pending, taken);
        assertTrue("a 200-screenshot backlog needs more than one batch", batches > 1);
    }

    @Test
    public void aSingleRowLargerThanTheCapDoesNotStall() {
        // 20 MB of base64 in one row: over the 8 MB budget, so it can only ever go alone.
        long rowBytes = 20L * 1024 * 1024;

        int rowsInBatch = 0;
        long bytesInBatch = 0;
        assertFalse(SyncBatchBudget.holdForNextBatch(rowsInBatch, bytesInBatch, rowBytes,
                SyncBatchBudget.MAX_PAYLOAD_BYTES));
        rowsInBatch++;
        bytesInBatch += rowBytes;
        // And it does not drag a second row along with it.
        assertTrue(SyncBatchBudget.holdForNextBatch(rowsInBatch, bytesInBatch, 1,
                SyncBatchBudget.MAX_PAYLOAD_BYTES));
    }
}
