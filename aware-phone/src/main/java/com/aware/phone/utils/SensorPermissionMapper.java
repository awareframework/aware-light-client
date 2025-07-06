package com.aware.phone.utils;

import android.Manifest;
import com.aware.Aware_Preferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class that maps AWARE sensors to their required Android permissions
 */
public class SensorPermissionMapper {
    
    private static final Map<String, List<String>> sensorPermissionMap = new HashMap<>();
    
    static {
        // Initialize the sensor to permission mappings
        
        // Motion sensors - no special permissions required
        sensorPermissionMap.put(Aware_Preferences.STATUS_ACCELEROMETER, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_GYROSCOPE, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_MAGNETOMETER, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_BAROMETER, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_GRAVITY, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_LINEAR_ACCELEROMETER, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_ROTATION, new ArrayList<String>());
        
        // Environmental sensors - no special permissions required
        sensorPermissionMap.put(Aware_Preferences.STATUS_LIGHT, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_PROXIMITY, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_TEMPERATURE, new ArrayList<String>());
        
        // Location sensors
        List<String> locationPermissions = new ArrayList<>();
        locationPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        locationPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        sensorPermissionMap.put(Aware_Preferences.STATUS_LOCATION_GPS, locationPermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_LOCATION_NETWORK, locationPermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_LOCATION_PASSIVE, locationPermissions);
        
        // Communication sensors
        List<String> phonePermissions = new ArrayList<>();
        phonePermissions.add(Manifest.permission.READ_PHONE_STATE);
        sensorPermissionMap.put(Aware_Preferences.STATUS_CALLS, phonePermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_MESSAGES, phonePermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_TELEPHONY, phonePermissions);
        
        // WiFi sensor
        List<String> wifiPermissions = new ArrayList<>();
        wifiPermissions.add(Manifest.permission.ACCESS_WIFI_STATE);
        wifiPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION); // Required for WiFi scanning on newer Android
        sensorPermissionMap.put(Aware_Preferences.STATUS_WIFI, wifiPermissions);
        
        // Bluetooth sensor
        List<String> bluetoothPermissions = new ArrayList<>();
        bluetoothPermissions.add(Manifest.permission.BLUETOOTH);
        bluetoothPermissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        bluetoothPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION); // Required for BT scanning on newer Android
        sensorPermissionMap.put(Aware_Preferences.STATUS_BLUETOOTH, bluetoothPermissions);
        
        // Applications sensor - needs accessibility service
        List<String> appPermissions = new ArrayList<>();
        // Note: Accessibility service is handled separately, not through normal permissions
        sensorPermissionMap.put(Aware_Preferences.STATUS_APPLICATIONS, appPermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_KEYBOARD, appPermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_NOTIFICATIONS, appPermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_SCREENTEXT, appPermissions);
        sensorPermissionMap.put(Aware_Preferences.STATUS_TOUCH, appPermissions);
        
        // Other sensors
        sensorPermissionMap.put(Aware_Preferences.STATUS_BATTERY, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_SCREEN, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_NETWORK_EVENTS, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_NETWORK_TRAFFIC, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_PROCESSOR, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_TIMEZONE, new ArrayList<String>());
        sensorPermissionMap.put(Aware_Preferences.STATUS_SIGNIFICANT_MOTION, new ArrayList<String>());
        
        // Screenshot - needs screen capture permission (handled specially)
        sensorPermissionMap.put(Aware_Preferences.STATUS_SCREENSHOT, new ArrayList<String>());
        
        // ESM - no special permissions
        sensorPermissionMap.put(Aware_Preferences.STATUS_ESM, new ArrayList<String>());
        
        // Ambient noise plugin
        List<String> audioPermissions = new ArrayList<>();
        audioPermissions.add(Manifest.permission.RECORD_AUDIO);
        sensorPermissionMap.put(Aware_Preferences.STATUS_PLUGIN_AMBIENT_NOISE, audioPermissions);
        
        // OpenWeather plugin - needs location for weather data
        sensorPermissionMap.put(Aware_Preferences.STATUS_PLUGIN_OPENWEATHER, locationPermissions);
    }
    
    /**
     * Get the list of permissions required for a specific sensor
     * @param sensorSetting The sensor preference key (e.g., Aware_Preferences.STATUS_ACCELEROMETER)
     * @return List of permission strings required for this sensor
     */
    public static List<String> getRequiredPermissions(String sensorSetting) {
        List<String> permissions = sensorPermissionMap.get(sensorSetting);
        return permissions != null ? new ArrayList<>(permissions) : new ArrayList<String>();
    }
    
    /**
     * Check if a sensor requires accessibility service
     * @param sensorSetting The sensor preference key
     * @return true if the sensor requires accessibility service
     */
    public static boolean requiresAccessibilityService(String sensorSetting) {
        return sensorSetting.equals(Aware_Preferences.STATUS_APPLICATIONS) ||
               sensorSetting.equals(Aware_Preferences.STATUS_KEYBOARD) ||
               sensorSetting.equals(Aware_Preferences.STATUS_NOTIFICATIONS) ||
               sensorSetting.equals(Aware_Preferences.STATUS_SCREENTEXT) ||
               sensorSetting.equals(Aware_Preferences.STATUS_TOUCH);
    }
    
    /**
     * Check if a sensor requires screen capture permission
     * @param sensorSetting The sensor preference key
     * @return true if the sensor requires screen capture permission
     */
    public static boolean requiresScreenCapturePermission(String sensorSetting) {
        return sensorSetting.equals(Aware_Preferences.STATUS_SCREENSHOT);
    }
    
    /**
     * Get a user-friendly description of why a permission is needed
     * @param permission The Android permission string
     * @return A user-friendly description
     */
    public static String getPermissionDescription(String permission) {
        switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                return "Precise location for GPS, WiFi, and Bluetooth scanning";
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return "Approximate location for network-based positioning";
            case Manifest.permission.READ_PHONE_STATE:
                return "Phone state for call and message monitoring";
            case Manifest.permission.ACCESS_WIFI_STATE:
                return "WiFi state for network monitoring";
            case Manifest.permission.BLUETOOTH:
                return "Bluetooth access for device scanning";
            case Manifest.permission.BLUETOOTH_ADMIN:
                return "Bluetooth administration for scanning control";
            case Manifest.permission.RECORD_AUDIO:
                return "Microphone access for ambient noise detection";
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return "Storage access for saving sensor data";
            case Manifest.permission.READ_EXTERNAL_STORAGE:
                return "Storage access for reading configuration";
            default:
                return "Required for sensor functionality";
        }
    }
}