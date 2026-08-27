package com.aware.utils;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Bounded sensor sample buffer backed by one ordered, retrying database worker. */
public final class SensorDataBuffer implements AutoCloseable {

    public static final int BATCH_SIZE = 250;
    private static final int MAX_QUEUED_BATCHES = 8;
    private static final long INITIAL_RETRY_DELAY_MS = 1_000;
    private static final long MAXIMUM_RETRY_DELAY_MS = 60_000;
    private static final long BACKPRESSURE_LOG_INTERVAL_MS = 60_000;

    private final String tag;
    private final List<ContentValues> activeBatch = new ArrayList<>(BATCH_SIZE);
    private final RetryingSerialExecutor<ContentValues[]> persistence;
    private long lastBackpressureLog;

    public SensorDataBuffer(Context context, Uri contentUri, String broadcastAction, String tag) {
        Context applicationContext = context.getApplicationContext();
        this.tag = tag;
        this.persistence = new RetryingSerialExecutor<>(
                tag + "::database",
                MAX_QUEUED_BATCHES,
                INITIAL_RETRY_DELAY_MS,
                MAXIMUM_RETRY_DELAY_MS,
                new RetryingSerialExecutor.Worker<ContentValues[]>() {
                    @Override
                    public void execute(ContentValues[] values) {
                        applicationContext.getContentResolver().bulkInsert(contentUri, values);
                        applicationContext.sendBroadcast(new Intent(broadcastAction));
                    }
                },
                new RetryingSerialExecutor.FailureListener<ContentValues[]>() {
                    @Override
                    public void onFailure(ContentValues[] values, Exception error, long retryDelayMs) {
                        Log.e(
                                tag,
                                "Sensor database write failed; preserving " + values.length
                                        + " samples and retrying in " + retryDelayMs + " ms",
                                error
                        );
                    }
                }
        );
    }

    /**
     * Adds a copied sample. If persistence is saturated, the existing batch is retained and the
     * new sample is rejected, keeping memory usage bounded and the sensor callback responsive.
     */
    public synchronized boolean add(ContentValues sample) {
        if (activeBatch.size() >= BATCH_SIZE && !flush(false)) {
            logBackpressure();
            return false;
        }
        activeBatch.add(new ContentValues(sample));
        return true;
    }

    public synchronized int size() {
        return activeBatch.size();
    }

    public synchronized boolean isEmpty() {
        return activeBatch.isEmpty();
    }

    /**
     * Hands the current batch to the background worker. In DEBUG_DB_SLOW mode, discarding retains
     * the previous intentional behavior of suppressing database writes.
     */
    public synchronized boolean flush(boolean discardWrites) {
        if (activeBatch.isEmpty()) return true;
        if (discardWrites) {
            activeBatch.clear();
            return true;
        }

        ContentValues[] values = activeBatch.toArray(new ContentValues[0]);
        if (!persistence.offer(values)) return false;

        activeBatch.clear();
        return true;
    }

    public synchronized void close(boolean discardWrites) {
        if (!flush(discardWrites)) {
            Log.e(tag, "Database queue is full during shutdown; the final sensor batch could not be enqueued");
        }
        persistence.close();
    }

    @Override
    public void close() {
        close(false);
    }

    private void logBackpressure() {
        long now = System.currentTimeMillis();
        if (now >= lastBackpressureLog + BACKPRESSURE_LOG_INTERVAL_MS) {
            Log.w(tag, "Sensor database queue is full; preserving the buffered batch and rejecting new samples");
            lastBackpressureLog = now;
        }
    }
}
