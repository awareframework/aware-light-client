package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for SensorDiagnostics.reasonGivenState() — the pure reason-classification core behind
 * the "sensor_status" lines written to aware_log so a researcher can see, through the sync that
 * already exists, why a given sensor isn't collecting on a participant's phone: no hardware,
 * missing permission, accessibility off, or location services off. Uses "status_wifi" (permission +
 * location services gated) and "status_applications" (accessibility gated) as representative
 * settings, and a made-up key with no Gate entry to cover the "hardware-only" sensors.
 */
public class SensorDiagnosticsTest {

    @Test
    public void noHardware_reportedRegardlessOfOtherState() {
        // Hardware is checked first and short-circuits everything else — a sensor with no hardware
        // is excluded no matter what its permission/accessibility/location state happens to be.
        String reason = SensorDiagnostics.reasonGivenState(
                "status_light", false, "android.permission.ACCESS_FINE_LOCATION", false, false);
        assertEquals("No such sensor hardware on this device", reason);
    }

    @Test
    public void unGatedSetting_withHardware_isNotExcluded() {
        // "status_battery" (or any setting with no Gate entry, e.g. this made-up one) isn't gated by
        // anything beyond hardware — with hardware available, nothing should exclude it.
        String reason = SensorDiagnostics.reasonGivenState("status_battery", true, null, false, false);
        assertEquals("", reason);
    }

    @Test
    public void missingPermission_isReportedWithShortName() {
        String reason = SensorDiagnostics.reasonGivenState(
                "status_wifi", true, "android.permission.ACCESS_COARSE_LOCATION", true, true);
        assertEquals("Missing permission: ACCESS_COARSE_LOCATION", reason);
    }

    @Test
    public void wifi_locationServicesOff_reportedBeforePermission() {
        // status_wifi needs both the permission and the OS Location toggle; location services being
        // off is checked before the permission, since a missing permission is passed as non-null
        // here too — this locks in that ordering rather than leaving it to accidentally match.
        String reason = SensorDiagnostics.reasonGivenState(
                "status_wifi", true, "android.permission.ACCESS_COARSE_LOCATION", true, false);
        assertEquals("Location services are off", reason);
    }

    @Test
    public void wifi_allSatisfied_isNotExcluded() {
        String reason = SensorDiagnostics.reasonGivenState("status_wifi", true, null, true, true);
        assertEquals("", reason);
    }

    @Test
    public void accessibilityGatedSetting_off_isReported() {
        String reason = SensorDiagnostics.reasonGivenState("status_applications", true, null, false, false);
        assertEquals("Accessibility service is off", reason);
    }

    @Test
    public void accessibilityGatedSetting_on_isNotExcluded() {
        String reason = SensorDiagnostics.reasonGivenState("status_applications", true, null, true, false);
        assertEquals("", reason);
    }

    @Test
    public void reasonIsEmpty_meansNotExcluded() {
        // Sanity check tying the reason string directly to how logSensorStatus() derives "excluded"
        // (excluded = !reason.isEmpty()) — an empty reason must mean "not excluded", not just
        // "no reason text".
        String reason = SensorDiagnostics.reasonGivenState("status_battery", true, null, false, false);
        assertTrue(reason.isEmpty());
    }

    @Test
    public void sampledSensor_reportsCollectingOnlyInsideFrequencyWindow() {
        assertEquals("collecting", SensorDiagnostics.stateGiven(
                "", true, false, 1_000_000L, 880_000L, 120_000L));
        assertEquals("delayed", SensorDiagnostics.stateGiven(
                "", true, false, 1_000_001L, 880_000L, 120_000L));
    }

    @Test
    public void eventDrivenSensor_waitsWithoutBeingDelayed() {
        assertEquals("waiting_for_event", SensorDiagnostics.stateGiven(
                "", true, true, 1_000_000L, 0L, 0L));
    }

    @Test
    public void unavailableAndDisabledHaveExplicitStates() {
        assertEquals("unavailable", SensorDiagnostics.stateGiven(
                "No such sensor hardware on this device", true, false,
                1_000_000L, 0L, 120_000L));
        assertEquals("disabled", SensorDiagnostics.stateGiven(
                "", false, false, 1_000_000L, 999_999L, 120_000L));
        assertEquals("disabled", SensorDiagnostics.stateGiven(
                "Missing permission: ACCESS_FINE_LOCATION", false, false,
                1_000_000L, 999_999L, 120_000L));
    }

    // --- When a sensor last produced data ---
    //
    // Two records disagree once data has been uploaded: the local table is emptied by
    // webservice_remove_data, while the upload bookmark keeps the delivered timestamp. Reading only
    // the table reports a working sensor as never having collected, in the participant's status text
    // and in the diagnostics uploaded to the researcher.

    @Test
    public void deliveredDataCountsWhenTheLocalRowsAreGone() {
        // The bug: uploaded and cleaned up, so nothing local — but the database holds data up to
        // 999_999, so the sensor plainly has collected.
        assertEquals(999_999L, SensorDiagnostics.observedLatest(0L, 999_999L));
    }

    @Test
    public void aWorkingSensorIsNeverReportedAsWaitingForItsFirstSample() {
        // Ties the reconciliation to the decision it feeds: 0 means waiting_first_sample, so any
        // evidence of delivery has to survive into that call.
        long observed = SensorDiagnostics.observedLatest(0L, 999_999L);
        assertEquals("collecting", SensorDiagnostics.stateGiven(
                "", true, false, 1_000_000L, observed, 120_000L));
    }

    @Test
    public void localRowsCountWhenNothingHasBeenDeliveredYet() {
        // A fresh enrolment, or an upload outage: the local table is the only record.
        assertEquals(999_999L, SensorDiagnostics.observedLatest(999_999L, 0L));
    }

    @Test
    public void theLaterOfTheTwoWins() {
        // Local rows are newer than the bookmark whenever data has arrived since the last upload.
        assertEquals(999_999L, SensorDiagnostics.observedLatest(999_999L, 500_000L));
        assertEquals(999_999L, SensorDiagnostics.observedLatest(500_000L, 999_999L));
    }

    @Test
    public void noDataAnywhereIsStillNever() {
        // The genuine case must survive: a sensor that really has not collected reports 0.
        assertEquals(0L, SensorDiagnostics.observedLatest(0L, 0L));
        assertEquals("waiting_first_sample", SensorDiagnostics.stateGiven(
                "", true, false, 1_000_000L, 0L, 120_000L));
    }
}
