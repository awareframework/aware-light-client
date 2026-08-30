package com.aware.utils;

import com.aware.providers.Aware_Provider.Aware_Device;

import java.util.Map;

/**
 * Decides whether a device's current hardware and OS facts differ from what the aware_device table
 * already records for it.
 *
 * aware_device holds one row per device, which the phone keeps current: {@code UNIQUE(device_id)}
 * allows a single row per device_id, and a change to the device's facts rewrites it with a fresh
 * timestamp. That timestamp is what carries the change to the server, where each rewrite arrives as
 * its own row — so release/sdk/build_id across two server rows read as a mid-study Android upgrade.
 * A spurious "changed" verdict therefore costs a server row that says nothing, which is why null and
 * empty are treated as equal below.
 *
 * Free of Android types and of any state, so it is unit-testable directly.
 */
public final class DeviceFacts {

    private DeviceFacts() {
    }

    /**
     * The aware_device columns that describe the device itself, and so decide whether the stored row
     * still reflects reality.
     *
     * Excluded are {@code _id} and {@code timestamp}, which move on their own terms, and
     * {@code device_id}, which identifies the row being compared.
     */
    public static final String[] COMPARED_COLUMNS = {
            Aware_Device.BOARD, Aware_Device.DEVICE, Aware_Device.BUILD_ID,
            Aware_Device.HARDWARE, Aware_Device.MANUFACTURER, Aware_Device.MODEL,
            Aware_Device.PRODUCT, Aware_Device.RELEASE, Aware_Device.SDK};

    /**
     * Compares a stored aware_device row against the device's current facts across
     * {@link #COMPARED_COLUMNS}.
     *
     * Null and empty count as the same value: a column the platform reports as null but SQLite
     * stores as empty text (or the reverse) would otherwise read as changed on every check and
     * rewrite the row on every service start.
     *
     * @param stored  the stored row for this device_id, or null when the device has none
     * @param current the device's facts as the platform reports them now
     * @return true when the stored row already says everything the current facts say
     */
    public static boolean unchanged(Map<String, String> stored, Map<String, String> current) {
        if (stored == null || current == null) return false;
        for (String column : COMPARED_COLUMNS) {
            String was = stored.get(column) == null ? "" : stored.get(column);
            String now = current.get(column) == null ? "" : current.get(column);
            if (!was.equals(now)) return false;
        }
        return true;
    }
}
