package com.aware.phone.ui.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure status-text helpers in SensorCollection — the strings shown both in the
 * locked-mode status dialog and in the editable-mode status row. They pin down each state: collecting
 * vs not, the reason line, last-data and delivered-through (a real time, "never", or "nothing yet"),
 * and the optional "what to do" hint.
 */
public class SensorStatusTextTest {

    @Test
    public void headline_reflectsCollectingState() {
        assertEquals("●  Collecting data", SensorCollection.statusHeadline(true));
        assertEquals("○  Not collecting", SensorCollection.statusHeadline(false));
    }

    @Test
    public void detail_withFixHint_includesWhatToDo() {
        String detail = SensorCollection.statusDetail(
                "Accessibility service is off",
                "never",
                null,
                "Enable AWARE under Settings > Accessibility");
        assertEquals(
                "Accessibility service is off"
                        + "\n\nLast data: never"
                        + "\nDelivered: nothing yet"
                        + "\n\nWhat to do: Enable AWARE under Settings > Accessibility",
                detail);
    }

    @Test
    public void detail_withoutFixHint_omitsWhatToDo() {
        String detail = SensorCollection.statusDetail(
                "Collecting data", "5 minutes ago", "8 minutes ago", null);
        assertEquals(
                "Collecting data\n\nLast data: 5 minutes ago\nDelivered: up to 8 minutes ago",
                detail);
    }

    @Test
    public void detail_neverHasData_readsNever() {
        String detail = SensorCollection.statusDetail(
                "Waiting for first sample", "never", null, null);
        assertEquals(
                "Waiting for first sample\n\nLast data: never\nDelivered: nothing yet", detail);
    }

    @Test
    public void detail_nothingDelivered_saysSoRatherThanShowingNoLine() {
        // A sensor collecting normally while nothing has reached the server is the case this line
        // exists for, so it has to be stated rather than left blank.
        String detail = SensorCollection.statusDetail(
                "Collecting data", "2 minutes ago", null, null);
        assertTrue(detail.contains("Last data: 2 minutes ago"));
        assertTrue(detail.contains("Delivered: nothing yet"));
    }

    @Test
    public void detail_deliveredIsPhrasedAsAPointReachedNotAnEvent() {
        // The bookmark holds the timestamp of the last delivered row, not the time of the upload, so
        // the wording must not read as "the upload happened 8 minutes ago".
        String detail = SensorCollection.statusDetail(
                "Collecting data", "2 minutes ago", "8 minutes ago", null);
        assertTrue(detail.contains("Delivered: up to 8 minutes ago"));
    }

    @Test
    public void summary_isHeadlineThenDetail() {
        String summary = SensorCollection.statusSummary(
                false, "Missing permission: ACCESS_FINE_LOCATION", "never", null,
                "Grant it on the next screen");
        assertEquals(
                "○  Not collecting"
                        + "\n\nMissing permission: ACCESS_FINE_LOCATION"
                        + "\n\nLast data: never"
                        + "\nDelivered: nothing yet"
                        + "\n\nWhat to do: Grant it on the next screen",
                summary);
    }

    @Test
    public void summary_collecting_matchesHeadlineAndDetail() {
        boolean collecting = true;
        String reason = "Collecting data";
        CharSequence lastData = "2 minutes ago";
        CharSequence lastDelivered = "4 minutes ago";
        String summary = SensorCollection.statusSummary(
                collecting, reason, lastData, lastDelivered, null);
        assertEquals(
                SensorCollection.statusHeadline(collecting) + "\n\n"
                        + SensorCollection.statusDetail(reason, lastData, lastDelivered, null),
                summary);
    }

    @Test
    public void eventDrivenStatus_isEnabledRatherThanFalselyNotCollecting() {
        SensorCollection.Status status = new SensorCollection.Status(
                true,
                true,
                0,
                "This sensor records data when an event occurs",
                null);
        assertEquals("●  Enabled — waiting for an event", SensorCollection.statusHeadline(status));
    }
}
