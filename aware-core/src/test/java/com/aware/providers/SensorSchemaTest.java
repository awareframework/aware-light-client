package com.aware.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Documents which legacy label columns are redundant and which still carry sensor state.
 */
public class SensorSchemaTest {

    private static void assertHasNoLabel(String table, String schema) {
        assertFalse(table + " still declares the unused label column",
                schema.contains("label text"));
    }

    @Test
    public void physicalSensorSamplesDoNotCarryUnusedLabels() {
        assertHasNoLabel("accelerometer", Accelerometer_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("barometer", Barometer_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("gravity", Gravity_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("gyroscope", Gyroscope_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("light", Light_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("linear_accelerometer",
                Linear_Accelerometer_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("magnetometer", Magnetometer_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("proximity", Proximity_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("rotation", Rotation_Provider.TABLES_FIELDS[1]);
        assertHasNoLabel("temperature", Temperature_Provider.TABLES_FIELDS[1]);
    }

    @Test
    public void semanticEventLabelsRemainAvailable() {
        // These similarly named columns are not arbitrary annotations: Bluetooth groups a scan,
        // while Locations and Wi-Fi record disabled/out-of-bounds sensor state.
        assertTrue(Bluetooth_Provider.TABLES_FIELDS[1].contains("label text"));
        assertTrue(Locations_Provider.TABLES_FIELDS[0].contains("label text"));
        assertTrue(WiFi_Provider.TABLES_FIELDS[0].contains("label text"));
    }
}
