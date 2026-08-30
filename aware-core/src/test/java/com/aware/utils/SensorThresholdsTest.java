package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aware.Aware_Preferences;

import org.junit.Test;

/**
 * Pins the unit and usable range of every sensitivity threshold, and the rejection of values past
 * that range.
 *
 * A deployed study config set threshold_accelerometer to 120 m/s² and threshold_magnetometer to
 * 1,000,000 µT. Those exceed anything the hardware produces, so every sample was filtered out and
 * the seven threshold-filtered physical sensors held four rows between them for the whole study,
 * while each reported itself as enabled. These tests are the guard against reintroducing that.
 */
public class SensorThresholdsTest {

    private static final String[] ALL = {
            Aware_Preferences.THRESHOLD_ACCELEROMETER,
            Aware_Preferences.THRESHOLD_LINEAR_ACCELEROMETER,
            Aware_Preferences.THRESHOLD_GRAVITY,
            Aware_Preferences.THRESHOLD_GYROSCOPE,
            Aware_Preferences.THRESHOLD_ROTATION,
            Aware_Preferences.THRESHOLD_MAGNETOMETER,
            Aware_Preferences.THRESHOLD_BAROMETER,
            Aware_Preferences.THRESHOLD_LIGHT,
            Aware_Preferences.THRESHOLD_TEMPERATURE,
            Aware_Preferences.THRESHOLD_PROXIMITY,
    };

    @Test
    public void everyThresholdSettingHasAUnitAndALimit() {
        for (String key : ALL) {
            SensorThresholds.Spec spec = SensorThresholds.of(key);
            assertNotNull(key + " has no spec", spec);
            assertFalse(key + " has no unit", spec.unit.isEmpty());
            assertTrue(key + " has a non-positive limit", spec.limit > 0);
            assertTrue(key + " has an odd axis count", spec.axes == 1 || spec.axes == 3);
        }
    }

    @Test
    public void nonThresholdSettingsHaveNoSpec() {
        assertNull(SensorThresholds.of(Aware_Preferences.FREQUENCY_ACCELEROMETER));
        assertNull(SensorThresholds.of("status_wifi"));
        assertNull(SensorThresholds.of(null));
        assertFalse(SensorThresholds.isThreshold("status_wifi"));
        assertTrue(SensorThresholds.isThreshold(Aware_Preferences.THRESHOLD_LIGHT));
    }

    @Test
    public void threeAxisSensorsAreTheMotionAndFieldOnes() {
        assertEquals(3, SensorThresholds.of(Aware_Preferences.THRESHOLD_ACCELEROMETER).axes);
        assertEquals(3, SensorThresholds.of(Aware_Preferences.THRESHOLD_GYROSCOPE).axes);
        assertEquals(3, SensorThresholds.of(Aware_Preferences.THRESHOLD_MAGNETOMETER).axes);
        assertEquals(1, SensorThresholds.of(Aware_Preferences.THRESHOLD_LIGHT).axes);
        assertEquals(1, SensorThresholds.of(Aware_Preferences.THRESHOLD_BAROMETER).axes);
    }

    @Test
    public void zeroIsAlwaysValidBecauseItDisablesFiltering() {
        for (String key : ALL) {
            assertTrue(key + " rejected 0", SensorThresholds.isWithinRange(key, 0));
        }
    }

    @Test
    public void negativeValuesAreRejected() {
        for (String key : ALL) {
            assertFalse(key + " accepted a negative", SensorThresholds.isWithinRange(key, -1));
        }
    }

    @Test
    public void aValueAtTheLimitIsStillAccepted() {
        for (String key : ALL) {
            double limit = SensorThresholds.of(key).limit;
            assertTrue(key + " rejected its own limit",
                    SensorThresholds.isWithinRange(key, limit));
            assertFalse(key + " accepted just past its limit",
                    SensorThresholds.isWithinRange(key, limit + 0.001));
        }
    }

    /** The values that were live in a deployed study config. */
    @Test
    public void theDeployedStudysThresholdsAreRejected() {
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_ACCELEROMETER, 120));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_LINEAR_ACCELEROMETER, 100));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_GRAVITY, 10));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_GYROSCOPE, 10));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_ROTATION, 10));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_MAGNETOMETER, 1000000));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_PROXIMITY, 10));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_BAROMETER, 10));
        assertFalse(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_TEMPERATURE, 100));
    }

    /**
     * Light is the exception. Illuminance really does swing by hundreds of lux, so the deployed
     * 100 was not impossible - only coarser than any tier worth recommending, since it discards
     * the whole evening and night-time range.
     */
    @Test
    public void theDeployedLightThresholdIsCoarseButNotRejected() {
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_LIGHT, 100));
    }

    @Test
    public void theRecommendedPresetsAreAllAccepted() {
        assertTrue(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_ACCELEROMETER, 0.05));
        assertTrue(SensorThresholds.isWithinRange(
                Aware_Preferences.THRESHOLD_ACCELEROMETER, 1.0));
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_GYROSCOPE, 0.01));
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_ROTATION, 0.005));
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_MAGNETOMETER, 10));
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_BAROMETER, 0.4));
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_LIGHT, 50));
        assertTrue(SensorThresholds.isWithinRange(Aware_Preferences.THRESHOLD_TEMPERATURE, 0.5));
    }

    @Test
    public void explainStatesTheUnitAndWhatTheValueDoes() {
        String text = SensorThresholds.explain(Aware_Preferences.THRESHOLD_ACCELEROMETER, 0.3);
        assertTrue(text, text.contains("0.3"));
        assertTrue(text, text.contains("m/s²"));
        assertTrue(text, text.contains("all three axes"));
    }

    @Test
    public void explainOmitsTheAxisRuleForSingleValueSensors() {
        String text = SensorThresholds.explain(Aware_Preferences.THRESHOLD_LIGHT, 10);
        assertTrue(text, text.contains("lux"));
        assertFalse(text, text.contains("all three axes"));
    }

    @Test
    public void explainCallsOutAnOutOfRangeValue() {
        String text = SensorThresholds.explain(Aware_Preferences.THRESHOLD_ACCELEROMETER, 120);
        assertTrue(text, text.contains("120"));
        assertTrue(text, text.contains("20"));
        assertTrue(text, text.contains("records nothing"));
    }

    @Test
    public void explainDescribesZeroAsNoFiltering() {
        String text = SensorThresholds.explain(Aware_Preferences.THRESHOLD_GRAVITY, 0);
        assertTrue(text, text.contains("every sample"));
    }

    @Test
    public void explainRejectsANegative() {
        assertEquals("Enter 0 or more.",
                SensorThresholds.explain(Aware_Preferences.THRESHOLD_GRAVITY, -0.5));
    }

    @Test
    public void explainIsEmptyForANonThresholdSetting() {
        assertEquals("", SensorThresholds.explain("status_wifi", 1));
    }
}
