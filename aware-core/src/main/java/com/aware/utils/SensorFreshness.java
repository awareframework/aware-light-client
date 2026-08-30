package com.aware.utils;

/**
 * Shared policy for deciding how long regularly sampled sensor data remains "fresh".
 *
 * The grace window is three configured sampling intervals, never less than two minutes and never
 * more than one day. The floor avoids flickering during normal Android scheduling/batching delays;
 * the cap prevents a bad or extreme setting from making a stopped sensor look healthy forever.
 * Event-driven sensors do not use this policy.
 */
public final class SensorFreshness {

    public enum Unit {
        MICROSECONDS,
        MILLISECONDS,
        SECONDS,
        MINUTES
    }

    public static final long MIN_WINDOW_MS = 2L * 60L * 1000L;
    public static final long MAX_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long INTERVAL_MULTIPLIER = 3L;

    private SensorFreshness() {}

    public static long windowMs(String configuredValue, long defaultValue, Unit unit) {
        long value = defaultValue;
        try {
            if (configuredValue != null && !configuredValue.trim().isEmpty()) {
                value = Long.parseLong(configuredValue.trim());
            }
        } catch (NumberFormatException ignored) {
            value = defaultValue;
        }

        if (value < 0) value = defaultValue;
        long intervalMs = toMilliseconds(value, unit);
        long candidate;
        if (intervalMs > Long.MAX_VALUE / INTERVAL_MULTIPLIER) {
            candidate = Long.MAX_VALUE;
        } else {
            candidate = intervalMs * INTERVAL_MULTIPLIER;
        }
        return Math.max(MIN_WINDOW_MS, Math.min(candidate, MAX_WINDOW_MS));
    }

    public static boolean isFresh(long nowMs, long lastDataMs, long windowMs) {
        return lastDataMs > 0 && nowMs >= lastDataMs && nowMs - lastDataMs <= windowMs;
    }

    private static long toMilliseconds(long value, Unit unit) {
        switch (unit) {
            case MICROSECONDS:
                return value / 1000L;
            case SECONDS:
                return safeMultiply(value, 1000L);
            case MINUTES:
                return safeMultiply(value, 60L * 1000L);
            case MILLISECONDS:
            default:
                return value;
        }
    }

    private static long safeMultiply(long value, long multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
