package com.aware.providers;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import com.aware.Aware;
import com.aware.utils.DatabaseHelper;
import com.aware.utils.DatabaseTransaction;

import java.util.HashMap;

public class Notes_Provider extends ContentProvider {
    private static final int DATABASE_VERSION = 5;

    public static String AUTHORITY = "com.aware.provider.notes";

    private static final int NOTES = 1;
    private static final int NOTES_ID = 2;

    public static final class Notes_Data implements BaseColumns {
        private Notes_Data() {}

        public static final Uri CONTENT_URI = Uri.parse("content://"
                + Notes_Provider.AUTHORITY + "/notes");
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.aware.notes";
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.aware.notes";

        public static final String _ID = "_id";
        public static final String TIMESTAMP = "timestamp";
        public static final String DEVICE_ID = "device_id";
        public static final String NOTE = "note";

    }

    public static String DATABASE_NAME = "notes.db";

    public static final String[] DATABASE_TABLES = {"notes"};

    public static final String[] TABLES_FIELDS = {
            Notes_Data._ID + " integer primary key autoincrement,"
                    + Notes_Data.TIMESTAMP + " real default 0,"
                    + Notes_Data.DEVICE_ID + " text default '',"
                    + Notes_Data.NOTE + " text default ''"
    };

    private UriMatcher sUriMatcher = null;
    private HashMap<String, String> notesMap = null;

    private DatabaseHelper dbHelper;
    private static SQLiteDatabase database;

    private void initialiseDatabase() {
        if (dbHelper == null)
            dbHelper = new DatabaseHelper(getContext(), DATABASE_NAME, null, DATABASE_VERSION, DATABASE_TABLES, TABLES_FIELDS);
        if (database == null)
            database = dbHelper.getWritableDatabase();
    }


    /**
     * Delete entry from the database
     */
    @Override
    public synchronized int delete(Uri uri, String selection, String[] selectionArgs) {

        initialiseDatabase();

        //lock database for transaction
        try (DatabaseTransaction transaction = DatabaseTransaction.begin(database)) {

            int count;
            switch (sUriMatcher.match(uri)) {
                case NOTES:
                    count = database.delete(DATABASE_TABLES[0], selection,
                            selectionArgs);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }

            transaction.commit();

            getContext().getContentResolver().notifyChange(uri, null, false);
            return count;
        }
    }

    @Override
    public String getType(Uri uri) {
        switch (sUriMatcher.match(uri)) {
            case NOTES:
                return Notes_Data.CONTENT_TYPE;
            case NOTES_ID:
                return Notes_Data.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public synchronized Uri insert(Uri uri, ContentValues initialValues) {

        initialiseDatabase();

        ContentValues values = (initialValues != null) ? new ContentValues(initialValues) : new ContentValues();

        try (DatabaseTransaction transaction = DatabaseTransaction.begin(database)) {


            switch (sUriMatcher.match(uri)) {
                case NOTES:
                    long notes_id = database.insertWithOnConflict(DATABASE_TABLES[0],
                            null, values, SQLiteDatabase.CONFLICT_IGNORE);
                    transaction.commit();
                    if (notes_id > 0) {
                        Uri notesUri = ContentUris.withAppendedId(
                                Notes_Data.CONTENT_URI, notes_id);
                        getContext().getContentResolver().notifyChange(notesUri, null, false);
                        return notesUri;
                    }
                    throw new SQLException("Failed to insert row into " + uri);
                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
        }
    }

    /**
     * Returns the provider authority that is dynamic
     * @return
     */
    public static String getAuthority(Context context) {
        AUTHORITY = context.getPackageName() + ".provider.notes";
        return AUTHORITY;
    }

    @Override
    public boolean onCreate() {
        AUTHORITY = getContext().getPackageName() + ".provider.notes";

        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY,
                DATABASE_TABLES[0], NOTES);
        sUriMatcher.addURI(AUTHORITY, DATABASE_TABLES[0]
                + "/#", NOTES_ID);

        notesMap = new HashMap<>();
        notesMap.put(Notes_Data._ID,Notes_Data._ID);
        notesMap.put(Notes_Data.TIMESTAMP, Notes_Data.TIMESTAMP);
        notesMap.put(Notes_Data.DEVICE_ID, Notes_Data.DEVICE_ID);
        notesMap.put(Notes_Data.NOTE, Notes_Data.NOTE);


        return true;
    }

    /**
     * Query entries from the database
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {

        initialiseDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true);
        switch (sUriMatcher.match(uri)) {
            case NOTES:
                qb.setTables(DATABASE_TABLES[0]);
                qb.setProjectionMap(notesMap);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        try {
            Cursor c = qb.query(database, projection, selection, selectionArgs,
                    null, null, sortOrder);
            c.setNotificationUri(getContext().getContentResolver(), uri);
            return c;
        } catch (IllegalStateException e) {
            if (Aware.DEBUG)
                Log.e(Aware.TAG, e.getMessage());
            throw e;
        }
    }

    /**
     * Update application on the database
     */
    @Override
    public synchronized int update(Uri uri, ContentValues values, String selection,
                                   String[] selectionArgs) {

        initialiseDatabase();

        try (DatabaseTransaction transaction = DatabaseTransaction.begin(database)) {

            int count;
            switch (sUriMatcher.match(uri)) {
                case NOTES:
                    count = database.update(DATABASE_TABLES[0], values, selection,
                            selectionArgs);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }

            transaction.commit();

            getContext().getContentResolver().notifyChange(uri, null, false);
            return count;
        }
    }
}
