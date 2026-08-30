package com.aware.utils;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.providers.Aware_Provider;

import org.json.JSONArray;
import org.json.JSONObject;

import android.database.Cursor;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes one "sensor_status" line per study-enabled sensor into aware_log — a table that already
 * syncs to the researcher's database via AwareSyncAdapter, so this needs no new server-side schema.
 * Today a researcher can see *that* a sensor has no rows on a participant's device, but not *why*
 * (no hardware, missing permission, accessibility off, location services off) — that reason only
 * ever existed on-device, in aware-phone's SensorCollection, which the participant sees but the
 * researcher never does. This makes the same reason visible to the researcher.
 *
 * Deliberately duplicates a slimmed-down version of SensorCollection's gating registry (permissions
 * / accessibility / location-services only, keyed by the status_* setting name rather than
 * SensorCollection's UI categoryKey) because aware-core cannot depend on aware-phone. Hardware
 * gating specifically is NOT duplicated — this and SensorCollection both delegate to
 * {@link SensorAvailability}, so there's exactly one place that decides "does this device have the
 * hardware", even though there are still two places that decide "does this sensor need a runtime
 * permission" (this class, and SensorCollection's own registry, for its participant-facing UI).
 *
 * For regularly sampled sensors this also queries the latest local provider timestamp and applies
 * the same frequency-derived freshness policy as the participant UI. Event-driven sensors are kept
 * separate: a quiet period is normal for them, so they report waiting_for_event rather than delayed.
 * Each periodic record therefore gives the researcher both prerequisite failures and silent/stale
 * collection failures without requiring a new server-side table.
 */
public final class SensorDiagnostics {

    private SensorDiagnostics() {}

    /** Gating requirements for one status_* setting, beyond hardware (see SensorAvailability). */
    private static final class Gate {
        final String[] permissions;
        final boolean needsAccessibility;
        final boolean needsLocationServices;

        Gate(String[] permissions, boolean needsAccessibility, boolean needsLocationServices) {
            this.permissions = permissions;
            this.needsAccessibility = needsAccessibility;
            this.needsLocationServices = needsLocationServices;
        }
    }

    private static final String[] NONE = new String[0];
    private static final Map<String, Gate> GATES = new HashMap<>();
    private static final Map<String, Source> SAMPLED = new HashMap<>();

    private static final class Source {
        final String authoritySuffix;
        final String table;
        final String frequencySetting;
        final long defaultFrequency;
        final SensorFreshness.Unit unit;

        Source(
                String authoritySuffix,
                String table,
                String frequencySetting,
                long defaultFrequency,
                SensorFreshness.Unit unit) {
            this.authoritySuffix = authoritySuffix;
            this.table = table;
            this.frequencySetting = frequencySetting;
            this.defaultFrequency = defaultFrequency;
            this.unit = unit;
        }
    }

    static {
        GATES.put(
            Aware_Preferences.STATUS_LOCATION_GPS,
            new Gate(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_LOCATION_NETWORK,
            new Gate(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                false,
                false
            )
        );
        // WiFi scanning needs the OS-level Location toggle on, in addition to the permission below —
        // see SensorCollection's identical note on WifiManager.startScan().
        GATES.put(
            Aware_Preferences.STATUS_WIFI,
            new Gate(
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                false,
                true
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_BLUETOOTH,
            new Gate(
                // API 31+ needs BLUETOOTH_SCAN/CONNECT (referenced by string — added after this
                // module's compile SDK); older versions gate Bluetooth scanning on location.
                Build.VERSION.SDK_INT >= 31
                    ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}
                    : new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_TELEPHONY,
            new Gate(
                new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_COMMUNICATION_EVENTS,
            new Gate(
                new String[]{
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_SMS
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_CALLS,
            new Gate(
                new String[]{
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_MESSAGES,
            new Gate(
                new String[]{
                    Manifest.permission.READ_SMS
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_APPLICATIONS,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_NOTIFICATIONS,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_CRASHES,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_KEYBOARD,
            new Gate(
                NONE, 
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_SCREENSHOT,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_PLUGIN_AMBIENT_NOISE,
            new Gate(
                new String[]{Manifest.permission.RECORD_AUDIO},
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_PLUGIN_OPENWEATHER,
            new Gate(
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                false,
                false
            )
        );

        sampled(Aware_Preferences.STATUS_ACCELEROMETER, ".provider.accelerometer",
                "accelerometer", Aware_Preferences.FREQUENCY_ACCELEROMETER,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_LINEAR_ACCELEROMETER, ".provider.accelerometer.linear",
                "linear_accelerometer", Aware_Preferences.FREQUENCY_LINEAR_ACCELEROMETER,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_BAROMETER, ".provider.barometer",
                "barometer", Aware_Preferences.FREQUENCY_BAROMETER,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_GRAVITY, ".provider.gravity",
                "gravity", Aware_Preferences.FREQUENCY_GRAVITY,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_GYROSCOPE, ".provider.gyroscope",
                "gyroscope", Aware_Preferences.FREQUENCY_GYROSCOPE,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_LIGHT, ".provider.light",
                "light", Aware_Preferences.FREQUENCY_LIGHT,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_MAGNETOMETER, ".provider.magnetometer",
                "magnetometer", Aware_Preferences.FREQUENCY_MAGNETOMETER,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_PROXIMITY, ".provider.proximity",
                "proximity", Aware_Preferences.FREQUENCY_PROXIMITY,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_ROTATION, ".provider.rotation",
                "rotation", Aware_Preferences.FREQUENCY_ROTATION,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_TEMPERATURE, ".provider.temperature",
                "temperature", Aware_Preferences.FREQUENCY_TEMPERATURE,
                200000, SensorFreshness.Unit.MICROSECONDS);
        sampled(Aware_Preferences.STATUS_BLUETOOTH, ".provider.bluetooth",
                "bluetooth", Aware_Preferences.FREQUENCY_BLUETOOTH,
                60, SensorFreshness.Unit.SECONDS);
        sampled(Aware_Preferences.STATUS_PROCESSOR, ".provider.processor",
                "processor", Aware_Preferences.FREQUENCY_PROCESSOR,
                10, SensorFreshness.Unit.SECONDS);
        sampled(Aware_Preferences.STATUS_WIFI, ".provider.wifi",
                "wifi", Aware_Preferences.FREQUENCY_WIFI,
                60, SensorFreshness.Unit.SECONDS);
        sampled(Aware_Preferences.STATUS_NETWORK_TRAFFIC, ".provider.traffic",
                "network_traffic", Aware_Preferences.FREQUENCY_NETWORK_TRAFFIC,
                30, SensorFreshness.Unit.SECONDS);
        sampled(Aware_Preferences.STATUS_LOCATION_GPS, ".provider.locations",
                "locations", Aware_Preferences.FREQUENCY_LOCATION_GPS,
                180, SensorFreshness.Unit.SECONDS);
        sampled(Aware_Preferences.STATUS_LOCATION_NETWORK, ".provider.locations",
                "locations", Aware_Preferences.FREQUENCY_LOCATION_NETWORK,
                300, SensorFreshness.Unit.SECONDS);
        sampled(Aware_Preferences.STATUS_SCREENSHOT, ".provider.screenshot",
                "screenshot", Aware_Preferences.CAPTURE_TIME_INTERVAL,
                60000, SensorFreshness.Unit.MILLISECONDS);
    }

    private static void sampled(
            String statusSetting,
            String authoritySuffix,
            String table,
            String frequencySetting,
            long defaultFrequency,
            SensorFreshness.Unit unit) {
        SAMPLED.put(statusSetting, new Source(
                authoritySuffix, table, frequencySetting, defaultFrequency, unit));
    }

    /**
     * True if a status_* setting needs an explicit participant grant — a runtime permission or the
     * Accessibility Service — i.e. it's a sensor consent must cover before it may collect. Base
     * sensors (no gate, or a gate needing only the Location-services toggle) return false: they need
     * no per-sensor agreement. Lets aware-core (e.g. the config sync) decide which newly-added
     * sensors to hold off until consent, without depending on aware-phone's SensorCollection.
     */
    public static boolean requiresConsent(String statusSetting) {
        Gate gate = GATES.get(statusSetting);
        return gate != null && (gate.permissions.length > 0 || gate.needsAccessibility);
    }

    /**
     * Pure reason computation given already-resolved booleans — split out from
     * {@link #computeReason(Context, String, boolean)} so it's directly unit-testable without a
     * Context, same reasoning as StudyUtils.driftSignature()'s split from liveDriftSignature().
     *
     * @param statusSetting          the status_* setting, used only to look up its Gate
     * @param hardwareAvailable      result of SensorAvailability.isHardwareAvailable
     * @param missingPermission      the first ungranted permission this sensor needs, or null
     * @param accessibilityEnabled   whether AWARE's Accessibility Service is currently on
     * @param locationServicesEnabled whether the OS-level Location toggle is currently on
     * @return "" if nothing is blocking this sensor, otherwise a short human-readable reason
     */
    static String reasonGivenState(
        String statusSetting,
        boolean hardwareAvailable,
        String missingPermission,
        boolean accessibilityEnabled,
        boolean locationServicesEnabled
    ) {
        if (!hardwareAvailable) return "No such sensor hardware on this device";

        Gate gate = GATES.get(statusSetting);
        if (gate == null) return ""; // not gated by anything besides hardware, already checked above

        if (gate.needsAccessibility && !accessibilityEnabled) return "Accessibility service is off";
        if (gate.needsLocationServices && !locationServicesEnabled) return "Location services are off";
        if (missingPermission != null) return "Missing permission: " + shortPermission(missingPermission);
        return "";
    }

    /** Context-backed wrapper of {@link #reasonGivenState} — resolves the live state and delegates. */
    public static String computeReason(
        Context context,
        String statusSetting,
        boolean accessibilityEnabled
    ) {
        boolean hardwareAvailable = SensorAvailability.isHardwareAvailable(context, statusSetting);
        Gate gate = GATES.get(statusSetting);
        String missingPermission = gate == null ? null : firstMissingPermission(context, gate.permissions);
        boolean locationServicesEnabled = gate != null && gate.needsLocationServices && isLocationServicesEnabled(context);
        return reasonGivenState(statusSetting, hardwareAvailable, missingPermission, accessibilityEnabled, locationServicesEnabled);
    }

    /**
     * Writes one "sensor_status" line to aware_log for every status_* setting in {@code sensors}
     * that the study enabled (value == true). Sampled sensors are evaluated against three configured
     * intervals (with a two-minute floor and one-day cap); event-driven sensors report
     * waiting_for_event and are not treated as delayed. Format is a fixed, parseable key=value line so a
     * researcher can filter aware_log with e.g. "WHERE log_message LIKE 'sensor_status%'":
     * <pre>sensor_status ts=&lt;ms&gt; sensor=&lt;key&gt; state=&lt;state&gt; device_enabled=&lt;bool&gt;
     * last_data_ms=&lt;ms&gt; expected_within_ms=&lt;ms&gt; excluded=&lt;bool&gt; reason="&lt;text&gt;"</pre>
     */
    public static void logSensorStatus(Context context, JSONArray sensors) {
        if (sensors == null) return;
        boolean accessibilityEnabled = isAccessibilityServiceEnabled(context);
        long now = System.currentTimeMillis();

        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;

            String setting = sensor.optString("setting", "");
            if (!setting.startsWith("status_") || !sensor.optBoolean("value", false)) continue;

            String blocker = computeReason(context, setting, accessibilityEnabled);
            boolean deviceEnabled = "true".equals(Aware.getSetting(context, setting));
            Source source = SAMPLED.get(setting);
            long lastData = source == null ? 0 : latestTimestamp(context, source);
            long freshnessWindow = source == null ? 0 : SensorFreshness.windowMs(
                    Aware.getSetting(context, source.frequencySetting),
                    source.defaultFrequency,
                    source.unit);
            String state = stateGiven(
                    blocker, deviceEnabled, source == null, now, lastData, freshnessWindow);
            boolean excluded = !state.equals("collecting") && !state.equals("waiting_for_event");
            String reason = stateReason(state, blocker);
            String key = setting.substring("status_".length());

            String line = "sensor_status ts=" + now
                    + " sensor=" + key
                    + " enabled=true"
                    + " configured_enabled=true"
                    + " device_enabled=" + deviceEnabled
                    + " state=" + state
                    + " last_data_ms=" + lastData
                    + " expected_within_ms=" + freshnessWindow
                    + " excluded=" + excluded
                    + " reason=\"" + reason.replace("\"", "'") + "\"";
            Aware.debug(context, Aware.LogType.DIAGNOSTICS, line);
        }
    }

    static String stateGiven(
            String blocker,
            boolean deviceEnabled,
            boolean eventDriven,
            long now,
            long lastData,
            long freshnessWindow) {
        if (blocker != null && blocker.startsWith("No such sensor hardware")) return "unavailable";
        if (!deviceEnabled) return "disabled";
        if (blocker != null && !blocker.isEmpty()) return "blocked";
        if (eventDriven) return "waiting_for_event";
        if (lastData == 0) return "waiting_first_sample";
        return SensorFreshness.isFresh(now, lastData, freshnessWindow) ? "collecting" : "delayed";
    }

    private static String stateReason(String state, String blocker) {
        if ("unavailable".equals(state) || "blocked".equals(state)) return blocker;
        if ("disabled".equals(state)) return "Sensor is disabled on the device";
        if ("waiting_for_event".equals(state)) return "Enabled; records data when an event occurs";
        if ("waiting_first_sample".equals(state)) return "Waiting for the first sample";
        if ("delayed".equals(state)) return "Latest sample is older than the expected window";
        return "";
    }

    /**
     * The most recent moment this sensor is known to have produced data.
     *
     * Taken as the later of the newest local row and the point the upload bookmark says was
     * delivered. The local table alone is not enough: with {@code webservice_remove_data} on,
     * acknowledged rows are deleted after upload, so a sensor that is collecting and delivering
     * normally can hold no local rows at all. Reading only the table then reports 0, which
     * {@link #stateFor} turns into {@code waiting_first_sample} — a sensor that has been working for
     * hours described as never having started, in the participant's status text and in the
     * diagnostics uploaded to the researcher.
     *
     * The bookmark is a truthful lower bound: if data up to T reached the database, data existed
     * at T. It recovers exactly the fact deletion destroyed.
     */
    private static long latestTimestamp(Context context, Source source) {
        return observedLatest(newestLocalRow(context, source), deliveredUpTo(context, source.table));
    }

    /**
     * Reconciles the two records of when a sensor last produced data. Pure, so the case that caused
     * the bug — nothing local, something delivered — is covered by a unit test.
     */
    static long observedLatest(long newestLocalRow, long deliveredUpTo) {
        return Math.max(newestLocalRow, deliveredUpTo);
    }

    private static long newestLocalRow(Context context, Source source) {
        Uri uri = Uri.parse("content://" + context.getPackageName()
                + source.authoritySuffix + "/" + source.table);
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    uri, new String[]{"timestamp"}, null, null, "_id DESC LIMIT 1");
            if (cursor != null && cursor.moveToFirst()) return (long) cursor.getDouble(0);
        } catch (Exception ignored) {
            // Missing/unreadable provider is represented as no sample yet.
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    /**
     * The timestamp this table's upload bookmark reports as delivered, or 0 when there is none.
     * A bookmark keyed by a name no longer in use simply yields 0, leaving the local row as the
     * only evidence — the behaviour before this lookup existed.
     */
    private static long deliveredUpTo(Context context, String table) {
        Cursor marker = null;
        try {
            marker = context.getContentResolver().query(
                    Aware_Provider.Aware_Sync_Markers.CONTENT_URI,
                    new String[]{Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED},
                    Aware_Provider.Aware_Sync_Markers.MARKER_TABLE + "=?",
                    new String[]{table}, null);
            if (marker != null && marker.moveToFirst()) return (long) marker.getDouble(0);
        } catch (Exception ignored) {
            // No bookmark yet — nothing delivered.
        } finally {
            if (marker != null) marker.close();
        }
        return 0;
    }

    /**
     * Convenience for callers that only have a Context, not an already-parsed sensors array (e.g.
     * the periodic compliance schedule) — looks up the currently active study and logs its sensors.
     * No-op if there's no active study or its config can't be parsed.
     */
    public static void logActiveStudySensorStatus(Context context) {
        Cursor study = Aware.getActiveStudy(context);
        if (study == null) return;
        try {
            if (!study.moveToFirst()) return;
            JSONArray configs = new JSONArray(study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
            for (int i = 0; i < configs.length(); i++) {
                JSONObject element = configs.optJSONObject(i);
                if (element != null && element.has("sensors")) {
                    logSensorStatus(context, element.optJSONArray("sensors"));
                    return; // one config element carries the sensors array; no need to keep looking
                }
            }
        } catch (Exception e) {
            // Malformed/missing config — nothing meaningful to log.
        } finally {
            study.close();
        }
    }

    private static String firstMissingPermission(Context context, String[] permissions) {
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                return p;
            }
        }
        return null;
    }

    private static String shortPermission(String permission) {
        int dot = permission.lastIndexOf('.');
        return dot >= 0 ? permission.substring(dot + 1) : permission;
    }

    /** The OS-level Location toggle (Settings › Location) — distinct from the location permission. */
    private static boolean isLocationServicesEnabled(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /** True if AWARE's Accessibility Service (backs Applications, Keyboard, Screenshot) is on. */
    private static boolean isAccessibilityServiceEnabled(Context context) {
        ComponentName expected = new ComponentName(context, Applications.class);
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (expected.equals(ComponentName.unflattenFromString(splitter.next()))) {
                return true;
            }
        }
        return false;
    }
}
