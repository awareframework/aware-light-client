package com.aware;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Regression test for the crash class fixed across every sensor's onStartCommand(): a study
 * setting (frequency/threshold) parsed directly with Integer.parseInt/Long.parseLong/Double.parseDouble
 * throws NumberFormatException and crashes the sensor service if the setting is empty (e.g.
 * transiently mid-config-apply) or malformed (e.g. a bad study config). Aware.getSettingAsInt/
 * Long/Double wrap the parse with a fallback default instead; these tests cover the Context-free
 * parse core (parseIntOrDefault/parseLongOrDefault/parseDoubleOrDefault) directly, so they don't
 * need a real or mocked Android Context.
 */
public class AwareSettingsParsingTest {

    @Test
    public void validInt_isParsed() {
        assertEquals(42, Aware.parseIntOrDefault("42", 200000));
    }

    @Test
    public void emptyInt_fallsBackToDefault() {
        assertEquals(200000, Aware.parseIntOrDefault("", 200000));
    }

    @Test
    public void malformedInt_fallsBackToDefault() {
        assertEquals(200000, Aware.parseIntOrDefault("not_a_number", 200000));
    }

    @Test
    public void nullInt_fallsBackToDefault() {
        assertEquals(200000, Aware.parseIntOrDefault(null, 200000));
    }

    @Test
    public void validLong_isParsed() {
        assertEquals(60L, Aware.parseLongOrDefault("60", 30L));
    }

    @Test
    public void emptyLong_fallsBackToDefault() {
        assertEquals(30L, Aware.parseLongOrDefault("", 30L));
    }

    @Test
    public void malformedLong_fallsBackToDefault() {
        assertEquals(30L, Aware.parseLongOrDefault("not_a_number", 30L));
    }

    @Test
    public void nullLong_fallsBackToDefault() {
        assertEquals(30L, Aware.parseLongOrDefault(null, 30L));
    }

    @Test
    public void validDouble_isParsed() {
        assertEquals(1.5, Aware.parseDoubleOrDefault("1.5", 0.0), 0.0);
    }

    @Test
    public void emptyDouble_fallsBackToDefault() {
        assertEquals(0.0, Aware.parseDoubleOrDefault("", 0.0), 0.0);
    }

    @Test
    public void malformedDouble_fallsBackToDefault() {
        assertEquals(0.0, Aware.parseDoubleOrDefault("not_a_number", 0.0), 0.0);
    }

    @Test
    public void nullDouble_fallsBackToDefault() {
        // Double.parseDouble(null) throws NullPointerException rather than NumberFormatException —
        // this is the case that would have slipped through a catch (NumberFormatException e) only.
        assertEquals(0.0, Aware.parseDoubleOrDefault(null, 0.0), 0.0);
    }
}
