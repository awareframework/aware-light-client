package com.aware.utils;

/**
 * Where the device UUID is read from, and which copy needs repairing, kept as pure logic so it can be
 * verified without a Context.
 * <p>
 * The UUID lives in the aware_settings table, which Aware.reset() clears wholesale. A sensor thread
 * inserting during that gap read an empty device_id and stamped a row that no participant can be
 * matched to, and Aware.onCreate() seeing the same empty table minted a second UUID, splitting one
 * participant across two identities. A copy in SharedPreferences survives a settings wipe, so the
 * value can be recovered instead of re-invented.
 * <p>
 * This deliberately never generates a UUID. Minting one stays in Aware.onCreate(), which runs once
 * per process start before any sensor does; letting the ~180 read sites mint one would let two
 * processes race to invent different identities for the same install.
 */
public final class DeviceId {

    /**
     * Its own SharedPreferences file rather than the "com.aware.phone" one, which
     * PreferenceManager.setDefaultValues() populates from aware_preferences.xml -- the UUID has no
     * XML default and must not be reachable by anything that re-applies defaults.
     */
    public static final String MIRROR_PREFERENCES = "aware_device_identity";

    public static final String MIRROR_KEY = "device_id";

    private DeviceId() {
    }

    /**
     * Which copies of the UUID disagree, and what to do about it.
     */
    public static final class Resolution {

        private final String deviceId;
        private final boolean healSettings;
        private final boolean healMirror;

        private Resolution(String deviceId, boolean healSettings, boolean healMirror) {
            this.deviceId = deviceId;
            this.healSettings = healSettings;
            this.healMirror = healMirror;
        }

        /**
         * The UUID to stamp on rows, or empty when this install has no identity yet.
         */
        public String getDeviceId() {
            return deviceId;
        }

        /**
         * True when the settings table lost the UUID and the mirror still has it.
         */
        public boolean shouldHealSettings() {
            return healSettings;
        }

        /**
         * True when the mirror is missing or stale -- including on the first run of this build, where
         * the UUID predates the mirror existing at all.
         */
        public boolean shouldHealMirror() {
            return healMirror;
        }

        /**
         * False only when neither copy holds a UUID, which means no row should be stamped yet.
         */
        public boolean isResolved() {
            return !deviceId.isEmpty();
        }
    }

    /**
     * Picks the UUID to use from the two stored copies. The settings table wins when both hold a
     * value: it is what every other read site and the server already see, so healing towards it keeps
     * a single install on a single identity even if the mirror somehow diverged.
     */
    public static Resolution resolve(String fromSettings, String fromMirror) {
        String settings = trimToEmpty(fromSettings);
        String mirror = trimToEmpty(fromMirror);

        if (!settings.isEmpty()) return new Resolution(settings, false, !settings.equals(mirror));
        if (!mirror.isEmpty()) return new Resolution(mirror, true, false);

        return new Resolution("", false, false);
    }

    /**
     * Treats whitespace as absent: a blank UUID orphans a row exactly as an empty one does, and the
     * column's declared default is the empty string, so blank is what a lost setting reads back as.
     */
    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
