package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for the two decisions upload health makes on its own: when a delivery failure is worth
 * interrupting the participant for, and what the app tells them in the meantime.
 *
 * Both are pure, so they can be exercised without a device or a real clock. The recording
 * side needs a Context and is covered by the device checks in BETA_BLOCKERS.md instead.
 */
public class UploadHealthTest {

    private static final long HOUR = 60 * 60 * 1000L;
    private static final List<String> NONE = Collections.emptyList();
    private static final List<String> ONE_TABLE = Collections.singletonList("accelerometer");

    // --- When to notify ---

    @Test
    public void healthyDeliveryNeverNotifies() {
        assertFalse(UploadHealth.shouldNotify(0L, false));
    }

    @Test
    public void aFailingDeliveryNotifiesImmediately() {
        // No waiting period: a phone that has stopped reaching the study should say so on the first
        // refused batch, not after the participant has lost hours of delivery.
        assertTrue(UploadHealth.shouldNotify(1L, false));
        assertTrue(UploadHealth.shouldNotify(100 * HOUR, false));
    }

    @Test
    public void theSameOutageIsNotNotifiedTwice() {
        // Every table reports the same outage on every sync tick, and re-posting the same
        // notification id alerts again each time. Without this the participant is buzzed once per
        // table per minute — the spam this design exists to avoid.
        assertFalse(UploadHealth.shouldNotify(100 * HOUR, true));
    }

    @Test
    public void aNegativeOutageStartIsTreatedAsHealthy() {
        // Defensive: a corrupt or backwards-clock value must not read as an active outage.
        assertFalse(UploadHealth.shouldNotify(-1L, false));
    }

    // --- What the app shows ---

    @Test
    public void aNewEnrolmentSaysNothingHasBeenDeliveredYet() {
        // Distinct from "fallen behind": on a fresh join there is no gap to explain.
        assertEquals("Nothing delivered yet", UploadHealth.statusLine(null, NONE, 0));
    }

    @Test
    public void healthyDeliveryReportsHowFarItReached() {
        assertEquals("Delivered up to 5 minutes ago",
                UploadHealth.statusLine("5 minutes ago", NONE, 0));
    }

    @Test
    public void aFailingUploadSaysSoAndPromisesTheDataIsKept() {
        String line = UploadHealth.statusLine("2 days ago", ONE_TABLE, 480);
        assertTrue(line.contains("not delivering right now"));
        assertTrue(line.contains("480 records waiting"));
        // The reassurance matters as much as the warning: the participant should not conclude their
        // data is being lost, because it is not.
        assertTrue(line.contains("kept on the device"));
    }

    @Test
    public void aPendingCountOfOneReadsAsSingular() {
        assertTrue(UploadHealth.statusLine("1 hour ago", ONE_TABLE, 1).contains("1 record waiting"));
    }

    @Test
    public void anUnknownPendingCountIsOmittedRatherThanShownAsZero() {
        String line = UploadHealth.statusLine("1 hour ago", ONE_TABLE, 0);
        assertFalse("0 records waiting would misreport an unknown count", line.contains("0 record"));
        assertTrue(line.contains("not delivering right now"));
    }

    @Test
    public void aFailingUploadWithNoPriorDeliveryStillExplainsItself() {
        String line = UploadHealth.statusLine(null, ONE_TABLE, 12);
        assertTrue(line.startsWith("Nothing delivered yet"));
        assertTrue(line.contains("not delivering right now"));
    }

    // --- Per-table outage state ---
    //
    // These cover the behaviour that let a broken table report itself healthy. Roughly 30 sync
    // adapters run in parallel, each calling recordSuccess/recordFailure for its own table, so the
    // question is what one table's success does to another table's recorded outage.

    /**
     * The regression test for the bug. bluetooth fails, then accelerometer succeeds — which is
     * exactly what happened in the field for two hours. bluetooth must still be recorded as failing.
     */
    @Test
    public void oneTablesSuccessDoesNotClearAnotherTablesOutage() {
        Map<String, Long> outages = UploadHealth.withFailure(
                UploadHealth.parseOutages(""), "bluetooth", 1000L);
        assertTrue(outages.containsKey("bluetooth"));

        outages = UploadHealth.withSuccess(outages, "accelerometer");

        assertTrue("accelerometer's success erased bluetooth's outage",
                outages.containsKey("bluetooth"));
        assertEquals(1000L, (long) outages.get("bluetooth"));
    }

    @Test
    public void aTablesOwnSuccessClearsItsOwnOutage() {
        Map<String, Long> outages = UploadHealth.withFailure(
                UploadHealth.parseOutages(""), "bluetooth", 1000L);
        assertTrue(UploadHealth.withSuccess(outages, "bluetooth").isEmpty());
    }

    @Test
    public void severalTablesCanBeFailingAtOnce() {
        Map<String, Long> outages = UploadHealth.parseOutages("");
        outages = UploadHealth.withFailure(outages, "bluetooth", 1000L);
        outages = UploadHealth.withFailure(outages, "locations", 2000L);

        assertEquals(2, outages.size());
        assertEquals(1000L, UploadHealth.earliestOutage(outages));
    }

    @Test
    public void parallelFailureUpdatesRetainEveryTable() throws Exception {
        final int adapterCount = 30;
        AtomicReference<String> stored = new AtomicReference<>("");
        CountDownLatch ready = new CountDownLatch(adapterCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(adapterCount);

        for (int i = 0; i < adapterCount; i++) {
            final int adapter = i;
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    UploadHealth.withOutageStateLock(() -> {
                        Map<String, Long> outages = UploadHealth.parseOutages(stored.get());
                        Thread.yield();
                        stored.set(UploadHealth.formatOutages(UploadHealth.withFailure(
                                outages, "table_" + adapter, 1000L + adapter)));
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "upload-health-test-" + i).start();
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        Map<String, Long> outages = UploadHealth.parseOutages(stored.get());
        assertEquals(adapterCount, outages.size());
        for (int i = 0; i < adapterCount; i++) {
            assertTrue("parallel update lost table_" + i, outages.containsKey("table_" + i));
        }
    }

    @Test
    public void concurrentRecoveryDoesNotEraseAnotherTablesFailure() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>("recovering:1000");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread recovery = new Thread(() -> {
            await(start);
            UploadHealth.withOutageStateLock(() -> stored.set(UploadHealth.formatOutages(
                    UploadHealth.withSuccess(UploadHealth.parseOutages(stored.get()), "recovering"))));
            done.countDown();
        });
        Thread failure = new Thread(() -> {
            await(start);
            UploadHealth.withOutageStateLock(() -> stored.set(UploadHealth.formatOutages(
                    UploadHealth.withFailure(UploadHealth.parseOutages(stored.get()), "broken", 2000L))));
            done.countDown();
        });

        recovery.start();
        failure.start();
        start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));

        Map<String, Long> outages = UploadHealth.parseOutages(stored.get());
        assertEquals(Collections.singletonMap("broken", 2000L), outages);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A table that keeps failing must keep its original start time, not have it pushed forward. */
    @Test
    public void arepeatedFailureKeepsTheOriginalStartTime() {
        Map<String, Long> outages = UploadHealth.withFailure(
                UploadHealth.parseOutages(""), "bluetooth", 1000L);
        outages = UploadHealth.withFailure(outages, "bluetooth", 9999L);

        assertEquals(1000L, (long) outages.get("bluetooth"));
    }

    @Test
    public void noFailingTablesMeansNoOutageStart() {
        assertEquals(0L, UploadHealth.earliestOutage(UploadHealth.parseOutages("")));
    }

    @Test
    public void outagesSurviveASaveAndReload() {
        Map<String, Long> outages = UploadHealth.parseOutages("");
        outages = UploadHealth.withFailure(outages, "locations", 2000L);
        outages = UploadHealth.withFailure(outages, "bluetooth", 1000L);

        assertEquals(outages, UploadHealth.parseOutages(UploadHealth.formatOutages(outages)));
    }

    @Test
    public void aMalformedStoredValueIsSkippedRatherThanGuessed() {
        assertTrue(UploadHealth.parseOutages("garbage").isEmpty());
        assertTrue(UploadHealth.parseOutages("bluetooth:").isEmpty());
        assertTrue(UploadHealth.parseOutages(":1000").isEmpty());
        assertTrue(UploadHealth.parseOutages("bluetooth:notanumber").isEmpty());
        assertTrue(UploadHealth.parseOutages("bluetooth:0").isEmpty());
        assertTrue(UploadHealth.parseOutages(null).isEmpty());
        // A good entry alongside a bad one is still kept.
        assertEquals(1, UploadHealth.parseOutages("garbage,locations:2000").size());
    }

    // --- Naming the failing tables ---
    //
    // "not delivering" on its own reads as a whole-study outage. The common case is one sensor.

    @Test
    public void oneFailingTableIsNamedInTheSingular() {
        String line = UploadHealth.statusLine("2 minutes ago",
                Collections.singletonList("bluetooth"), 0);
        assertTrue(line, line.contains("bluetooth is not delivering"));
    }

    @Test
    public void severalFailingTablesAreListedInThePlural() {
        String line = UploadHealth.statusLine("2 minutes ago",
                Arrays.asList("bluetooth", "locations"), 0);
        assertTrue(line, line.contains("bluetooth and locations are not delivering"));
    }

    @Test
    public void threeFailingTablesReadAsAList() {
        assertEquals("a, b and c",
                UploadHealth.describeFailing(Arrays.asList("a", "b", "c")));
    }

    @Test
    public void noFailingTablesSaysNothingAboutDelivery() {
        String line = UploadHealth.statusLine("2 minutes ago", NONE, 0);
        assertFalse(line, line.contains("not delivering"));
        assertEquals("", UploadHealth.describeFailing(NONE));
        assertEquals("", UploadHealth.describeFailing(null));
    }

    @Test
    public void aNullFailingListIsTreatedAsHealthy() {
        assertFalse(UploadHealth.statusLine("2 minutes ago", null, 0).contains("not delivering"));
    }
}
