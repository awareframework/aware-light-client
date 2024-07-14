package com.aware.providers;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aware.utils.DatabaseHelper;

import java.util.Objects;

public class ScreenShot_Provider extends ContentProvider {

    private static final int DATABASE_VERSION = 1;
    public static final String AUTHORITY = "com.aware.provider.screenshot";
    private static final int SCREENSHOT = 1;

    public static final class ScreenshotData {
        private ScreenshotData() {
        }

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/screenshot");
        public static final String _ID = "_id";
        public static final String TIMESTAMP = "timestamp";
        public static final String DEVICE_ID = "device_id";
        public static final String IMAGE_DATA = "image_data";
    }

    public static final String DATABASE_NAME = "screenshot.db";
    public static final String[] DATABASE_TABLES = {"screenshot"};
    public static final String[] TABLES_FIELDS = {
            ScreenshotData._ID + " integer primary key autoincrement,"
                    + ScreenshotData.TIMESTAMP + " real default 0,"
                    + ScreenshotData.DEVICE_ID + " text default '',"
                    + ScreenshotData.IMAGE_DATA + " blob"
    };

    private UriMatcher sUriMatcher;
    private DatabaseHelper dbHelper;
    private static SQLiteDatabase database;

    private void initialiseDatabase() {
        if (dbHelper == null)
            dbHelper = new DatabaseHelper(getContext(), DATABASE_NAME, null, DATABASE_VERSION, DATABASE_TABLES, TABLES_FIELDS);
        if (database == null)
            database = dbHelper.getWritableDatabase();
        Log.d("ScreenShot_Provider", "Database initialized");
    }

    @Override
    public boolean onCreate() {
        Log.d("ScreenShot_Provider", "onCreate called");

        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, "screenshot", SCREENSHOT);

        Log.d("ScreenShot_Provider", "Provider setup complete");
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        initialiseDatabase();

        ContentValues values = (initialValues != null) ? new ContentValues(initialValues) : new ContentValues();

        long timestamp = values.getAsLong(ScreenshotData.TIMESTAMP);
        byte[] imageData = values.getAsByteArray(ScreenshotData.IMAGE_DATA);
        int imageSize = (imageData != null) ? imageData.length : 0;

        Log.d("ScreenShot_Provider", "Inserting screenshot with timestamp: " + timestamp + " and image size: " + imageSize + " bytes");

        database.beginTransaction();
        try {
            if (sUriMatcher.match(uri) == SCREENSHOT) {
                long screenshot_id = database.insertWithOnConflict(DATABASE_TABLES[0], ScreenshotData.DEVICE_ID, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (screenshot_id > 0) {
                    Uri screenshotUri = ContentUris.withAppendedId(ScreenshotData.CONTENT_URI, screenshot_id);
                    Objects.requireNonNull(getContext()).getContentResolver().notifyChange(screenshotUri, null, false);
                    database.setTransactionSuccessful();
                    Log.d("ScreenShot_Provider", "Insert successful, new row id: " + screenshot_id);
                    return screenshotUri;
                }
                throw new SQLException("Failed to insert row into " + uri);
            }
            throw new IllegalArgumentException("Unknown URI " + uri);
        } finally {
            database.endTransaction();
        }
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] strings, @Nullable String s, @Nullable String[] strings1, @Nullable String s1) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
