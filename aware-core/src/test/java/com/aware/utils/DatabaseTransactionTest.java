package com.aware.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DatabaseTransactionTest {

    private static class FakeBackend implements DatabaseTransaction.Backend {
        int begins;
        int successes;
        int ends;

        @Override
        public void begin() {
            begins++;
        }

        @Override
        public void setSuccessful() {
            successes++;
        }

        @Override
        public void end() {
            ends++;
        }
    }

    @Test
    public void successfulScopeCommitsAndEndsOnce() {
        FakeBackend backend = new FakeBackend();

        try (DatabaseTransaction transaction = DatabaseTransaction.begin(backend)) {
            transaction.commit();
            assertEquals(1, backend.ends);
        }

        assertEquals(1, backend.begins);
        assertEquals(1, backend.successes);
        assertEquals(1, backend.ends);
    }

    @Test
    public void exceptionStillEndsWithoutCommitting() {
        FakeBackend backend = new FakeBackend();

        try {
            try (DatabaseTransaction ignored = DatabaseTransaction.begin(backend)) {
                throw new IllegalStateException("write failed");
            }
        } catch (IllegalStateException expected) {
            // The caller receives the original failure after close() releases the lock.
        }

        assertEquals(1, backend.begins);
        assertEquals(0, backend.successes);
        assertEquals(1, backend.ends);
    }

    @Test
    public void commitFailureStillEnds() {
        FakeBackend backend = new FakeBackend() {
            @Override
            public void setSuccessful() {
                super.setSuccessful();
                throw new IllegalStateException("commit failed");
            }
        };

        try {
            try (DatabaseTransaction transaction = DatabaseTransaction.begin(backend)) {
                transaction.commit();
            }
        } catch (IllegalStateException expected) {
            // close() still rolls the transaction out of SQLite's active state.
        }

        assertEquals(1, backend.successes);
        assertEquals(1, backend.ends);
    }

    @Test
    public void closeIsIdempotent() {
        FakeBackend backend = new FakeBackend();
        DatabaseTransaction transaction = DatabaseTransaction.begin(backend);

        transaction.close();
        transaction.close();

        assertEquals(1, backend.ends);
    }
}
