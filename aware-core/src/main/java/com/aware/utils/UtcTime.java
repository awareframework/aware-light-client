package com.aware.utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * UTC formatting for the few places that record a date or a time as text rather than as epoch
 * milliseconds. Every sensor's {@code timestamp} column holds System.currentTimeMillis(), which is
 * an absolute UTC-anchored instant, so these helpers keep the text-valued fields on the same footing
 * and a researcher reads one timezone across the whole dataset. Localising back to what the
 * participant saw on their screen is done with the Timezone sensor, which is the only sensor that
 * records a timezone-dependent value.
 * <p>
 * SimpleDateFormat rather than java.time because minSdkVersion is 24 and core library desugaring is
 * not enabled.
 */
public final class UtcTime {

    /**
     * ISO-8601 instant in UTC, e.g. {@code 2026-08-17T14:30:00Z}.
     */
    public static final String ISO_INSTANT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    /**
     * ISO-8601 basic format, for filenames where {@code :} is not portable across filesystems.
     */
    public static final String ISO_INSTANT_BASIC = "yyyyMMdd'T'HHmmss'Z'";

    private UtcTime() {
    }

    private static SimpleDateFormat utcFormat(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    /**
     * Formats an epoch-millisecond timestamp as a UTC ISO-8601 instant.
     */
    public static String instant(long epochMillis) {
        return utcFormat(ISO_INSTANT).format(new Date(epochMillis));
    }

    /**
     * Formats an epoch-millisecond timestamp for use inside a filename.
     */
    public static String fileStamp(long epochMillis) {
        return utcFormat(ISO_INSTANT_BASIC).format(new Date(epochMillis));
    }

    /**
     * Converts a date-and-time the participant picked -- held in a Calendar on the device's local
     * timezone -- into the UTC instant it denotes. Seconds and milliseconds are dropped because the
     * pickers only offer minute resolution, so whatever sits in those fields came from the moment
     * the dialog opened rather than from the participant.
     */
    public static String pickedDateTime(Calendar picked) {
        Calendar instant = (Calendar) picked.clone();
        instant.set(Calendar.SECOND, 0);
        instant.set(Calendar.MILLISECOND, 0);
        return instant(instant.getTimeInMillis());
    }

    /**
     * Converts a date-only pick into midnight UTC on the same calendar day.
     * <p>
     * A date-only answer names a day, not an instant, so the year/month/day fields are re-anchored in
     * UTC instead of being converted from local time. Converting would move the answer onto the
     * neighbouring day for any participant whose local offset crosses midnight -- the picked day is
     * the datum here, and it survives a round trip to any timezone only when it sits at 00:00:00Z.
     */
    public static String pickedDate(Calendar picked) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH));
        return instant(utc.getTimeInMillis());
    }
}
