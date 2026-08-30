package com.aware;

import static org.junit.Assert.assertEquals;

import com.aware.utils.SensorTimeUnits;

import org.junit.Test;

/**
 * Locks in the time unit that each user-configurable FREQUENCY_* setting is expressed in, and
 * the conversion (if any) each sensor applies before handing the value to the underlying Android
 * API. This was prompted by a real mismatch: Aware_Preferences.FREQUENCY_APPLICATIONS was
 * documented as "seconds (default = 30)" but Applications.java actually fed it straight into
 * Scheduler.Schedule#setInterval(long), which takes minutes, with a real default of 0 -- so the
 * doc comment described neither the unit nor the default the code used.
 *
 * Every sensor routes its "raw setting -> value passed to the Android API" step through
 * SensorTimeUnits, so it can be verified here without a real or mocked Android Context. The
 * conversions are identical across sensors, so they live in one shared utility -- reusable by
 * any future sensor rather than re-derived -- and are tested once rather than once per sensor
 * class. The reference table is:
 *
 * <pre>
 * Setting                          Unit         Conversion            Verified via
 * --------------------------------------------------------------------------------------------------
 * FREQUENCY_ACCELEROMETER          microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_GRAVITY                microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_GYROSCOPE              microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_LIGHT                  microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_LINEAR_ACCELEROMETER   microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_MAGNETOMETER           microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_BAROMETER              microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_PROXIMITY              microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_ROTATION               microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_TEMPERATURE            microseconds none (native unit)    SensorTimeUnits.samplingPeriodUs
 * FREQUENCY_LOCATION_GPS           seconds      * 1000 -> millis      SensorTimeUnits.secondsToMillis
 * FREQUENCY_LOCATION_NETWORK       seconds      * 1000 -> millis      SensorTimeUnits.secondsToMillis
 * FREQUENCY_BLUETOOTH (start)      seconds      * 1000 -> millis      SensorTimeUnits.secondsToMillis
 * FREQUENCY_BLUETOOTH (repeat)     seconds      * 2000 -> millis      SensorTimeUnits.doubleSecondsToMillis
 * FREQUENCY_WIFI                   seconds      * 1000 -> millis      SensorTimeUnits.secondsToMillis
 * FREQUENCY_NETWORK_TRAFFIC        seconds      * 1000 -> millis      SensorTimeUnits.secondsToMillis
 * FREQUENCY_PROCESSOR              seconds      * 1000 -> millis      SensorTimeUnits.secondsToMillis
 * FREQUENCY_APPLICATIONS           minutes      none (Scheduler unit) SensorTimeUnits.minutesAsIs
 * </pre>
 */
public class SensorFrequencyUnitsTest {

    // --- Microsecond sensors: SensorManager.registerListener()'s native sampling-period unit,
    // so the setting passes through unchanged. Covers the documented SENSOR_DELAY_* presets too.
    // Shared by Accelerometer, Gravity, Gyroscope, Light, LinearAccelerometer, Magnetometer,
    // Barometer, Proximity, Rotation and Temperature.

    @Test
    public void samplingPeriodUs_passesThroughUnchanged() {
        assertEquals(200000, SensorTimeUnits.samplingPeriodUs(200000)); // normal (default)
        assertEquals(0, SensorTimeUnits.samplingPeriodUs(0)); // fastest
        assertEquals(20000, SensorTimeUnits.samplingPeriodUs(20000)); // game
        assertEquals(60000, SensorTimeUnits.samplingPeriodUs(60000)); // UI
    }

    // --- Seconds-based polling sensors: setting is in seconds, Android API wants milliseconds.
    // Shared by Locations (GPS + network), Bluetooth (scan start), WiFi and Processor.

    @Test
    public void secondsToMillis_convertsEachSensorsDocumentedDefault() {
        assertEquals(180_000L, SensorTimeUnits.secondsToMillis(180)); // Locations GPS default
        assertEquals(300_000L, SensorTimeUnits.secondsToMillis(300)); // Locations network default
        assertEquals(60_000L, SensorTimeUnits.secondsToMillis(60)); // Bluetooth / WiFi default
        assertEquals(10_000L, SensorTimeUnits.secondsToMillis(10)); // Processor default
        assertEquals(30_000L, SensorTimeUnits.secondsToMillis(30)); // Network traffic default
    }

    @Test
    public void doubleSecondsToMillis_isTwiceTheFrequencyInMillis() {
        assertEquals(120_000L, SensorTimeUnits.doubleSecondsToMillis(60)); // Bluetooth's repeat interval, documented default
    }

    // --- Minutes-based scheduling: Scheduler.Schedule#setInterval() already takes minutes.

    @Test
    public void minutesAsIs_passesThroughUnchanged() {
        assertEquals(0L, SensorTimeUnits.minutesAsIs(0)); // Applications' real default: disabled
        assertEquals(30L, SensorTimeUnits.minutesAsIs(30));
    }
}
