package com.aware.utils;

import com.aware.Aware_Preferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The unit and the usable upper limit of every THRESHOLD_* setting.
 *
 * A threshold is a change filter, not a cutoff on the reading. A sensor stores a reading only when
 * it differs from the last reading it STORED by at least the threshold, in the sensor's own native
 * unit; on the three-axis sensors only when every axis is within it. Slow drift therefore still
 * accumulates until it crosses.
 *
 * {@link Spec#limit} is the largest value that leaves the sensor recording usefully: the biggest
 * change the sensor produces in normal use. Past it, readings essentially never differ by that
 * much, so every sample is filtered out and the sensor goes silent while still reporting itself as
 * enabled. A deployed study config carried threshold_accelerometer 120 and threshold_magnetometer
 * 1000000, and those sensors held four rows between them for the study's lifetime.
 *
 * The numbers match the presets offered by the study Configurator, so a value picked there and a
 * value picked on the phone mean the same thing.
 */
public final class SensorThresholds {

    /** The unit a threshold is expressed in, and the point past which it stops being useful. */
    public static final class Spec {
        public final String unit;
        public final double limit;
        public final int axes;

        Spec(String unit, double limit, int axes) {
            this.unit = unit;
            this.limit = limit;
            this.axes = axes;
        }
    }

    private static final Map<String, Spec> SPECS;

    static {
        Map<String, Spec> specs = new HashMap<>();
        // Motion: per-axis noise measured across five smartphones is 0.0042-0.0106 m/s², and
        // changes beyond ~20 m/s² are not what a phone in a pocket produces.
        specs.put(Aware_Preferences.THRESHOLD_ACCELEROMETER, new Spec("m/s²", 20, 3));
        specs.put(Aware_Preferences.THRESHOLD_LINEAR_ACCELEROMETER, new Spec("m/s²", 20, 3));
        // Gravity is an orientation signal: a single axis spans ±9.81.
        specs.put(Aware_Preferences.THRESHOLD_GRAVITY, new Spec("m/s²", 9.81, 3));
        // 5 rad/s is about 286°/s, past anything normal handling produces.
        specs.put(Aware_Preferences.THRESHOLD_GYROSCOPE, new Spec("rad/s", 5, 3));
        // Rotation vector components are unitless quaternion parts in -1…1.
        specs.put(Aware_Preferences.THRESHOLD_ROTATION, new Spec("quaternion units", 1, 3));
        // Earth's field is 23-65 µT; a strong local disturbance is tens, not hundreds.
        specs.put(Aware_Preferences.THRESHOLD_MAGNETOMETER, new Spec("µT", 100, 3));
        // 5 hPa is about 40 m of height, or a whole weather system passing.
        specs.put(Aware_Preferences.THRESHOLD_BAROMETER, new Spec("hPa", 5, 1));
        // Illuminance genuinely spans five orders of magnitude, so this limit is far looser than
        // the others - a coarse light threshold is defensible where a coarse motion one is not.
        specs.put(Aware_Preferences.THRESHOLD_LIGHT, new Spec("lux", 10000, 1));
        // Ambient temperature spans roughly 70 °C across the range a phone sees.
        specs.put(Aware_Preferences.THRESHOLD_TEMPERATURE, new Spec("°C", 10, 1));
        // Most proximity hardware reports two states, near ≈0 cm and far ≈5 cm, and only on
        // change, so there are no intermediate readings for a threshold to remove.
        specs.put(Aware_Preferences.THRESHOLD_PROXIMITY, new Spec("cm", 5, 1));
        SPECS = Collections.unmodifiableMap(specs);
    }

    private SensorThresholds() {
    }

    /** The spec for a THRESHOLD_* setting key, or null for any other setting. */
    public static Spec of(String settingKey) {
        return settingKey == null ? null : SPECS.get(settingKey);
    }

    public static boolean isThreshold(String settingKey) {
        return of(settingKey) != null;
    }

    /**
     * Whether a threshold still leaves the sensor recording. 0 disables filtering and is always
     * valid; a negative value never is. An unknown setting is not judged.
     */
    public static boolean isWithinRange(String settingKey, double value) {
        Spec spec = of(settingKey);
        if (spec == null) return true;
        return value >= 0 && value <= spec.limit;
    }

    /** What a threshold does at this value, for the dialog that accepts a typed-in one. */
    public static String explain(String settingKey, double value) {
        Spec spec = of(settingKey);
        if (spec == null) return "";
        if (value < 0) return "Enter 0 or more.";
        if (value == 0) return "0 stores every sample, with no filtering.";
        if (value > spec.limit) {
            return format(value) + " " + spec.unit + " is above " + format(spec.limit) + " "
                    + spec.unit + ", more than this sensor's readings change in normal use. At this"
                    + " value almost every sample is filtered out and the sensor records nothing.";
        }
        return "Stores a reading once it differs from the last stored one by "
                + format(value) + " " + spec.unit + "."
                + (spec.axes == 3
                        ? " A sample is dropped only when all three axes changed by less than that."
                        : "");
    }

    private static String format(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }
}
