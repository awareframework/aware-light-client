
package com.aware;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.app.NotificationCompat;
import androidx.core.view.accessibility.AccessibilityEventCompat;
import androidx.core.view.accessibility.AccessibilityManagerCompat;
import androidx.legacy.content.WakefulBroadcastReceiver;
import com.aware.providers.Applications_Provider;
import com.aware.providers.Applications_Provider.Applications_Crashes;
import com.aware.providers.Applications_Provider.Applications_Foreground;
import com.aware.providers.Applications_Provider.Applications_History;
import com.aware.providers.Applications_Provider.Applications_Notifications;
import com.aware.providers.Keyboard_Provider;
import com.aware.providers.ScreenText_Provider;
import com.aware.providers.Screen_Provider;
import com.aware.utils.Converters;
import com.aware.utils.Encrypter;
import com.aware.utils.Scheduler;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import android.graphics.Rect;

/**
 * Service that logs application usage on the device.
 * Updates every time the user changes application or accesses a sub activity on the screen.
 * - ACTION_AWARE_APPLICATIONS_FOREGROUND: new application on the screen
 * - ACTION_AWARE_APPLICATIONS_HISTORY: sync_applications running was just updated
 * - ACTION_AWARE_APPLICATIONS_NOTIFICATIONS: new notification received
 * - ACTION_AWARE_APPLICATIONS_CRASHES: an application crashed, error and ANR conditions
 *
 * @author denzil
 */
public class Applications extends AccessibilityService {

    private static String TAG = "AWARE::Applications";
    private static boolean DEBUG = false;

    public static final String STATUS_AWARE_ACCESSIBILITY = "STATUS_AWARE_ACCESSIBILITY";

    /**
     * Broadcasted event: a new application is visible on the foreground
     */
    public static final String ACTION_AWARE_APPLICATIONS_FOREGROUND = "ACTION_AWARE_APPLICATIONS_FOREGROUND";

    /**
     * Broadcasted event: new foreground and background statistics are available
     */
    public static final String ACTION_AWARE_APPLICATIONS_HISTORY = "ACTION_AWARE_APPLICATIONS_HISTORY";

    /**
     * Broadcasted event: new notification is available
     */
    public static final String ACTION_AWARE_APPLICATIONS_NOTIFICATIONS = "ACTION_AWARE_APPLICATIONS_NOTIFICATIONS";

    /**
     * Broadcasted event: application just crashed
     */
    public static final String ACTION_AWARE_APPLICATIONS_CRASHES = "ACTION_AWARE_APPLICATIONS_CRASHES";

    public static final String EXTRA_DATA = "data";

    public static final int ACCESSIBILITY_NOTIFICATION_ID = 42;

    private static final String SCHEDULER_APPLICATIONS_BACKGROUND = "SCHEDULER_APPLICATIONS_BACKGROUND";

    public String AUTHORITY = "";

    // Screentext variables
    private String currScreenText = "";

    ArrayList<ContentValues> contentBuffer = new ArrayList<ContentValues>();

    Set<Integer> textBuffer = new HashSet<>();

    int TEXT_BUFFER_LIMIT = 100;

    private String previousForegroundApp = "";

//    int mDebugDepth = 0;

    private static int screenStatus = 0; //  ACTION_AWARE_SCREEN_OFF ACTION_AWARE_SCREEN_UNLOCKED


    public static void setScreenStatus(int status){
        screenStatus = status;
    }

    public static int getScreenStatus(){
        return screenStatus;
    }

    /**
     * Recursively track all the text on the screen in a tree structure to the text variable
     *
     * @param mNodeInfo
     */
    private void textTree(AccessibilityNodeInfo mNodeInfo){
        if (mNodeInfo == null) return;

        // conditions to filter the meaningless input
        if (mNodeInfo.getText() != null && !mNodeInfo.getText().toString().equals("")){
            Rect rect = new Rect();

            mNodeInfo.getBoundsInScreen(rect);

            currScreenText += mNodeInfo.getText() + "***" + rect.toString() + "||"; // Add division sign for the tree
        }

        if (mNodeInfo.getChildCount() < 1) return;
//        mDebugDepth++;

        for (int i = 0; i < mNodeInfo.getChildCount(); i++) {
            textTree(mNodeInfo.getChild(i));
        }
//        mDebugDepth--;
    }

    /**
     * Given a package name, get application label in the default language of the device
     *
     * @param package_name
     * @return appName
     */
    private String getApplicationName(String package_name) {
        PackageManager packageManager = getPackageManager();
        ApplicationInfo appInfo;
        try {
            appInfo = packageManager.getApplicationInfo(package_name, PackageManager.GET_META_DATA);
        } catch (final NameNotFoundException e) {
            appInfo = null;
        }
        String appName = "";
        if (appInfo != null && packageManager.getApplicationLabel(appInfo) != null) {
            appName = (String) packageManager.getApplicationLabel(appInfo);
        }
        return appName;
    }

    /**
     * Monitors for events of:
     * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}
     * {@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED}
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;

        // if content buffer is full then send all contents in the content buffer
        if (contentBuffer.size() == TEXT_BUFFER_LIMIT){
            // Log.d("Screen_Text", "==========LIMIT REACH============");

            for (ContentValues content: contentBuffer){
                getContentResolver().insert(ScreenText_Provider.ScreenTextData.CONTENT_URI, content);

                if (awareSensor != null) awareSensor.onScreentext(content);
                Intent screen_text_data = new Intent(ScreenText.ACTION_SCREENTEXT_DETECT);
                sendBroadcast(screen_text_data);
            }
            textBuffer.clear();
            contentBuffer.clear();

            // Log.d("Screen_Text", "==========CLEAN BUFFER============");
        }


        Boolean track_screentext = true;

        // Package specification criteria:
        // 0: only track data of the inclusive packages for screentext function
        // 1: only track data except the exclusive packages for screentext function
        // 2: by default track all apps


        int criteria = 2; // Default value

        try {
            String criteriaSetting = Aware.getSetting(getApplicationContext(), Aware_Preferences.PACKAGE_SPECIFICATION);
            if (!criteriaSetting.isEmpty()) {
                criteria = Integer.parseInt(criteriaSetting);
            }
        } catch (NumberFormatException e) {
            Log.e("Error", "Failed to parse PACKAGE_SPECIFICATION setting: " + e.getMessage());
        }

        if (criteria == 0 || criteria == 1){
            String app_names = Aware.getSetting(getApplicationContext(), Aware_Preferences.PACKAGE_NAMES);
            String curr_app = event.getPackageName().toString();
            if (criteria == 0 && !app_names.contains(curr_app)){ // package not in inclusive packages

                track_screentext = false;
            }
            else if (criteria == 1 && app_names.contains(curr_app)) { // package in exclusive packages
                track_screentext = false;
            }
        }


        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENTEXT).equals("true") && getScreenStatus() == 0) {
            // Get the current foreground app
            String currentForegroundApp = event.getPackageName().toString();

            // Get text tree
            AccessibilityNodeInfo mNodeInfo = event.getSource();
            textTree(mNodeInfo);

            if (!currScreenText.isEmpty() && track_screentext && !event.isPassword()) {
                ContentValues screenText = new ContentValues();
                screenText.put(ScreenText_Provider.ScreenTextData.TIMESTAMP, System.currentTimeMillis());
                screenText.put(ScreenText_Provider.ScreenTextData.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));

                if (event.getPackageName() != null) {
                    screenText.put(ScreenText_Provider.ScreenTextData.PACKAGE_NAME, event.getPackageName().toString());
                } else {
                    screenText.put(ScreenText_Provider.ScreenTextData.PACKAGE_NAME, "");
                }

                if (event.getClassName() != null) {
                    screenText.put(ScreenText_Provider.ScreenTextData.CLASS_NAME, event.getClassName().toString());
                } else {
                    screenText.put(ScreenText_Provider.ScreenTextData.CLASS_NAME, "");
                }

                screenText.put(ScreenText_Provider.ScreenTextData.USER_ACTION, event.getAction());
                screenText.put(ScreenText_Provider.ScreenTextData.EVENT_TYPE, event.getEventType());
                screenText.put(ScreenText_Provider.ScreenTextData.TEXT, currScreenText);

                int hashedText = currScreenText.hashCode();

                // Check if the foreground app has changed
                if (!currentForegroundApp.equals(previousForegroundApp)) {
//                    // Log.d("Screen_Text", "==========App Switch============");
                    if (!textBuffer.contains(hashedText)) {
                        textBuffer.add(hashedText);
                        contentBuffer.add(screenText);
//                       // Log.d("Screen_Text", "Add ContentText: " + currScreenText);
                    }


                    // Log.d("Screen_Text", "Current ContentBuffer Size: " + contentBuffer.size());

                    for (ContentValues content: contentBuffer){
                        getContentResolver().insert(ScreenText_Provider.ScreenTextData.CONTENT_URI, content);

                        if (awareSensor != null) awareSensor.onScreentext(content);
                        Intent screen_text_data = new Intent(ScreenText.ACTION_SCREENTEXT_DETECT);
                        sendBroadcast(screen_text_data);
                    }

                    textBuffer.clear();
                    contentBuffer.clear();
                    // Log.d("Screen_Text", "==========CLEAN BUFFER============");
                    // Update the previous foreground app
                    previousForegroundApp = currentForegroundApp;

                } else {
                    // Add to content: get rid of the duplicate text
                    if (!textBuffer.contains(hashedText)) {
                        textBuffer.add(hashedText);
                        contentBuffer.add(screenText);
                        // Log.d("Screen_Text", "Add ContentText: " + currScreenText);
                    }
                }

                currScreenText = "";
            }

        }


        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_NOTIFICATIONS).equals("true") && event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            Notification notificationDetails = (Notification) event.getParcelableData();
            if (notificationDetails != null) {
                ContentValues rowData = new ContentValues();
                rowData.put(Applications_Notifications.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                rowData.put(Applications_Notifications.TIMESTAMP, System.currentTimeMillis());
                rowData.put(Applications_Notifications.PACKAGE_NAME, event.getPackageName().toString());
                rowData.put(Applications_Notifications.APPLICATION_NAME, getApplicationName(event.getPackageName().toString()));

                if (Aware.getSetting(getApplicationContext(), Aware_Preferences.MASK_NOTIFICATION_TEXT).equals("true"))
                    rowData.put(Applications_Notifications.TEXT, Encrypter.hash(getApplicationContext(), event.getText().toString()));
                else
                    rowData.put(Applications_Notifications.TEXT, event.getText().toString());

                rowData.put(Applications_Notifications.SOUND, ((notificationDetails.sound != null) ? notificationDetails.sound.toString() : ""));
                rowData.put(Applications_Notifications.VIBRATE, ((notificationDetails.vibrate != null) ? notificationDetails.vibrate.toString() : ""));
                rowData.put(Applications_Notifications.DEFAULTS, notificationDetails.defaults);
                rowData.put(Applications_Notifications.FLAGS, notificationDetails.flags);

                if (DEBUG) Log.d(TAG, "New notification:" + rowData.toString());

                getContentResolver().insert(Applications_Notifications.CONTENT_URI, rowData);

                if (awareSensor != null) awareSensor.onNotification(rowData);

                Intent notification = new Intent(ACTION_AWARE_APPLICATIONS_NOTIFICATIONS);
                notification.putExtra(EXTRA_DATA, rowData);
                sendBroadcast(notification);
            }
        }

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_APPLICATIONS).equals("true") && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            PackageManager packageManager = getPackageManager();

            //Fixed: Window State Changed from the same application (showing keyboard within an app) should be ignored
            boolean same_app = false;
            Cursor last_foreground = getContentResolver().query(Applications_Foreground.CONTENT_URI, null, null, null, Applications_Foreground.TIMESTAMP + " DESC LIMIT 1");
            if (last_foreground != null && last_foreground.moveToFirst()) {
                if (last_foreground.getString(last_foreground.getColumnIndex(Applications_Foreground.PACKAGE_NAME)).equals(event.getPackageName())) {
                    same_app = true;
                }
            }
            if (last_foreground != null && !last_foreground.isClosed()) last_foreground.close();

            if (!same_app) {
                ApplicationInfo appInfo;
                try {
                    appInfo = packageManager.getApplicationInfo(event.getPackageName().toString(), PackageManager.GET_META_DATA);
                } catch (NameNotFoundException | NullPointerException | Resources.NotFoundException e) {
                    appInfo = null;
                }

                PackageInfo pkgInfo;
                try {
                    pkgInfo = packageManager.getPackageInfo(event.getPackageName().toString(), PackageManager.GET_META_DATA);
                } catch (NameNotFoundException | NullPointerException | Resources.NotFoundException e) {
                    pkgInfo = null;
                }

                String appName = "";
                try {
                    if (appInfo != null) {
                        appName = packageManager.getApplicationLabel(appInfo).toString();
                    }
                } catch (Resources.NotFoundException | NullPointerException e) {
                    appName = "";
                }

                ContentValues rowData = new ContentValues();
                rowData.put(Applications_Foreground.TIMESTAMP, System.currentTimeMillis());
                rowData.put(Applications_Foreground.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                rowData.put(Applications_Foreground.PACKAGE_NAME, event.getPackageName().toString());
                rowData.put(Applications_Foreground.APPLICATION_NAME, appName);
                rowData.put(Applications_Foreground.IS_SYSTEM_APP, pkgInfo != null && isSystemPackage(pkgInfo));

                if (DEBUG) Log.d(TAG, "FOREGROUND: " + rowData.toString());

                try {
                    getContentResolver().insert(Applications_Foreground.CONTENT_URI, rowData);

                    if (awareSensor != null) awareSensor.onForeground(rowData);

                } catch (SQLException e) {
                    if (DEBUG) Log.d(TAG, e.getMessage());
                }

                Intent newForeground = new Intent(ACTION_AWARE_APPLICATIONS_FOREGROUND);
                newForeground.putExtra(EXTRA_DATA, rowData);
                sendBroadcast(newForeground);
            }

            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_CRASHES).equals("true")) {
                //Check if there is a crashed application
                ActivityManager activityMng = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ProcessErrorStateInfo> errors = activityMng.getProcessesInErrorState();
                if (errors != null) {

                    Iterator<ActivityManager.ProcessErrorStateInfo> iter = errors.iterator();
                    while (iter.hasNext()) {

                        ActivityManager.ProcessErrorStateInfo error = iter.next();

                        try {
                            PackageInfo pkgInfo = packageManager.getPackageInfo(error.processName, PackageManager.GET_META_DATA);
                            ApplicationInfo appInfo = packageManager.getApplicationInfo(event.getPackageName().toString(), PackageManager.GET_META_DATA);
                            String appName = (appInfo != null) ? (String) packageManager.getApplicationLabel(appInfo) : "";

                            ContentValues crashData = new ContentValues();
                            crashData.put(Applications_Crashes.TIMESTAMP, System.currentTimeMillis());
                            crashData.put(Applications_Crashes.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                            crashData.put(Applications_Crashes.PACKAGE_NAME, error.processName);
                            crashData.put(Applications_Crashes.APPLICATION_NAME, appName);
                            crashData.put(Applications_Crashes.APPLICATION_VERSION, (pkgInfo != null) ? pkgInfo.versionCode : -1); //some prepackages don't have version codes...
                            crashData.put(Applications_Crashes.ERROR_SHORT, error.shortMsg);

                            String error_long = "";
                            error_long += error.longMsg + "\nStack:\n";
                            error_long += (error.stackTrace != null) ? error.stackTrace : "";

                            crashData.put(Applications_Crashes.ERROR_LONG, error_long);
                            crashData.put(Applications_Crashes.ERROR_CONDITION, error.condition);
                            crashData.put(Applications_Crashes.IS_SYSTEM_APP, pkgInfo != null && isSystemPackage(pkgInfo));

                            getContentResolver().insert(Applications_Crashes.CONTENT_URI, crashData);

                            if (awareSensor != null) awareSensor.onCrash(crashData);

                            if (DEBUG) Log.d(TAG, "Crashed: " + crashData.toString());

                            Intent crashed = new Intent(ACTION_AWARE_APPLICATIONS_CRASHES);
                            crashed.putExtra(EXTRA_DATA, crashData);
                            sendBroadcast(crashed);
                        } catch (NameNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_KEYBOARD).equals("true") && event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            ContentValues keyboard = new ContentValues();
            keyboard.put(Keyboard_Provider.Keyboard_Data.TIMESTAMP, System.currentTimeMillis());
            keyboard.put(Keyboard_Provider.Keyboard_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            keyboard.put(Keyboard_Provider.Keyboard_Data.PACKAGE_NAME, (String) event.getPackageName());
            keyboard.put(Keyboard_Provider.Keyboard_Data.IS_PASSWORD, event.isPassword());
            if (event.isPassword()){
                keyboard.put(Keyboard_Provider.Keyboard_Data.BEFORE_TEXT, "");
                keyboard.put(Keyboard_Provider.Keyboard_Data.CURRENT_TEXT, "");
            } else{
                if (Aware.getSetting(getApplicationContext(), Aware_Preferences.MASK_KEYBOARD).equals("true")){
                    keyboard.put(Keyboard_Provider.Keyboard_Data.BEFORE_TEXT, Converters.maskString(event.getBeforeText().toString()));
                    keyboard.put(Keyboard_Provider.Keyboard_Data.CURRENT_TEXT, Converters.maskString(event.getText().toString()));
                }
                else{
                    keyboard.put(Keyboard_Provider.Keyboard_Data.BEFORE_TEXT, event.getBeforeText().toString());
                    keyboard.put(Keyboard_Provider.Keyboard_Data.CURRENT_TEXT, event.getText().toString());
                }
            }

            if (awareSensor != null) awareSensor.onKeyboard(keyboard);

            getContentResolver().insert(Keyboard_Provider.Keyboard_Data.CONTENT_URI, keyboard);

            if (DEBUG) Log.d(TAG, "Keyboard: " + keyboard.toString());

            Intent keyboard_data = new Intent(Keyboard.ACTION_AWARE_KEYBOARD);
            sendBroadcast(keyboard_data);
        }

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_TOUCH).equals("true")) {
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                if (event.getFromIndex() == 0) last_scroll_index = 0;

                if (last_scroll_index > 0) {
                    if (event.getFromIndex() < last_scroll_index) {

                        ContentValues touch = new ContentValues();
                        touch.put(Screen_Provider.Screen_Touch.TIMESTAMP, System.currentTimeMillis());
                        touch.put(Screen_Provider.Screen_Touch.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_APP, event.getPackageName().toString());
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION, Screen.ACTION_AWARE_TOUCH_SCROLLED_UP);
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_INDEX_ITEMS, event.getItemCount());
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_FROM_INDEX, event.getFromIndex());
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_TO_INDEX, event.getToIndex());

                        if (awareSensor != null) awareSensor.onTouch(touch);

                        getContentResolver().insert(Screen_Provider.Screen_Touch.CONTENT_URI, touch);

                        if (DEBUG) Log.d(TAG, "Touch: " + touch.toString());

                        Intent touch_data = new Intent(Screen.ACTION_AWARE_TOUCH_SCROLLED_UP);
                        sendBroadcast(touch_data);

                    } else if (event.getFromIndex() > last_scroll_index) {

                        ContentValues touch = new ContentValues();
                        touch.put(Screen_Provider.Screen_Touch.TIMESTAMP, System.currentTimeMillis());
                        touch.put(Screen_Provider.Screen_Touch.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_APP, event.getPackageName().toString());
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION, Screen.ACTION_AWARE_TOUCH_SCROLLED_DOWN);
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_INDEX_ITEMS, event.getItemCount());
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_FROM_INDEX, event.getFromIndex());
                        touch.put(Screen_Provider.Screen_Touch.TOUCH_TO_INDEX, event.getToIndex());

                        if (awareSensor != null) awareSensor.onTouch(touch);

                        getContentResolver().insert(Screen_Provider.Screen_Touch.CONTENT_URI, touch);

                        if (DEBUG) Log.d(TAG, "Touch: " + touch.toString());

                        Intent touch_data = new Intent(Screen.ACTION_AWARE_TOUCH_SCROLLED_DOWN);
                        sendBroadcast(touch_data);

                    }
                }
                last_scroll_index = event.getFromIndex();
            }

            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                ContentValues touch = new ContentValues();
                touch.put(Screen_Provider.Screen_Touch.TIMESTAMP, System.currentTimeMillis());
                touch.put(Screen_Provider.Screen_Touch.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                touch.put(Screen_Provider.Screen_Touch.TOUCH_APP, event.getPackageName().toString());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION, Screen.ACTION_AWARE_TOUCH_CLICKED);
                if (Aware.getSetting(getApplicationContext(), Aware_Preferences.MASK_TOUCH_TEXT).equals("true"))
                    touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION_TEXT, Converters.maskString(event.getText().toString()));
                else
                    touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION_TEXT, event.getText().toString());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_INDEX_ITEMS, event.getItemCount());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_FROM_INDEX, event.getFromIndex());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_TO_INDEX, event.getToIndex());

                if (awareSensor != null) awareSensor.onTouch(touch);

                getContentResolver().insert(Screen_Provider.Screen_Touch.CONTENT_URI, touch);

                if (DEBUG) Log.d(TAG, "Touch: " + touch.toString());

                Intent touch_data = new Intent(Screen.ACTION_AWARE_TOUCH_CLICKED);
                sendBroadcast(touch_data);
            }

            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                ContentValues touch = new ContentValues();
                touch.put(Screen_Provider.Screen_Touch.TIMESTAMP, System.currentTimeMillis());
                touch.put(Screen_Provider.Screen_Touch.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                touch.put(Screen_Provider.Screen_Touch.TOUCH_APP, event.getPackageName().toString());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION, Screen.ACTION_AWARE_TOUCH_LONG_CLICKED);
                if (Aware.getSetting(getApplicationContext(), Aware_Preferences.MASK_TOUCH_TEXT).equals("true"))
                    touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION_TEXT, Converters.maskString(event.getText().toString()));
                else
                    touch.put(Screen_Provider.Screen_Touch.TOUCH_ACTION_TEXT, event.getText().toString());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_INDEX_ITEMS, event.getItemCount());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_FROM_INDEX, event.getFromIndex());
                touch.put(Screen_Provider.Screen_Touch.TOUCH_TO_INDEX, event.getToIndex());

                if (awareSensor != null) awareSensor.onTouch(touch);

                getContentResolver().insert(Screen_Provider.Screen_Touch.CONTENT_URI, touch);

                if (DEBUG) Log.d(TAG, "Touch: " + touch.toString());

                Intent touch_data = new Intent(Screen.ACTION_AWARE_TOUCH_LONG_CLICKED);
                sendBroadcast(touch_data);
            }
        }
    }

    private int last_scroll_index = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AUTHORITY = Applications_Provider.getAuthority(this);

        if (!Aware.IS_CORE_RUNNING) {
            Intent aware = new Intent(this, Aware.class);
            startService(aware);
        }

        //Aware.debug(this, "created: " + getClass().getName() + " package: " + getPackageName());

        Aware.startScheduler(this);

        DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");
        TAG = Aware.getSetting(this, Aware_Preferences.DEBUG_TAG);

        if (DEBUG) Log.d(Aware.TAG, "Aware service connected to accessibility services...");

        //This makes sure that plugins and apps can check if the accessibility service is active
        Aware.setSetting(this, Applications.STATUS_AWARE_ACCESSIBILITY, true);

        IntentFilter webservices = new IntentFilter();
        webservices.addAction(Aware.ACTION_AWARE_SYNC_DATA);
        registerReceiver(awareMonitor, webservices);

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.FOREGROUND_PRIORITY).equals("true")) {
            sendBroadcast(new Intent(Aware.ACTION_AWARE_PRIORITY_FOREGROUND));
        }

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_APPLICATIONS).length() == 0) {
            Aware.setSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_APPLICATIONS, 0);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_APPLICATIONS).equals("true") && Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_APPLICATIONS)) > 0) {
            try {
                Scheduler.Schedule backgroundApps = Scheduler.getSchedule(getApplicationContext(), SCHEDULER_APPLICATIONS_BACKGROUND);
                if (backgroundApps == null) {
                    backgroundApps = new Scheduler.Schedule(SCHEDULER_APPLICATIONS_BACKGROUND)
                            .setInterval(Long.parseLong(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_APPLICATIONS)))
                            .setActionIntentAction(ACTION_AWARE_APPLICATIONS_HISTORY)
                            .setActionType(Scheduler.ACTION_TYPE_SERVICE)
                            .setActionClass(getPackageName() + "/" + BackgroundService.class.getName());

                    Scheduler.saveSchedule(this, backgroundApps);
                } else {
                    if (backgroundApps.getInterval() != Long.parseLong(Aware.getSetting(this, Aware_Preferences.FREQUENCY_APPLICATIONS))) {
                        backgroundApps.setInterval(Long.parseLong(Aware.getSetting(this, Aware_Preferences.FREQUENCY_APPLICATIONS)));
                        Scheduler.saveSchedule(this, backgroundApps);
                    }
                }

                if (DEBUG)
                    Log.d(TAG, "Checking background services every " + backgroundApps.getInterval() + " minutes");

            } catch (JSONException e) {
                e.printStackTrace();
            }
        } else {
            Scheduler.removeSchedule(getApplicationContext(), SCHEDULER_APPLICATIONS_BACKGROUND);
            Aware.startScheduler(this);
            if (DEBUG)
                Log.d(TAG, "Checking background services is not possible starting Android 5+");
        }

        //Aware.debug(this, "active: " + getClass().getName() + " package: " + getPackageName());

        if (Aware.isStudy(this)) {
            ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), Applications_Provider.getAuthority(this), 1);
            ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Applications_Provider.getAuthority(this), true);

            long frequency = Long.parseLong(Aware.getSetting(this, Aware_Preferences.FREQUENCY_WEBSERVICE)) * 60;
            SyncRequest request = new SyncRequest.Builder()
                    .syncPeriodic(frequency, frequency / 3)
                    .setSyncAdapter(Aware.getAWAREAccount(this), Applications_Provider.getAuthority(this))
                    .setExtras(new Bundle()).build();
            ContentResolver.requestSync(request);
        }
    }

    @Override
    public void onInterrupt() {
        if (Aware.getSetting(getApplicationContext(), Applications.STATUS_AWARE_ACCESSIBILITY).equals("true")) {
            try {
                if (awareMonitor != null) unregisterReceiver(awareMonitor);
            } catch (IllegalArgumentException e) {
            }
        }

        Scheduler.removeSchedule(this, SCHEDULER_APPLICATIONS_BACKGROUND);
        Aware.startScheduler(this);

        Log.d(TAG, "Accessibility Service has been interrupted...");
    }

    @Override
    public boolean onUnbind(Intent intent) {

        if (Aware.getSetting(getApplicationContext(), Applications.STATUS_AWARE_ACCESSIBILITY).equals("true")) {
            try {
                if (awareMonitor != null) unregisterReceiver(awareMonitor);
            } catch (IllegalArgumentException e) {
            }
        }

        Aware.setSetting(this, Applications.STATUS_AWARE_ACCESSIBILITY, false);
        Scheduler.removeSchedule(this, SCHEDULER_APPLICATIONS_BACKGROUND);

        //notify the user
        Applications.isAccessibilityServiceActive(getApplicationContext());

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), Applications_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                Applications_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        Log.d(TAG, "Accessibility Service has been unbound...");

        return super.onUnbind(intent);
    }

    /**
     * Received AWARE broadcasts
     * - ACTION_AWARE_SYNC_DATA
     *
     * @author df
     */
    private final ContextBroadcaster awareMonitor = new ContextBroadcaster();

    public class ContextBroadcaster extends WakefulBroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Aware.ACTION_AWARE_SYNC_DATA) && AUTHORITY.length() > 0) {
                Bundle sync = new Bundle();
                sync.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                sync.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                ContentResolver.requestSync(Aware.getAWAREAccount(context), AUTHORITY, sync);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //Aware.debug(this, "destroyed: " + getClass().getName() + " package: " + getPackageName());
    }

    private static Applications.AWARESensorObserver awareSensor;

    public static void setSensorObserver(Applications.AWARESensorObserver observer) {
        awareSensor = observer;
    }

    public static Applications.AWARESensorObserver getSensorObserver() {
        return awareSensor;
    }

    public interface AWARESensorObserver {
        /**
         * Callback when the foreground application changed
         *
         * @param data
         */
        void onForeground(ContentValues data);

        /**
         * Callback when a notification is triggered
         *
         * @param data
         */
        void onNotification(ContentValues data);

        /**
         * Callback when an application crashed
         *
         * @param data
         */
        void onCrash(ContentValues data);

        /**
         * Callback upon keyboard input changed
         *
         * @param data
         */
        void onKeyboard(ContentValues data);

        /**
         * NOTE: Not compatible with Lollipop 5+
         * Callback when background services changed
         *
         * @param data
         */
        void onBackground(ContentValues data);

        /**
         * Callback upon touch input changed
         *
         * @param data
         */
        void onTouch(ContentValues data);

        /**
         * Callback upon screentext input changed
         *
         * @param data
         */
        void onScreentext(ContentValues data);
    }

    private synchronized static boolean isAccessibilityEnabled(Context context) {
        boolean enabled = false;

        AccessibilityManager accessibilityManager = (AccessibilityManager) context.getSystemService(ACCESSIBILITY_SERVICE);

        //Try to fetch active accessibility services directly from Android OS database instead of broken API...
        String settingValue = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue != null) {
            if (settingValue.contains(context.getPackageName())) {
                enabled = true;
            }
        }
        if (!enabled) {
            try {
                List<AccessibilityServiceInfo> enabledServices = AccessibilityManagerCompat.getEnabledAccessibilityServiceList(accessibilityManager, AccessibilityEventCompat.TYPES_ALL_MASK);
                if (!enabledServices.isEmpty()) {
                    for (AccessibilityServiceInfo service : enabledServices) {
                        if (service.getId().contains(context.getPackageName())) {
                            enabled = true;
                            break;
                        }
                    }
                }
            } catch (NoSuchMethodError e) {
            }
        }
        if (!enabled) {
            try {
                List<AccessibilityServiceInfo> enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK);
                if (!enabledServices.isEmpty()) {
                    for (AccessibilityServiceInfo service : enabledServices) {
                        if (service.getId().contains(context.getPackageName())) {
                            enabled = true;
                            break;
                        }
                    }
                }
            } catch (NoSuchMethodError e) {
            }
        }

        //Keep the global setting up-to-date
        Aware.setSetting(context, Applications.STATUS_AWARE_ACCESSIBILITY, enabled, "com.aware.phone");

        return enabled;
    }

    /**
     * Check if the accessibility service for AWARE Aware is active
     *
     * @return boolean isActive
     */
    public synchronized static boolean isAccessibilityServiceActive(Context c) {
        if (!isAccessibilityEnabled(c)) {
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(c, Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL);
            mBuilder.setSmallIcon(R.drawable.ic_stat_aware_accessibility);
            mBuilder.setContentTitle(c.getResources().getString(R.string.aware_activate_accessibility_title));
            mBuilder.setContentText(c.getResources().getString(R.string.aware_activate_accessibility));
            mBuilder.setAutoCancel(true);
            mBuilder.setOnlyAlertOnce(true); //notify the user only once
            mBuilder.setDefaults(NotificationCompat.DEFAULT_ALL);
            mBuilder = Aware.setNotificationProperties(mBuilder, Aware.AWARE_NOTIFICATION_IMPORTANCE_GENERAL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                mBuilder.setChannelId(Aware.AWARE_NOTIFICATION_CHANNEL_GENERAL);

            Intent accessibilitySettings = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            accessibilitySettings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent clickIntent = PendingIntent.getActivity(c, 0, accessibilitySettings, PendingIntent.FLAG_UPDATE_CURRENT);
            mBuilder.setContentIntent(clickIntent);

            NotificationManager notManager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            notManager.notify(Applications.ACCESSIBILITY_NOTIFICATION_ID, mBuilder.build());
            return false;
        }
        return true;
    }

    /**
     * Applications background service
     * - Updates the current running sync_applications statistics
     *
     * @author df
     */
    public static class BackgroundService extends IntentService {
        public BackgroundService() {
            super(TAG + " background service");
        }

        @Override
        protected void onHandleIntent(Intent intent) {

            if (!Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_APPLICATIONS).equals("true")) {
                Scheduler.removeSchedule(getApplicationContext(), SCHEDULER_APPLICATIONS_BACKGROUND);
                if (DEBUG)
                    Log.d(TAG, "Removed scheduler: " + SCHEDULER_APPLICATIONS_BACKGROUND);
            }

            //Updating list of running sync_applications/services
            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_APPLICATIONS).equals("true") && intent.getAction().equals(ACTION_AWARE_APPLICATIONS_HISTORY)) {

                ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                PackageManager packageManager = getPackageManager();
                List<RunningAppProcessInfo> runningApps = activityManager.getRunningAppProcesses();

                if (runningApps == null) return;

                if (DEBUG) Log.d(TAG, "Running " + runningApps.size() + " applications");

                for (RunningAppProcessInfo app : runningApps) {
                    try {
                        PackageInfo appPkg = packageManager.getPackageInfo(app.processName, PackageManager.GET_META_DATA);
                        ApplicationInfo appInfo = packageManager.getApplicationInfo(app.processName, PackageManager.GET_META_DATA);

                        String appName = (appInfo != null) ? (String) packageManager.getApplicationLabel(appInfo) : "";

                        Cursor appUnclosed = getContentResolver().query(Applications_History.CONTENT_URI, null, Applications_History.PACKAGE_NAME + " LIKE '%" + app.processName + "%' AND " + Applications_History.PROCESS_ID + "=" + app.pid + " AND " + Applications_History.END_TIMESTAMP + "=0", null, null);
                        if (appUnclosed == null || !appUnclosed.moveToFirst()) {
                            ContentValues rowData = new ContentValues();
                            rowData.put(Applications_History.TIMESTAMP, System.currentTimeMillis());
                            rowData.put(Applications_History.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                            rowData.put(Applications_History.PACKAGE_NAME, app.processName);
                            rowData.put(Applications_History.APPLICATION_NAME, appName);
                            rowData.put(Applications_History.PROCESS_IMPORTANCE, app.importance);
                            rowData.put(Applications_History.PROCESS_ID, app.pid);
                            rowData.put(Applications_History.END_TIMESTAMP, 0);
                            rowData.put(Applications_History.IS_SYSTEM_APP, isSystemPackage(appPkg));
                            try {
                                if (awareSensor != null) awareSensor.onBackground(rowData);
                                getContentResolver().insert(Applications_History.CONTENT_URI, rowData);
                            } catch (SQLiteException e) {
                                if (DEBUG) Log.d(TAG, e.getMessage());
                            } catch (SQLException e) {
                                if (DEBUG) Log.d(TAG, e.getMessage());
                            }
                        } else if (appUnclosed.getInt(appUnclosed.getColumnIndex(Applications_History.PROCESS_IMPORTANCE)) != app.importance) {
                            //Close last importance
                            ContentValues rowData = new ContentValues();
                            rowData.put(Applications_History.END_TIMESTAMP, System.currentTimeMillis());
                            try {
                                getContentResolver().update(Applications_History.CONTENT_URI, rowData, Applications_History._ID + "=" + appUnclosed.getInt(appUnclosed.getColumnIndex(Applications_History._ID)), null);
                            } catch (SQLiteException e) {
                                if (DEBUG) Log.d(TAG, e.getMessage());
                            } catch (SQLException e) {
                                if (DEBUG) Log.d(TAG, e.getMessage());
                            }

                            if (!appUnclosed.isClosed()) appUnclosed.close();

                            //Insert new importance
                            rowData = new ContentValues();
                            rowData.put(Applications_History.TIMESTAMP, System.currentTimeMillis());
                            rowData.put(Applications_History.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                            rowData.put(Applications_History.PACKAGE_NAME, app.processName);
                            rowData.put(Applications_History.APPLICATION_NAME, appName);
                            rowData.put(Applications_History.PROCESS_IMPORTANCE, app.importance);
                            rowData.put(Applications_History.PROCESS_ID, app.pid);
                            rowData.put(Applications_History.END_TIMESTAMP, 0);
                            rowData.put(Applications_History.IS_SYSTEM_APP, isSystemPackage(appPkg));
                            try {
                                getContentResolver().insert(Applications_History.CONTENT_URI, rowData);
                            } catch (SQLiteException e) {
                                if (DEBUG) Log.d(TAG, e.getMessage());
                            } catch (SQLException e) {
                                if (DEBUG) Log.d(TAG, e.getMessage());
                            }
                        }
                        if (appUnclosed != null && !appUnclosed.isClosed()) appUnclosed.close();
                    } catch (PackageManager.NameNotFoundException | IllegalStateException | SQLiteException e) {
                        if (DEBUG) Log.e(TAG, e.toString());
                    }
                }

                //Close open sync_applications that are not running anymore
                try {
                    Cursor appsOpened = getContentResolver().query(Applications_History.CONTENT_URI, null, Applications_History.END_TIMESTAMP + "=0", null, null);
                    if (appsOpened != null && appsOpened.moveToFirst()) {
                        do {
                            if (!exists(runningApps, appsOpened)) {
                                ContentValues rowData = new ContentValues();
                                rowData.put(Applications_History.END_TIMESTAMP, System.currentTimeMillis());
                                try {
                                    getContentResolver().update(Applications_History.CONTENT_URI, rowData, Applications_History._ID + "=" + appsOpened.getInt(appsOpened.getColumnIndex(Applications_History._ID)), null);
                                } catch (SQLiteException e) {
                                    if (DEBUG) Log.d(TAG, e.getMessage());
                                } catch (SQLException e) {
                                    if (DEBUG) Log.d(TAG, e.getMessage());
                                }
                            }
                        } while (appsOpened.moveToNext());
                    }
                    if (appsOpened != null && !appsOpened.isClosed()) appsOpened.close();
                } catch (IllegalStateException | SQLiteException e) {
                    if (DEBUG) Log.e(TAG, e.toString());
                }

                Intent statsUpdated = new Intent(ACTION_AWARE_APPLICATIONS_HISTORY);
                sendBroadcast(statsUpdated);
            }
        }

        /**
         * Check if the application on the database, exists on the running sync_applications
         *
         * @param {@link List}<RunningAppProcessInfo> runningApps
         * @param {@link Cursor} row
         * @return boolean
         */
        private boolean exists(List<RunningAppProcessInfo> running, Cursor dbApp) {
            for (RunningAppProcessInfo app : running) {
                if (dbApp.getString(dbApp.getColumnIndexOrThrow(Applications_History.PACKAGE_NAME)).equals(app.processName) &&
                        dbApp.getInt(dbApp.getColumnIndexOrThrow(Applications_History.PROCESS_IMPORTANCE)) == app.importance &&
                        dbApp.getInt(dbApp.getColumnIndexOrThrow(Applications_History.PROCESS_ID)) == app.pid) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Check if a certain application is pre-installed or part of the operating system.
     *
     * @param {@link PackageInfo} obj
     * @return boolean
     */
    public static boolean isSystemPackage(PackageInfo pkgInfo) {
        return pkgInfo != null && ((pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 1);
    }
}
