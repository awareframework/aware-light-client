package com.aware.plugin.openweather;

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
     * OpenWeather API endpoint
     */
    public static final String OPENWEATHER_API_URL = "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&lang=%s&units=%s&appid=%s";

    /**
     * State
     */
    public static final String STATUS_PLUGIN_OPENWEATHER = "status_plugin_openweather";

    /**
     * Measurement units
     */
    public static final String UNITS_PLUGIN_OPENWEATHER = "plugin_openweather_measurement_units";

    /**
     * How frequently we status the weather conditions
     */
    public static final String PLUGIN_OPENWEATHER_FREQUENCY = "plugin_openweather_frequency";

    /**
     * Openweather API key
     */
    public static final String OPENWEATHER_API_KEY = "plugin_openweather_api_key";

    /**
     * Enable/disable config updates
     */
    public static final String ENABLE_CONFIG_UPDATE = "enable_config_update";

    private CheckBoxPreference status;
    private ListPreference units;
    private ListPreference frequency;
    private EditTextPreference openweather_api_key;
    private static final String TAG = "openweather";

    /**
     * Preference summaries are visible on the settings screen without tapping into the field, so
     * the raw key must never be passed to setSummary() — only whether one is set, plus a few
     * trailing characters as a "is this the key I think it is" hint.
     */
    private static String maskApiKey(String key) {
        if (key == null || key.length() == 0) return "Not set";
        if (key.length() <= 4) return "••••";
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < key.length() - 4; i++) masked.append('•');
        masked.append(key.substring(key.length() - 4));
        return masked.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_openweather);

        if (Aware.getSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE).length() == 0) {
            Aware.setSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE, true);
        }

        if (Aware.getSetting(getApplicationContext(), STATUS_PLUGIN_OPENWEATHER).length() == 0) {
            Aware.setSetting(getApplicationContext(), STATUS_PLUGIN_OPENWEATHER, true);
        }

        if (Aware.getSetting(getApplicationContext(), UNITS_PLUGIN_OPENWEATHER).length() == 0) {
            Aware.setSetting(getApplicationContext(), UNITS_PLUGIN_OPENWEATHER, "metric");
        }

        if (Aware.getSetting(getApplicationContext(), PLUGIN_OPENWEATHER_FREQUENCY).length() == 0) {
            Aware.setSetting(getApplicationContext(), PLUGIN_OPENWEATHER_FREQUENCY, 30);
        }

        Log.d(TAG, "Syncing API KEY setting");
        if (Aware.getSetting(getApplicationContext(), OPENWEATHER_API_KEY).length() == 0) {
            Aware.setSetting(getApplicationContext(), OPENWEATHER_API_KEY, "");
        }
    }

    private void updatePreferencesState() {
        boolean configUpdateEnabled = Aware.getSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE).equals("true");

        status.setEnabled(configUpdateEnabled);
        units.setEnabled(configUpdateEnabled);
        frequency.setEnabled(configUpdateEnabled);
        openweather_api_key.setEnabled(configUpdateEnabled);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Status
        status = (CheckBoxPreference) findPreference(STATUS_PLUGIN_OPENWEATHER);
        String statusValue = Aware.getSetting(getApplicationContext(), STATUS_PLUGIN_OPENWEATHER);
        boolean isActive = statusValue.equals("true");
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(STATUS_PLUGIN_OPENWEATHER, isActive)
                .apply();
        status.setChecked(isActive);

        // Units
        units = (ListPreference) findPreference(UNITS_PLUGIN_OPENWEATHER);
        String unitsValue = Aware.getSetting(getApplicationContext(), UNITS_PLUGIN_OPENWEATHER);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(UNITS_PLUGIN_OPENWEATHER, unitsValue)
                .apply();
        units.setSummary(unitsValue);

        // Frequency
        frequency = (ListPreference) findPreference(PLUGIN_OPENWEATHER_FREQUENCY);
        String freqValue = Aware.getSetting(getApplicationContext(), PLUGIN_OPENWEATHER_FREQUENCY);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(PLUGIN_OPENWEATHER_FREQUENCY, freqValue)
                .apply();
        frequency.setValue(freqValue);
        frequency.setSummary("Every " + freqValue + " minute(s)");

        openweather_api_key = (EditTextPreference) findPreference(OPENWEATHER_API_KEY);
        String apiKeyValue = Aware.getSetting(getApplicationContext(), OPENWEATHER_API_KEY);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(OPENWEATHER_API_KEY, apiKeyValue)
                .apply();
        openweather_api_key.setText(apiKeyValue);
        openweather_api_key.setSummary(maskApiKey(apiKeyValue));

        // Update preferences state based on enable_config_update
        updatePreferencesState();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference preference = findPreference(key);
        boolean configUpdateEnabled = Aware.getSetting(getApplicationContext(), ENABLE_CONFIG_UPDATE).equals("true");

        if (!configUpdateEnabled && !key.equals(STATUS_PLUGIN_OPENWEATHER)) {
            Log.d(TAG, "Config updates disabled. Ignoring change to: " + key);
            return;
        }

        if (preference.getKey().equals(STATUS_PLUGIN_OPENWEATHER)) {
            boolean isChecked = sharedPreferences.getBoolean(key, false);
            Aware.setSetting(getApplicationContext(), key, isChecked);
            status.setChecked(isChecked);

            if (isChecked) {
                Aware.startPlugin(getApplicationContext(), "com.aware.plugin.openweather");
            } else {
                Aware.stopPlugin(getApplicationContext(), "com.aware.plugin.openweather");
            }
        }
        else if (preference.getKey().equals(UNITS_PLUGIN_OPENWEATHER)) {
            String value = sharedPreferences.getString(key, "metric");
            Aware.setSetting(getApplicationContext(), key, value);
            preference.setSummary(value);
        }
        else if (preference.getKey().equals(PLUGIN_OPENWEATHER_FREQUENCY)) {
            String value = sharedPreferences.getString(key, "30");
            Aware.setSetting(getApplicationContext(), key, value);
            preference.setSummary("Every " + value + " minute(s)");
        }
        else if (preference.getKey().equals(OPENWEATHER_API_KEY)) {
            String value = sharedPreferences.getString(key, "");
            Aware.setSetting(getApplicationContext(), key, value);
            preference.setSummary(maskApiKey(value));
        }
    }
}
