package com.aware.phone.ui.prefs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrequencyValueConverterTest {

    @Test
    public void samplingRateHzToPeriodUs_convertsWholeAndDecimalRates() {
        assertEquals("20000", FrequencyValueConverter.samplingRateHzToPeriodUs("50"));
        assertEquals("400000", FrequencyValueConverter.samplingRateHzToPeriodUs("2.5"));
    }

    @Test
    public void periodUsToSamplingRateHz_formatsForResearcherFacingDisplay() {
        assertEquals("50", FrequencyValueConverter.periodUsToSamplingRateHz("20000"));
        assertEquals("2.5", FrequencyValueConverter.periodUsToSamplingRateHz("400000"));
    }

    @Test
    public void samplingRateHzDescription_explainsRateAndEquivalentInterval() {
        assertEquals(
                "20 Hz = 20 samples/second = one sample every 50 ms (1/20 second)",
                FrequencyValueConverter.samplingRateHzDescription("20"));
        assertEquals("every 400 ms",
                FrequencyValueConverter.samplingRateHzInterval("2.5"));
        assertEquals("every 1 second",
                FrequencyValueConverter.samplingRateHzInterval("1"));
        assertEquals("every 2 seconds",
                FrequencyValueConverter.samplingRateHzInterval("0.5"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void samplingRateHzToPeriodUs_rejectsZero() {
        FrequencyValueConverter.samplingRateHzToPeriodUs("0");
    }
}
