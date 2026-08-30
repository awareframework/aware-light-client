package com.aware.phone.ui.prefs;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.hardware.Sensor;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.providers.Aware_Provider;
import com.aware.utils.SensorAvailability;
import com.aware.utils.SensorFreshness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Determines, per sensor, whether AWARE is actually collecting data on this device, and — when it
 * isn't — the most likely reason. "Collecting" is grounded in the sensor provider having a recent
 * row (the ground truth); if there is none, the reason is derived from preconditions (sensor
 * hardware present, runtime permission granted, accessibility enabled, or simply no data yet).
 *
 * Used by the study sensor list to color each sensor blue (collecting) vs grey (not), and to show a
 * details dialog on tap.
 */
public final class SensorCollection {

    private SensorCollection() {}

    /** Result of a collection check for one sensor. */
    public static final class Status {
        public final boolean collecting;
        public final boolean waitingForEvent;
        public final long lastDataMs;  // 0 = never any data
        public final String reason;    // short human-readable status
        public final String fixHint;   // actionable hint, or null

        Status(boolean collecting, long lastDataMs, String reason, String fixHint) {
            this(collecting, false, lastDataMs, reason, fixHint);
        }

        Status(boolean collecting, boolean waitingForEvent, long lastDataMs, String reason, String fixHint) {
            this.collecting = collecting;
            this.waitingForEvent = waitingForEvent;
            this.lastDataMs = lastDataMs;
            this.reason = reason;
            this.fixHint = fixHint;
        }
    }

    /** Registry entry describing where a sensor's data lives and what it needs to collect. */
    private static final class Def {
        final String authoritySuffix; // e.g. ".provider.battery"
        final String table;           // primary table, e.g. "battery"
        final int hardwareType;       // Sensor.TYPE_* for physical sensors, or -1
        final String[] permissions;   // required runtime permissions (may be empty)
        final boolean needsAccessibility;
        final boolean needsLocationServices; // the OS-level Location toggle, not a permission

        Def(String authoritySuffix, String table, int hardwareType, String[] permissions, boolean needsAccessibility) {
            this(authoritySuffix, table, hardwareType, permissions, needsAccessibility, false);
        }

        Def(String authoritySuffix, String table, int hardwareType, String[] permissions, boolean needsAccessibility, boolean needsLocationServices) {
            this.authoritySuffix = authoritySuffix;
            this.table = table;
            this.hardwareType = hardwareType;
            this.permissions = permissions;
            this.needsAccessibility = needsAccessibility;
            this.needsLocationServices = needsLocationServices;
        }
    }

    private static final String[] NONE = new String[0];
    private static final Map<String, Def> REGISTRY = new HashMap<>();
    private static final Map<String, String[]> STATUS_SETTINGS = new HashMap<>();
    private static final Map<String, SampleDef> SAMPLED = new HashMap<>();

    private static final class SampleDef {
        final String frequencySetting;
        final long defaultValue;
        final SensorFreshness.Unit unit;

        SampleDef(String frequencySetting, long defaultValue, SensorFreshness.Unit unit) {
            this.frequencySetting = frequencySetting;
            this.defaultValue = defaultValue;
            this.unit = unit;
        }
    }

    static {
        REGISTRY.put("accelerometer", new Def(".provider.accelerometer", "accelerometer", Sensor.TYPE_ACCELEROMETER, NONE, false));
        REGISTRY.put("linear_accelerometer", new Def(".provider.accelerometer.linear", "linear_accelerometer", Sensor.TYPE_LINEAR_ACCELERATION, NONE, false));
        REGISTRY.put("significant_motion", new Def(".provider.significant", "significant", Sensor.TYPE_ACCELEROMETER, NONE, false));
        REGISTRY.put("barometer", new Def(".provider.barometer", "barometer", Sensor.TYPE_PRESSURE, NONE, false));
        REGISTRY.put("gravity", new Def(".provider.gravity", "gravity", Sensor.TYPE_GRAVITY, NONE, false));
        REGISTRY.put("gyroscope", new Def(".provider.gyroscope", "gyroscope", Sensor.TYPE_GYROSCOPE, NONE, false));
        REGISTRY.put("light", new Def(".provider.light", "light", Sensor.TYPE_LIGHT, NONE, false));
        REGISTRY.put("magnetometer", new Def(".provider.magnetometer", "magnetometer", Sensor.TYPE_MAGNETIC_FIELD, NONE, false));
        REGISTRY.put("proximity", new Def(".provider.proximity", "proximity", Sensor.TYPE_PROXIMITY, NONE, false));
        REGISTRY.put("rotation", new Def(".provider.rotation", "rotation", Sensor.TYPE_ROTATION_VECTOR, NONE, false));
        REGISTRY.put("temperature", new Def(".provider.temperature", "temperature", Sensor.TYPE_AMBIENT_TEMPERATURE, NONE, false));

        REGISTRY.put("battery", new Def(".provider.battery", "battery", -1, NONE, false));
        REGISTRY.put("screen", new Def(".provider.screen", "screen", -1, NONE, false));
        REGISTRY.put("network", new Def(".provider.network", "network", -1, NONE, false));
        REGISTRY.put("processor", new Def(".provider.processor", "processor", -1, NONE, false));
        REGISTRY.put("timezone", new Def(".provider.timezone", "timezone", -1, NONE, false));
        REGISTRY.put("esm", new Def(".provider.esm", "esms", -1, NONE, false));

        REGISTRY.put("locations", new Def(".provider.locations", "locations", -1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, false));
        // WiFi scanning requires the OS-level Location toggle on system-wide, in addition to the
        // ACCESS_FINE_LOCATION permission below — Android blocks WifiManager.startScan() with a
        // SecurityException when Location services are off, regardless of granted permissions.
        REGISTRY.put("wifi", new Def(".provider.wifi", "wifi", -1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, false, true));
        REGISTRY.put("bluetooth", new Def(".provider.bluetooth", "bluetooth", -1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, false));
        REGISTRY.put("communication", new Def(".provider.communication", "calls", -1, new String[]{Manifest.permission.READ_CALL_LOG}, false));
        REGISTRY.put("telephony", new Def(".provider.telephony", "telephony", -1, new String[]{Manifest.permission.READ_PHONE_STATE}, false));

        // Accessibility-backed sensors
        REGISTRY.put("applications", new Def(".provider.applications", "applications_foreground", -1, NONE, true));
        REGISTRY.put("screenshot", new Def(".provider.screenshot", "screenshot", -1, NONE, true));

        status("accelerometer", Aware_Preferences.STATUS_ACCELEROMETER);
        status("linear_accelerometer", Aware_Preferences.STATUS_LINEAR_ACCELEROMETER);
        status("significant_motion", Aware_Preferences.STATUS_SIGNIFICANT_MOTION);
        status("barometer", Aware_Preferences.STATUS_BAROMETER);
        status("gravity", Aware_Preferences.STATUS_GRAVITY);
        status("gyroscope", Aware_Preferences.STATUS_GYROSCOPE);
        status("light", Aware_Preferences.STATUS_LIGHT);
        status("magnetometer", Aware_Preferences.STATUS_MAGNETOMETER);
        status("proximity", Aware_Preferences.STATUS_PROXIMITY);
        status("rotation", Aware_Preferences.STATUS_ROTATION);
        status("temperature", Aware_Preferences.STATUS_TEMPERATURE);
        status("battery", Aware_Preferences.STATUS_BATTERY);
        status("screen", Aware_Preferences.STATUS_SCREEN);
        status("network", Aware_Preferences.STATUS_NETWORK_EVENTS, Aware_Preferences.STATUS_NETWORK_TRAFFIC);
        status("processor", Aware_Preferences.STATUS_PROCESSOR);
        status("timezone", Aware_Preferences.STATUS_TIMEZONE);
        status("esm", Aware_Preferences.STATUS_ESM);
        status("locations", Aware_Preferences.STATUS_LOCATION_GPS,
                Aware_Preferences.STATUS_LOCATION_NETWORK, Aware_Preferences.STATUS_LOCATION_PASSIVE);
        status("wifi", Aware_Preferences.STATUS_WIFI);
        status("bluetooth", Aware_Preferences.STATUS_BLUETOOTH);
        status("communication", Aware_Preferences.STATUS_COMMUNICATION_EVENTS,
                Aware_Preferences.STATUS_CALLS, Aware_Preferences.STATUS_MESSAGES);
        status("telephony", Aware_Preferences.STATUS_TELEPHONY);
        status("applications", Aware_Preferences.STATUS_APPLICATIONS,
                Aware_Preferences.STATUS_INSTALLATIONS, Aware_Preferences.STATUS_NOTIFICATIONS,
                Aware_Preferences.STATUS_CRASHES, Aware_Preferences.STATUS_KEYBOARD,
                Aware_Preferences.STATUS_SCREENTEXT);
        status("screenshot", Aware_Preferences.STATUS_SCREENSHOT);

        sampled("accelerometer", Aware_Preferences.FREQUENCY_ACCELEROMETER, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("linear_accelerometer", Aware_Preferences.FREQUENCY_LINEAR_ACCELEROMETER, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("barometer", Aware_Preferences.FREQUENCY_BAROMETER, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("gravity", Aware_Preferences.FREQUENCY_GRAVITY, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("gyroscope", Aware_Preferences.FREQUENCY_GYROSCOPE, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("light", Aware_Preferences.FREQUENCY_LIGHT, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("magnetometer", Aware_Preferences.FREQUENCY_MAGNETOMETER, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("proximity", Aware_Preferences.FREQUENCY_PROXIMITY, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("rotation", Aware_Preferences.FREQUENCY_ROTATION, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("temperature", Aware_Preferences.FREQUENCY_TEMPERATURE, 200000, SensorFreshness.Unit.MICROSECONDS);
        sampled("bluetooth", Aware_Preferences.FREQUENCY_BLUETOOTH, 60, SensorFreshness.Unit.SECONDS);
        sampled("processor", Aware_Preferences.FREQUENCY_PROCESSOR, 10, SensorFreshness.Unit.SECONDS);
        sampled("wifi", Aware_Preferences.FREQUENCY_WIFI, 60, SensorFreshness.Unit.SECONDS);
        sampled("screenshot", Aware_Preferences.CAPTURE_TIME_INTERVAL, 60000, SensorFreshness.Unit.MILLISECONDS);
    }

    private static void status(String category, String... settings) {
        STATUS_SETTINGS.put(category, settings);
    }

    private static void sampled(
        String category,
        String frequencySetting,
        long defaultValue,
        SensorFreshness.Unit unit
    ) {
        SAMPLED.put(category, new SampleDef(frequencySetting, defaultValue, unit));
    }

    /** True if the given preference key is a known sensor category. */
    public static boolean isSensor(String categoryKey) {
        return REGISTRY.containsKey(categoryKey);
    }

    // --- Status text (pure; shared by the locked-mode status dialog and the editable-mode status row) ---

    /** The one-line collecting / not-collecting headline. */
    public static String statusHeadline(boolean collecting) {
        return collecting ? "●  Collecting data" : "○  Not collecting";
    }

    public static String statusHeadline(Status status) {
        return status.waitingForEvent ? "●  Enabled — waiting for an event"
                : statusHeadline(status.collecting);
    }

    /**
     * The detail block: why, what was captured, how much of it has reached the research database,
     * and what to do.
     *
     * {@code lastData} and {@code lastDelivered} are pre-formatted relative times, passed in so this
     * stays free of Android time formatting. They answer different questions and are shown together
     * because the gap between them is the interesting part: capture is local, delivery is not, and a
     * sensor can be collecting perfectly while nothing has left the device. A null
     * {@code lastDelivered} means nothing has been delivered yet; {@code fixHint} may also be null.
     */
    public static String statusDetail(
        String reason, CharSequence lastData,
        CharSequence lastDelivered,
        String fixHint
    ) {
        StringBuilder msg = new StringBuilder();
        msg.append(reason);
        msg.append("\n\nLast data: ").append(lastData);
        msg.append("\nDelivered: ")
                .append(lastDelivered == null ? "nothing yet" : "up to " + lastDelivered);
        if (fixHint != null) msg.append("\n\nWhat to do: ").append(fixHint);
        return msg.toString();
    }

    /** Full multi-line status text (headline + detail), as shown in the sensor status dialog. */
    public static String statusSummary(boolean collecting, String reason, CharSequence lastData,
                                       CharSequence lastDelivered, String fixHint) {
        return statusHeadline(collecting) + "\n\n"
                + statusDetail(reason, lastData, lastDelivered, fixHint);
    }

    public static String statusSummary(Status status, CharSequence lastData,
                                       CharSequence lastDelivered) {
        return statusHeadline(status) + "\n\n"
                + statusDetail(status.reason, lastData, lastDelivered, status.fixHint);
    }

    /**
     * Computes the collection status for one sensor category.
     *
     * @param context               any context
     * @param categoryKey           the sensor preference key (e.g. "battery")
     * @param accessibilityEnabled  whether AWARE's accessibility service is currently on
     */
    public static Status getStatus(Context context, String categoryKey, boolean accessibilityEnabled) {
        Def def = REGISTRY.get(categoryKey);
        if (def == null) {
            return new Status(
                false,
                0,
                "Unknown sensor",
                null
            );
        }

        long lastData = latestTimestamp(context, def);

        // Resolve permanent/permission blockers before freshness. A stale row from before a setting
        // or permission change must not make a currently blocked sensor look healthy.
        if (!isHardwareAvailable(context, categoryKey)) {
            return new Status(
                false,
                lastData,
                "This device has no " + prettyName(categoryKey) + " sensor",
                null);
        }
        if (!isCategoryEnabled(context, categoryKey)) {
            return new Status(false, lastData, "Sensor is disabled", "Turn on Activate to collect data");
        }
        if (def.needsAccessibility && !accessibilityEnabled) {
            return new Status(
                false,
                lastData,
                "Accessibility service is off",
                "Enable AWARE under Settings > Accessibility"
            );
        }
        if (def.needsLocationServices && !isLocationServicesEnabled(context)) {
            return new Status(
                false,
                lastData,
                "Location services are off",
                "Enable Location under Settings > Location"
            );
        }
        String missing = firstMissingPermission(context, def.permissions);
        if (missing != null) {
            String p = shortPermission(missing);
            return new Status(
                false,
                lastData,
                "Missing permission: " + p,
                "Grant the " + p + " permission in app settings"
            );
        }

        long freshnessWindow = freshnessWindowMs(context, categoryKey);
        if (freshnessWindow < 0) {
            return new Status(
                    true,
                    true,
                    lastData,
                    "This sensor records data when an event occurs",
                    null);
        }

        if (SensorFreshness.isFresh(System.currentTimeMillis(), lastData, freshnessWindow)) {
            return new Status(true, lastData, "Collecting data", null);
        }
        if (lastData == 0) {
            return new Status(
                false,
                0,
                "Waiting for the first sample",
                "Expected within " + formatDuration(freshnessWindow)
            );
        }
        return new Status(
            false,
            lastData,
            "Data is delayed",
            "Expected at least one sample every " + formatDuration(freshnessWindow)
        );
    }

    private static boolean isCategoryEnabled(Context context, String categoryKey) {
        String[] settings = STATUS_SETTINGS.get(categoryKey);
        if (settings == null) return true;
        for (String setting : settings) {
            if ("true".equals(Aware.getSetting(context, setting))) return true;
        }
        return false;
    }

    /**
     * Returns the recency window for sampled categories, or -1 for event-driven categories.
     * Location shares one provider table, so use the shortest enabled GPS/network interval.
     */
    private static long freshnessWindowMs(Context context, String categoryKey) {
        if ("locations".equals(categoryKey)) {
            long window = Long.MAX_VALUE;
            if ("true".equals(Aware.getSetting(context, Aware_Preferences.STATUS_LOCATION_GPS))) {
                window = Math.min(window, SensorFreshness.windowMs(
                        Aware.getSetting(context, Aware_Preferences.FREQUENCY_LOCATION_GPS),
                        180, SensorFreshness.Unit.SECONDS));
            }
            if ("true".equals(Aware.getSetting(context, Aware_Preferences.STATUS_LOCATION_NETWORK))) {
                window = Math.min(window, SensorFreshness.windowMs(
                        Aware.getSetting(context, Aware_Preferences.FREQUENCY_LOCATION_NETWORK),
                        300, SensorFreshness.Unit.SECONDS));
            }
            return window == Long.MAX_VALUE ? -1 : window;
        }

        SampleDef sample = SAMPLED.get(categoryKey);
        if (sample == null) return -1;
        return SensorFreshness.windowMs(
                Aware.getSetting(context, sample.frequencySetting), sample.defaultValue, sample.unit);
    }

    private static String formatDuration(long durationMs) {
        long minutes = Math.max(1, durationMs / (60L * 1000L));
        if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = minutes / 60;
        return hours + (hours == 1 ? " hour" : " hours");
    }

    /**
     * The most recent moment this sensor is known to have produced data.
     *
     * The later of the newest local row and the point the upload bookmark says was delivered. The
     * local table alone is not enough: with {@code webservice_remove_data} on, acknowledged rows are
     * deleted after upload, so a sensor that is collecting and delivering normally can hold no local
     * rows. Reading only the table then reports "never" beside a delivery time, and drives the status
     * to "waiting for the first sample" for a sensor that has been working for hours.
     *
     * The bookmark is a truthful lower bound: if data up to T reached the database, data existed at T.
     */
    private static long latestTimestamp(Context context, Def def) {
        return Math.max(newestLocalRow(context, def), deliveredUpTo(context, def.table));
    }

    private static long newestLocalRow(Context context, Def def) {
        Uri uri = Uri.parse("content://" + context.getPackageName() + def.authoritySuffix + "/" + def.table);
        Cursor c = null;
        try {
            // Order by _id (rowid, indexed) rather than timestamp to stay fast on large tables;
            // rows are inserted in time order, so the last rowid holds the newest timestamp.
            c = context.getContentResolver().query(uri, new String[]{"timestamp"}, null, null, "_id DESC LIMIT 1");
            if (c != null && c.moveToFirst()) {
                return (long) c.getDouble(0);
            }
        } catch (Exception e) {
            // Provider missing / not readable — treat as no data.
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /**
     * How far this sensor's data has reached the research database: the timestamp of the newest row
     * the server has acknowledged, or 0 when none has been.
     *
     * Read from the upload bookmark rather than from the sensor's own table, so it reports what the
     * server holds and not what the phone captured. The bookmark stores the timestamp of the last
     * delivered row, not the clock time of the upload, which is why the value is the point the
     * database has been brought up to.
     *
     * @param categoryKey the sensor preference key (e.g. "battery")
     */
    public static long lastDeliveredMs(Context context, String categoryKey) {
        Def def = REGISTRY.get(categoryKey);
        if (def == null) return 0;
        return deliveredUpTo(context, def.table);
    }

    /** The timestamp this table's upload bookmark reports as delivered, or 0 when there is none. */
    private static long deliveredUpTo(Context context, String table) {
        Cursor marker = null;
        try {
            marker = context.getContentResolver().query(
                    Aware_Provider.Aware_Sync_Markers.CONTENT_URI,
                    new String[]{Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED},
                    Aware_Provider.Aware_Sync_Markers.MARKER_TABLE + "=?",
                    new String[]{table}, null);
            if (marker != null && marker.moveToFirst()) {
                return (long) marker.getDouble(0);
            }
        } catch (Exception e) {
            // No bookmark for this table yet — treat as nothing delivered.
        } finally {
            if (marker != null) marker.close();
        }
        return 0;
    }

    // Delegates to aware-core's SensorAvailability rather than calling SensorManager directly here
    // too — that's now the single place in the codebase that talks to SensorManager for hardware
    // presence, so the background config-sync drift check (StudyUtils, aware-core) and this UI
    // status check can't drift apart on what "this device has no <sensor>" means.
    private static boolean hasHardware(Context context, int sensorType) {
        return SensorAvailability.hasHardware(context, sensorType);
    }

    /**
     * Whether this category can exist on the current device. Categories that do not depend on a
     * physical Android sensor are available by definition.
     */
    public static boolean isHardwareAvailable(Context context, String categoryKey) {
        Def def = REGISTRY.get(categoryKey);
        return def == null || def.hardwareType == -1 || hasHardware(context, def.hardwareType);
    }

    /** The OS-level Location toggle (Settings › Location) — distinct from the location permission. */
    public static boolean isLocationServicesEnabled(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
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

    private static String prettyName(String categoryKey) {
        return categoryKey.replace('_', ' ');
    }

    // ---------------------------------------------------------------------------------------------
    // Per-sensor consent (used by the post-join consent screen).
    //
    // Only sensors that need an explicit participant grant appear here — either a runtime permission
    // dialog (location, call log, contacts, phone state, SMS) or the Accessibility Service toggle
    // (applications, keyboard), which is granted via a Settings screen instead of a dialog. Install-time
    // permissions (e.g. bluetooth/wifi/network *state*) never prompt and aren't listed. Each item lists
    // the study settings that enable the sensor and either the runtime permissions to request or
    // needsAccessibility=true.
    // ---------------------------------------------------------------------------------------------

    /** One promptable sensor: what enables it, why we ask, and how the participant grants it. */
    public static final class ConsentItem {
        public final String key;             // stable id, e.g. "locations"
        public final String label;           // participant-facing name, e.g. "Location"
        public final String reason;          // one-line plain-language reason
        public final String[] statusSettings; // any of these == "true" means the study enabled it
        public final String[] permissions;   // runtime permissions to request for this sensor
        public final boolean needsAccessibility; // true if granted via Settings > Accessibility instead

        ConsentItem(String key, String label, String reason, String[] statusSettings, String[] permissions) {
            this(key, label, reason, statusSettings, permissions, false);
        }

        ConsentItem(String key, String label, String reason, String[] statusSettings, String[] permissions, boolean needsAccessibility) {
            this.key = key;
            this.label = label;
            this.reason = reason;
            this.statusSettings = statusSettings;
            this.permissions = permissions;
            this.needsAccessibility = needsAccessibility;
        }
    }

    // Referenced by string (not Manifest.permission.*) because they were added in API 31 and this
    // module compiles against an older SDK. Requesting them still works at runtime on API 31+.
    private static final String PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final String PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    /**
     * Runtime permissions the Bluetooth sensor needs to scan and read the local adapter. On Android
     * 12 (API 31) and up that's BLUETOOTH_SCAN + BLUETOOTH_CONNECT; below it, scanning is gated on
     * location instead. Without the API-31 pair the sensor throws SecurityException on modern devices.
     */
    private static String[] bluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{ PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT };
        }
        return new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION };
    }

    // Added in API 29; referenced by string because this module compiles against an older SDK.
    private static final String PERMISSION_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION";

    /**
     * True if AWARE can read location in the background ("Allow all the time"). Before Android 10
     * (API 29) there's no separate background-location permission, so it's always true. On API 29+ it
     * requires ACCESS_BACKGROUND_LOCATION, which the participant grants as "Allow all the time" — the
     * foreground ("while using the app") grant does NOT include it, and without it location logging
     * stops whenever AWARE isn't in the foreground.
     */
    public static boolean hasBackgroundLocation(Context context) {
        if (Build.VERSION.SDK_INT < 29) return true;
        return ContextCompat.checkSelfPermission(context, PERMISSION_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static final ConsentItem[] CONSENTS = new ConsentItem[]{
            new ConsentItem(
                "locations",
                "Location",
                "Record the places you visit.",
                new String[]{
                    Aware_Preferences.STATUS_LOCATION_GPS,
                    Aware_Preferences.STATUS_LOCATION_NETWORK
                },
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }
            ),
            new ConsentItem(
                "wifi",
                "Wi-Fi",
                "Detect nearby Wi-Fi networks.",
                new String[]{
                    Aware_Preferences.STATUS_WIFI
                },
                new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }
            ),
            new ConsentItem(
                "bluetooth",
                "Bluetooth",
                "Detect nearby Bluetooth devices.",
                new String[]{
                    Aware_Preferences.STATUS_BLUETOOTH
                },
                bluetoothPermissions()
            ),
            new ConsentItem(
                "telephony",
                "Telephony",
                "Cell tower / network information.",
                new String[]{
                    Aware_Preferences.STATUS_TELEPHONY
                },
                new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }
            ),
            new ConsentItem(
                "communication",
                "Calls & messages",
                "Call and message patterns (content is never read).",
                new String[]{
                    Aware_Preferences.STATUS_COMMUNICATION_EVENTS,
                    Aware_Preferences.STATUS_CALLS,
                    Aware_Preferences.STATUS_MESSAGES
                },
                new String[]{
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_SMS
                }
            ),
            new ConsentItem(
                "application",
                "Applications usage",
                "Foreground app activity, notifications, crashes, and app install/update/removal events.",
                new String[]{
                    Aware_Preferences.STATUS_APPLICATIONS,
                    Aware_Preferences.STATUS_NOTIFICATIONS,
                    Aware_Preferences.STATUS_CRASHES
                },
                NONE,
                true
            ),
            new ConsentItem(
                "keyboard",
                "Keyboard masked text",
                "Keyboard usage patterns, the content is masked, no passwords and text exposed",
                new String[]{
                    Aware_Preferences.STATUS_KEYBOARD
                },
                NONE,
                true
            ),
            new ConsentItem(
                "screenshot",
                "Screenshots",
                "Periodic screenshots of what's on screen.",
                new String[]{
                    Aware_Preferences.STATUS_SCREENSHOT
                },
                NONE,
                true
            ),
            new ConsentItem(
                "ambient_noise",
                "Ambient Noise plugin",
                "Measure environmental sound levels.",
                new String[]{
                    Aware_Preferences.STATUS_PLUGIN_AMBIENT_NOISE
                },
                new String[]{
                    Manifest.permission.RECORD_AUDIO
                }
            ),
            new ConsentItem(
                "openweather",
                "OpenWeather plugin",
                "Use location to record local weather conditions.",
                new String[]{
                    Aware_Preferences.STATUS_PLUGIN_OPENWEATHER
                },
                new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }
            )
    };

    /**
     * The consent item covering a sensor-list category key (as used by the study sensor list and
     * {@link #getStatus}), or null if that category needs no participant grant. Handles the one key
     * that differs between the two vocabularies ("applications" category ↔ "application" consent).
     */
    public static ConsentItem consentItemForCategory(String categoryKey) {
        String consentKey = "applications".equals(categoryKey) ? "application" : categoryKey;
        for (ConsentItem item : CONSENTS) {
            if (item.key.equals(consentKey)) return item;
        }
        return null;
    }

    /**
     * The consent item whose status settings include this exact setting key, or null if the setting
     * needs no participant grant. Lets a directly-toggled sensor checkbox (e.g. status_applications,
     * status_keyboard) find the accessibility / permission grant it still requires.
     */
    public static ConsentItem consentItemForSetting(String statusSetting) {
        if (statusSetting == null) return null;
        for (ConsentItem item : CONSENTS) {
            for (String setting : item.statusSettings) {
                if (setting.equals(statusSetting)) return item;
            }
        }
        return null;
    }

    /**
     * Every setting controlled by a consent choice. Installation events do not technically require
     * Accessibility, so they are not one of the settings that makes the Applications consent row
     * appear. When that row is present, however, its disclosure explicitly includes installation
     * events; accepting or declining it must therefore control installations as part of the same
     * participant choice.
     */
    public static List<String> controlledSettings(ConsentItem item) {
        List<String> settings = new ArrayList<>(Arrays.asList(item.statusSettings));
        if ("application".equals(item.key)) {
            settings.add(Aware_Preferences.STATUS_INSTALLATIONS);
        }
        return settings;
    }

    /**
     * Consent items represented by a top-level sensor category. Applications is special: its
     * preference screen contains both application-usage and keyboard collection, even though they
     * are independent consent choices backed by the same Accessibility Service toggle.
     */
    public static List<ConsentItem> consentItemsForCategory(String categoryKey) {
        List<ConsentItem> matches = new ArrayList<>();
        for (ConsentItem item : CONSENTS) {
            if (item.key.equals(categoryKey)
                    || ("applications".equals(categoryKey)
                    && ("application".equals(item.key) || "keyboard".equals(item.key)))) {
                matches.add(item);
            }
        }
        return matches;
    }

    /**
     * Which of {@code candidates} the active study config actually enables (value == true). Used when
     * a participant enables a consent group from a sensor's details, so only the sub-settings the
     * study wants are turned on, not the whole group.
     */
    public static List<String> configEnabledSettings(JSONObject config, String[] candidates) {
        List<String> out = new ArrayList<>();
        JSONArray sensors = config == null ? null : config.optJSONArray("sensors");
        if (sensors == null) return out;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null || !sensor.optBoolean("value", false)) continue;
            String setting = sensor.optString("setting", "");
            for (String candidate : candidates) {
                if (candidate.equals(setting)) out.add(candidate);
            }
        }
        return out;
    }

    /**
     * The consent items a study's config would enable, regardless of whether their permissions are
     * granted yet. Reads sensor enablement directly from the not-yet-applied config JSON, so this can
     * run before {@code StudyUtils.applySettings} has flipped any status_* setting — consent must gate
     * enabling, not just follow it.
     */
    public static List<ConsentItem> enabledConsentsForConfig(JSONArray configs) {
        List<ConsentItem> enabled = new ArrayList<>();
        for (ConsentItem item : CONSENTS) {
            if (item.permissions.length == 0 && !item.needsAccessibility) continue;
            if (isSensorEnabledInConfig(configs, item.statusSettings)) enabled.add(item);
        }
        return enabled;
    }

    /** How (if at all) the participant must act for a sensor shown on the consent screen. */
    public enum ConsentBadge { PERMISSION, ACCESSIBILITY, AUTOMATIC }
    public enum ConsentGroup { SENSOR, PLUGIN }

    /**
     * One participant-facing row on the join consent screen. Either a grant-requiring sensor
     * ({@link #grantItem} non-null — tap to grant a runtime permission or the Accessibility service)
     * or an automatically-collected sensor ({@link #grantItem} null — disclosed for transparency,
     * with nothing to grant).
     */
    public static final class ConsentRow {
        public final String label;
        public final String description;
        public final String emoji;
        public final ConsentBadge badge;
        public final ConsentGroup group;
        public final ConsentItem grantItem; // non-null only for grant-requiring sensors

        ConsentRow(String label, String description, String emoji, ConsentBadge badge,
                   ConsentGroup group, ConsentItem grantItem) {
            this.label = label;
            this.description = description;
            this.emoji = emoji;
            this.badge = badge;
            this.group = group;
            this.grantItem = grantItem;
        }

        public boolean requiresGrant() { return grantItem != null; }
    }

    /**
     * The full, ordered list of sensors a study collects, for the pre-join consent screen: every
     * grant-requiring sensor the config enables (each a tap-to-grant row) followed by every
     * automatically-collected sensor it enables (disclosed only). Built directly from the
     * not-yet-applied config JSON, same as {@link #enabledConsentsForConfig}, so it can run before the
     * study is applied.
     */
    public static List<ConsentRow> consentRowsForConfig(JSONArray configs) {
        List<ConsentRow> sensorRows = new ArrayList<>();
        List<ConsentRow> pluginRows = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (ConsentItem item : enabledConsentsForConfig(configs)) {
            ConsentBadge badge = item.needsAccessibility ? ConsentBadge.ACCESSIBILITY : ConsentBadge.PERMISSION;
            ConsentGroup group = consentGroup(item.statusSettings);
            ConsentRow row = new ConsentRow(
                    item.label, item.reason, consentEmoji(item.key), badge, group, item);
            (group == ConsentGroup.PLUGIN ? pluginRows : sensorRows).add(row);
            covered.addAll(controlledSettings(item));
        }

        List<ConsentRow> automaticSensors = new ArrayList<>();
        List<ConsentRow> automaticPlugins = new ArrayList<>();
        for (String setting : enabledStatusSettings(configs)) {
            if (covered.contains(setting) || consentItemForSetting(setting) != null) continue;
            String name = setting.substring("status_".length());
            ConsentGroup group = setting.startsWith("status_plugin_")
                    ? ConsentGroup.PLUGIN : ConsentGroup.SENSOR;
            ConsentRow row = new ConsentRow(
                    autoLabel(name), autoDescription(name), autoEmoji(name),
                    ConsentBadge.AUTOMATIC, group, null);
            (group == ConsentGroup.PLUGIN ? automaticPlugins : automaticSensors).add(row);
        }
        Comparator<ConsentRow> byLabel = new Comparator<ConsentRow>() {
            @Override
            public int compare(ConsentRow a, ConsentRow b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        };
        Collections.sort(automaticSensors, byLabel);
        Collections.sort(automaticPlugins, byLabel);
        sensorRows.addAll(automaticSensors);
        pluginRows.addAll(automaticPlugins);

        List<ConsentRow> rows = new ArrayList<>();
        rows.addAll(sensorRows);
        rows.addAll(pluginRows);
        return rows;
    }

    /** A single grant-requiring display row for {@code item} — e.g. the mid-study update list. */
    public static ConsentRow rowFor(ConsentItem item) {
        ConsentBadge badge = item.needsAccessibility ? ConsentBadge.ACCESSIBILITY : ConsentBadge.PERMISSION;
        return new ConsentRow(item.label, item.reason, consentEmoji(item.key), badge,
                consentGroup(item.statusSettings), item);
    }

    private static ConsentGroup consentGroup(String[] statusSettings) {
        for (String setting : statusSettings) {
            if (setting != null && setting.startsWith("status_plugin_")) {
                return ConsentGroup.PLUGIN;
            }
        }
        return ConsentGroup.SENSOR;
    }

    /** Every status_* setting the config enables (value == true), across all config elements. */
    private static Set<String> enabledStatusSettings(JSONArray configs) {
        Set<String> out = new HashSet<>();
        if (configs == null) return out;
        for (int i = 0; i < configs.length(); i++) {
            JSONObject element = configs.optJSONObject(i);
            JSONArray sensors = element == null ? null : element.optJSONArray("sensors");
            if (sensors == null) continue;
            for (int j = 0; j < sensors.length(); j++) {
                JSONObject sensor = sensors.optJSONObject(j);
                if (sensor == null || !sensor.optBoolean("value", false)) continue;
                String setting = sensor.optString("setting", "");
                if (setting.startsWith("status_")) out.add(setting);
            }
        }
        return out;
    }

    private static String consentEmoji(String consentKey) {
        switch (consentKey) {
            case "locations": return "📍";          // 📍
            case "wifi": return "📶";               // 📶
            case "bluetooth": return "🔵";          // 🔵
            case "telephony": return "📡";          // 📡
            case "communication": return "💬";      // 💬
            case "application": return "📊";        // 📊
            case "keyboard": return "⌨️";           // ⌨️
            case "screenshot": return "📸";         // 📸
            case "ambient_noise": return "🎤";      // 🎤
            case "openweather": return "🌦️";        // 🌦️
            default: return "🔒";                   // 🔒
        }
    }

    private static String autoEmoji(String name) {
        if (name.startsWith("plugin_")) {
            String plugin = name.substring("plugin_".length());
            if ("openweather".equals(plugin)) return "🌦️";
            if ("esm_scheduler".equals(plugin)) return "📝";
            if ("ambient_noise".equals(plugin)) return "🎤";
            return "🧩";
        }
        switch (name) {
            case "accelerometer":
            case "linear_accelerometer":
            case "significant_motion": return "🏃";  // 🏃
            case "gyroscope": return "🌀";           // 🌀
            case "magnetometer":
            case "rotation": return "🧭";            // 🧭
            case "gravity": return "⬇️";             // ⬇️
            case "barometer": return "🌦️";           // 🌦️
            case "light": return "💡";               // 💡
            case "proximity": return "📏";           // 📏
            case "temperature": return "🌡️";         // 🌡️
            case "battery": return "🔋";             // 🔋
            case "screen": return "📱";              // 📱
            case "processor": return "⚙️";           // ⚙️
            case "network_traffic": return "📈";     // 📈
            case "timezone": return "🕐";            // 🕐
            default: return "📊";                    // 📊
        }
    }

    private static String autoLabel(String name) {
        if (name.startsWith("plugin_")) {
            String plugin = name.substring("plugin_".length());
            if ("openweather".equals(plugin)) return "OpenWeather plugin";
            if ("esm_scheduler".equals(plugin)) return "ESM Scheduler plugin";
            if ("ambient_noise".equals(plugin)) return "Ambient Noise plugin";
            String prettyPlugin = plugin.replace('_', ' ');
            return (prettyPlugin.isEmpty()
                    ? "Plugin"
                    : Character.toUpperCase(prettyPlugin.charAt(0)) + prettyPlugin.substring(1))
                    + " plugin";
        }
        switch (name) {
            case "linear_accelerometer": return "Linear accelerometer";
            case "significant_motion": return "Significant motion";
            case "network_traffic": return "Network traffic";
            case "installations": return "Application installations";
            case "processor": return "Processor (CPU)";
            case "timezone": return "Time zone";
            default:
                String pretty = name.replace('_', ' ');
                return pretty.isEmpty() ? pretty : Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1);
        }
    }

    private static String autoDescription(String name) {
        if (name.startsWith("plugin_")) {
            String plugin = name.substring("plugin_".length());
            if ("openweather".equals(plugin)) return "Record local weather conditions.";
            if ("esm_scheduler".equals(plugin)) return "Schedule study questionnaires.";
            if ("ambient_noise".equals(plugin)) return "Measure environmental sound levels.";
            return "Additional study data collection.";
        }
        switch (name) {
            case "accelerometer": return "Device movement and orientation.";
            case "linear_accelerometer": return "Movement with gravity removed.";
            case "significant_motion": return "Detects notable movement.";
            case "gyroscope": return "Rotation and turning of the device.";
            case "magnetometer": return "Magnetic field / compass heading.";
            case "rotation": return "Device orientation in space.";
            case "gravity": return "Direction of gravity.";
            case "barometer": return "Air pressure.";
            case "light": return "Ambient light level.";
            case "proximity": return "Whether something is close to the screen.";
            case "temperature": return "Ambient temperature.";
            case "battery": return "Charging state and battery level.";
            case "screen": return "Screen on/off and lock/unlock.";
            case "processor": return "Processor (CPU) load.";
            case "network_traffic": return "Amount of data sent and received.";
            case "installations": return "Application install, update, and removal events.";
            case "timezone": return "Device time zone.";
            default: return "Collected automatically.";
        }
    }

    /**
     * True if the study config enables at least one sensor that still needs a permission grant or
     * Accessibility Service enable the participant hasn't already given — i.e. there's actually
     * something new to ask about. False when every promptable sensor the config enables is already
     * satisfied, so the consent screen would have nothing left to show.
     */
    public static boolean hasPendingConsents(Context context, JSONArray configs) {
        for (ConsentItem item : enabledConsentsForConfig(configs)) {
            if (!isAlreadyGranted(context, item)) return true;
        }
        return false;
    }

    /**
     * Fingerprint of what a consent for this study would cover: the study URL plus the sorted keys
     * of every promptable sensor its config enables (like "https://…|bluetooth,locations,wifi").
     * Stored as {@link com.aware.Aware_Preferences#STUDY_CONSENT_RECORD} when the participant
     * completes the consent screen; a mismatch (different study, or a changed sensor set) means
     * their recorded agreement doesn't cover this join.
     */
    public static String consentFingerprint(String studyUrl, JSONArray configs) {
        List<String> keys = new ArrayList<>();
        for (ConsentItem item : enabledConsentsForConfig(configs)) {
            keys.add(item.key);
        }
        java.util.Collections.sort(keys);
        return (studyUrl == null ? "" : studyUrl) + "|" + TextUtils.join(",", keys);
    }

    /**
     * True if the participant's recorded consent (if any) covers exactly this study and its current
     * promptable-sensor set. False when there is no record — never consented, or the record was
     * wiped by quitting the study — or when the set changed since they agreed.
     */
    public static boolean hasMatchingConsentRecord(Context context, String studyUrl, JSONArray configs) {
        String recorded = Aware.getSetting(context, Aware_Preferences.STUDY_CONSENT_RECORD);
        return recorded.length() > 0 && recorded.equals(consentFingerprint(studyUrl, configs));
    }

    /** The set of status_* keys the participant has declined (persisted, comma-joined). */
    private static Set<String> declinedSet(Context context) {
        Set<String> out = new HashSet<>();
        for (String key : Aware.getSetting(context, Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")) {
            if (key.trim().length() > 0) out.add(key.trim());
        }
        return out;
    }

    /**
     * Promptable sensors the study config enables that are currently declined (held off) — i.e. the
     * study wants them but the participant hasn't agreed. Used by the "study updated" re-consent
     * prompt to show exactly what's waiting on agreement after a config change.
     */
    public static List<ConsentItem> heldConsentsForConfig(Context context, JSONArray configs) {
        Set<String> declined = declinedSet(context);
        List<ConsentItem> held = new ArrayList<>();
        for (ConsentItem item : enabledConsentsForConfig(configs)) {
            for (String setting : item.statusSettings) {
                if (declined.contains(setting)) {
                    held.add(item);
                    break;
                }
            }
        }
        return held;
    }

    /** Held consent choices belonging to one top-level sensor category. */
    public static List<ConsentItem> heldConsentsForCategory(Context context, JSONArray configs,
                                                            String categoryKey) {
        List<ConsentItem> categoryItems = consentItemsForCategory(categoryKey);
        List<ConsentItem> held = new ArrayList<>();
        for (ConsentItem item : heldConsentsForConfig(context, configs)) {
            if (categoryItems.contains(item)) held.add(item);
        }
        return held;
    }

    /**
     * Researcher-visible snapshot of the current consent state for every promptable sensor enabled
     * by the study config. Unlike an isolated "sensor enabled" event, this supersedes ambiguity in
     * an earlier consent row after the participant changes their choice during the study.
     */
    public static String consentStateSummary(JSONArray configs, Set<String> declinedSettings) {
        List<String> enabled = new ArrayList<>();
        List<String> declined = new ArrayList<>();
        Set<String> declinedKeys = declinedSettings == null
                ? new HashSet<String>() : declinedSettings;
        for (ConsentItem item : enabledConsentsForConfig(configs)) {
            boolean itemDeclined = false;
            for (String setting : item.statusSettings) {
                if (declinedKeys.contains(setting)) {
                    itemDeclined = true;
                    break;
                }
            }
            (itemDeclined ? declined : enabled).add(item.label);
        }
        return "enabled=" + enabled + " declined=" + declined;
    }

    /** True if the study config enables at least one promptable sensor currently declined/held off. */
    public static boolean hasHeldConsents(Context context, JSONArray configs) {
        return !heldConsentsForConfig(context, configs).isEmpty();
    }

    /**
     * READ_SMS and READ_CALL_LOG are Android "hard-restricted" permissions: the platform refuses to
     * grant them to an app that isn't the device's default SMS/Dialer app (and isn't whitelisted by
     * its installer), so the runtime dialog is effectively a no-op. Treating them as required would
     * trap the Communication row on "Enable" forever, since checkSelfPermission can never return
     * granted. They're best-effort instead — the sensor collects call/message metadata only if the
     * platform does grant them, but consent doesn't hinge on it.
     */
    private static boolean isBestEffortPermission(String permission) {
        return Manifest.permission.READ_SMS.equals(permission)
                || Manifest.permission.READ_CALL_LOG.equals(permission);
    }

    /**
     * True if a consent item's requirement is already satisfied — the Accessibility Service is on
     * (for accessibility-backed items) or every one of its required runtime permissions is granted.
     * Hard-restricted permissions (see {@link #isBestEffortPermission}) don't count against this, so a
     * row isn't trapped waiting on a grant the platform will never give. Judged from the live
     * permission state so it reflects reality regardless of the request callback's result array. Used
     * both to decide whether the consent screen needs to appear ({@link #hasPendingConsents}) and, once
     * shown, which rows are satisfied.
     */
    public static boolean isAlreadyGranted(Context context, ConsentItem item) {
        if (item.needsAccessibility) {
            return isAccessibilityServiceEnabled(context);
        }
        for (String permission : item.permissions) {
            if (isBestEffortPermission(permission)) continue;
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** True if any of the given status settings is "value": true for some sensor entry in the config. */
    private static boolean isSensorEnabledInConfig(JSONArray configs, String[] statusSettings) {
        for (int i = 0; i < configs.length(); i++) {
            JSONObject element = configs.optJSONObject(i);
            if (element == null) continue;
            JSONArray sensors = element.optJSONArray("sensors");
            if (sensors == null) continue;
            for (int j = 0; j < sensors.length(); j++) {
                JSONObject sensor = sensors.optJSONObject(j);
                if (sensor == null) continue;
                String setting = sensor.optString("setting", "");
                if (!sensor.optBoolean("value", false)) continue;
                for (String s : statusSettings) {
                    if (s.equals(setting)) return true;
                }
            }
        }
        return false;
    }

    /**
     * True if AWARE's Accessibility Service (backs Applications, Keyboard and Screenshot capture) is
     * currently enabled. Reads the OS setting directly rather than {@link Applications#isAccessibilityServiceActive}
     * so checking status here has no side effects (that method posts a notification when disabled).
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
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
