package com.aware.phone.ui.prefs;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts researcher-facing sampling rates to Android's stored sampling-period representation.
 */
final class FrequencyValueConverter {

    private static final BigDecimal MICROSECONDS_PER_SECOND = new BigDecimal("1000000");

    private FrequencyValueConverter() {
    }

    static String samplingRateHzToPeriodUs(String samplingRateHz) {
        BigDecimal rate = new BigDecimal(samplingRateHz);
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("Sampling rate must be greater than zero");
        }

        BigDecimal period = MICROSECONDS_PER_SECOND.divide(rate, 0, RoundingMode.HALF_UP);
        if (period.signum() <= 0 || period.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Sampling rate is outside the supported range");
        }
        return period.toPlainString();
    }

    static String periodUsToSamplingRateHz(String samplingPeriodUs) {
        BigDecimal period = new BigDecimal(samplingPeriodUs);
        if (period.signum() <= 0) {
            throw new IllegalArgumentException("Sampling period must be greater than zero");
        }
        return MICROSECONDS_PER_SECOND.divide(period, 3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    static String samplingRateHzDescription(String samplingRateHz) {
        String normalizedRate = new BigDecimal(samplingRateHz).stripTrailingZeros().toPlainString();
        String periodUs = samplingRateHzToPeriodUs(samplingRateHz);
        return normalizedRate + " Hz = " + normalizedRate
                + " samples/second = one sample every " + readablePeriod(periodUs)
                + " (1/" + normalizedRate + " second)";
    }

    static String samplingRateHzInterval(String samplingRateHz) {
        return "every " + readablePeriod(samplingRateHzToPeriodUs(samplingRateHz));
    }

    private static String readablePeriod(String samplingPeriodUs) {
        BigDecimal microseconds = new BigDecimal(samplingPeriodUs);
        if (microseconds.compareTo(new BigDecimal("1000")) < 0) {
            return format(microseconds) + " µs";
        }
        if (microseconds.compareTo(MICROSECONDS_PER_SECOND) < 0) {
            return format(microseconds.divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP))
                    + " ms";
        }
        String seconds = format(microseconds.divide(
                MICROSECONDS_PER_SECOND, 3, RoundingMode.HALF_UP));
        return seconds + ("1".equals(seconds) ? " second" : " seconds");
    }

    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
