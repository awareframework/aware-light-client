package com.aware.syncadapters;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.aware.providers.Applications_Provider;
import com.aware.providers.ScreenText_Provider;

public class ScreenText_Sync extends Service {
    private AwareSyncAdapter sSyncAdapter = null;
    private static final Object sSyncAdapterLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new AwareSyncAdapter(getApplicationContext(), true, true);


                sSyncAdapter.init(
                        ScreenText_Provider.DATABASE_TABLES,
                        ScreenText_Provider.TABLES_FIELDS,
                        new Uri[]{
                                ScreenText_Provider.ScreenTextData.CONTENT_URI,
                        });
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
