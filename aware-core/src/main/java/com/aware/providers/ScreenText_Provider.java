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

import java.util.HashMap;


public class ScreenText_Provider extends ContentProvider {

    private static final int DATABASE_VERSION = 1;

    /**
     * Authority of Battery content provider
     */
    public static String AUTHORITY = "com.aware.provider.screentext";


    // ContentProvider query paths
    private static final int SCREEN_TEXT = 1;
    private static final int SCREEN_TEXT_ID = 2;

    public static final class ScreenTextData implements BaseColumns {
        private ScreenTextData() {
        }

        public static final Uri CONTENT_URI = Uri.parse("content://" + ScreenText_Provider.AUTHORITY + "/screentext");
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.aware.screentext";
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.aware.screentext";

        public static final String _ID = "_id";
        public static final String TIMESTAMP = "timestamp";
        public static final String DEVICE_ID = "device_id";
        public static final String CLASS_NAME = "class_name";
        public static final String PACKAGE_NAME = "package_name";
        public static final String TEXT = "text";
        public static final String USER_ACTION = "user_action";
        public static final String EVENT_TYPE = "event_type";

    }




    public static String DATABASE_NAME = "screentext.db";

    public static final String[] DATABASE_TABLES = {"screentext"};
    public static final String[] TABLES_FIELDS = {
            // screen text
            ScreenTextData._ID + " integer primary key autoincrement,"
                    + ScreenTextData.TIMESTAMP + " real default 0,"
                    + ScreenTextData.DEVICE_ID + " text default '',"
                    + ScreenTextData.CLASS_NAME + " text default '',"
                    + ScreenTextData.PACKAGE_NAME + " text default '',"
                    + ScreenTextData.TEXT + " longtext default '',"
                    + ScreenTextData.USER_ACTION + " integer default 0,"
                    + ScreenTextData.EVENT_TYPE + " integer default 0"
    };



    private UriMatcher sUriMatcher = null;
    private HashMap<String, String> screenTextMap = null;

    private DatabaseHelper dbHelper;
    private static SQLiteDatabase database;


    private void initialiseDatabase() {
        if (dbHelper == null)
            dbHelper = new DatabaseHelper(getContext(), DATABASE_NAME, null, DATABASE_VERSION, DATABASE_TABLES, TABLES_FIELDS);
        if (database == null)
            database = dbHelper.getWritableDatabase();
    }


    /**
     * Returns the provider authority that is dynamic
     *
     * @return
     */
    public static String getAuthority(Context context) {
        AUTHORITY = context.getPackageName() + ".provider.screentext";
        return AUTHORITY;
    }


    @Override
    public boolean onCreate() {

        AUTHORITY = getContext().getPackageName() + ".provider.screentext";

        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(ScreenText_Provider.AUTHORITY, DATABASE_TABLES[0],
                SCREEN_TEXT);
        sUriMatcher.addURI(ScreenText_Provider.AUTHORITY, DATABASE_TABLES[0]
                + "/#", SCREEN_TEXT_ID);


        screenTextMap = new HashMap<String, String>();
        screenTextMap.put(ScreenTextData._ID, ScreenTextData._ID);
        screenTextMap.put(ScreenTextData.TIMESTAMP, ScreenTextData.TIMESTAMP);
        screenTextMap.put(ScreenTextData.DEVICE_ID, ScreenTextData.DEVICE_ID);
        screenTextMap.put(ScreenTextData.CLASS_NAME, ScreenTextData.CLASS_NAME);
        screenTextMap.put(ScreenTextData.PACKAGE_NAME, ScreenTextData.PACKAGE_NAME);
        screenTextMap.put(ScreenTextData.TEXT, ScreenTextData.TEXT);
        screenTextMap.put(ScreenTextData.USER_ACTION, ScreenTextData.USER_ACTION);
        screenTextMap.put(ScreenTextData.EVENT_TYPE, ScreenTextData.EVENT_TYPE);

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        initialiseDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true);
        switch (sUriMatcher.match(uri)) {
            case SCREEN_TEXT:
                qb.setTables(DATABASE_TABLES[0]);
                qb.setProjectionMap(screenTextMap);
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
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        switch (sUriMatcher.match(uri)) {
            case SCREEN_TEXT:
                return ScreenTextData.CONTENT_TYPE;
            case SCREEN_TEXT_ID:
                return ScreenTextData.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        initialiseDatabase();

        ContentValues values = (initialValues != null) ? new ContentValues(initialValues) : new ContentValues();

        database.beginTransaction();

        switch (sUriMatcher.match(uri)) {
            case SCREEN_TEXT:
                long screen_text_id = database.insertWithOnConflict(DATABASE_TABLES[0],
                        ScreenTextData.DEVICE_ID, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (screen_text_id > 0) {
                    Uri screenTextUri = ContentUris.withAppendedId(
                            ScreenTextData.CONTENT_URI, screen_text_id);
                    getContext().getContentResolver().notifyChange(screenTextUri, null, false);
                    database.setTransactionSuccessful();
                    database.endTransaction();
                    return screenTextUri;
                }
                database.endTransaction();
                throw new SQLException("Failed to insert row into " + uri);
            default:
                database.endTransaction();
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        initialiseDatabase();

        //lock database for transaction
        database.beginTransaction();

        int count;
        switch (sUriMatcher.match(uri)) {
            case SCREEN_TEXT:
                count = database.delete(DATABASE_TABLES[0], selection,
                        selectionArgs);
                break;
            default:
                database.endTransaction();
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        database.setTransactionSuccessful();
        database.endTransaction();

        getContext().getContentResolver().notifyChange(uri, null, false);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        initialiseDatabase();

        database.beginTransaction();

        int count;
        switch (sUriMatcher.match(uri)) {
            case SCREEN_TEXT:
                count = database.update(DATABASE_TABLES[0], values, selection,
                        selectionArgs);
                break;
            default:
                database.endTransaction();
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        database.setTransactionSuccessful();
        database.endTransaction();

        getContext().getContentResolver().notifyChange(uri, null, false);
        return count;
    }
}
