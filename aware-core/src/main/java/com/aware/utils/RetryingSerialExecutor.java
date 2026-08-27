package com.aware.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes queued work in submission order on one background thread.
 *
 * <p>The queue is deliberately bounded. A failed item remains owned by the worker and is retried
 * before later items, so callers can apply backpressure without losing or reordering an accepted
 * batch.</p>
 */
public final class RetryingSerialExecutor<T> implements AutoCloseable {

    public interface Worker<T> {
        void execute(T item) throws Exception;
    }

    public interface FailureListener<T> {
        void onFailure(T item, Exception error, long retryDelayMs);
    }

    private final ArrayBlockingQueue<T> queue;
    private final Worker<T> worker;
    private final FailureListener<T> failureListener;
    private final long initialRetryDelayMs;
    private final long maximumRetryDelayMs;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Thread thread;

    public RetryingSerialExecutor(
            String threadName,
            int queueCapacity,
            long initialRetryDelayMs,
            long maximumRetryDelayMs,
            Worker<T> worker,
            FailureListener<T> failureListener
    ) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        if (initialRetryDelayMs < 0) throw new IllegalArgumentException("initialRetryDelayMs must not be negative");
        if (maximumRetryDelayMs < initialRetryDelayMs) {
            throw new IllegalArgumentException("maximumRetryDelayMs must be at least initialRetryDelayMs");
        }

        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = worker;
        this.failureListener = failureListener;
        this.initialRetryDelayMs = initialRetryDelayMs;
        this.maximumRetryDelayMs = maximumRetryDelayMs;
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runWorker();
            }
        }, threadName);
        this.thread.start();
    }

    /** Returns false when the bounded queue is full or shutdown has begun. */
    public synchronized boolean offer(T item) {
        return accepting.get() && queue.offer(item);
    }

    /** Stops accepting work and lets the worker drain all already accepted items. */
    @Override
    public synchronized void close() {
        if (accepting.compareAndSet(true, false)) {
            thread.interrupt();
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        thread.join(unit.toMillis(timeout));
        return !thread.isAlive();
    }

    private void runWorker() {
        T current = null;
        long retryDelayMs = initialRetryDelayMs;

        while (accepting.get() || current != null || !queue.isEmpty()) {
            if (current == null) {
                try {
                    current = accepting.get()
                            ? queue.poll(1, TimeUnit.SECONDS)
                            : queue.poll();
                } catch (InterruptedException ignored) {
                    continue;
                }
                if (current == null) continue;
            }

            try {
                worker.execute(current);
                current = null;
                retryDelayMs = initialRetryDelayMs;
            } catch (Exception error) {
                if (failureListener != null) {
                    failureListener.onFailure(current, error, retryDelayMs);
                }

                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ignored) {
                    // Shutdown interrupts the wait, but the accepted item is still retried.
                }

                if (retryDelayMs == 0) {
                    retryDelayMs = Math.min(1, maximumRetryDelayMs);
                } else {
                    retryDelayMs = Math.min(retryDelayMs * 2, maximumRetryDelayMs);
                }
            }
        }
    }
}
