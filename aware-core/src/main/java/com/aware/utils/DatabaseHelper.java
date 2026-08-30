
package com.aware.utils;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.aware.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * ContentProvider database helper<br/>
 * This class is responsible to make sure we have the most up-to-date database structures from plugins and sensors
 *
 * @author denzil
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private final boolean DEBUG = true;

    private String TAG = "AwareDBHelper";

    private String databaseName;
    private String[] databaseTables;
    private String[] tableFields;
    private int newVersion;
    private CursorFactory cursorFactory;
    private SQLiteDatabase database;
    private Context mContext;

    private HashMap<String, String> renamed_columns = new HashMap<>();
    private List<String> metadataOnlyTrailingColumnDrops = new ArrayList<>();

    public DatabaseHelper(Context context, String database_name, CursorFactory cursor_factory, int database_version, String[] database_tables, String[] table_fields) {
        super(context, database_name, cursor_factory, database_version);
        mContext = context;
        databaseName = database_name;
        databaseTables = database_tables;
        tableFields = table_fields;
        newVersion = database_version;
        cursorFactory = cursor_factory;
    }

    public void setRenamedColumns(HashMap<String, String> renamed) {
        renamed_columns = renamed;
    }

    /**
     * Allows a provider to remove known trailing columns without copying the entire table.
     *
     * SQLite records are self-describing. If columns are removed only from the end of a table,
     * older records may safely retain those trailing values: SQLite ignores values beyond the
     * table definition, while new records use the shorter definition. Updating sqlite_master is
     * therefore a metadata-only operation. This is deliberately opt-in because it is safe only for
     * trailing columns whose values no longer have meaning.
     */
    public void setMetadataOnlyTrailingColumnDrops(String... columns) {
        metadataOnlyTrailingColumnDrops = new ArrayList<>(Arrays.asList(columns));
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        if (DEBUG) Log.w(TAG, "Creating database: " + db.getPath());
        for (int i = 0; i < databaseTables.length; i++) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + databaseTables[i] + " (" + tableFields[i] + ");");
            createTimeDeviceIndex(db, i);
        }
        db.setVersion(newVersion);
    }

    /**
     * Creates the (timestamp, device_id) lookup index for one table, when that table declares both
     * columns. Tables that declare neither — aware_settings, aware_plugins, aware_sync_markers —
     * are skipped: indexing a column a table does not have throws, and inside an upgrade that
     * aborts the whole migration transaction.
     */
    private void createTimeDeviceIndex(SQLiteDatabase db, int table) {
        List<String> declared = declaredColumns(tableFields[table]);
        if (!declared.contains("timestamp") || !declared.contains("device_id")) return;
        db.execSQL("CREATE INDEX IF NOT EXISTS time_device ON " + databaseTables[table]
                + " (timestamp, device_id);");
    }

    /**
     * The column names declared by a table's field definition.
     *
     * Read from the definition rather than from the table it creates, because the carry-over below
     * needs the new column set before the new table holds any rows, and a table's shape is fully
     * described by the definition already in hand.
     *
     * Splits on the commas between column definitions, which means stepping over the commas inside a
     * trailing table constraint such as {@code UNIQUE(a, b)}; a constraint contributes no column and
     * is skipped.
     *
     * @param fields one entry of the table-fields array, as handed to the constructor
     * @return the column names, in declaration order
     */
    static List<String> declaredColumns(String fields) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i <= fields.length(); i++) {
            char c = i < fields.length() ? fields.charAt(i) : ',';
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                String definition = current.toString().trim();
                current.setLength(0);
                if (definition.isEmpty()) continue;
                String name = definition.split("\\s+")[0];
                // A table constraint (UNIQUE(...), PRIMARY KEY(...), FOREIGN KEY ...) names no column.
                if (name.indexOf('(') >= 0) continue;
                String upper = name.toUpperCase();
                if (upper.equals("UNIQUE") || upper.equals("PRIMARY") || upper.equals("FOREIGN")
                        || upper.equals("CHECK") || upper.equals("CONSTRAINT")) continue;
                columns.add(name);
            } else {
                current.append(c);
            }
        }
        return columns;
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (DEBUG) Log.w(TAG, "Upgrading database: " + db.getPath());

        boolean tableDefinitionRewritten = false;

        for (int i = 0; i < databaseTables.length; i++) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + databaseTables[i] + " (" + tableFields[i] + ");");

            //Modify existing tables if there are changes, while retaining old data. This also works for brand new tables, where nothing is changed.
            List<String> columns = getColumns(db, databaseTables[i]);
            List<String> desiredColumns = declaredColumns(tableFields[i]);

            if (!metadataOnlyTrailingColumnDrops.isEmpty()) {
                // Providers opting into the metadata-only path use a version bump solely to drop
                // a known trailing field. Do not rebuild their unchanged companion tables.
                if (columns.equals(desiredColumns)) {
                    createTimeDeviceIndex(db, i);
                    continue;
                }
                if (isConfiguredTrailingColumnDrop(columns, desiredColumns,
                        metadataOnlyTrailingColumnDrops)) {
                    rewriteTableDefinition(db, databaseTables[i], tableFields[i]);
                    tableDefinitionRewritten = true;
                    createTimeDeviceIndex(db, i);
                    continue;
                }
            }

            // An upgrade runs in a transaction, so an attempt that fails rolls back and leaves the
            // original table in place — but a temp_ table created outside that transaction's reach
            // would survive and collide with the rename below.
            db.execSQL("DROP TABLE IF EXISTS temp_" + databaseTables[i] + ";");
            db.execSQL("ALTER TABLE " + databaseTables[i] + " RENAME TO temp_" + databaseTables[i] + ";");

            db.execSQL("CREATE TABLE " + databaseTables[i] + " (" + tableFields[i] + ");");
            createTimeDeviceIndex(db, i);

            // The new table is empty at this point, so its shape comes from the definition that
            // created it. Carrying over only the columns both shapes share is what lets a column be
            // dropped: it stays behind with the temp table.
            columns.retainAll(declaredColumns(tableFields[i]));

            String cols = TextUtils.join(",", columns);
            String new_cols = cols;

            if (renamed_columns.size() > 0) {
                for (String key : renamed_columns.keySet()) {
                    if (DEBUG) Log.d(TAG, "Renaming: " + key + " -> " + renamed_columns.get(key));
                    new_cols = new_cols.replace(key, renamed_columns.get(key));
                }
            }

            //restore old data back
            if (DEBUG)
                Log.d(TAG, String.format("INSERT INTO %s (%s) SELECT %s from temp_%s;", databaseTables[i], new_cols, cols, databaseTables[i]));

            db.execSQL(String.format("INSERT INTO %s (%s) SELECT %s from temp_%s;", databaseTables[i], new_cols, cols, databaseTables[i]));
            db.execSQL("DROP TABLE temp_" + databaseTables[i] + ";");
        }

        if (tableDefinitionRewritten) {
            // Force SQLite and other connections to discard their cached copy of sqlite_master.
            Cursor schemaVersion = db.rawQuery("PRAGMA schema_version", null);
            int version = 0;
            if (schemaVersion != null && schemaVersion.moveToFirst()) {
                version = schemaVersion.getInt(0);
            }
            if (schemaVersion != null && !schemaVersion.isClosed()) schemaVersion.close();
            db.execSQL("PRAGMA schema_version = " + (version + 1));
        }
        db.setVersion(newVersion);
    }

    static boolean isConfiguredTrailingColumnDrop(List<String> existing,
                                                   List<String> desired,
                                                   List<String> allowedDrops) {
        if (existing.size() <= desired.size()) return false;
        if (!existing.subList(0, desired.size()).equals(desired)) return false;
        for (String dropped : existing.subList(desired.size(), existing.size())) {
            if (!allowedDrops.contains(dropped)) return false;
        }
        return true;
    }

    private static void rewriteTableDefinition(SQLiteDatabase db, String table, String fields) {
        db.execSQL("PRAGMA writable_schema = ON");
        try {
            db.execSQL("UPDATE sqlite_master SET sql = ? WHERE type = 'table' AND name = ?",
                    new Object[]{"CREATE TABLE " + table + " (" + fields + ")", table});
        } finally {
            db.execSQL("PRAGMA writable_schema = OFF");
        }
    }

    /**
     * Creates a String of a JSONArray representation of a database cursor result
     *
     * @param cursor
     * @return String
     */
    public static String cursorToString(Cursor cursor) {
        JSONArray jsonArray = new JSONArray();
        if (cursor != null && cursor.moveToFirst()) {
            do {
                int nColumns = cursor.getColumnCount();
                JSONObject row = new JSONObject();
                for (int i = 0; i < nColumns; i++) {
                    String colName = cursor.getColumnName(i);
                    if (colName != null) {
                        try {
                            switch (cursor.getType(i)) {
                                case Cursor.FIELD_TYPE_BLOB:
                                    row.put(colName, cursor.getBlob(i).toString());
                                    break;
                                case Cursor.FIELD_TYPE_FLOAT:
                                    row.put(colName, cursor.getDouble(i));
                                    break;
                                case Cursor.FIELD_TYPE_INTEGER:
                                    row.put(colName, cursor.getLong(i));
                                    break;
                                case Cursor.FIELD_TYPE_NULL:
                                    row.put(colName, null);
                                    break;
                                case Cursor.FIELD_TYPE_STRING:
                                    row.put(colName, cursor.getString(i));
                                    break;
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
                jsonArray.put(row);
            } while (cursor.moveToNext());
        }
        if (cursor != null && !cursor.isClosed()) cursor.close();

        return jsonArray.toString();
    }

    private static List<String> getColumns(SQLiteDatabase db, String tableName) {
        List<String> columns = null;
        Cursor database_meta = db.rawQuery("SELECT * FROM " + tableName + " LIMIT 1", null);
        if (database_meta != null) {
            columns = new ArrayList<>(Arrays.asList(database_meta.getColumnNames()));
        }
        if (database_meta != null && !database_meta.isClosed()) database_meta.close();

        return columns;
    }

    @Override
    public synchronized SQLiteDatabase getWritableDatabase() {
        try {
            if (database != null) {
                if (!database.isOpen()) {
                    database = null;
                } else if (!database.isReadOnly()) {
                    return database;
                }
            }

            database = getDatabaseFile();

            int current_version = database.getVersion();
            if (current_version != newVersion) {
                database.beginTransaction();
                try {
                    if (current_version == 0) {
                        onCreate(database);
                    } else {
                        onUpgrade(database, current_version, newVersion);
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
            }
            return database;
        } catch (Exception e) {
            // A rolled-back migration leaves the old schema and version in place, so later callers
            // read a database that disagrees with the provider's columns.
            Log.e(TAG, "Failed to open " + databaseName + " at version " + newVersion
                    + "; the schema migration was rolled back: "
                    + e.getClass().getName() + ": " + e.getMessage());
            // The cache check at the top returns without consulting the version, so this handle has
            // to go: it points at the un-migrated schema.
            if (database != null) {
                try {
                    database.close();
                } catch (Exception ignored) {
                    // Already unusable.
                }
                database = null;
            }
            SQLiteException failure = new SQLiteException("Failed to open " + databaseName
                    + " at version " + newVersion + ": " + e.getMessage());
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public synchronized SQLiteDatabase getReadableDatabase() {
        // This helper has no read-only fallback: openOrCreateDatabase() always requests a writable
        // handle. Reuse the checked path so reads cannot observe a schema whose migration failed.
        return getWritableDatabase();
    }

    /**
     * Returns the SQLiteDatabase
     *
     * @return
     */
    private synchronized SQLiteDatabase getDatabaseFile() {
        try {
            File aware_folder;
            if (mContext.getResources().getBoolean(R.bool.internalstorage)) {
                // Internal storage.  This is not acceassible to any other apps and is removed once
                // app is uninstalled.  Plugins can't use it.  Hard-coded to off, only change if
                // you know what you are doing.  Beware!
                aware_folder = mContext.getFilesDir();
            } else if (!mContext.getResources().getBoolean(R.bool.standalone)) {
                // sdcard/AWARE/ (shareable, does not delete when uninstalling)
                aware_folder = new File(Environment.getExternalStoragePublicDirectory("AWARE").toString());
            } else {
                if (isEmulator()) {
                    aware_folder = mContext.getFilesDir();
                } else {
                    // sdcard/Android/<app_package_name>/AWARE/ (not shareable, deletes when uninstalling package)
                    aware_folder = new File(ContextCompat.getExternalFilesDirs(mContext, null)[0] + "/AWARE");
                }
            }

            if (!aware_folder.exists()) {
                aware_folder.mkdirs();
            }

            database = SQLiteDatabase.openOrCreateDatabase(new File(aware_folder, this.databaseName).getPath(), this.cursorFactory);
            return database;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to open database file " + databaseName + ": " + e.getMessage());
            throw e;
        }
    }

    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }
}
