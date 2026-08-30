package com.aware.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for PermissionSequence — the pure core of PermissionsHandler's request flow. They pin
 * down the two behaviours a participant actually sees: enabling one permission of a group also
 * satisfies its sibling (so the sibling is never prompted again), and every permission is asked for at
 * most once and in order; and the guard behind the "Allow ..." dialog loop bug — a declined permission
 * must never ask the caller service to restart, since that just relaunches the prompt forever.
 *
 * The OS is faked with a mutable set of granted permissions. Granting a group (e.g. FINE together
 * with COARSE) is modelled by adding both to that set at once, exactly as the platform reports it.</p>
 */
public class PermissionSequenceTest {

    private static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    private static final String A = "perm.A";
    private static final String B = "perm.B";
    private static final String C = "perm.C";

    /** A stand-in for the OS grant state: whatever has been "granted" reads back as granted. */
    private static class FakeGrants implements PermissionSequence.GrantChecker {
        private final Set<String> granted = new HashSet<>();

        FakeGrants(String... initiallyGranted) {
            granted.addAll(Arrays.asList(initiallyGranted));
        }

        void grant(String... permissions) {
            granted.addAll(Arrays.asList(permissions));
        }

        @Override
        public boolean isGranted(String permission) {
            return granted.contains(permission);
        }
    }

    @Test
    public void grantingLocation_skipsItsAlreadyGrantedSibling() {
        // Location is one runtime group: the OS grants COARSE the moment FINE is allowed. After the
        // participant allows the first location permission the sibling must never be prompted again.
        FakeGrants grants = new FakeGrants();
        PermissionSequence seq = new PermissionSequence(Arrays.asList(FINE, COARSE), grants);

        assertEquals(FINE, seq.nextToRequest());
        grants.grant(FINE, COARSE); // OS grants the whole group at once
        seq.onResult(true);

        assertNull("the sibling COARSE is already granted, so it is not prompted", seq.nextToRequest());
        assertTrue(seq.isDone());
        assertFalse(seq.anyDenied());
        assertTrue(seq.shouldRestartService());
    }

    @Test
    public void eachPermissionPromptedExactlyOnceInOrder() {
        // "Every requested permission is called reasonably": A, then B, then C, each once, then done —
        // no repeats and no permission left un-prompted.
        FakeGrants grants = new FakeGrants();
        PermissionSequence seq = new PermissionSequence(Arrays.asList(A, B, C), grants);

        assertEquals(A, seq.nextToRequest());
        grants.grant(A);
        seq.onResult(true);

        assertEquals(B, seq.nextToRequest());
        grants.grant(B);
        seq.onResult(true);

        assertEquals(C, seq.nextToRequest());
        grants.grant(C);
        seq.onResult(true);

        assertNull(seq.nextToRequest());
        assertTrue(seq.shouldRestartService());
    }

    @Test
    public void alreadyGrantedPermissions_areSkipped() {
        // A and C are already held (e.g. granted for an earlier sensor); only the missing B is prompted.
        FakeGrants grants = new FakeGrants(A, C);
        PermissionSequence seq = new PermissionSequence(Arrays.asList(A, B, C), grants);

        assertEquals(B, seq.nextToRequest());
        grants.grant(B);
        seq.onResult(true);

        assertNull(seq.nextToRequest());
        assertTrue(seq.shouldRestartService());
    }

    @Test
    public void allPermissionsAlreadyGranted_restartsWithoutPrompting() {
        // The "permissions are already fine, just (re)start the sensor" path: nothing to ask, and the
        // service is allowed to start.
        FakeGrants grants = new FakeGrants(A, B);
        PermissionSequence seq = new PermissionSequence(Arrays.asList(A, B), grants);

        assertNull(seq.nextToRequest());
        assertTrue(seq.isDone());
        assertFalse(seq.anyDenied());
        assertTrue(seq.shouldRestartService());
    }

    @Test
    public void denyingRequiredPermission_doesNotRestartService() {
        // The loop bug: restarting the service after a denial makes it find the permission still missing
        // and relaunch the handler — an unbreakable "Allow ..." dialog. A denial must leave it alone.
        FakeGrants grants = new FakeGrants();
        PermissionSequence seq = new PermissionSequence(Collections.singletonList(COARSE), grants);

        assertEquals(COARSE, seq.nextToRequest());
        seq.onResult(false); // denied on the system dialog

        assertNull(seq.nextToRequest());
        assertTrue(seq.anyDenied());
        assertFalse(seq.shouldRestartService());
    }

    @Test
    public void cancellingPermissionFlow_declinesAllRemainingWithoutRestart() {
        PermissionSequence seq = new PermissionSequence(
                Arrays.asList("location", "microphone"),
                permission -> false);

        assertEquals("location", seq.nextToRequest());
        seq.cancelRemaining();

        assertTrue(seq.isDone());
        assertFalse(seq.shouldRestartService());
    }

    @Test
    public void skippingWithNotNow_countsAsDenied_andAdvances() {
        // "Not now" on the rationale skips that permission but still moves the sequence forward, and it
        // counts as a denial so the service is not restarted.
        FakeGrants grants = new FakeGrants();
        PermissionSequence seq = new PermissionSequence(Arrays.asList(A, B), grants);

        assertEquals(A, seq.nextToRequest());
        seq.onSkipped();

        assertEquals(B, seq.nextToRequest());
        grants.grant(B);
        seq.onResult(true);

        assertNull(seq.nextToRequest());
        assertTrue(seq.anyDenied());
        assertFalse(seq.shouldRestartService());
    }

    @Test
    public void grantingSomeButDenyingOthers_doesNotRestart() {
        // A granted, B denied: because a required permission is still missing, the service is not asked
        // to restart.
        FakeGrants grants = new FakeGrants();
        PermissionSequence seq = new PermissionSequence(Arrays.asList(A, B), grants);

        assertEquals(A, seq.nextToRequest());
        grants.grant(A);
        seq.onResult(true);

        assertEquals(B, seq.nextToRequest());
        seq.onResult(false);

        assertNull(seq.nextToRequest());
        assertFalse(seq.shouldRestartService());
    }

    @Test
    public void nextToRequest_isStableUntilResultRecorded() {
        // Querying the next permission repeatedly (e.g. across a config change) must not skip ahead;
        // only recording a result advances the sequence.
        PermissionSequence seq = new PermissionSequence(Arrays.asList(A, B), new FakeGrants());

        assertEquals(A, seq.nextToRequest());
        assertEquals(A, seq.nextToRequest());
    }

    @Test
    public void emptyPermissions_isDoneAndRestarts() {
        PermissionSequence seq = new PermissionSequence(Collections.<String>emptyList(), new FakeGrants());

        assertNull(seq.nextToRequest());
        assertTrue(seq.isDone());
        assertTrue(seq.shouldRestartService());
    }

    @Test
    public void nullPermissions_isTolerated() {
        PermissionSequence seq = new PermissionSequence(null, new FakeGrants());

        assertNull(seq.nextToRequest());
        assertTrue(seq.isDone());
        assertTrue(seq.shouldRestartService());
    }

    @Test
    public void grantedResult_advances() {
        // A grant just moves on, whatever the rationale flag happens to be.
        assertEquals(PermissionSequence.ResultAction.ADVANCE,
                PermissionSequence.actionAfterResult(true, false));
        assertEquals(PermissionSequence.ResultAction.ADVANCE,
                PermissionSequence.actionAfterResult(true, true));
    }

    @Test
    public void softDenial_stillRepromptable_advances() {
        // Denied but the OS will still show the dialog again (rationale allowed): no settings detour,
        // the user can be re-prompted later.
        assertEquals(PermissionSequence.ResultAction.ADVANCE,
                PermissionSequence.actionAfterResult(false, true));
    }

    @Test
    public void blockedDenial_promptsSettings() {
        // Denied and the OS will not re-prompt (blocked / "don't ask again", or already blocked so the
        // dialog never appeared): requesting again is a no-op, so the user must be routed to settings.
        // This is the case where tapping "Continue" produced no system dialog.
        assertEquals(PermissionSequence.ResultAction.PROMPT_SETTINGS,
                PermissionSequence.actionAfterResult(false, false));
    }
}
