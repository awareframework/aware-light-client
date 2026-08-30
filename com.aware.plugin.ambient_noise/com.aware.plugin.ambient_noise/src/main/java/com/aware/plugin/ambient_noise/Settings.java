package com.aware.plugin.ambient_noise;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.Log;

import com.aware.Aware;
import com.aware.ui.AppCompatPreferenceActivity;

public class Settings extends AppCompatPreferenceActivity implements OnSharedPreferenceChangeListener {

    /**
     * Activate/deactivate plugin
     */
    public static final String STATUS_PLUGIN_AMBIENT_NOISE = "status_plugin_ambient_noise";

    /**
     * How frequently do we sample the microphone (default = 5) in minutes
     */
    public static final String FREQUENCY_PLUGIN_AMBIENT_NOISE = "frequency_plugin_ambient_noise";

    /**
     * For how long we listen (default = 30) in seconds
     */
    public static final String PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE = "plugin_ambient_noise_sample_size";

    /**
     * Silence threshold (default = 50) in dB
     */
    public static final String PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD = "plugin_ambient_noise_silence_threshold";

    /**
     * Enable/disable config updates
     */
    public static final String ENABLE_CONFIG_UPDATE = "enable_config_update";

    private CheckBoxPreference active;
    private ListPreference frequency;
    private EditTextPreference listen, silence;
    private static final String TAG = "ambient_noise";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_ambient_noise);

        if (Aware.getSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE).length() == 0) {
            Aware.setSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE, true);
        }

        if (Aware.getSetting(getApplicationContext(), STATUS_PLUGIN_AMBIENT_NOISE).length() == 0) {
            Aware.setSetting(getApplicationContext(), STATUS_PLUGIN_AMBIENT_NOISE, true);
        }

        if (Aware.getSetting(getApplicationContext(), FREQUENCY_PLUGIN_AMBIENT_NOISE).length() == 0) {
            Aware.setSetting(getApplicationContext(), FREQUENCY_PLUGIN_AMBIENT_NOISE, 5);
        }

        if (Aware.getSetting(getApplicationContext(), PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE).length() == 0) {
            Aware.setSetting(getApplicationContext(), PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE, 30);
        }

        if (Aware.getSetting(getApplicationContext(), PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD).length() == 0) {
            Aware.setSetting(getApplicationContext(), PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD, 50);
        }
    }

    private void updatePreferencesState() {
        boolean configUpdateEnabled = Aware.getSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE).equals("true");

        active.setEnabled((configUpdateEnabled));
        frequency.setEnabled(configUpdateEnabled);
        listen.setEnabled(configUpdateEnabled);
        silence.setEnabled(configUpdateEnabled);
    }

    @Override
    protected void onResume() {
        super.onResume();

        active = (CheckBoxPreference) findPreference(STATUS_PLUGIN_AMBIENT_NOISE);
        String statusValue = Aware.getSetting(getApplicationContext(), STATUS_PLUGIN_AMBIENT_NOISE);
        boolean isActive = statusValue.equals("true");
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(STATUS_PLUGIN_AMBIENT_NOISE, isActive)
                .apply();
        active.setChecked(isActive);

        // Frequency
        frequency = (ListPreference) findPreference(FREQUENCY_PLUGIN_AMBIENT_NOISE);
        String freqValue = Aware.getSetting(getApplicationContext(), FREQUENCY_PLUGIN_AMBIENT_NOISE);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(FREQUENCY_PLUGIN_AMBIENT_NOISE, freqValue)
                .apply();
        frequency.setValue(freqValue);
        frequency.setSummary("Every " + freqValue + " minutes");

        // Listen duration
        listen = (EditTextPreference) findPreference(PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE);
        String listenValue = Aware.getSetting(getApplicationContext(), PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE, listenValue)
                .apply();
        listen.setText(listenValue);
        listen.setSummary("Listen " + listenValue + " second(s)");

        // Silence threshold
        silence = (EditTextPreference) findPreference(PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD);
        String silenceValue = Aware.getSetting(getApplicationContext(), PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD, silenceValue)
                .apply();
        silence.setText(silenceValue);
        silence.setSummary("Silent below " + silenceValue + "dB");

        // Update preferences state based on enable_config_update
        updatePreferencesState();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference preference = findPreference(key);
        boolean configUpdateEnabled = Aware.getSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE).equals("true");

        if (!configUpdateEnabled && !key.equals(STATUS_PLUGIN_AMBIENT_NOISE)) {
            Log.d(TAG, "Config updates disabled. Ignoring change to: " + key);
            return;
        }

        if (preference.getKey().equals(STATUS_PLUGIN_AMBIENT_NOISE)) {
            boolean isChecked = sharedPreferences.getBoolean(key, false);
            Aware.setSetting(getApplicationContext(), key, isChecked);
            active.setChecked(isChecked);

            if (isChecked) {
                Aware.startPlugin(getApplicationContext(), "com.aware.plugin.ambient_noise");
            } else {
                Aware.stopPlugin(getApplicationContext(), "com.aware.plugin.ambient_noise");
            }
        }
        else if (preference.getKey().equals(FREQUENCY_PLUGIN_AMBIENT_NOISE)) {
            String value = sharedPreferences.getString(key, "5");
            Aware.setSetting(getApplicationContext(), key, value);
            frequency.setSummary("Every " + value + " minutes");
        }
        else if (preference.getKey().equals(PLUGIN_AMBIENT_NOISE_SAMPLE_SIZE)) {
            String value = sharedPreferences.getString(key, "30");
            Aware.setSetting(getApplicationContext(), key, value);
            listen.setSummary("Listen " + value + " second(s)");
        }
        else if (preference.getKey().equals(PLUGIN_AMBIENT_NOISE_SILENCE_THRESHOLD)) {
            String value = sharedPreferences.getString(key, "50");
            Aware.setSetting(getApplicationContext(), key, value);
            silence.setSummary("Silent below " + value + "dB");
        }
    }
}
