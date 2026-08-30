package com.aware.utils;

import java.util.regex.Pattern;

/**
 * Small, dependency-free helper that strips credential values out of text before it is written to
 * the log. It does NOT change how credentials are stored or transported (the study configuration
 * still embeds the database password); it only prevents that password from leaking into Logcat,
 * crash reports, support bundles, or connected-device tooling.
 *
 * The study configuration is logged in several shapes — pretty-printed JSON
 * ({@code JSONObject.toString(indent)}), compact JSON, {@code ContentValues.toString()}, and
 * {@code DatabaseUtils.dumpCursorToString()} — but in every case the secret appears as a JSON
 * key/value pair such as {@code "database_password":"secret"}. Redacting that pair as plain text
 * therefore covers all of those log shapes with one pass.
 */
public class LogRedactor {

    /**
     * Matches a JSON key whose name contains password/passwd/secret/token (case-insensitive)
     * followed by a quoted string value, capturing the key-and-separator prefix in group 1 so the
     * value alone can be replaced. Handles both compact ({@code "k":"v"}) and pretty
     * ({@code "k" : "v"}) spacing and escaped characters inside the value.
     */
    private static final Pattern SENSITIVE_JSON_STRING = Pattern.compile(
            "(\"[A-Za-z0-9_]*(?:password|passwd|secret|token)[A-Za-z0-9_]*\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"",
            Pattern.CASE_INSENSITIVE);

    private static final String REDACTED = "\"***\"";

    private LogRedactor() {
    }

    /**
     * Returns {@code message} with the value of any password/secret/token JSON field replaced by
     * {@code "***"}. Safe to call on any log string; input without a sensitive field is returned
     * unchanged. A {@code null} input is returned as-is.
     *
     * @param message the text about to be logged
     * @return the same text with credential values masked
     */
    public static String redact(String message) {
        if (message == null) return null;
        // "$1" is a backreference to the key/separator prefix; REDACTED has no regex-special chars.
        return SENSITIVE_JSON_STRING.matcher(message).replaceAll("$1" + REDACTED);
    }
}
