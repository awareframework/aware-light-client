package com.aware.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.aware.Aware;

import java.util.ArrayList;

/**
 * This is an invisible activity used to request the needed permissions from the user from API 23 onwards.
 * Created by denzil on 22/10/15.
 */
public class PermissionsHandler extends Activity {

    private String TAG = "PermissionsHandler";

    /**
     * Extra ArrayList<String> with Manifest.permission that require explicit users' permission on Android API 23+
     */
    public static final String EXTRA_REQUIRED_PERMISSIONS = "required_permissions";

    /**
     * Class name of the Activity redirect, e.g., Class.getClass().getName();
     */
    public static final String EXTRA_REDIRECT_ACTIVITY = "redirect_activity";

    /**
     * Class name of the Service redirect, e.g., Class.getClass().getName();
     */
    public static final String EXTRA_REDIRECT_SERVICE = "redirect_service";

    /**
     * Used on redirect service to know when permissions have been accepted
     */
    public static final String ACTION_AWARE_PERMISSIONS_CHECK = "ACTION_AWARE_PERMISSIONS_CHECK";

    /**
     * The request code for the permissions
     */
    public static final int RC_PERMISSIONS = 112;

    private Intent redirect_activity, redirect_service;

    private PermissionSequence sequence;
    private boolean sequenceStarted = false;
    private AlertDialog rationaleDialog;

    // Set to the permission we sent the user to app settings for; re-checked when they return so a
    // blocked permission granted there is picked up, and one still refused is skipped rather than looped.
    private String awaitingSettingsFor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("Permissions", "Permissions request for " + getPackageName());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Coming back from the app-settings screen: pick up the permission if it was granted there;
        // if it's still refused, count it denied and move past it so we don't re-offer the same one.
        if (awaitingSettingsFor != null) {
            String permission = awaitingSettingsFor;
            awaitingSettingsFor = null;
            boolean granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
            Log.d(Aware.TAG, permission + (granted ? " was granted in settings" : " still not granted after settings"));
            if (!granted) sequence.onResult(false);
            requestNextPermission();
            return;
        }

        // Returning from a system permission dialog re-runs onResume; don't restart the sequence.
        if (sequenceStarted) return;

        if (getIntent() != null && getIntent().getExtras() != null && getIntent().getSerializableExtra(EXTRA_REQUIRED_PERMISSIONS) != null) {
            ArrayList<String> permissionsNeeded = (ArrayList<String>) getIntent().getSerializableExtra(EXTRA_REQUIRED_PERMISSIONS);
            sequence = new PermissionSequence(permissionsNeeded, new PermissionSequence.GrantChecker() {
                @Override
                public boolean isGranted(String permission) {
                    return ContextCompat.checkSelfPermission(PermissionsHandler.this, permission) == PackageManager.PERMISSION_GRANTED;
                }
            });

            if (getIntent().hasExtra(EXTRA_REDIRECT_ACTIVITY)) {
                redirect_activity = new Intent();
                String[] component = getIntent().getStringExtra(EXTRA_REDIRECT_ACTIVITY).split("/");
                redirect_activity.setComponent(new ComponentName(component[0], component[1]));
                redirect_activity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else if (getIntent().hasExtra(EXTRA_REDIRECT_SERVICE)) {
                redirect_service = new Intent();
                redirect_service.setAction(ACTION_AWARE_PERMISSIONS_CHECK);
                String[] component = getIntent().getStringExtra(EXTRA_REDIRECT_SERVICE).split("/");
                redirect_service.setComponent(new ComponentName(component[0], component[1]));
            }

            sequenceStarted = true;
            requestNextPermission();
        } else {
            Intent activity = new Intent();
            setResult(Activity.RESULT_OK, activity);
            finish();
        }
    }

    /**
     * Requests the next not-yet-granted permission on its own, after a short rationale. Already-granted
     * permissions are skipped. When none remain, hands back to the caller via {@link #finishWithResult()}.
     */
    private void requestNextPermission() {
        final String permission = sequence.nextToRequest();
        if (permission == null) {
            finishWithResult();
            return;
        }

        rationaleDialog = new AlertDialog.Builder(this)
                .setTitle(permissionTitle(permission))
                .setMessage(permissionRationale(permission))
                .setCancelable(true)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ActivityCompat.requestPermissions(PermissionsHandler.this, new String[]{permission}, RC_PERMISSIONS);
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(Aware.TAG, permission + " was skipped");
                        sequence.onSkipped();
                        requestNextPermission();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        sequence.cancelRemaining();
                        finishWithResult();
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == RC_PERMISSIONS) {
            // One permission is requested per prompt. An empty result (dialog cancelled) counts as not
            // granted, so it can't be mistaken for success and restart the loop.
            String permission = permissions.length > 0 ? permissions[0] : null;
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (permission != null) Log.d(Aware.TAG, permission + (granted ? " was granted" : " was not granted"));

            boolean rationale = permission != null
                    && ActivityCompat.shouldShowRequestPermissionRationale(this, permission);
            if (permission != null
                    && PermissionSequence.actionAfterResult(granted, rationale) == PermissionSequence.ResultAction.PROMPT_SETTINGS) {
                // Blocked: requesting again is a silent no-op (this is why the system dialog stopped
                // appearing). Offer the app-settings route instead of leaving the tap doing nothing.
                showBlockedDialog(permission);
            } else {
                sequence.onResult(granted);
                requestNextPermission();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Sets the activity result once every permission has been handled and finishes; the redirect back
     * to the caller (activity or service) is performed in {@link #onDestroy()}.
     */
    private void finishWithResult() {
        int result = sequence.anyDenied() ? Activity.RESULT_CANCELED : Activity.RESULT_OK;
        setResult(result, redirect_activity != null ? redirect_activity : new Intent());
        finish();
    }

    /**
     * Shown when a permission the user asked to allow is blocked (the OS won't prompt again). Offers to
     * open the app's settings so they can grant it by hand, or to skip it. On return, {@link #onResume()}
     * re-checks it via {@link #awaitingSettingsFor}.
     */
    private void showBlockedDialog(final String permission) {
        rationaleDialog = new AlertDialog.Builder(this)
                .setTitle(permissionTitle(permission))
                .setMessage("AWARE can't ask for the " + humanLabel(permission) + " permission again because "
                        + "it was blocked. Enable it in Settings > Permissions for the sensor to work, or "
                        + "choose Not now to skip it.")
                .setCancelable(true)
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        awaitingSettingsFor = permission;
                        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        settings.setData(Uri.fromParts("package", getPackageName(), null));
                        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(settings);
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(Aware.TAG, permission + " is blocked; skipped");
                        sequence.onResult(false);
                        requestNextPermission();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        sequence.cancelRemaining();
                        finishWithResult();
                    }
                })
                .show();
    }

    private static String permissionTitle(String permission) {
        return "Allow " + humanLabel(permission);
    }

    private static String permissionRationale(String permission) {
        return "AWARE needs the " + humanLabel(permission) + " permission to work as this study expects. "
                + "Allow it on the next screen, or choose Not now to skip it.";
    }

    /** A short, human-readable name for a permission, for use in the rationale prompt. */
    private static String humanLabel(String permission) {
        if (permission == null) return "requested";
        switch (permission) {
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_COARSE_LOCATION":
                return "Location";
            case "android.permission.ACCESS_BACKGROUND_LOCATION":
                return "Background location";
            case "android.permission.READ_PHONE_STATE":
                return "Phone";
            case "android.permission.READ_CALL_LOG":
                return "Call log";
            case "android.permission.READ_SMS":
                return "SMS";
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return "Storage";
            case "android.permission.GET_ACCOUNTS":
                return "Accounts";
            case "android.permission.BLUETOOTH_SCAN":
            case "android.permission.BLUETOOTH_CONNECT":
                return "Nearby devices";
            case "android.permission.POST_NOTIFICATIONS":
                return "Notifications";
            case "android.permission.ACTIVITY_RECOGNITION":
                return "Physical activity";
            case "android.permission.RECORD_AUDIO":
                return "Microphone";
            case "android.permission.CAMERA":
                return "Camera";
            default:
                String tail = permission.substring(permission.lastIndexOf('.') + 1);
                return tail.replace('_', ' ').toLowerCase();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rationaleDialog != null && rationaleDialog.isShowing()) rationaleDialog.dismiss();
        // Only restart the requesting service when every permission was granted. Restarting it after a
        // denial just makes Aware_Sensor.onStartCommand find the permission still missing and relaunch
        // this handler, which spins an unbreakable "Allow ..." dialog loop (the redirect starts the
        // service directly, so it keeps a consent-declined sensor alive regardless of its on/off status).
        if (redirect_service != null && sequence != null && sequence.shouldRestartService()) {
            Log.d(TAG, "Redirecting to Service: " + redirect_service.getComponent().toString());
            redirect_service.setAction(ACTION_AWARE_PERMISSIONS_CHECK);
            startService(redirect_service);
        } else if (redirect_service != null) {
            Log.d(TAG, "Not restarting " + redirect_service.getComponent().toString()
                    + ": user declined a required permission, so no re-prompt loop.");
        }
        if (redirect_activity != null && sequence != null && sequence.shouldRestartService()) {
            Log.d(TAG, "Redirecting to Activity: " + redirect_activity.getComponent().toString());
            setResult(Activity.RESULT_OK, redirect_activity);
            startActivity(redirect_activity);
        } else if (redirect_activity != null) {
            Log.d(TAG, "Not redirecting to " + redirect_activity.getComponent().toString()
                    + ": a denied permission would immediately reopen this handler.");
        }
        Log.d("Permissions", "Handled permissions for " + getPackageName());
    }
}
