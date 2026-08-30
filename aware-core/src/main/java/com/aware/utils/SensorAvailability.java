package com.aware.utils;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;

import com.aware.Aware_Preferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for "does this device physically have the sensor hardware a given
 * status_* setting depends on". Lives in aware-core (not aware-phone) so background code — the
 * config-sync drift check in {@link StudyUtils}, and potentially the compliance/diagnostics
 * scheduler — can ask this question without depending on the UI module.
 *
 * aware-phone's SensorCollection has its own categoryKey-keyed registry for the same underlying
 * fact (used for participant-facing "why isn't this collecting" messages, keyed by UI preference
 * key rather than the raw status_* setting string) and delegates its own hardware check to
 * {@link #hasHardware(Context, int)} here rather than calling SensorManager directly a second
 * time — this class is the one place that talks to SensorManager for hardware presence.
 */
public final class SensorAvailability {

    private SensorAvailability() {}

    /**
     * status_* setting -> Android Sensor.TYPE_* constant, for every sensor that depends on
     * physical hardware. A setting absent from this map isn't hardware-gated at all (e.g. it's
     * permission-gated, or always available), so {@link #isHardwareAvailable} treats it as
     * available by definition.
     */
    private static final Map<String, Integer> HARDWARE_BACKED = new HashMap<>();

    static {
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_LINEAR_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_SIGNIFICANT_MOTION, Sensor.TYPE_ACCELEROMETER);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_BAROMETER, Sensor.TYPE_PRESSURE);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_GRAVITY, Sensor.TYPE_GRAVITY);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_GYROSCOPE, Sensor.TYPE_GYROSCOPE);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_LIGHT, Sensor.TYPE_LIGHT);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_MAGNETOMETER, Sensor.TYPE_MAGNETIC_FIELD);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_PROXIMITY, Sensor.TYPE_PROXIMITY);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_ROTATION, Sensor.TYPE_ROTATION_VECTOR);
        HARDWARE_BACKED.put(Aware_Preferences.STATUS_TEMPERATURE, Sensor.TYPE_AMBIENT_TEMPERATURE);
    }

    /**
     * True if {@code statusSetting} isn't hardware-gated (nothing to check), or the device has the
     * hardware it needs. False only when the setting requires specific sensor hardware and this
     * device doesn't have it — the one case where no amount of settings-reapplying can ever make
     * the sensor start collecting.
     */
    public static boolean isHardwareAvailable(Context context, String statusSetting) {
        if (!isPlatformSupported(statusSetting, Build.VERSION.SDK_INT)) return false;
        Integer sensorType = HARDWARE_BACKED.get(statusSetting);
        if (sensorType == null) return true; // not hardware-gated at all
        return hasHardware(context, sensorType);
    }

    /**
     * Some legacy AWARE sensors do not depend on SensorManager hardware but are nevertheless
     * unavailable on newer Android releases. Processor reads /proc/stat, which Android blocks from
     * Nougat onward; treating it as available creates a feedback loop where the config turns it on,
     * the service turns itself off, and config reconciliation turns it on again.
     */
    static boolean isPlatformSupported(String statusSetting, int sdkInt) {
        return !Aware_Preferences.STATUS_PROCESSOR.equals(statusSetting)
                || sdkInt < Build.VERSION_CODES.N;
    }

    /** True if {@code statusSetting} depends on specific physical sensor hardware at all. */
    static boolean isHardwareBacked(String statusSetting) {
        return HARDWARE_BACKED.containsKey(statusSetting);
    }

    /** Raw hardware-presence check — the one place in the codebase that should call this. */
    public static boolean hasHardware(Context context, int sensorType) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(sensorType) != null;
    }
}
