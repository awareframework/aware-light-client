package com.aware.plugin.ambient_noise;

import android.Manifest;
import android.app.IntentService;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.aware.Aware;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Created by denzil on 31/07/16.
 */
public class AudioAnalyser extends IntentService {
    private static volatile long lastSampleCompletedAt;
    public static double sound_frequency;
    public static double sound_db;
    public static boolean is_silent;
    public static double sound_rms;

    public AudioAnalyser() {
        super(Aware.TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        // This IntentService is triggered directly by the Scheduler, not through Plugin's own
        // onStartCommand() (which normally ensures these via initializeSettings()) — so a config
        // sync that wipes settings (Aware.reset(), called on every applySettings()) can leave them
        // empty here even while the plugin keeps running, crashing the parseInt/parseDouble calls
        // below. Delegate to Plugin's own shared method rather than duplicating its defaults, so the
        // two can never drift out of sync.
        Plugin.initializeSettings(getApplicationContext());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w("AWARE::Ambient Noise", "Skipping sample: microphone consent is missing");
            return;
        }

        // IntentService serializes work but still queues every scheduler request. If a recording
        // takes longer than its interval, that queue can otherwise run back-to-back indefinitely.
        long configuredGapMs = Math.max(60_000L,
                Aware.getSettingAsLong(this, Settings.FREQUENCY_PLUGIN_AMBIENT_NOISE, 5)
                        * 60_000L);
        if (System.currentTimeMillis() - lastSampleCompletedAt < configuredGapMs) return;

        //Check if microphone is available right now
        if(!isMicrophoneAvailable(getApplicationContext())) {
            lastSampleCompletedAt = System.currentTimeMillis();
            return;
        }

        //Get minimum size of the buffer for pre-determined audio setup and minutes
        int buffer_size = AudioRecord.getMinBufferSize(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 10;
        if (buffer_size <= 0) {
            Log.e("AWARE::Ambient Noise", "Invalid AudioRecord buffer size: " + buffer_size);
            lastSampleCompletedAt = System.currentTimeMillis();
            return;
        }

        //Initialize audio recorder. Use MediaRecorder.AudioSource.VOICE_RECOGNITION to disable Automated Voice Gain from microphone and use DSP if available
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffer_size);

            // AudioRecord construction is synchronous. An uninitialized instance will not become
            // initialized by polling it, so the former loop could spin forever.
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e("AWARE::Ambient Noise", "AudioRecord failed to initialize");
                return;
            }

            recorder.startRecording();
            Log.d("AWARE::Ambient Noise", "Collecting audio sample...");

            int sampleSizeSeconds = Math.max(1, Math.min(300, Aware.getSettingAsInt(
                    getApplicationContext(),
                    Settings.PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE,
                    30)));

            long startedAt = System.currentTimeMillis();
            while (System.currentTimeMillis() - startedAt < sampleSizeSeconds * 1000L) {
                short[] realtime = new short[buffer_size];
                int read = recorder.read(realtime, 0, buffer_size);
                if (read <= 0) {
                    Log.e("AWARE::Ambient Noise", "AudioRecord read failed: " + read);
                    break;
                }

                AudioAnalysis audio_analysis = new AudioAnalysis(this, realtime);
                sound_rms = audio_analysis.getRMS();
                sound_frequency = audio_analysis.getFrequency();
                sound_db = audio_analysis.getdB();
                is_silent = audio_analysis.isSilent(sound_db);

                ContentValues data = new ContentValues();
                data.put(Provider.AmbientNoise_Data.TIMESTAMP, System.currentTimeMillis());
                data.put(Provider.AmbientNoise_Data.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
                data.put(Provider.AmbientNoise_Data.FREQUENCY, sound_frequency);
                data.put(Provider.AmbientNoise_Data.DECIBELS, sound_db);
                data.put(Provider.AmbientNoise_Data.RMS, sound_rms);
                data.put(Provider.AmbientNoise_Data.IS_SILENT, is_silent);
                data.put(Provider.AmbientNoise_Data.SILENCE_THRESHOLD, Aware.getSetting(getApplicationContext(), Settings.PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD));
                getContentResolver().insert(Provider.AmbientNoise_Data.CONTENT_URI, data);

                if (Plugin.getSensorObserver() != null)
                    Plugin.getSensorObserver().onRecording(data);
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e("AWARE::Ambient Noise", "Unable to collect audio sample", e);
        } finally {
            if (recorder != null) {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        recorder.stop();
                    } catch (IllegalStateException ignored) {
                    }
                }
                recorder.release();
            }
            lastSampleCompletedAt = System.currentTimeMillis();
        }

        Log.d("AWARE::Ambient Noise", "Finished audio sample...");
    }

    /**
     * Check if the microphone is available or not
     * @param context
     * @return
     */
    public static boolean isMicrophoneAvailable(Context context) {
        MediaRecorder recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
        recorder.setOutputFile(new File(context.getCacheDir(), "MediaUtil#micAvailTestFile").getAbsolutePath());
        boolean available = true;
        try {
            recorder.prepare();
            recorder.start();
        } catch (Exception exception) {
            available = false;
        }
        recorder.release();
        return available;
    }
}
