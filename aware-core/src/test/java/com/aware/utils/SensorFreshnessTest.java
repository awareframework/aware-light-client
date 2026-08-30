package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SensorFreshnessTest {

    @Test
    public void fastSensor_usesTwoMinuteFloor() {
        assertEquals(
                120_000L,
                SensorFreshness.windowMs("20000", 200000, SensorFreshness.Unit.MICROSECONDS));
    }

    @Test
    public void fiveMinuteSensor_usesThreeIntervals() {
        assertEquals(
                15L * 60L * 1000L,
                SensorFreshness.windowMs("300", 60, SensorFreshness.Unit.SECONDS));
    }

    @Test
    public void extremeInterval_isCappedAtOneDay() {
        assertEquals(
                SensorFreshness.MAX_WINDOW_MS,
                SensorFreshness.windowMs("1000000", 60, SensorFreshness.Unit.MINUTES));
    }

    @Test
    public void invalidValue_usesDefault() {
        assertEquals(
                3L * 60L * 1000L,
                SensorFreshness.windowMs("invalid", 60, SensorFreshness.Unit.SECONDS));
    }

    @Test
    public void freshness_includesDeadlineAndRejectsFutureOrMissingRows() {
        assertTrue(SensorFreshness.isFresh(1_000_000L, 880_000L, 120_000L));
        assertFalse(SensorFreshness.isFresh(1_000_001L, 880_000L, 120_000L));
        assertFalse(SensorFreshness.isFresh(1_000_000L, 0L, 120_000L));
        assertFalse(SensorFreshness.isFresh(1_000_000L, 1_000_001L, 120_000L));
    }
}
