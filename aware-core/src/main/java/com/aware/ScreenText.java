package com.aware;


import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.legacy.content.WakefulBroadcastReceiver;

import com.aware.providers.Applications_Provider;
import com.aware.providers.Aware_Provider;
import com.aware.providers.Keyboard_Provider;
import com.aware.providers.ScreenText_Provider;
import com.aware.providers.Screen_Provider;
import com.aware.utils.Aware_Sensor;
import com.aware.utils.Scheduler;
import java.util.Timer;
import java.util.TimerTask;



/**
 * Service that track text from screen.
 * Text updates with a set time interval by researchers
 * Others (e.g. user actions) updates whenever occurring
 *
 * @author Tianyi Zhang
 */
public class ScreenText extends Aware_Sensor {

    private static String TAG = "AWARE::Screen_text";

    public String AUTHORITY = "";

//    private static final String SCHEDULER_APPLICATIONS_BACKGROUND = "SCHEDULER_APPLICATIONS_BACKGROUND";
//    public static final String ACTION_USER_INTERACT = "ACTION_USER_INTERACT";

    public static final String ACTION_SCREENTEXT_DETECT = "ACTION_SCREENTEXT_DETECT";


    public static final ScreentextBroadcaster screentext_BR = new ScreentextBroadcaster();
    public static class ScreentextBroadcaster extends BroadcastReceiver{

        @Override
        public void onReceive(Context context, Intent intent) {

//            if (Aware.isStudy(context)) {
//                ContentResolver.setIsSyncable(Aware.getAWAREAccount(context), Applications_Provider.getAuthority(context), 1);
//                ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(context), Applications_Provider.getAuthority(context), true);
//
//                long frequency = Long.parseLong(Aware.getSetting(context, Aware_Preferences.FREQUENCY_WEBSERVICE)) * 60;
//                SyncRequest request = new SyncRequest.Builder()
//                        .syncPeriodic(frequency, frequency / 3)
//                        .setSyncAdapter(Aware.getAWAREAccount(context), Applications_Provider.getAuthority(context))
//                        .setExtras(new Bundle()).build();
//                ContentResolver.requestSync(request);
//            }

            if (intent.getAction().equals(Aware.ACTION_AWARE_SYNC_DATA)) {

                Bundle sync = new Bundle();
                sync.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                sync.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

                ContentResolver.requestSync(Aware.getAWAREAccount(context), ScreenText_Provider.AUTHORITY, sync);
            }
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = ScreenText_Provider.getAuthority(this);

        TAG = "AWARE::ScreenText";

        if (Aware.DEBUG) Log.d(TAG, "ScreenText service created!");

        IntentFilter awareActions = new IntentFilter();
        awareActions.addAction(Aware.ACTION_AWARE_SYNC_CONFIG);
        awareActions.addAction(Aware.ACTION_AWARE_SYNC_DATA);
        awareActions.addAction(Aware.ACTION_QUIT_STUDY);
        registerReceiver(screentext_BR, awareActions);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        super.onStartCommand(intent, flags, startId);
        if (PERMISSIONS_OK) {
            DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

            Aware.setSetting(this, Aware_Preferences.STATUS_SCREENTEXT, true);
            if (Aware.DEBUG) Log.d(TAG, "ScreenText service active...");


            if (Aware.isStudy(this)) {
                ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), ScreenText_Provider.getAuthority(this), 1);
                ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), ScreenText_Provider.getAuthority(this), true);
                long frequency = Long.parseLong(Aware.getSetting(this, Aware_Preferences.FREQUENCY_WEBSERVICE)) * 60;
                SyncRequest request = new SyncRequest.Builder()
                        .syncPeriodic(frequency, frequency / 3)
                        .setSyncAdapter(Aware.getAWAREAccount(this), ScreenText_Provider.getAuthority(this))
                        .setExtras(new Bundle()).build();
                ContentResolver.requestSync(request);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), ScreenText_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                ScreenText_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (Aware.DEBUG) Log.d(TAG, "ScreenText service terminated...");
        unregisterReceiver(screentext_BR);
    }


    @Override
    public boolean onUnbind(Intent intent) {

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENTEXT).equals("true")) {
            try {
                if (screentext_BR != null) unregisterReceiver(screentext_BR);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "ScreenText service unbind...");
            }
        }
        return super.onUnbind(intent);
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
