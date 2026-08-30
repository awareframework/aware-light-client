package com.aware.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.sql.SQLException;

/**
 * Unit test for {@link Jdbc#classify(SQLException)} — the pure classifier behind the study
 * password re-authentication flow. It must reliably tell an access-denied (wrong password) error
 * apart from a reachability failure, because only the former should ask the participant to
 * re-enter the study password; an unreachable/transient failure must never trigger that prompt.
 */
public class JdbcClassifyTest {

    @Test
    public void sqlState28000IsAuthFailure() {
        // MySQL access-denied is signalled with SQLState 28000.
        SQLException e = new SQLException("Access denied for user", "28000");
        assertEquals(Jdbc.ConnectionResult.AUTH_FAILED, Jdbc.classify(e));
    }

    @Test
    public void vendorError1045IsAuthFailure() {
        // MySQL vendor error 1045 = access denied, even if the SQLState differs.
        SQLException e = new SQLException("Access denied for user", "HY000", 1045);
        assertEquals(Jdbc.ConnectionResult.AUTH_FAILED, Jdbc.classify(e));
    }

    @Test
    public void communicationsLinkFailureIsUnreachable() {
        SQLException e = new SQLException("Communications link failure", "08S01");
        assertEquals(Jdbc.ConnectionResult.UNREACHABLE, Jdbc.classify(e));
    }

    @Test
    public void connectionRejectedIsUnreachable() {
        SQLException e = new SQLException("Unable to connect", "08001");
        assertEquals(Jdbc.ConnectionResult.UNREACHABLE, Jdbc.classify(e));
    }

    @Test
    public void unknownExceptionDefaultsToUnreachable() {
        // No SQLState, no vendor code: safe default is UNREACHABLE (never prompt on the unknown).
        SQLException e = new SQLException("Something unexpected");
        assertEquals(Jdbc.ConnectionResult.UNREACHABLE, Jdbc.classify(e));
    }

    @Test
    public void nullDefaultsToUnreachable() {
        assertEquals(Jdbc.ConnectionResult.UNREACHABLE, Jdbc.classify(null));
    }
}
