
package com.aware.utils;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.HashMap;

public class Aware_TTS extends Service implements OnInitListener {

    public static final String ACTION_AWARE_TTS_SPEAK = "ACTION_AWARE_TTS_SPEAK";

    private static final String TAG = "AWARE::TTS";
    public static final String EXTRA_TTS_TEXT = "tts_text";
    public static final String EXTRA_TTS_REQUESTER = "tts_requester";
    
    private TextToSpeech tts;
    private boolean ready = false;
    private String text;
    private String package_requester;
    private int latestStartId;

    /**
     * Speak the given text
     * @param text
     */
    public void speak(String text, int startId) {
        if( ready ) {
            latestStartId = startId;
            String utteranceId = Integer.toString(startId);
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
            } else {
                HashMap<String, String> params = new HashMap<>();
                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                tts.speak(text, TextToSpeech.QUEUE_ADD, params);
            }
        }
    }

    @Override
    public void onInit(int status) {
        if( status == TextToSpeech.SUCCESS ) {
            ready = true;
            if( text != null && text.length() > 0 ) {
                speak(text, latestStartId);
            }
        } else {
            ready = false;
            stopSelf();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AWARE_TTS_SPEAK);

        registerReceiver(awareTTS, filter);

        tts = new TextToSpeech(this, this);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override public void onDone(String utteranceId) {
                stopIfLatest(utteranceId);
            }

            @Override public void onError(String utteranceId) {
                stopIfLatest(utteranceId);
            }
        });
    }

    private void stopIfLatest(String utteranceId) {
        try {
            int completedStartId = Integer.parseInt(utteranceId);
            if (completedStartId == latestStartId) stopSelf(completedStartId);
        } catch (NumberFormatException ignored) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if( intent != null ) {
            latestStartId = startId;
            text = intent.getStringExtra(EXTRA_TTS_TEXT);
            package_requester = intent.getStringExtra(EXTRA_TTS_REQUESTER);

            if (!getPackageName().equalsIgnoreCase(package_requester)) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }

            if( tts != null && text != null && text.length() > 0 ) {
                speak(intent.getStringExtra(EXTRA_TTS_TEXT), startId);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver(awareTTS);

        if( tts != null ) tts.shutdown();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static final Aware_TTS_Receiver awareTTS = new Aware_TTS_Receiver();
    public static class Aware_TTS_Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null
                    && ACTION_AWARE_TTS_SPEAK.equals(intent.getAction())
                    && context.getPackageName().equals(
                            intent.getStringExtra(EXTRA_TTS_REQUESTER))) {
                Intent tts_work = new Intent( context, Aware_TTS.class );
                tts_work.putExtra(EXTRA_TTS_TEXT, intent.getStringExtra(EXTRA_TTS_TEXT));
                tts_work.putExtra(EXTRA_TTS_REQUESTER, context.getPackageName());
                context.startService(tts_work);
            }
        }
    }
}
