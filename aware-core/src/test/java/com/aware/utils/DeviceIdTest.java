package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks in which copy of the device UUID wins and which one gets repaired.
 *
 * Two failure modes drove this. Aware.reset() used to delete every row of aware_settings before
 * writing device_id back, so a sensor inserting on another thread during that gap stamped a row with
 * the empty string -- a row on the server belonging to no participant. And Aware.onCreate() reading
 * that same empty table could not tell a lost row from a first run, so it minted a second UUID and
 * split one participant across two identities, which is unrepairable after upload.
 *
 * The mirror in SharedPreferences survives a settings wipe, so resolve() can recover the value instead
 * of the caller inventing one. Nothing here generates a UUID -- see DeviceId's class comment.
 */
public class DeviceIdTest {

    private static final String UUID = "0e5a1f6c-3d2b-4a71-9c8e-5f3b7d914a20";
    private static final String OTHER_UUID = "b71c4e02-9a8f-4d63-8e15-2c7a0b6f3d94";

    // --- The settled case: both copies agree, nothing to do.

    @Test
    public void bothCopiesAgree_usesTheUuidAndRepairsNothing() {
        DeviceId.Resolution resolution = DeviceId.resolve(UUID, UUID);

        assertEquals(UUID, resolution.getDeviceId());
        assertTrue(resolution.isResolved());
        assertFalse(resolution.shouldHealSettings());
        assertFalse(resolution.shouldHealMirror());
    }

    // --- Settings has it, the mirror does not: the upgrade path. Existing installs generated their
    // UUID long before the mirror existed, so the first read has to populate it -- otherwise the
    // recovery below has nothing to recover from.

    @Test
    public void mirrorMissing_usesSettingsAndBacksItUp() {
        DeviceId.Resolution resolution = DeviceId.resolve(UUID, "");

        assertEquals(UUID, resolution.getDeviceId());
        assertTrue(resolution.shouldHealMirror());
        assertFalse(resolution.shouldHealSettings());
    }

    @Test
    public void mirrorDiverged_settingsWinsBecauseThatIsWhatTheServerAlreadySees() {
        DeviceId.Resolution resolution = DeviceId.resolve(UUID, OTHER_UUID);

        assertEquals(UUID, resolution.getDeviceId());
        assertTrue(resolution.shouldHealMirror());
        assertFalse(resolution.shouldHealSettings());
    }

    // --- The reset() window: settings lost the row, the mirror still has it.

    @Test
    public void settingsWiped_recoversFromTheMirrorAndRestoresTheSetting() {
        DeviceId.Resolution resolution = DeviceId.resolve("", UUID);

        assertEquals(UUID, resolution.getDeviceId());
        assertTrue(resolution.isResolved());
        assertTrue(resolution.shouldHealSettings());
        assertFalse(resolution.shouldHealMirror());
    }

    @Test
    public void settingsNull_isTreatedAsWipedRatherThanCrashing() {
        assertEquals(UUID, DeviceId.resolve(null, UUID).getDeviceId());
    }

    // --- Blank is absent. The column's declared default is '', and a whitespace-only value orphans a
    // row exactly as an empty one does.

    @Test
    public void blankSettingsFallsBackToTheMirror() {
        DeviceId.Resolution resolution = DeviceId.resolve("   ", UUID);

        assertEquals(UUID, resolution.getDeviceId());
        assertTrue(resolution.shouldHealSettings());
    }

    @Test
    public void surroundingWhitespaceIsNotTreatedAsADifferentUuid() {
        DeviceId.Resolution resolution = DeviceId.resolve(" " + UUID + " ", UUID);

        assertEquals(UUID, resolution.getDeviceId());
        assertFalse("trimmed value equals the mirror, so the mirror is already correct",
                resolution.shouldHealMirror());
    }

    // --- Neither copy has one: a genuine first run. Reporting unresolved is what keeps the decision to
    // mint a UUID in Aware.onCreate() instead of spread across the read sites.

    @Test
    public void neitherCopyHasAUuid_reportsUnresolvedAndInventsNothing() {
        DeviceId.Resolution resolution = DeviceId.resolve("", "");

        assertEquals("", resolution.getDeviceId());
        assertFalse(resolution.isResolved());
        assertFalse(resolution.shouldHealSettings());
        assertFalse(resolution.shouldHealMirror());
    }

    @Test
    public void bothNull_reportsUnresolved() {
        assertFalse(DeviceId.resolve(null, null).isResolved());
    }

    @Test
    public void trimToEmpty_normalisesNullAndWhitespace() {
        assertEquals("", DeviceId.trimToEmpty(null));
        assertEquals("", DeviceId.trimToEmpty("  "));
        assertEquals(UUID, DeviceId.trimToEmpty(" " + UUID + "\n"));
    }
}
