package com.aware.plugin.openweather;

import android.Manifest;
import android.accounts.Account;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SyncRequest;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.utils.Aware_Plugin;
import com.aware.utils.Http;
import com.aware.utils.PluginsManager;
import com.aware.utils.Scheduler;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public class Plugin extends Aware_Plugin implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    /**
     * Shared context: new OpenWeather data is available
     */
    public static final String ACTION_AWARE_PLUGIN_OPENWEATHER = "ACTION_AWARE_PLUGIN_OPENWEATHER";

    /**
     * Request latest Openweather data
     */
    private static final String ACTION_AWARE_PLUGIN_OPENWEATHER_UPDATE = "ACTION_AWARE_PLUGIN_OPENWEATHER_UPDATE";

    /**
     * Extra string: openweather<br/>
     * JSONObject from OpenWeather<br/>
     */
    public static final String EXTRA_OPENWEATHER = "openweather";

    public static ContextProducer sContextProducer;
    public static ContentValues sOpenWeather;

    public static GoogleApiClient mGoogleApiClient;
    private final static LocationRequest locationRequest = new LocationRequest();
    private static PendingIntent pIntent;

    private static final String SCHEDULER_PLUGIN_OPENWEATHER = "scheduler_plugin_openweather";

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = Provider.getAuthority(this);

        TAG = "AWARE: OpenWeather";

        CONTEXT_PRODUCER = new ContextProducer() {
            @Override
            public void onContext() {
                Intent mOpenWeather = new Intent(ACTION_AWARE_PLUGIN_OPENWEATHER);
                mOpenWeather.putExtra(EXTRA_OPENWEATHER, sOpenWeather);
                sendBroadcast(mOpenWeather);
            }
        };
        sContextProducer = CONTEXT_PRODUCER;

        //Permissions needed for our plugin
        REQUIRED_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (!is_google_services_available()) {
            if (DEBUG)
                Log.e(TAG, "Google Services Fused location are not available on this device");
            stopSelf();
        } else {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApiIfAvailable(LocationServices.API)
                    .build();

            Intent openWeatherIntent = new Intent(getApplicationContext(), Plugin.class);
            openWeatherIntent.setAction(ACTION_AWARE_PLUGIN_OPENWEATHER_UPDATE);
            pIntent = PendingIntent.getService(getApplicationContext(), 0, openWeatherIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {

            DEBUG = Aware.getSetting(getApplicationContext(), Aware_Preferences.DEBUG_FLAG).equals("true");

            if (Aware.getSetting(getApplicationContext(), Settings.STATUS_PLUGIN_OPENWEATHER).length() == 0) {
                Aware.setSetting(getApplicationContext(), Settings.STATUS_PLUGIN_OPENWEATHER, true);
            } else {
                if (Aware.getSetting(getApplicationContext(), Settings.STATUS_PLUGIN_OPENWEATHER).equalsIgnoreCase("false")) {
                    Aware.stopPlugin(getApplicationContext(), getPackageName());
                    return START_STICKY;
                }
            }

            if (Aware.getSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER).length() == 0)
                Aware.setSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER, "metric");

            if (Aware.getSetting(getApplicationContext(), Settings.PLUGIN_OPENWEATHER_FREQUENCY).length() == 0)
                Aware.setSetting(getApplicationContext(), Settings.PLUGIN_OPENWEATHER_FREQUENCY, 30);


            if (mGoogleApiClient != null && !mGoogleApiClient.isConnected())
                mGoogleApiClient.connect();

            try {
                Scheduler.Schedule openweather = Scheduler.getSchedule(this, SCHEDULER_PLUGIN_OPENWEATHER);
                long openweatherFrequency = Aware.getSettingAsLong(getApplicationContext(), Settings.PLUGIN_OPENWEATHER_FREQUENCY, 30);
                if (openweather == null || openweather.getInterval() != openweatherFrequency) {
                    openweather = new Scheduler.Schedule(SCHEDULER_PLUGIN_OPENWEATHER);
                    openweather
                            .setInterval(openweatherFrequency)
                            .setActionType(Scheduler.ACTION_TYPE_SERVICE)
                            .setActionIntentAction(ACTION_AWARE_PLUGIN_OPENWEATHER_UPDATE)
                            .setActionClass(getPackageName() + "/" + Plugin.class.getName());
                    Scheduler.saveSchedule(this, openweather);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            if (intent != null && intent.getAction() != null && intent.getAction().equalsIgnoreCase(ACTION_AWARE_PLUGIN_OPENWEATHER_UPDATE)) {
                try {
                    final Location location = LocationServices.FusedLocationApi.getLastLocation(Plugin.mGoogleApiClient);
                    if (location != null) {
                        Thread network = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String server_response = new Http().dataGET(
                                            String.format(
                                                    Locale.ENGLISH,
                                                    Settings.OPENWEATHER_API_URL,
                                                    location.getLatitude(),
                                                    location.getLongitude(),
                                                    Locale.getDefault().getLanguage(),
                                                    Aware.getSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER),
                                                    Aware.getSetting(getApplicationContext(), Settings.OPENWEATHER_API_KEY)),
                                            false);

                                    if (server_response == null || server_response.length() == 0 || server_response.contains("Invalid API key"))
                                        return;

                                    JSONObject raw_data = new JSONObject(server_response);

                                    Log.d(Aware.TAG, "OpenWeather answer: " + raw_data.toString(5));

                                    JSONObject wind = raw_data.getJSONObject("wind");
                                    JSONObject weather_characteristics = raw_data.getJSONObject("main");
                                    JSONObject weather = raw_data.getJSONArray("weather").getJSONObject(0);
                                    JSONObject clouds = raw_data.getJSONObject("clouds");

                                    JSONObject rain = null;
                                    if (raw_data.opt("rain") != null) {
                                        rain = raw_data.optJSONObject("rain");
                                    }
                                    JSONObject snow = null;
                                    if (raw_data.opt("snow") != null) {
                                        snow = raw_data.optJSONObject("snow");
                                    }
                                    JSONObject sys = raw_data.getJSONObject("sys");

                                    ContentValues weather_data = new ContentValues();
                                    weather_data.put(Provider.OpenWeather_Data.TIMESTAMP, System.currentTimeMillis());
                                    weather_data.put(Provider.OpenWeather_Data.DEVICE_ID, Aware.getDeviceID(getApplicationContext()));
                                    weather_data.put(Provider.OpenWeather_Data.CITY, raw_data.getString("name"));
                                    weather_data.put(Provider.OpenWeather_Data.TEMPERATURE, weather_characteristics.getDouble("temp"));
                                    weather_data.put(Provider.OpenWeather_Data.TEMPERATURE_MAX, weather_characteristics.getDouble("temp_max"));
                                    weather_data.put(Provider.OpenWeather_Data.TEMPERATURE_MIN, weather_characteristics.getDouble("temp_min"));
                                    weather_data.put(Provider.OpenWeather_Data.UNITS, Aware.getSetting(getApplicationContext(), Settings.UNITS_PLUGIN_OPENWEATHER));
                                    weather_data.put(Provider.OpenWeather_Data.HUMIDITY, weather_characteristics.getDouble("humidity"));
                                    weather_data.put(Provider.OpenWeather_Data.PRESSURE, weather_characteristics.getDouble("pressure"));
                                    weather_data.put(Provider.OpenWeather_Data.WIND_SPEED, wind.getDouble("speed"));
                                    weather_data.put(Provider.OpenWeather_Data.WIND_DEGREES, wind.getDouble("deg"));
                                    weather_data.put(Provider.OpenWeather_Data.CLOUDINESS, clouds.getDouble("all"));

                                    double rain_value = 0;
                                    if (rain != null) {
                                        if (rain.opt("1h") != null) {
                                            rain_value = rain.optDouble("1h", 0);
                                        } else if (rain.opt("3h") != null) {
                                            rain_value = rain.optDouble("3h", 0);
                                        } else if (rain.opt("6h") != null) {
                                            rain_value = rain.optDouble("6h", 0);
                                        } else if (rain.opt("12h") != null) {
                                            rain_value = rain.optDouble("12h", 0);
                                        } else if (rain.opt("24h") != null) {
                                            rain_value = rain.optDouble("24h", 0);
                                        } else if (rain.opt("day") != null) {
                                            rain_value = rain.optDouble("day", 0);
                                        }
                                    }

                                    double snow_value = 0;
                                    if (snow != null) {
                                        if (snow.opt("1h") != null) {
                                            snow_value = snow.optDouble("1h", 0);
                                        } else if (snow.opt("3h") != null) {
                                            snow_value = snow.optDouble("3h", 0);
                                        } else if (snow.opt("6h") != null) {
                                            snow_value = snow.optDouble("6h", 0);
                                        } else if (snow.opt("12h") != null) {
                                            snow_value = snow.optDouble("12h", 0);
                                        } else if (snow.opt("24h") != null) {
                                            snow_value = snow.optDouble("24h", 0);
                                        } else if (snow.opt("day") != null) {
                                            snow_value = snow.optDouble("day", 0);
                                        }
                                    }
                                    weather_data.put(Provider.OpenWeather_Data.RAIN, rain_value);
                                    weather_data.put(Provider.OpenWeather_Data.SNOW, snow_value);
                                    weather_data.put(Provider.OpenWeather_Data.SUNRISE, sys.getDouble("sunrise"));
                                    weather_data.put(Provider.OpenWeather_Data.SUNSET, sys.getDouble("sunset"));
                                    weather_data.put(Provider.OpenWeather_Data.WEATHER_ICON_ID, weather.getInt("id"));
                                    weather_data.put(Provider.OpenWeather_Data.WEATHER_DESCRIPTION, weather.getString("main") + ": " + weather.getString("description"));

                                    getContentResolver().insert(Provider.OpenWeather_Data.CONTENT_URI, weather_data);

                                    Plugin.sOpenWeather = weather_data;

                                    if (Plugin.sContextProducer != null)
                                        Plugin.sContextProducer.onContext();

                                    if (Aware.DEBUG) Log.d(Aware.TAG, weather_data.toString());

                                    sendBroadcast(new Intent("ACTION_AWARE_UPDATE_STREAM"));
                                } catch (JSONException | NullPointerException | SecurityException e) {
                                    e.printStackTrace();
                                }
                            }
                        });

                        network.start();
                    }
                } catch (NullPointerException | SecurityException e) {
                    e.printStackTrace();
                }
            }

            if (Aware.isStudy(this)) {
                Account aware_account = Aware.getAWAREAccount(getApplicationContext());
                String authority = Provider.getAuthority(getApplicationContext());
                long frequency = Aware.getSettingAsLong(this, Aware_Preferences.FREQUENCY_WEBSERVICE, 30) * 60;

                ContentResolver.setIsSyncable(aware_account, authority, 1);
                ContentResolver.setSyncAutomatically(aware_account, authority, true);
                SyncRequest request = new SyncRequest.Builder()
                        .syncPeriodic(frequency, frequency / 3)
                        .setSyncAdapter(aware_account, authority)
                        .setExtras(new Bundle()).build();
                ContentResolver.requestSync(request);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, pIntent);
            mGoogleApiClient.disconnect();
        }
        Scheduler.removeSchedule(this, SCHEDULER_PLUGIN_OPENWEATHER);

        Aware.setSetting(getApplicationContext(), Settings.STATUS_PLUGIN_OPENWEATHER, false);

        String packageName = getPackageName();
        PluginsManager.disablePlugin(getApplicationContext(), packageName);
        
        if (getPackageName().equals("com.aware.phone") ||
                getResources().getBoolean(R.bool.standalone)) {
            sendBroadcast(new Intent(Aware.ACTION_AWARE_UPDATE_PLUGINS_INFO));
        }
    }

    private boolean is_google_services_available() {
        GoogleApiAvailability googleApi = GoogleApiAvailability.getInstance();
        int result = googleApi.isGooglePlayServicesAvailable(this);
        return (result == ConnectionResult.SUCCESS);
    }

    @Override
    public void onConnected(Bundle bundle) {
        if (DEBUG)
            Log.i(TAG, "Connected to Google Fused Location API");

        // This is an async Google Play Services callback, not routed through onStartCommand()'s own
        // defaults-ensuring checks above — it can fire whenever Play Services decides to connect,
        // including right after a concurrent config sync's Aware.reset() has wiped this setting and
        // before it's been re-applied. getSettingAsLong() tolerates that (empty or malformed) and
        // falls back to the same default as onStartCommand()'s check, same fix as ambient_noise's
        // AudioAnalyser for the same race.
        long openweatherFrequency = Aware.getSettingAsLong(getApplicationContext(), Settings.PLUGIN_OPENWEATHER_FREQUENCY, 30);
        locationRequest.setInterval(openweatherFrequency * 60 * 1000);
        locationRequest.setFastestInterval(openweatherFrequency * 60 * 1000);
        locationRequest.setPriority(LocationRequest.PRIORITY_LOW_POWER);

        try {
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, pIntent);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        if (DEBUG)
            Log.w(TAG, "Error connecting to Google Fused Location services, will try again in 5 minutes");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        if (DEBUG)
            Log.w(TAG, "Error connecting to Google Fused Location services, will try again in 5 minutes");
    }
}
