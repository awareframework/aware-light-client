package com.aware.utils;

/**
 * Shared time-unit conversions for sensor FREQUENCY_* settings, kept in one place instead of
 * duplicated per sensor class since the underlying math is identical across all of them.
 */
public final class SensorTimeUnits {

    private SensorTimeUnits() {
    }

    /**
     * FREQUENCY_ACCELEROMETER, FREQUENCY_GRAVITY, FREQUENCY_GYROSCOPE, FREQUENCY_LIGHT,
     * FREQUENCY_LINEAR_ACCELEROMETER, FREQUENCY_MAGNETOMETER, FREQUENCY_BAROMETER,
     * FREQUENCY_PROXIMITY, FREQUENCY_ROTATION and FREQUENCY_TEMPERATURE are already stored in
     * microseconds -- SensorManager.registerListener()'s native sampling-period unit -- so this
     * is an explicit no-op, not a missing conversion.
     */
    public static int samplingPeriodUs(int frequencyMicroseconds) {
        return frequencyMicroseconds;
    }

    /**
     * FREQUENCY_LOCATION_GPS, FREQUENCY_LOCATION_NETWORK, FREQUENCY_BLUETOOTH, FREQUENCY_WIFI,
     * FREQUENCY_PROCESSOR and FREQUENCY_NETWORK_TRAFFIC are all stored in seconds, but the Android
     * APIs that consume them (LocationManager.requestLocationUpdates(), AlarmManager.setRepeating(),
     * Handler.postDelayed()) all want milliseconds.
     */
    public static long secondsToMillis(int frequencySeconds) {
        return frequencySeconds * 1000L;
    }

    /**
     * Some AlarmManager.setRepeating()-based sensors (currently Bluetooth) scan on an initial
     * delay of the configured frequency, but repeat at twice that frequency to cut battery/CPU
     * overhead on the recurring scan. Reuse this if a future sensor adopts the same pattern.
     */
    public static long doubleSecondsToMillis(int frequencySeconds) {
        return secondsToMillis(frequencySeconds) * 2;
    }

    /**
     * FREQUENCY_APPLICATIONS -- and any future setting feeding Scheduler.Schedule#setInterval()
     * -- is already stored in minutes, which is Scheduler's native unit, so this is an explicit
     * no-op, not a missing conversion.
     */
    public static long minutesAsIs(long frequencyMinutes) {
        return frequencyMinutes;
    }
}
