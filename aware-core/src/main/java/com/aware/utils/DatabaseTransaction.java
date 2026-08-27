package com.aware.utils;

import android.database.sqlite.SQLiteDatabase;

/**
 * A transaction scope that always releases SQLite's transaction lock.
 *
 * Providers use try-with-resources around every explicit transaction. A return or exception from
 * any branch therefore reaches {@link #close()}, instead of relying on each branch to remember its
 * own {@code endTransaction()} call.
 */
public final class DatabaseTransaction implements AutoCloseable {

    interface Backend {
        void begin();

        void setSuccessful();

        void end();
    }

    private final Backend backend;
    private boolean closed;

    public static DatabaseTransaction begin(final SQLiteDatabase database) {
        if (database == null) {
            throw new IllegalStateException("Cannot begin a transaction without an open database");
        }
        return new DatabaseTransaction(new Backend() {
            @Override
            public void begin() {
                database.beginTransaction();
            }

            @Override
            public void setSuccessful() {
                database.setTransactionSuccessful();
            }

            @Override
            public void end() {
                database.endTransaction();
            }
        });
    }

    static DatabaseTransaction begin(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("backend cannot be null");
        return new DatabaseTransaction(backend);
    }

    private DatabaseTransaction(Backend backend) {
        this.backend = backend;
        backend.begin();
    }

    /**
     * Commits and closes at the call site where providers previously paired
     * setTransactionSuccessful() with endTransaction(). Closing immediately keeps change
     * notifications outside the transaction; close() remains a rollback fallback for every failure
     * path before this point.
     */
    public void commit() {
        if (closed) throw new IllegalStateException("Transaction is already closed");
        backend.setSuccessful();
        close();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        backend.end();
    }
}
