package com.aware.utils;

/**
 * Bounds an upload batch by the size of its payload rather than by its row count alone.
 *
 * A row-count cap is payload-blind: the same limit covers an accelerometer row of roughly a hundred
 * bytes and a screenshot row carrying a base64-encoded image of several hundred kilobytes to over a
 * megabyte. At the row counts a modern phone is given (5,000–10,000), a screenshot backlog reaches
 * gigabytes per batch, which fails twice over — the phone materialises the whole batch as JSON in
 * its heap, and the merged INSERT statement passes the server's {@code max_allowed_packet}.
 *
 * That failure does not settle at "some batches fail". A batch that never lands is retried
 * unchanged, the sync marker never advances, local cleanup never runs, the table only grows, and the
 * next batch is larger still. Capping the bytes is what keeps a backlog draining.
 */
public final class SyncBatchBudget {

    private SyncBatchBudget() {
    }

    /**
     * Ceiling on the estimated payload of a single batch.
     *
     * Comfortably inside a 64 MB {@code max_allowed_packet} while leaving the driver room for the
     * statement text around the values, and small enough that the JSON the phone holds in its heap
     * to build the batch stays affordable on a low-memory device.
     */
    public static final long MAX_PAYLOAD_BYTES = 8L * 1024L * 1024L;

    /**
     * Bytes a column contributes beyond its value: the column name, the quoting around the value,
     * and the separators between columns.
     */
    public static final int COLUMN_OVERHEAD_BYTES = 8;

    /**
     * Bytes charged for a numeric value in place of measuring it, generously rounded up so the
     * estimate errs towards smaller batches. Avoids formatting every number to a string purely to
     * take its length, on the tables where rows are numerous and individually tiny.
     */
    public static final int NUMERIC_VALUE_BYTES = 24;

    /**
     * The estimated contribution of one column to a batch's payload.
     *
     * @param columnName  the column being written
     * @param valueLength length of the value's text form, or {@link #NUMERIC_VALUE_BYTES} for a number
     */
    public static long columnBytes(String columnName, int valueLength) {
        return columnName.length() + COLUMN_OVERHEAD_BYTES + valueLength;
    }

    /**
     * Whether a row must be left for the next batch because adding it would take this one past the
     * cap.
     *
     * A batch already holding nothing takes the row regardless of its size. That clause is what
     * keeps an oversized single row from wedging the table: a batch of zero rows reports nothing
     * uploaded, the sync marker never advances, and the same row is offered again on every sync
     * forever. Taking it means an unsendable row fails loudly against the server instead.
     *
     * @param rowsInBatch  rows already accepted into the batch
     * @param bytesInBatch estimated payload of those rows
     * @param rowBytes     estimated payload of the row being considered
     * @param capBytes     ceiling for this batch, normally {@link #MAX_PAYLOAD_BYTES}
     * @return true to hold the row back for a later batch
     */
    public static boolean holdForNextBatch(int rowsInBatch, long bytesInBatch, long rowBytes,
                                           long capBytes) {
        return rowsInBatch > 0 && bytesInBatch + rowBytes > capBytes;
    }
}
