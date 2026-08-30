package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression test for {@link LogRedactor}. Locks in the fix for the beta-stability issue "Database
 * passwords appear in logs": the study configuration embeds the database password, and that config
 * is written to Logcat in several shapes (pretty JSON, compact JSON, ContentValues.toString(), and
 * DatabaseUtils.dumpCursorToString()). The redactor must strip the password out of all of them
 * while leaving non-sensitive fields readable for debugging.
 */
public class LogRedactorTest {

    @Test
    public void redactsCompactJsonPassword() {
        String in = "{\"database_password\":\"s3cr3t\"}";
        assertEquals("{\"database_password\":\"***\"}", LogRedactor.redact(in));
    }

    @Test
    public void redactsPrettyPrintedPasswordWithSpacesAroundColon() {
        String in = "\"database_password\" : \"s3cr3t\"";
        assertEquals("\"database_password\" : \"***\"", LogRedactor.redact(in));
    }

    @Test
    public void redactsPlainPasswordKey() {
        String in = "{\"password\":\"hunter2\"}";
        assertEquals("{\"password\":\"***\"}", LogRedactor.redact(in));
    }

    @Test
    public void redactsRegardlessOfKeyCase() {
        String in = "{\"Database_Password\":\"hunter2\"}";
        assertEquals("{\"Database_Password\":\"***\"}", LogRedactor.redact(in));
    }

    @Test
    public void keepsNonSensitiveFields() {
        // The host/username/name must stay readable so a connection failure is still diagnosable.
        String in = "{\"database_host\":\"db.example.org\",\"database_password\":\"s3cr3t\","
                + "\"database_username\":\"researcher\"}";
        String expected = "{\"database_host\":\"db.example.org\",\"database_password\":\"***\","
                + "\"database_username\":\"researcher\"}";
        assertEquals(expected, LogRedactor.redact(in));
    }

    @Test
    public void redactsValueContainingEscapedQuote() {
        // Value is a"b written with an escaped inner quote; must not stop the match early.
        String in = "{\"password\":\"a\\\"b\"}";
        assertEquals("{\"password\":\"***\"}", LogRedactor.redact(in));
    }

    @Test
    public void redactsEveryOccurrence() {
        String in = "{\"password\":\"one\"} ... {\"password\":\"two\"}";
        assertEquals("{\"password\":\"***\"} ... {\"password\":\"***\"}", LogRedactor.redact(in));
    }

    @Test
    public void redactsPasswordEmbeddedInContentValuesDump() {
        // Mirrors ContentValues.toString(): the config JSON is one value inside a larger blob.
        String in = "study_config=[{\"database\":{\"database_password\":\"s3cr3t\"}}] study_title=Demo";
        String out = LogRedactor.redact(in);
        assertFalse(out.contains("s3cr3t"));
        assertTrue(out.contains("\"database_password\":\"***\""));
        assertTrue(out.contains("study_title=Demo"));
    }

    @Test
    public void leavesTextWithoutSecretsUnchanged() {
        String in = "Establishing connection to remote database...";
        assertEquals(in, LogRedactor.redact(in));
    }

    @Test
    public void doesNotTouchBooleanConfigWithoutPasswordFlag() {
        // "config_without_password" contains the word "password" but its value is an unquoted
        // boolean, so there is no string value to redact and the flag stays visible.
        String in = "{\"config_without_password\":false}";
        assertEquals(in, LogRedactor.redact(in));
    }

    @Test
    public void handlesNull() {
        assertNull(LogRedactor.redact(null));
    }
}
