package com.aware.plugin.ambient_noise;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;

import com.aware.Aware;

import ca.uol.aig.fftpack.RealDoubleFFT;
import org.json.JSONException;
import org.json.JSONObject;

public class AudioAnalysis {

    private Context context;
    private static short[] audio_data;

    public AudioAnalysis(Context c, short[] audio) {
        context = c;
        audio_data = audio;
    }

    /**
     * Get sample Root Mean Squares value. Used to detect speech.
     *
     * @return RMS value
     */
    public double getRMS() {
        double sum = 0d;
        for (short data : audio_data) {
            sum += data;
        }
        double average = sum / audio_data.length;
        double sumMeanSquare = 0d;
        for (short data : audio_data) {
            sumMeanSquare += Math.pow(data - average, 2d);
        }
        double averageMeanSquare = sumMeanSquare / audio_data.length;
        return Math.sqrt(averageMeanSquare);
    }

    public boolean isSilent(double db) {
        double threshold;
        try {
            threshold = Double.parseDouble(Aware.getSetting(context, Settings.PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD));
        } catch (NumberFormatException e) {
            threshold = 50;
        }
        return (db <= threshold);
    }

    public float getFrequency() {
        int buffer_size = AudioRecord.getMinBufferSize(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 10;

        double[] fft = new double[buffer_size];
        for (int i = 0; i < buffer_size; i++) {
            fft[i] = (double) audio_data[i] / 32768.0; //signed 16-bit
        }

        RealDoubleFFT transformer = new RealDoubleFFT(buffer_size);
        transformer.ft(fft);

        double hz = 0;
        for (double d : fft) {
            if (Math.abs(d) > hz) hz = Math.abs(d);
        }

        return (float) hz;
    }

    /**
     * Relative ambient noise in dB
     *
     * @return dB level
     */
    public double getdB() {
        if (audio_data.length == 0) return 0;
        double amplitude = -1;
        for (short data : audio_data) {
            if (amplitude < data) {
                amplitude = data;
            }
        }
        if (amplitude == 0) return 0;
        return 96 - Math.abs(20 * Math.log10(amplitude/32768.0));
    }
}
