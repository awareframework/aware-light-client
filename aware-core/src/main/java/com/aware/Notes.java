package com.aware;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncRequest;
import android.os.Bundle;
import android.util.Log;

import com.aware.providers.Notes_Provider;
import com.aware.syncadapters.Notes_Sync;
import com.aware.utils.Aware_Sensor;

/**
 * Created by denzil on 23/10/14.
 */
public class Notes extends Aware_Sensor {

    /**
     * Broadcasted event: keyboard input detected
     */
    public static final String ACTION_AWARE_NOTES = "ACTION_AWARE_NOTES";

    public static final String ACTION_NOTE_STATUS = "ACTION_NOTE_STATUS";

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = Notes_Provider.getAuthority(this);

        TAG = "AWARE::Notes";

        Log.d(TAG, "Notes service created!");
    }


    private final BroadcastReceiver noteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case Aware.ACTION_AWARE_SYNC_DATA:
                        Bundle sync = new Bundle();
                        sync.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                        sync.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                        ContentResolver.requestSync(Aware.getAWAREAccount(context), Notes_Provider.AUTHORITY, sync);
                }
            }
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Notes_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                Notes_Provider.getAuthority(this),
                Bundle.EMPTY
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {
            DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

            Log.d(TAG, "Notes service active...");

            if (Aware.isStudy(this)) {
                Log.d(TAG, "sett up sync frequency");
                ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), Notes_Provider.getAuthority(this), 1);
                ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Notes_Provider.getAuthority(this), true);
                long frequency = Aware.getSettingAsLong(this, Aware_Preferences.FREQUENCY_WEBSERVICE, 30) * 60;
                SyncRequest request = new SyncRequest.Builder()
                        .syncPeriodic(frequency, frequency / 3)
                        .setSyncAdapter(Aware.getAWAREAccount(this), Notes_Provider.getAuthority(this))
                        .setExtras(new Bundle()).build();
                ContentResolver.requestSync(request);
            }
            registerNoteReceiver();
            Log.d(TAG, "Sync configured for authority: " + Notes_Provider.getAuthority(this));
            Log.d(TAG, "Is syncable: " + ContentResolver.getIsSyncable(Aware.getAWAREAccount(this), Notes_Provider.getAuthority(this)));
            Log.d(TAG, "Auto sync: " + ContentResolver.getSyncAutomatically(Aware.getAWAREAccount(this), Notes_Provider.getAuthority(this)));
        }

        return START_STICKY;
    }

    private void registerNoteReceiver(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(Aware.ACTION_AWARE_SYNC_DATA);
        registerReceiver(noteReceiver, filter);
    }
}
