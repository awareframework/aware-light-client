package com.aware.utils;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * This class will encapsulate the processes between the client and a MySQL database via JDBC API.
 */
public class Jdbc {
    private final static String TAG = "JDBC";
    private static Connection connection;
    private static int transactionCount = 0;

    /**
     * Bounds on the shared sync connection. Without them the driver inherits Java's defaults, where
     * a read timeout of 0 means wait forever: a server that accepts the connection and then stops
     * answering holds {@link #insertBatch}'s lock until TCP keepalive gives up, which is hours. Every
     * other table's upload waits behind that, and because the call never returns, the sync adapter
     * records neither success nor failure, so nothing reports the stall.
     *
     * The socket timeout applies per read rather than to the batch as a whole, so a large upload on
     * a slow link is not at risk unless the link itself stalls for this long. A timeout that does
     * trip is safe: the batch rolls back, the rows stay on the device, and the sync marker does not
     * advance, so the same rows are retried on the next sync.
     */
    static final int SYNC_CONNECT_TIMEOUT_MS = 30_000;
    static final int SYNC_SOCKET_TIMEOUT_MS = 60_000;

    /**
     * How long a connection-level failure suppresses further upload attempts.
     *
     * The timeouts above bound a single attempt, not a sync cycle: roughly 40 tables across 30 sync
     * adapters each take their own turn on the shared connection, so a dead server would still cost
     * 40 timeouts serially. One connection failure means the next table will fail the same way, so
     * the remaining attempts are skipped instead of re-proved. The window is far shorter than the
     * sync interval (30 minutes by default), so it delays no scheduled retry.
     */
    static final long BREAKER_COOLDOWN_MS = 60_000L;

    private static volatile long connectionFailedAt = 0;

    private static class JdbcConnectionException extends Exception {
        private JdbcConnectionException(String message) {
            super(message);
        }
    }

    /**
     * Outcome of a database connection attempt, distinguishing an authentication failure (the
     * stored password is wrong) from an unreachable server (transient / network). Callers need
     * this distinction so that "the password changed" is handled differently from "the database is
     * temporarily down": only the former should ask the participant to re-authenticate.
     */
    public enum ConnectionResult { OK, AUTH_FAILED, UNREACHABLE }

    /**
     * Classifies a {@link SQLException} from a connection attempt as an authentication failure or a
     * reachability failure. Pure and side-effect free so it can be unit-tested without a database.
     *
     * MySQL signals access-denied with SQLState {@code 28000} and vendor error code {@code 1045};
     * anything else (connect/socket timeout, communications link failure, unknown host, driver
     * errors) is treated as {@link ConnectionResult#UNREACHABLE}. The safe default is
     * {@code UNREACHABLE} so an unrecognised error never prompts the participant for a password.
     *
     * @param e the exception thrown while connecting
     * @return {@link ConnectionResult#AUTH_FAILED} for access-denied, else {@link ConnectionResult#UNREACHABLE}
     */
    static ConnectionResult classify(SQLException e) {
        if (e == null) return ConnectionResult.UNREACHABLE;
        if ("28000".equals(e.getSQLState()) || e.getErrorCode() == 1045) {
            return ConnectionResult.AUTH_FAILED;
        }
        return ConnectionResult.UNREACHABLE;
    }

    /**
     * Whether a failure is with the connection itself rather than with the statement, i.e. whether
     * every other table is about to fail the same way.
     *
     * SQLState class {@code 08} is the standard connection-exception class, which MySQL uses for a
     * communications link failure including a tripped socket timeout; {@code 28000} is access
     * denied, where no table can succeed either.
     *
     * Deliberately conservative: anything else is treated as statement-level. Misreading a
     * statement error as a connection error would suppress every other table's upload for the
     * cooldown, which is exactly how one broken table (a column the server lacks) could hide the
     * rest. Misreading it the other way only costs the redundant attempts this check exists to
     * avoid, so the cheaper mistake is the one it makes. Pure, so it is unit-testable without a
     * database.
     *
     * @param e the exception thrown while uploading a batch
     * @return true if the shared connection is the problem
     */
    static boolean isConnectionLevel(SQLException e) {
        if (e == null) return false;
        String state = e.getSQLState();
        if (state == null) return false;
        return state.startsWith("08") || "28000".equals(state);
    }

    /**
     * Whether upload attempts are currently suppressed after a connection-level failure.
     *
     * Pure, so the cooldown can be unit-tested without waiting on a clock.
     *
     * A clock that has stepped backwards since the failure — an NTP correction, or the participant
     * changing the time — reads as a negative elapsed time. That resolves to closed rather than open:
     * the alternative is suppressing uploads until the clock catches up, which for a correction of
     * any size is the silent multi-hour stall this cooldown exists to prevent.
     *
     * @param failedAt when the connection last failed, or 0 if it has not
     * @param now current time
     * @param cooldownMs how long a failure suppresses attempts
     */
    static boolean breakerOpen(long failedAt, long now, long cooldownMs) {
        if (failedAt <= 0) return false;
        long elapsed = now - failedAt;
        return elapsed >= 0 && elapsed < cooldownMs;
    }

    /**
     * The shared sync connection's URL. Pure, so the timeout parameters can be verified without a
     * database or an Android context.
     */
    static String syncConnectionUrl(String host, String port, String name, String tlsParameters) {
        return String.format(
                "jdbc:mysql://%s:%s/%s?rewriteBatchedStatements=true&connectTimeout=%d&socketTimeout=%d%s",
                host, port, name, SYNC_CONNECT_TIMEOUT_MS, SYNC_SOCKET_TIMEOUT_MS,
                tlsParameters == null ? "" : tlsParameters);
    }

    /**
     * Summarises a chain of {@link SQLWarning}s into one log-safe line, or null when the chain is
     * empty.
     *
     * The count matters as much as the text: MySQL reports one warning per offending row, so "1"
     * and "4000" distinguish a single odd value from a column whose type no longer matches what the
     * client sends. Pure and side-effect free so it can be unit-tested without a database.
     *
     * @param warning head of the warning chain, or null
     * @return a summary such as {@code 2 warning(s); first: [01000/1265] Data truncated}, or null
     */
    static String describeWarnings(SQLWarning warning) {
        if (warning == null) return null;

        int count = 0;
        SQLWarning current = warning;
        // A driver that chains a warning to itself would otherwise spin here forever.
        while (current != null && count < 1000) {
            count++;
            SQLWarning next = current.getNextWarning();
            if (next == current) break;
            current = next;
        }

        return count + " warning(s); first: [" + warning.getSQLState() + "/"
                + warning.getErrorCode() + "] " + warning.getMessage();
    }

    /**
     * Probes whether the given credentials can authenticate against the database, on a short-lived
     * connection that fails fast when the host is unreachable.
     *
     * Returns a three-state {@link ConnectionResult} so callers can tell a rejected password
     * ({@link ConnectionResult#AUTH_FAILED}) from a down/unreachable server
     * ({@link ConnectionResult#UNREACHABLE}). Connects on its own short-lived connection with
     * bounded {@code connectTimeout}/{@code socketTimeout}, leaving the shared {@link #connection}
     * used by uploads untouched, so a probe can run during a sync without disturbing it. The
     * password and connection string are never logged.
     *
     * @param context        application context, for the trust store behind {@link MysqlTls}
     * @param timeoutSeconds maximum time to spend connecting
     * @return {@link ConnectionResult#OK} if the credentials authenticate, otherwise the classified failure
     */
    public static ConnectionResult probeConnection(Context context, String host, String port,
                                                   String name, String username, String password,
                                                   int timeoutSeconds) {
        int timeoutMs = timeoutSeconds * 1000;

        Connection localConnection = null;
        try {
            String connectionUrl = String.format(
                    "jdbc:mysql://%s:%s/%s?connectTimeout=%d&socketTimeout=%d%s",
                    host, port, name, timeoutMs, timeoutMs,
                    MysqlTls.connectionParameters(context));
            Class.forName("com.mysql.jdbc.Driver");
            localConnection = DriverManager.getConnection(connectionUrl, username, password);
            return ConnectionResult.OK;
        } catch (SQLException e) {
            ConnectionResult result = classify(e);
            Log.i(TAG, "Database probe result: " + result);
            return result;
        } catch (Exception e) {
            // Driver load or other unexpected failure: treat as unreachable so we never prompt.
            Log.i(TAG, "Database probe result: " + ConnectionResult.UNREACHABLE);
            return ConnectionResult.UNREACHABLE;
        } finally {
            try {
                if (localConnection != null && !localConnection.isClosed()) localConnection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Inserts data into a remote database table.
     *
     * @param context application context
     * @param table name of table to insert data into
     * @param rows list of the rows of data to insert
     * @return true if the data is inserted successfully, false otherwise
     */
    public static boolean insertData(Context context, String table, JSONArray rows) {
        if (rows.length() == 0) return true;

        // A recent connection-level failure means this attempt would block for the socket timeout to
        // learn what the last one already established. Reported as a failure rather than a success,
        // so the table keeps its rows and the outage stays recorded.
        if (breakerOpen(connectionFailedAt, System.currentTimeMillis(), BREAKER_COOLDOWN_MS)) {
            Log.i(TAG, "Skipping upload of '" + table
                    + "': the database failed to respond within the last "
                    + (BREAKER_COOLDOWN_MS / 1000) + "s.");
            return false;
        }

        try {
            List<String> fields = new ArrayList<>();
            Iterator<String> fieldIterator = rows.getJSONObject(0).keys();
            while (fieldIterator.hasNext()) {
                fields.add(fieldIterator.next());
            }
            // Claim a reference only once nothing else here can throw: insertBatch's finally is what
            // releases it, so anything that fails between the two would leave the shared connection
            // referenced by a caller that has gone away, and never closed.
            Jdbc.transactionCount++;
            Jdbc.insertBatch(context, table, fields, rows);
        } catch (SQLException e) {
            if (isConnectionLevel(e)) openBreaker("upload of '" + table + "' failed: "
                    + e.getSQLState() + " " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (JdbcConnectionException e) {
            // connect() could not establish the connection at all, so no table will fare better.
            openBreaker("could not connect: " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (JSONException e) {
            // Malformed rows for this table only; the connection is fine.
            e.printStackTrace();
            return false;
        }

        connectionFailedAt = 0;
        return true;
    }

    private static void openBreaker(String reason) {
        connectionFailedAt = System.currentTimeMillis();
        Log.w(TAG, "Pausing uploads for " + (BREAKER_COOLDOWN_MS / 1000) + "s — " + reason);
    }

    /**
     * Best-effort, single-shot insert on a short-lived connection that fails fast when the host is
     * unreachable.
     *
     * Unlike {@link #insertData}, this does NOT reuse the shared sync connection and adds bounded
     * {@code connectTimeout}/{@code socketTimeout} query parameters, so an unreachable database
     * fails within roughly {@code timeoutSeconds} instead of hanging on the default TCP timeout.
     * It is used by the study-exit notification: leaving a study must stay responsive and must
     * never be blocked by an unreachable research database.
     *
     * @param context        application context
     * @param table          name of the remote table to insert into
     * @param rows           rows to insert
     * @param timeoutSeconds maximum time to spend connecting/talking to the database
     * @return true if the database acknowledged the insert; false if it could not be reached or the
     *         insert failed. Callers must treat false as "not notified", never as "leave failed".
     */
    public static boolean insertDataFastFail(Context context, String table, JSONArray rows, int timeoutSeconds) {
        if (rows.length() == 0) return true;

        int timeoutMs = timeoutSeconds * 1000;

        Connection localConnection = null;
        try {
            String connectionUrl = String.format(
                    "jdbc:mysql://%s:%s/%s?connectTimeout=%d&socketTimeout=%d%s",
                    Aware.getSetting(context, Aware_Preferences.DB_HOST),
                    Aware.getSetting(context, Aware_Preferences.DB_PORT),
                    Aware.getSetting(context, Aware_Preferences.DB_NAME),
                    timeoutMs, timeoutMs,
                    MysqlTls.connectionParameters(context));
            Class.forName("com.mysql.jdbc.Driver");
            localConnection = DriverManager.getConnection(connectionUrl,
                    Aware.getSetting(context, Aware_Preferences.DB_USERNAME),
                    Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));

            List<String> fields = new ArrayList<>();
            Iterator<String> fieldIterator = rows.getJSONObject(0).keys();
            while (fieldIterator.hasNext()) {
                fields.add(fieldIterator.next());
            }

            List<String> fieldsWithBacktick = new ArrayList<>();  // in case of reserved keywords
            List<Character> sqlParamPlaceholder = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                fieldsWithBacktick.add("`" + fields.get(i) + "`");
                sqlParamPlaceholder.add('?');
            }

            String sqlStatement = String.format("INSERT INTO %s (%s) VALUES (%s)", table,
                    TextUtils.join(",", fieldsWithBacktick),
                    TextUtils.join(",", sqlParamPlaceholder));
            PreparedStatement ps = localConnection.prepareStatement(sqlStatement);

            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                int paramIndex = 1;
                for (String field : fields) {
                    ps.setString(paramIndex, row.getString(field));
                    paramIndex++;
                }
                ps.addBatch();
            }

            ps.executeBatch();
            Log.i(TAG, "Study-exit notification acknowledged by remote table '" + table + "'");
            return true;
        } catch (Exception e) {
            // Do not log the connection string/credentials; the host is enough to diagnose.
            Log.w(TAG, "Study-exit notification could not reach the research database: " + e.getMessage());
            return false;
        } finally {
            try {
                if (localConnection != null && !localConnection.isClosed()) localConnection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Establish a connection to the database of the currently joined study.
     * @param context application context
     */
    private static void connect(Context context) throws JdbcConnectionException {
        Log.i(TAG, "Establishing connection to remote database...");

        try {
            String connectionUrl = syncConnectionUrl(
                    Aware.getSetting(context, Aware_Preferences.DB_HOST),
                    Aware.getSetting(context, Aware_Preferences.DB_PORT),
                    Aware.getSetting(context, Aware_Preferences.DB_NAME),
                    MysqlTls.connectionParameters(context));
            Class.forName("com.mysql.jdbc.Driver");

            connection = DriverManager.getConnection(connectionUrl,
                    Aware.getSetting(context, Aware_Preferences.DB_USERNAME),
                    Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));
            Log.i(TAG, "Connected to remote database...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish connection to database, reason: " + e.getMessage());
            e.printStackTrace();
            throw new JdbcConnectionException(e.getMessage());
        }
    }

    /**
     * Closes the current database connection.
     */
    private static void disconnect() {
        try {
            Log.i(TAG, "Closing connection to remote database...");
            if (connection != null && !connection.isClosed()) Jdbc.connection.close();
            Log.i(TAG, "Closed connection to remote database.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Batch inserts data into a remote database table.
     *
     * @param context application context
     * @param table name of table to batch insert data into
     * @param fields list of the table fields
     * @param rows list of the rows of data to insert
     * @throws JdbcConnectionException
     * @throws JSONException
     */
    private static synchronized void insertBatch(
        Context context,
        String table,
        List<String> fields,
        JSONArray rows
    ) throws JdbcConnectionException, JSONException, SQLException {
        try {
            if (Jdbc.connection == null || Jdbc.connection.isClosed()) {
                Jdbc.transactionCount = 1; // reset transaction count if this is the first INSERT
                connect(context);
            }
            Log.i(TAG, "# " + Jdbc.transactionCount + " Inserting " + rows.length() +
                    " row(s) of data into remote table '" + table + "'...");

            List<String> fieldsWithBacktick = new ArrayList<>();  // in case of reserved keywords
            List<Character> sqlParamPlaceholder = new ArrayList<>();
            for (int i = 0; i < fields.size(); i ++) {
                fieldsWithBacktick.add("`" + fields.get(i) + "`");
                sqlParamPlaceholder.add('?');
            }

            String sqlStatement = String.format("INSERT INTO %s (%s) VALUES (%s)", table,
                    TextUtils.join(",", fieldsWithBacktick),
                    TextUtils.join(",", sqlParamPlaceholder));

            // The batch lands all-or-nothing. rewriteBatchedStatements=true has the driver merge the
            // batch into several multi-row INSERTs; under autocommit each of those commits on its
            // own, so a failure part-way leaves the earlier chunks stored server-side while the
            // phone — which only learns "the batch failed" — keeps every local row and retries the
            // whole batch, duplicating them. The client's MySQL user has INSERT only, so such
            // duplicates can never be removed afterwards. Requires the target table to be InnoDB;
            // MyISAM silently ignores transactions.
            boolean autoCommitWas = Jdbc.connection.getAutoCommit();
            Jdbc.connection.setAutoCommit(false);
            try (PreparedStatement ps = Jdbc.connection.prepareStatement(sqlStatement)) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    int paramIndex = 1;

                    for (String field: fields) {
                        ps.setString(paramIndex, row.getString(field));
                        paramIndex++;
                    }
                    ps.addBatch();
                }

                // The connection is shared across batches and accumulates warnings, so clear it
                // first: what is read below has to belong to this batch and no earlier one.
                Jdbc.connection.clearWarnings();
                ps.executeBatch();

                // Every value is bound with setString(), including numeric and double_* columns, so
                // MySQL implicitly converts each one. A server in strict mode raises an error on a
                // bad conversion, but a non-strict server (or a MyISAM table, where strict mode
                // degrades to warnings for multi-row inserts) accepts it as a warning and stores
                // something other than what was sent. Report that rather than call it a clean
                // upload; the batch is still committed, because refusing to advance the sync marker
                // over a warning would wedge the table into retrying the same rows forever.
                String warnings = describeWarnings(ps.getWarnings());
                if (warnings == null) warnings = describeWarnings(Jdbc.connection.getWarnings());
                if (warnings != null) {
                    Log.w(TAG, "Remote table '" + table + "' accepted the insert with " + warnings);
                }

                Jdbc.connection.commit();
            } catch (SQLException e) {
                try {
                    Jdbc.connection.rollback();
                } catch (SQLException rollbackFailed) {
                    Log.e(TAG, "Rollback of the failed batch for '" + table + "' did not complete: "
                            + rollbackFailed.getMessage());
                }
                throw e;
            } finally {
                try {
                    Jdbc.connection.setAutoCommit(autoCommitWas);
                } catch (SQLException ignored) {
                }
            }
            Log.i(TAG, "Inserted " + rows.length() + " row(s) of data into remote table '" + table);
        } finally {
            Jdbc.transactionCount--;
            if (Jdbc.transactionCount == 0) disconnect();
        }
    }
}
