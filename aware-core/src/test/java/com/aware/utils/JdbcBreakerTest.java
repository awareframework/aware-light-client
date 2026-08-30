package com.aware.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.sql.SQLException;

/**
 * Covers the two bounds on the shared sync connection: the timeouts in its URL, and the cooldown
 * that stops every remaining table re-proving the same connection failure.
 *
 * Both exist because the shared connection is a serialization point. Uploads for roughly 40 tables
 * across 30 sync adapters take turns inside one synchronized method, so a single upload that never
 * returns stalls all of them — and, because the call returns neither success nor failure, the sync
 * adapter records nothing and no notification is raised.
 */
public class JdbcBreakerTest {

    // --- Connection URL: the timeouts are what make a stalled upload finite.

    @Test
    public void syncUrlBoundsBothConnectAndRead() {
        String url = Jdbc.syncConnectionUrl("10.0.0.1", "3306", "aware_android", "");

        assertTrue(url, url.contains("connectTimeout=" + Jdbc.SYNC_CONNECT_TIMEOUT_MS));
        assertTrue(url, url.contains("socketTimeout=" + Jdbc.SYNC_SOCKET_TIMEOUT_MS));
    }

    /** A timeout of 0 is the driver's default and means "wait forever" — the bug being fixed. */
    @Test
    public void neitherTimeoutIsUnbounded() {
        assertTrue(Jdbc.SYNC_CONNECT_TIMEOUT_MS > 0);
        assertTrue(Jdbc.SYNC_SOCKET_TIMEOUT_MS > 0);
        assertFalse(Jdbc.syncConnectionUrl("h", "3306", "db", "").contains("Timeout=0"));
    }

    /**
     * The socket timeout applies per read, so it has to leave room for a slow link; the connect
     * timeout only covers establishing the socket and can be tighter.
     */
    @Test
    public void theReadTimeoutIsTheMoreGenerousOfTheTwo() {
        assertTrue(Jdbc.SYNC_SOCKET_TIMEOUT_MS >= Jdbc.SYNC_CONNECT_TIMEOUT_MS);
    }

    @Test
    public void syncUrlKeepsBatchRewritingAndTheHostDetails() {
        String url = Jdbc.syncConnectionUrl("db.example.org", "3307", "aware_android", "");

        assertTrue(url, url.startsWith("jdbc:mysql://db.example.org:3307/aware_android?"));
        assertTrue(url, url.contains("rewriteBatchedStatements=true"));
    }

    @Test
    public void syncUrlAppendsTlsParametersAndToleratesTheirAbsence() {
        assertTrue(Jdbc.syncConnectionUrl("h", "3306", "db", "&useSSL=true")
                .endsWith("&useSSL=true"));
        assertFalse(Jdbc.syncConnectionUrl("h", "3306", "db", null).contains("null"));
    }

    // --- Failure classification: which failures mean every other table is also about to fail.

    @Test
    public void communicationsFailuresAreConnectionLevel() {
        assertTrue(Jdbc.isConnectionLevel(sqlState("08S01"))); // link failure, incl. socket timeout
        assertTrue(Jdbc.isConnectionLevel(sqlState("08003"))); // connection does not exist
        assertTrue(Jdbc.isConnectionLevel(sqlState("28000"))); // access denied
    }

    /**
     * The case this classification exists to get right. An unknown column is what silenced the
     * bluetooth table for two hours; treating it as a connection failure would have suppressed
     * every other table's upload as well, turning one broken table into a total outage.
     */
    @Test
    public void aRejectedStatementIsNotConnectionLevel() {
        assertFalse(Jdbc.isConnectionLevel(sqlState("42S22"))); // unknown column
        assertFalse(Jdbc.isConnectionLevel(sqlState("42S02"))); // unknown table
        assertFalse(Jdbc.isConnectionLevel(sqlState("23000"))); // constraint violation
        assertFalse(Jdbc.isConnectionLevel(sqlState("22007"))); // bad datetime value
    }

    /** Unrecognised failures stay statement-level: the cheaper mistake of the two. */
    @Test
    public void anUnknownFailureIsNotTreatedAsConnectionLevel() {
        assertFalse(Jdbc.isConnectionLevel(sqlState(null)));
        assertFalse(Jdbc.isConnectionLevel(new SQLException("no state at all")));
        assertFalse(Jdbc.isConnectionLevel(null));
    }

    // --- Cooldown.

    @Test
    public void noFailureMeansUploadsProceed() {
        assertFalse(Jdbc.breakerOpen(0, 5_000_000L, Jdbc.BREAKER_COOLDOWN_MS));
    }

    @Test
    public void aFreshFailureSuppressesTheNextAttempt() {
        long now = 5_000_000L;
        assertTrue(Jdbc.breakerOpen(now, now, Jdbc.BREAKER_COOLDOWN_MS));
        assertTrue(Jdbc.breakerOpen(now, now + 1, Jdbc.BREAKER_COOLDOWN_MS));
    }

    @Test
    public void theCooldownEndsExactlyAtItsLength() {
        long failedAt = 5_000_000L;
        assertTrue(Jdbc.breakerOpen(
                failedAt, failedAt + Jdbc.BREAKER_COOLDOWN_MS - 1, Jdbc.BREAKER_COOLDOWN_MS));
        assertFalse(Jdbc.breakerOpen(
                failedAt, failedAt + Jdbc.BREAKER_COOLDOWN_MS, Jdbc.BREAKER_COOLDOWN_MS));
        assertFalse(Jdbc.breakerOpen(
                failedAt, failedAt + Jdbc.BREAKER_COOLDOWN_MS + 1, Jdbc.BREAKER_COOLDOWN_MS));
    }

    /**
     * The cooldown must stay well inside one sync interval (30 minutes by default), or it would be
     * delaying scheduled retries rather than only collapsing one cycle's redundant attempts.
     */
    @Test
    public void theCooldownIsShorterThanTheSyncInterval() {
        assertTrue(Jdbc.BREAKER_COOLDOWN_MS < 30 * 60 * 1000L);
    }

    /** A clock that steps backwards must not wedge uploads open indefinitely. */
    @Test
    public void aBackwardsClockDoesNotHoldTheBreakerOpen() {
        long failedAt = 5_000_000L;
        assertFalse(Jdbc.breakerOpen(failedAt, failedAt - 1, Jdbc.BREAKER_COOLDOWN_MS));
    }

    private static SQLException sqlState(String state) {
        return new SQLException("failure", state, 0);
    }
}
