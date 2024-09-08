package com.aware.providers;

import android.content.ContentProvider;
import android.content.ContentResolver;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aware.Aware;
import com.aware.ScreenShot;
import com.aware.utils.DatabaseHelper;

import java.util.HashMap;
import java.util.Objects;

public class ScreenShot_Provider extends ContentProvider {

    private static final int DATABASE_VERSION = 1;
    public static String AUTHORITY = "com.aware.provider.screenshot";
    private static final int SCREENSHOT = 1;
    private static final int SCREENSHOT_ID = 2;


    public static final class ScreenshotData implements BaseColumns {
        private ScreenshotData() {
        }

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/screenshot");
        public static final String CONTENT_ITEM_TYPE = ContentResolver.CURSOR_ITEM_BASE_TYPE +
                "/vnd." + "aware.screenshot";
        public static final String CONTENT_TYPE =   ContentResolver.CURSOR_DIR_BASE_TYPE +
                "/vnd." + "aware.screenshot";
        public static final String _ID = "_id";
        public static final String TIMESTAMP = "timestamp";
        public static final String DEVICE_ID = "device_id";
        public static final String IMAGE_DATA = "image_data";
        public static final String PACKAGE_NAME = "package_name";
        public static final String APPLICATION_NAME = "application_name";
    }

    public static final String DATABASE_NAME = "screenshot.db";
    public static final String[] DATABASE_TABLES = {"screenshot"};
    public static final String[] TABLES_FIELDS = {
            ScreenshotData._ID + " integer primary key autoincrement,"
                    + ScreenshotData.TIMESTAMP + " real default 0,"
                    + ScreenshotData.PACKAGE_NAME + " text default '',"
                    + ScreenshotData.APPLICATION_NAME + " text default '',"
                    + ScreenshotData.DEVICE_ID + " text default '',"
                    + ScreenshotData.IMAGE_DATA + " blob"
    };

    private UriMatcher sUriMatcher;
    private DatabaseHelper dbHelper;
    private static SQLiteDatabase database;
    private HashMap<String, String>  screenshotDataMap = null;
    private void initialiseDatabase() {
        if (dbHelper == null)
            dbHelper = new DatabaseHelper(getContext(), DATABASE_NAME, null, DATABASE_VERSION, DATABASE_TABLES, TABLES_FIELDS);
        if (database == null)
            database = dbHelper.getWritableDatabase();
    }

    public static String getAuthority(Context context) {
        AUTHORITY = context.getPackageName() + ".provider.screenshot";
        return AUTHORITY;
    }

    @Override
    public boolean onCreate() {

        AUTHORITY = getContext().getPackageName() + ".provider.screenshot";

        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, DATABASE_TABLES[0], SCREENSHOT);
        sUriMatcher.addURI(AUTHORITY, DATABASE_TABLES[0] + "/#", SCREENSHOT_ID);

        screenshotDataMap = new HashMap<String,String>();
        screenshotDataMap.put(ScreenshotData._ID, ScreenshotData._ID);
        screenshotDataMap.put(ScreenshotData.TIMESTAMP, ScreenshotData.TIMESTAMP);
        screenshotDataMap.put(ScreenshotData.DEVICE_ID, ScreenshotData.DEVICE_ID);
        screenshotDataMap.put(ScreenshotData.IMAGE_DATA, ScreenshotData.IMAGE_DATA);
        screenshotDataMap.put(ScreenshotData.PACKAGE_NAME, ScreenshotData.PACKAGE_NAME);
        screenshotDataMap.put(ScreenshotData.APPLICATION_NAME, ScreenshotData.APPLICATION_NAME);

        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        initialiseDatabase();

        ContentValues values = (initialValues != null) ? new ContentValues(initialValues) : new ContentValues();

        long timestamp = values.getAsLong(ScreenshotData.TIMESTAMP);
        byte[] imageData = values.getAsByteArray(ScreenshotData.IMAGE_DATA);
        int imageSize = (imageData != null) ? imageData.length : 0;

        database.beginTransaction();
        switch (sUriMatcher.match(uri)){
            case SCREENSHOT:
                long screen_shot_id = database.insertWithOnConflict(DATABASE_TABLES[0],
                        ScreenShot_Provider.ScreenshotData.DEVICE_ID, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (screen_shot_id > 0) {
                    Uri screenShotUri = ContentUris.withAppendedId(ScreenshotData.CONTENT_URI, screen_shot_id);
                    Objects.requireNonNull(getContext()).getContentResolver().notifyChange(screenShotUri, null, false);
                    database.setTransactionSuccessful();
                    database.endTransaction();
                    return screenShotUri;
                }

                database.endTransaction();
                throw new SQLException("Failed to insert row into " + uri);

            default:
                database.endTransaction();
                throw new IllegalArgumentException("Unknown URI Insert " + uri);
        }
    }

    @Override
    public String getType(Uri uri) {

        switch (sUriMatcher.match(uri)){
            case SCREENSHOT:
                return ScreenshotData.CONTENT_TYPE;
            case SCREENSHOT_ID:
                return ScreenshotData.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("WTF Unknown URI " + uri);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        initialiseDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true);
        switch (sUriMatcher.match(uri)) {
            case SCREENSHOT:
                qb.setTables(DATABASE_TABLES[0]);
                qb.setProjectionMap(screenshotDataMap);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI query  " + uri);
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
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        initialiseDatabase();

        //lock database for transaction
        database.beginTransaction();

        int count;
        switch (sUriMatcher.match(uri)) {
            case SCREENSHOT:
                count = database.delete(DATABASE_TABLES[0], selection,
                        selectionArgs);
                break;
            default:
                database.endTransaction();
                throw new IllegalArgumentException("Unknown URI delete " + uri);
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
            case SCREENSHOT:
                count = database.update(DATABASE_TABLES[0], values, selection,
                        selectionArgs);
                break;
            default:
                database.endTransaction();
                throw new IllegalArgumentException("Unknown URI update" + uri);
        }

        database.setTransactionSuccessful();
        database.endTransaction();

        getContext().getContentResolver().notifyChange(uri, null, false);
        return count;
    }
}
