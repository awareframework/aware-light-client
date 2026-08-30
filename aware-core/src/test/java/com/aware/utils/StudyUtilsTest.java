package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Regression test for StudyUtils.jsonEquals(). Locks in the fix that switched the comparison from
 * JSONAssert's LENIENT mode to NON_EXTENSIBLE: LENIENT treats arrays as a one-directional subset
 * check, so a config edit that only *adds* an entry to the "sensors" array (rather than toggling
 * an existing entry's value) compared as equal to the old config and was silently ignored by
 * syncStudyConfig() — a real bug that shipped and was only caught by on-device testing.
 */
public class StudyUtilsTest {

    private static JSONObject configWithSensors(JSONObject... sensors) throws JSONException {
        JSONArray sensorsArray = new JSONArray();
        for (JSONObject sensor : sensors) sensorsArray.put(sensor);
        JSONObject config = new JSONObject();
        config.put("sensors", sensorsArray);
        return config;
    }

    private static JSONObject sensor(String setting, boolean value) throws JSONException {
        return sensorValue(setting, value);
    }

    private static JSONObject sensorValue(String setting, Object value) throws JSONException {
        JSONObject sensor = new JSONObject();
        sensor.put("setting", setting);
        sensor.put("value", value);
        return sensor;
    }

    @Test
    public void editableSync_skipsAutomaticUpdates() {
        assertTrue(StudyUtils.shouldSkipAutomaticConfigSync(true, false));
    }

    @Test
    public void editableSync_manualCheckAppliesServerUpdate() {
        assertFalse(StudyUtils.shouldSkipAutomaticConfigSync(true, true));
    }

    @Test
    public void lockedSync_keepsAutomaticUpdates() {
        assertFalse(StudyUtils.shouldSkipAutomaticConfigSync(false, false));
    }

    @Test
    public void editableManualUpdate_requiresPreviewBeforeApplying() {
        assertTrue(StudyUtils.shouldPreviewManualConfigUpdate(
                true, true, false, false));
    }

    @Test
    public void editableManualUpdate_matchingApprovalCanApply() {
        assertFalse(StudyUtils.shouldPreviewManualConfigUpdate(
                true, true, false, true));
    }

    @Test
    public void lockedManualUpdateDoesNotRequireParticipantApproval() {
        assertFalse(StudyUtils.shouldPreviewManualConfigUpdate(
                false, true, false, false));
    }

    @Test
    public void unchangedManualUpdateNeedsNoApproval() {
        assertFalse(StudyUtils.shouldPreviewManualConfigUpdate(
                true, true, true, false));
    }

    @Test
    public void editableSettingUpdate_preservesTypesAndAddsNewSettings() throws JSONException {
        JSONObject config = configWithSensors(
                sensor("status_accelerometer", true),
                sensorValue("frequency_accelerometer", 20000));

        JSONObject changedStatus =
                StudyUtils.withSensorSetting(config, "status_accelerometer", "false");
        JSONObject changedFrequency =
                StudyUtils.withSensorSetting(changedStatus, "frequency_accelerometer", "400000");
        JSONObject added =
                StudyUtils.withSensorSetting(changedFrequency, "frequency_light", "2.5");

        JSONArray sensors = added.getJSONArray("sensors");
        assertFalse(sensors.getJSONObject(0).getBoolean("value"));
        assertEquals(400000, sensors.getJSONObject(1).getInt("value"));
        assertEquals(2.5, sensors.getJSONObject(2).getDouble("value"), 0.0);
    }

    @Test
    public void additiveOnlySensorChange_isNotEqual() throws JSONException {
        JSONObject before = configWithSensors(sensor("status_location_gps", true));
        JSONObject after = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", true));

        // This is the exact regression: under the old LENIENT mode, "after" being a superset of
        // "before" compared as equal, so the newly-added sensor was never detected or applied.
        assertFalse(StudyUtils.jsonEquals(before, after));
    }

    @Test
    public void removedSensor_isNotEqual() throws JSONException {
        JSONObject before = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", true));
        JSONObject after = configWithSensors(sensor("status_location_gps", true));

        assertFalse(StudyUtils.jsonEquals(before, after));
    }

    @Test
    public void identicalConfigs_areEqual() throws JSONException {
        JSONObject before = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", false));
        JSONObject after = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", false));

        assertTrue(StudyUtils.jsonEquals(before, after));
    }

    /**
     * Regression test for the config-sync gate bug: syncStudyConfig() used to compare only the
     * stored config blob against the freshly-fetched server config (jsonEquals) and return early
     * the moment those matched — never checking whether the device's *live* aware_settings still
     * agreed. A drift (e.g. from Aware.reset() or an interrupted apply, config text unchanged)
     * therefore went undetected forever. driftSignature() is the fix's pure comparison core: given
     * a config and a map of what's actually live, it reports every status_* mismatch.
     */
    private static final Set<String> NO_HARDWARE_EXCLUSIONS = Collections.emptySet();

    @Test
    public void driftSignature_matchingLiveSettings_isEmpty() throws JSONException {
        JSONObject config = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", false));
        Map<String, String> live = new HashMap<>();
        live.put("status_location_gps", "true");
        live.put("status_wifi", "false");

        assertEquals("", StudyUtils.driftSignature(config, live, NO_HARDWARE_EXCLUSIONS));
    }

    @Test
    public void driftSignature_liveSettingOff_configSaysOn_isDetected() throws JSONException {
        // The exact failure mode this exists to catch: config still says the sensor should be on,
        // but the live setting somehow reads off (e.g. Aware.reset() ran without a full re-apply).
        JSONObject config = configWithSensors(sensor("status_location_gps", true));
        Map<String, String> live = new HashMap<>();
        live.put("status_location_gps", "false");

        String signature = StudyUtils.driftSignature(config, live, NO_HARDWARE_EXCLUSIONS);
        assertFalse(signature.isEmpty());
        assertTrue(signature.contains("status_location_gps"));
    }

    @Test
    public void driftSignature_missingLiveValue_treatedAsMismatch() throws JSONException {
        // A setting the config expects "true" for but that was never applied at all (not present
        // in the live map) must count as drift too, not be silently skipped.
        JSONObject config = configWithSensors(sensor("status_wifi", true));
        Map<String, String> live = new HashMap<>();

        assertFalse(StudyUtils.driftSignature(config, live, NO_HARDWARE_EXCLUSIONS).isEmpty());
    }

    @Test
    public void driftSignature_nonStatusSettingMismatch_isIgnored() throws JSONException {
        // Only status_* (on/off) settings are in scope — a frequency/threshold mismatch doesn't
        // mean a sensor is silently not collecting, just that its granularity differs, so it
        // shouldn't trigger the self-heal reapply path.
        JSONObject config = configWithSensors(sensor("frequency_light", true));
        Map<String, String> live = new HashMap<>();
        live.put("frequency_light", "false");

        assertEquals("", StudyUtils.driftSignature(config, live, NO_HARDWARE_EXCLUSIONS));
    }

    @Test
    public void driftSignature_isStableRegardlessOfSensorOrder() throws JSONException {
        // The signature is compared across polls (and persisted) to decide whether a drift is
        // "the same one we already tried to fix" — if two configs describing the same drift in a
        // different sensor order produced different signatures, the backoff guard would never
        // recognize a repeat and would reapply on every single poll.
        JSONObject configA = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", true));
        JSONObject configB = configWithSensors(sensor("status_wifi", true), sensor("status_location_gps", true));
        Map<String, String> live = new HashMap<>(); // both off live, both configs say on

        assertEquals(
                StudyUtils.driftSignature(configA, live, NO_HARDWARE_EXCLUSIONS),
                StudyUtils.driftSignature(configB, live, NO_HARDWARE_EXCLUSIONS));
    }

    /**
     * Regression test for the hardware-exclusion refinement: a status_* setting for hardware this
     * device doesn't have (e.g. status_temperature with no ambient temperature sensor) must never
     * be reported as drift, no matter what its live value is — it's a permanent, known fact about
     * the device, not something the 1-hour reconcile backoff should have to keep suppressing.
     */
    @Test
    public void driftSignature_hardwareUnavailableSetting_neverReportedAsDrift() throws JSONException {
        JSONObject config = configWithSensors(sensor("status_temperature", true));
        Map<String, String> live = new HashMap<>();
        live.put("status_temperature", "false"); // would otherwise be a clear mismatch

        Set<String> hardwareUnavailable = new HashSet<>();
        hardwareUnavailable.add("status_temperature");

        assertEquals("", StudyUtils.driftSignature(config, live, hardwareUnavailable));
    }

    @Test
    public void driftSignature_hardwareExclusion_onlyAppliesToNamedSetting() throws JSONException {
        // A hardware exclusion for one sensor shouldn't hide drift in an unrelated sensor.
        JSONObject config = configWithSensors(sensor("status_temperature", true), sensor("status_wifi", true));
        Map<String, String> live = new HashMap<>();
        live.put("status_temperature", "false");
        live.put("status_wifi", "false");

        Set<String> hardwareUnavailable = new HashSet<>();
        hardwareUnavailable.add("status_temperature");

        String signature = StudyUtils.driftSignature(config, live, hardwareUnavailable);
        assertFalse(signature.isEmpty());
        assertTrue(signature.contains("status_wifi"));
        assertFalse(signature.contains("status_temperature"));
    }

    @Test
    public void processorAvailability_isBlockedFromAndroidNougatOnward() {
        assertTrue(SensorAvailability.isPlatformSupported(
                "status_processor", android.os.Build.VERSION_CODES.M));
        assertFalse(SensorAvailability.isPlatformSupported(
                "status_processor", android.os.Build.VERSION_CODES.N));
        assertFalse(SensorAvailability.isPlatformSupported(
                "status_processor", 30));
    }

    @Test
    public void consentRequiring_picksEnabledPermissionAndAccessibilitySensors() throws JSONException {
        JSONObject config = configWithSensors(
                sensor("status_location_gps", true),   // runtime permission
                sensor("status_applications", true),   // accessibility service
                sensor("status_battery", true));       // no gate

        Set<String> result = StudyUtils.consentRequiringEnabledSettings(new JSONArray().put(config));

        assertTrue(result.contains("status_location_gps"));
        assertTrue(result.contains("status_applications"));
        assertFalse(result.contains("status_battery"));
    }

    @Test
    public void consentRequiring_ignoresDisabledSensors() throws JSONException {
        JSONObject config = configWithSensors(
                sensor("status_location_gps", false),
                sensor("status_battery", true));

        assertTrue(StudyUtils.consentRequiringEnabledSettings(new JSONArray().put(config)).isEmpty());
    }

    @Test
    public void consentRequiring_baseOnlyConfig_isEmpty() throws JSONException {
        JSONObject config = configWithSensors(
                sensor("status_battery", true),
                sensor("status_screen", true),
                sensor("status_accelerometer", true));

        assertTrue(StudyUtils.consentRequiringEnabledSettings(new JSONArray().put(config)).isEmpty());
    }

    @Test
    public void consentRequiring_ambientNoiseNeedsMicrophoneConsent() throws JSONException {
        JSONObject config = configWithSensors(
                sensor("status_plugin_ambient_noise", true));

        assertTrue(StudyUtils.consentRequiringEnabledSettings(new JSONArray().put(config))
                .contains("status_plugin_ambient_noise"));
    }

    @Test
    public void consentRequiring_openWeatherNeedsLocationConsent() throws JSONException {
        JSONObject config = configWithSensors(
                sensor("status_plugin_openweather", true));

        assertTrue(StudyUtils.consentRequiringEnabledSettings(new JSONArray().put(config))
                .contains("status_plugin_openweather"));
    }

    @Test
    public void groupedConsent_decliningApplicationsAlsoDeclinesInstallations() {
        Set<String> declined = new HashSet<>();
        declined.add("status_applications");

        Set<String> expanded = StudyUtils.expandGroupedConsentDeclines(declined);

        assertTrue(expanded.contains("status_applications"));
        assertTrue(expanded.contains("status_installations"));
    }

    @Test
    public void groupedConsent_doesNotTreatStandaloneInstallationsAsAccessibilityGated() {
        Set<String> declined = new HashSet<>();
        declined.add("status_battery");

        Set<String> expanded = StudyUtils.expandGroupedConsentDeclines(declined);

        assertFalse(expanded.contains("status_installations"));
    }

    /**
     * Regression test for the editable-mode preview loop: a manual "check for study updates" compared
     * the raw config blobs, so a server config that enabled a sensor this device physically lacks
     * (e.g. status_gyroscope on a phone with no gyroscope) never compared equal to the participant's
     * kept config. "Keep my settings" could never reconcile it — the sensor can't be turned on — so
     * the preview dialog reappeared on every check. configsEqualIgnoringSensors() is the pure core of
     * the fix: two configs compare equal once the unactionable (unavailable-hardware) sensors are set
     * aside, so a difference confined to them stops re-triggering the preview.
     */
    private static Set<String> ignoring(String... settings) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, settings);
        return set;
    }

    @Test
    public void configsEqualIgnoringSensors_differOnlyByIgnoredSensor_areEqual() throws JSONException {
        JSONObject local = configWithSensors(sensor("status_wifi", true), sensor("status_gyroscope", false));
        JSONObject server = configWithSensors(sensor("status_wifi", true), sensor("status_gyroscope", true));

        assertFalse(StudyUtils.jsonEquals(local, server)); // the raw blobs still differ
        assertTrue(StudyUtils.configsEqualIgnoringSensors(local, server, ignoring("status_gyroscope")));
    }

    @Test
    public void configsEqualIgnoringSensors_ignoredSensorAddedByServer_areEqual() throws JSONException {
        // The additive case: the server introduces a brand-new (unavailable) sensor entry the local
        // config never had. Dropping it from both must still compare equal.
        JSONObject local = configWithSensors(sensor("status_wifi", true));
        JSONObject server = configWithSensors(sensor("status_wifi", true), sensor("status_gyroscope", true));

        assertTrue(StudyUtils.configsEqualIgnoringSensors(local, server, ignoring("status_gyroscope")));
    }

    @Test
    public void configsEqualIgnoringSensors_availableSensorAlsoDiffers_areNotEqual() throws JSONException {
        // If an actionable (available) sensor also changed, the configs must NOT be treated as equal —
        // the participant still needs the preview for that change.
        JSONObject local = configWithSensors(sensor("status_wifi", false), sensor("status_gyroscope", false));
        JSONObject server = configWithSensors(sensor("status_wifi", true), sensor("status_gyroscope", true));

        assertFalse(StudyUtils.configsEqualIgnoringSensors(local, server, ignoring("status_gyroscope")));
    }

    @Test
    public void configsEqualIgnoringSensors_emptyIgnoreSet_matchesJsonEquals() throws JSONException {
        JSONObject local = configWithSensors(sensor("status_wifi", true));
        JSONObject server = configWithSensors(sensor("status_wifi", false));

        assertFalse(StudyUtils.configsEqualIgnoringSensors(local, server, NO_HARDWARE_EXCLUSIONS));
    }

    @Test
    public void configsEqualIgnoringSensors_identicalConfigs_areEqual() throws JSONException {
        JSONObject a = configWithSensors(sensor("status_wifi", true), sensor("status_gyroscope", true));
        JSONObject b = configWithSensors(sensor("status_wifi", true), sensor("status_gyroscope", true));

        assertTrue(StudyUtils.configsEqualIgnoringSensors(a, b, ignoring("status_gyroscope")));
    }

    /**
     * Regression test for the "you're locked" over-notification: disabling editable mode told every
     * participant their edit access changed even when it hadn't. enable_config_update absent means the
     * default (researcher-controlled) state, so enableConfigUpdateChanged() must treat absent the same
     * as false — only a real flip of effective editability returns a (non-null) new value.
     */
    private static JSONObject configWithEditable(Boolean editable) throws JSONException {
        if (editable == null) return configWithSensors(sensor("status_wifi", true));
        return configWithSensors(sensor("status_wifi", true),
                sensor("enable_config_update", editable));
    }

    @Test
    public void enableConfigUpdate_absentToFalse_isNotAChange() throws JSONException {
        // The exact bug: a config that never spelled out enable_config_update, then sets it false.
        // Effective state (locked) is unchanged, so nothing should notify the participant.
        assertNull(StudyUtils.enableConfigUpdateChanged(configWithEditable(null), configWithEditable(false)));
    }

    @Test
    public void enableConfigUpdate_falseToAbsent_isNotAChange() throws JSONException {
        assertNull(StudyUtils.enableConfigUpdateChanged(configWithEditable(false), configWithEditable(null)));
    }

    @Test
    public void enableConfigUpdate_falseToFalse_isNotAChange() throws JSONException {
        assertNull(StudyUtils.enableConfigUpdateChanged(configWithEditable(false), configWithEditable(false)));
    }

    @Test
    public void enableConfigUpdate_trueToTrue_isNotAChange() throws JSONException {
        assertNull(StudyUtils.enableConfigUpdateChanged(configWithEditable(true), configWithEditable(true)));
    }

    @Test
    public void enableConfigUpdate_editableDisabled_reportsFalse() throws JSONException {
        // A genuine true → false flip: the participant should be informed, once.
        assertEquals(Boolean.FALSE,
                StudyUtils.enableConfigUpdateChanged(configWithEditable(true), configWithEditable(false)));
    }

    @Test
    public void enableConfigUpdate_editableEnabled_reportsTrue() throws JSONException {
        assertEquals(Boolean.TRUE,
                StudyUtils.enableConfigUpdateChanged(configWithEditable(false), configWithEditable(true)));
    }

    @Test
    public void enableConfigUpdate_absentToTrue_reportsTrue() throws JSONException {
        // Locked-by-default → editable is a real change and should notify.
        assertEquals(Boolean.TRUE,
                StudyUtils.enableConfigUpdateChanged(configWithEditable(null), configWithEditable(true)));
    }
}
