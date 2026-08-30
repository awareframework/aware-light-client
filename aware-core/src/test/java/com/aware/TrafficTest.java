package com.aware;

import android.net.TrafficStats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TrafficTest {

    @Test
    public void counterDelta_returnsIncreaseAndRejectsCounterReset() {
        assertEquals(25, Traffic.counterDelta(125, 100));
        assertEquals(0, Traffic.counterDelta(90, 100));
    }

    @Test
    public void nonMobileCounter_subtractsMobileTraffic() {
        assertEquals(700, Traffic.nonMobileCounter(1_000, 300));
    }

    @Test
    public void nonMobileCounter_treatsUnsupportedMobileCounterAsZero() {
        assertEquals(1_000, Traffic.nonMobileCounter(1_000, TrafficStats.UNSUPPORTED));
    }
}
