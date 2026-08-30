package com.aware.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aware.providers.Aware_Provider.Aware_Device;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for the comparison that decides whether the stored aware_device row still describes the
 * device.
 *
 * A spurious "changed" verdict costs more here than a missed one: the row is rewritten with a fresh
 * timestamp, and the sync carries every rewrite to the server as its own row, so noise here reads as
 * device history that never happened. The null/empty equivalence is pinned in particular — a column
 * the platform reports as null but SQLite stores as empty text would otherwise read as changed on
 * every single check.
 */
public class DeviceFactsTest {

    /** A snapshot with every compared column populated. */
    private static Map<String, String> snapshot() {
        Map<String, String> facts = new HashMap<>();
        for (String column : DeviceFacts.COMPARED_COLUMNS) {
            facts.put(column, "value-of-" + column);
        }
        return facts;
    }

    @Test
    public void identicalSnapshotsAreUnchanged() {
        assertTrue(DeviceFacts.unchanged(snapshot(), snapshot()));
    }

    @Test
    public void noStoredRowCountsAsChanged() {
        // A device with no row yet must get one.
        assertFalse(DeviceFacts.unchanged(null, snapshot()));
    }

    @Test
    public void everyComparedColumnCanTriggerARow() {
        // Guards against a column being listed in COMPARED_COLUMNS but read under the wrong key: a
        // fact that cannot trigger a row is a real device change that would go unrecorded.
        for (String column : DeviceFacts.COMPARED_COLUMNS) {
            Map<String, String> current = snapshot();
            current.put(column, "something-else");
            assertFalse("a change to " + column + " should warrant a new row",
                    DeviceFacts.unchanged(snapshot(), current));
        }
    }

    @Test
    public void anAndroidUpgradeWarrantsARow() {
        Map<String, String> stored = snapshot();
        stored.put(Aware_Device.RELEASE, "15");
        stored.put(Aware_Device.SDK, "35");
        Map<String, String> current = snapshot();
        current.put(Aware_Device.RELEASE, "16");
        current.put(Aware_Device.SDK, "36");

        assertFalse(DeviceFacts.unchanged(stored, current));
    }

    @Test
    public void nullAndEmptyAreTheSameValue() {
        Map<String, String> stored = snapshot();
        stored.put(Aware_Device.MANUFACTURER, null);
        Map<String, String> current = snapshot();
        current.put(Aware_Device.MANUFACTURER, "");

        assertTrue(DeviceFacts.unchanged(stored, current));
        assertTrue(DeviceFacts.unchanged(current, stored));
    }

    @Test
    public void bothNullIsUnchanged() {
        Map<String, String> stored = snapshot();
        stored.put(Aware_Device.HARDWARE, null);
        Map<String, String> current = snapshot();
        current.put(Aware_Device.HARDWARE, null);

        assertTrue(DeviceFacts.unchanged(stored, current));
    }

    @Test
    public void aMissingKeyAndAnEmptyValueAreTheSame() {
        // The stored map is built by reading columns off a Cursor; a column absent from the table
        // yields no entry at all rather than an empty one.
        Map<String, String> stored = snapshot();
        stored.remove(Aware_Device.PRODUCT);
        Map<String, String> current = snapshot();
        current.put(Aware_Device.PRODUCT, "");

        assertTrue(DeviceFacts.unchanged(stored, current));
    }

    @Test
    public void aColumnOutsideTheComparedSetDoesNotWarrantARewrite() {
        // Only COMPARED_COLUMNS describe the device. A value carried alongside them in the same row
        // — device_id, or anything added to the table later — has no say in the verdict.
        Map<String, String> stored = snapshot();
        stored.put(Aware_Device.DEVICE_ID, "device-a");
        Map<String, String> current = snapshot();
        current.put(Aware_Device.DEVICE_ID, "device-b");

        assertTrue(DeviceFacts.unchanged(stored, current));
    }

    @Test
    public void aNewTimestampAloneDoesNotWarrantARow() {
        Map<String, String> stored = snapshot();
        stored.put(Aware_Device.TIMESTAMP, "1000");
        Map<String, String> current = snapshot();
        current.put(Aware_Device.TIMESTAMP, "2000");

        assertTrue(DeviceFacts.unchanged(stored, current));
    }

    @Test
    public void deviceIdIsNotCompared() {
        // device_id is the lookup key: the caller has already scoped the stored row to it, so
        // comparing it could only ever mask a genuine fact change.
        Map<String, String> stored = snapshot();
        stored.put(Aware_Device.DEVICE_ID, "device-a");
        Map<String, String> current = snapshot();
        current.put(Aware_Device.DEVICE_ID, "device-b");

        assertTrue(DeviceFacts.unchanged(stored, current));
    }
}
