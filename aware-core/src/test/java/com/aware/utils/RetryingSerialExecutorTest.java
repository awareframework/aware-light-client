package com.aware.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetryingSerialExecutorTest {

    @Test
    public void retriesFailedItemBeforeProcessingLaterItems() throws Exception {
        List<Integer> completed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger firstAttempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        RetryingSerialExecutor<Integer> executor = new RetryingSerialExecutor<>(
                "retry-order-test", 2, 1, 2,
                value -> {
                    if (value == 1 && firstAttempts.getAndIncrement() == 0) {
                        throw new Exception("temporary failure");
                    }
                    completed.add(value);
                    done.countDown();
                },
                null
        );

        assertTrue(executor.offer(1));
        assertTrue(executor.offer(2));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        executor.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        assertEquals(Arrays.asList(1, 2), completed);
        assertEquals(2, firstAttempts.get());
    }

    @Test
    public void rejectsNewWorkAfterShutdown() throws Exception {
        RetryingSerialExecutor<Integer> executor = new RetryingSerialExecutor<>(
                "shutdown-test", 1, 1, 2, value -> { }, null
        );

        executor.close();

        assertFalse(executor.offer(1));
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void rejectsWorkBeyondBoundedCapacityWithoutBlockingCaller() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        RetryingSerialExecutor<Integer> executor = new RetryingSerialExecutor<>(
                "capacity-test", 1, 1, 2,
                value -> {
                    workerStarted.countDown();
                    releaseWorker.await(2, TimeUnit.SECONDS);
                },
                null
        );

        assertTrue(executor.offer(1));
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
        assertTrue(executor.offer(2));
        assertFalse(executor.offer(3));

        releaseWorker.countDown();
        executor.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
}
