package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Locks in that the text-valued date/time fields are written in UTC, matching the epoch-millisecond
 * {@code timestamp} column every sensor already uses. Before this, ESM_Date wrote
 * "yyyy-MM-dd Z" and ESM_DateTime wrote "yyyy-MM-dd HH:mm:ss Z" in the device's default timezone, so
 * one study's answers arrived on whatever offset each participant's phone happened to be set to.
 *
 * The Calendars here carry explicit timezones and the default timezone is restored after each test,
 * so the assertions hold on any machine and in any CI region.
 */
public class UtcTimeTest {

    private static final TimeZone DEFAULT_TIMEZONE = TimeZone.getDefault();

    /** Europe/Zurich is UTC+2 in August, so a local wall clock runs ahead of UTC. */
    private static final TimeZone AHEAD_OF_UTC = TimeZone.getTimeZone("Europe/Zurich");

    /** America/New_York is UTC-4 in August, so a local wall clock runs behind UTC. */
    private static final TimeZone BEHIND_UTC = TimeZone.getTimeZone("America/New_York");

    @After
    public void restoreDefaultTimezone() {
        TimeZone.setDefault(DEFAULT_TIMEZONE);
    }

    private static Calendar pickedIn(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar;
    }

    // --- instant(): the shared formatter.

    @Test
    public void instant_formatsEpochMillisAsAnIsoUtcInstant() {
        assertEquals("2026-08-17T14:30:00Z", UtcTime.instant(1786977000000L));
        assertEquals("1970-01-01T00:00:00Z", UtcTime.instant(0L));
    }

    @Test
    public void instant_ignoresTheDeviceTimezone() {
        TimeZone.setDefault(AHEAD_OF_UTC);
        String ahead = UtcTime.instant(1786977000000L);

        TimeZone.setDefault(BEHIND_UTC);
        String behind = UtcTime.instant(1786977000000L);

        assertEquals("2026-08-17T14:30:00Z", ahead);
        assertEquals(ahead, behind);
    }

    // --- pickedDateTime(): ESM_DateTime. The participant picks a local wall clock; the recorded value
    // is the absolute instant that names, which is what makes it convertible to any timezone later.

    @Test
    public void pickedDateTime_convertsALocalWallClockToItsUtcInstant() {
        assertEquals("2026-08-17T12:30:00Z",
                UtcTime.pickedDateTime(pickedIn(AHEAD_OF_UTC, 2026, Calendar.AUGUST, 17, 14, 30)));
    }

    @Test
    public void pickedDateTime_rollsTheDayWhenTheInstantFallsOnTheNextUtcDay() {
        assertEquals("2026-08-18T02:00:00Z",
                UtcTime.pickedDateTime(pickedIn(BEHIND_UTC, 2026, Calendar.AUGUST, 17, 22, 0)));
    }

    @Test
    public void pickedDateTime_dropsTheSecondsLeftOverFromWhenTheDialogOpened() {
        Calendar picked = pickedIn(AHEAD_OF_UTC, 2026, Calendar.AUGUST, 17, 14, 30);
        picked.set(Calendar.SECOND, 47); // the pickers only offer minute resolution -- these came
        picked.set(Calendar.MILLISECOND, 321); // from Calendar.getInstance() at dialog creation

        assertEquals("2026-08-17T12:30:00Z", UtcTime.pickedDateTime(picked));
    }

    // --- pickedDate(): ESM_Date. A date-only answer names a day, not an instant, so the picked day is
    // anchored at midnight UTC. Converting the underlying instant instead would report the
    // neighbouring day whenever the participant's local offset crosses midnight.

    @Test
    public void pickedDate_keepsThePickedDayForAParticipantAheadOfUtc() {
        assertEquals("2026-08-17T00:00:00Z",
                UtcTime.pickedDate(pickedIn(AHEAD_OF_UTC, 2026, Calendar.AUGUST, 17, 0, 30)));
    }

    @Test
    public void pickedDate_keepsThePickedDayForAParticipantBehindUtc() {
        assertEquals("2026-08-17T00:00:00Z",
                UtcTime.pickedDate(pickedIn(BEHIND_UTC, 2026, Calendar.AUGUST, 17, 23, 30)));
    }

    @Test
    public void pickedDate_ignoresTheTimeOfDayEntirely() {
        assertEquals(
                UtcTime.pickedDate(pickedIn(AHEAD_OF_UTC, 2026, Calendar.AUGUST, 17, 0, 0)),
                UtcTime.pickedDate(pickedIn(AHEAD_OF_UTC, 2026, Calendar.AUGUST, 17, 23, 59)));
    }

    // --- fileStamp(): ScreenShot filenames, which cannot carry a colon.

    @Test
    public void fileStamp_isTheSameInstantWithoutFilesystemReservedCharacters() {
        String stamp = UtcTime.fileStamp(1786977000000L);

        assertEquals("20260817T143000Z", stamp);
        assertFalse(stamp.contains(":"));
    }
}
