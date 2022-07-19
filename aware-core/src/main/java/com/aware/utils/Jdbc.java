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

    private static class JdbcConnectionException extends Exception {
        private JdbcConnectionException(String message) {
            super(message);
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

        try {
            Jdbc.transactionCount++;
            List<String> fields = new ArrayList<>();
            Iterator<String> fieldIterator = rows.getJSONObject(0).keys();
            while (fieldIterator.hasNext()) {
                fields.add(fieldIterator.next());
            }
            Jdbc.insertBatch(context, table, fields, rows);
        } catch (JSONException | SQLException | JdbcConnectionException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Test if a connection to a database can be established.
     * @param host db host
     * @param port db port
     * @param name db name
     * @param username db username
     * @param password db password
     * @return true if a connection was established, false otherwise.
     */
    public static boolean testConnection(String host, String port, String name, String username, String password) {
        String connectionUrl = String.format("jdbc:mysql://%s:%s/%s", host, port, name);
        Log.i(TAG, "Establishing connection to remote database...");

        try {
            Class.forName("com.mysql.jdbc.Driver").newInstance();
            connection = DriverManager.getConnection(connectionUrl, username, password);
            Log.i(TAG, "Connected to remote database...");
            connection.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish connection to database, reason: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Establish a connection to the database of the currently joined study.
     * @param context application context
     */
    private static void connect(Context context) throws JdbcConnectionException {
        String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?rewriteBatchedStatements=true",
                Aware.getSetting(context, Aware_Preferences.DB_HOST),
                Aware.getSetting(context, Aware_Preferences.DB_PORT),
                Aware.getSetting(context, Aware_Preferences.DB_NAME));
        Log.i(TAG, "Establishing connection to remote database...");

        try {
            Class.forName("com.mysql.jdbc.Driver").newInstance();
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
    private static synchronized void insertBatch(Context context, String table, List<String> fields,
                                                 JSONArray rows)
            throws JdbcConnectionException, JSONException, SQLException {
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
            PreparedStatement ps = Jdbc.connection.prepareStatement(sqlStatement);

            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                int paramIndex = 1;

                for (String field: fields) {
                    ps.setString(paramIndex, row.getString(field));
                    paramIndex++;
                }
                ps.addBatch();
            }

            ps.executeBatch();
            Log.i(TAG, "Inserted " + rows.length() + " row(s) of data into remote table '" + table);
        } finally {
            Jdbc.transactionCount--;
            if (Jdbc.transactionCount == 0) disconnect();
        }
    }
}
