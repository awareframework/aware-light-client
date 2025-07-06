package com.aware.phone.ui.dialogs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.R;
import com.aware.phone.ui.Aware_Client;
import com.aware.phone.utils.AwareUtil;
import com.aware.phone.utils.SensorPermissionMapper;
import com.aware.ui.PermissionsHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialog that displays required permissions for enabled sensors in a study
 */
public class PermissionCheckDialog {
    
    private Context context;
    private JSONArray sensorConfigs;
    private ArrayList<SensorPermissionInfo> sensorPermissionList;
    private PermissionCheckAdapter adapter;
    private AlertDialog dialog;
    
    public PermissionCheckDialog(Context context) {
        this.context = context;
        this.sensorPermissionList = new ArrayList<>();
    }
    
    /**
     * Show the permission check dialog for the given study configuration
     * @param studyConfig The study configuration JSONArray
     * @param onComplete Callback when dialog is dismissed
     */
    public void showPermissionCheck(JSONArray studyConfig, final Runnable onComplete) {
        // Extract sensors from study config
        extractSensorsFromConfig(studyConfig);
        
        // Build the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Study Permission Requirements");
        
        // Inflate custom view
        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.dialog_permission_check, null);
        
        ListView permissionListView = dialogView.findViewById(R.id.permission_list);
        TextView summaryText = dialogView.findViewById(R.id.permission_summary);
        Button requestPermissionsButton = dialogView.findViewById(R.id.btn_request_permissions);
        
        // Set up the adapter
        adapter = new PermissionCheckAdapter();
        permissionListView.setAdapter(adapter);
        
        // Update summary
        updateSummaryText(summaryText);
        
        // Set up request permissions button
        requestPermissionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestMissingPermissions();
            }
        });
        
        builder.setView(dialogView);
        
        builder.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        
        builder.setNeutralButton("Settings", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Open app settings
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
                context.startActivity(intent);
            }
        });
        
        dialog = builder.create();
        dialog.show();
    }
    
    /**
     * Extract enabled sensors from study configuration
     */
    private void extractSensorsFromConfig(JSONArray studyConfig) {
        sensorPermissionList.clear();
        Set<String> processedSensors = new HashSet<>();
        
        for (int i = 0; i < studyConfig.length(); i++) {
            try {
                JSONObject element = studyConfig.getJSONObject(i);
                if (element.has("sensors")) {
                    JSONArray sensors = element.getJSONArray("sensors");
                    
                    for (int j = 0; j < sensors.length(); j++) {
                        JSONObject sensorConfig = sensors.getJSONObject(j);
                        String sensorSetting = sensorConfig.getString("setting");
                        
                        // Only process status settings and enabled sensors
                        if (sensorSetting.contains("status_") && 
                            sensorConfig.getBoolean("value") &&
                            !processedSensors.contains(sensorSetting)) {
                            
                            processedSensors.add(sensorSetting);
                            
                            // Get sensor name
                            String sensorName = AwareUtil.getSensorType(sensorSetting);
                            
                            // Get required permissions
                            List<String> permissions = SensorPermissionMapper.getRequiredPermissions(sensorSetting);
                            boolean requiresAccessibility = SensorPermissionMapper.requiresAccessibilityService(sensorSetting);
                            boolean requiresScreenCapture = SensorPermissionMapper.requiresScreenCapturePermission(sensorSetting);
                            
                            // Create sensor permission info
                            SensorPermissionInfo info = new SensorPermissionInfo(
                                sensorSetting, 
                                sensorName, 
                                permissions,
                                requiresAccessibility,
                                requiresScreenCapture
                            );
                            
                            sensorPermissionList.add(info);
                        }
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        
        // Sort by sensor name
        Collections.sort(sensorPermissionList, new Comparator<SensorPermissionInfo>() {
            @Override
            public int compare(SensorPermissionInfo s1, SensorPermissionInfo s2) {
                return s1.sensorName.compareToIgnoreCase(s2.sensorName);
            }
        });
    }
    
    /**
     * Update the summary text with permission statistics
     */
    private void updateSummaryText(TextView summaryText) {
        int totalSensors = sensorPermissionList.size();
        int sensorsWithAllPermissions = 0;
        Set<String> missingPermissions = new HashSet<>();
        
        for (SensorPermissionInfo info : sensorPermissionList) {
            if (info.hasAllPermissions()) {
                sensorsWithAllPermissions++;
            } else {
                missingPermissions.addAll(info.getMissingPermissions());
            }
        }
        
        String summary = String.format(
            "%d sensors enabled\n%d have all required permissions\n%d permissions missing",
            totalSensors,
            sensorsWithAllPermissions,
            missingPermissions.size()
        );
        
        summaryText.setText(summary);
    }
    
    /**
     * Request all missing permissions
     */
    private void requestMissingPermissions() {
        ArrayList<String> missingPermissions = new ArrayList<>();
        
        for (SensorPermissionInfo info : sensorPermissionList) {
            missingPermissions.addAll(info.getMissingPermissions());
        }
        
        // Remove duplicates
        Set<String> uniquePermissions = new HashSet<>(missingPermissions);
        missingPermissions.clear();
        missingPermissions.addAll(uniquePermissions);
        
        if (!missingPermissions.isEmpty()) {
            // Use PermissionsHandler to request permissions
            Intent permissionsIntent = new Intent(context, PermissionsHandler.class);
            permissionsIntent.putStringArrayListExtra(PermissionsHandler.EXTRA_REQUIRED_PERMISSIONS, missingPermissions);
            permissionsIntent.putExtra(PermissionsHandler.EXTRA_REDIRECT_ACTIVITY, 
                context.getPackageName() + "/" + context.getClass().getName());
            permissionsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(permissionsIntent);
            
            // Dismiss dialog to allow permission request
            if (dialog != null) {
                dialog.dismiss();
            }
        }
    }
    
    /**
     * Adapter for the permission list
     */
    private class PermissionCheckAdapter extends BaseAdapter {
        
        @Override
        public int getCount() {
            return sensorPermissionList.size();
        }
        
        @Override
        public Object getItem(int position) {
            return sensorPermissionList.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.permission_status_item, parent, false);
                holder = new ViewHolder();
                holder.sensorName = convertView.findViewById(R.id.sensor_name);
                holder.permissionStatus = convertView.findViewById(R.id.permission_status);
                holder.statusIcon = convertView.findViewById(R.id.status_icon);
                holder.permissionDetails = convertView.findViewById(R.id.permission_details);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            
            SensorPermissionInfo info = sensorPermissionList.get(position);
            
            holder.sensorName.setText(info.sensorName);
            
            if (info.hasAllPermissions()) {
                holder.permissionStatus.setText("All permissions granted");
                holder.permissionStatus.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark));
                holder.statusIcon.setImageResource(android.R.drawable.ic_menu_mylocation);
            } else {
                holder.permissionStatus.setText("Missing permissions");
                holder.permissionStatus.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark));
                holder.statusIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            }
            
            // Build permission details
            StringBuilder details = new StringBuilder();
            if (!info.permissions.isEmpty()) {
                details.append("Requires: ");
                for (String permission : info.permissions) {
                    String permissionName = permission.substring(permission.lastIndexOf('.') + 1);
                    details.append(permissionName).append(", ");
                }
                details.setLength(details.length() - 2); // Remove last comma
            }
            
            if (info.requiresAccessibility) {
                if (details.length() > 0) details.append("\n");
                details.append("Requires Accessibility Service");
            }
            
            if (info.requiresScreenCapture) {
                if (details.length() > 0) details.append("\n");
                details.append("Requires Screen Capture");
            }
            
            holder.permissionDetails.setText(details.toString());
            
            return convertView;
        }
        
        class ViewHolder {
            TextView sensorName;
            TextView permissionStatus;
            ImageView statusIcon;
            TextView permissionDetails;
        }
    }
    
    /**
     * Information about a sensor and its permission requirements
     */
    private class SensorPermissionInfo {
        String sensorSetting;
        String sensorName;
        List<String> permissions;
        boolean requiresAccessibility;
        boolean requiresScreenCapture;
        
        SensorPermissionInfo(String sensorSetting, String sensorName, List<String> permissions,
                            boolean requiresAccessibility, boolean requiresScreenCapture) {
            this.sensorSetting = sensorSetting;
            this.sensorName = sensorName;
            this.permissions = permissions;
            this.requiresAccessibility = requiresAccessibility;
            this.requiresScreenCapture = requiresScreenCapture;
        }
        
        boolean hasAllPermissions() {
            // Check normal permissions
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            
            // Check accessibility service
            if (requiresAccessibility && !Applications.isAccessibilityServiceActive(context)) {
                return false;
            }
            
            // Screen capture permission is checked differently - assume it's granted if sensor is enabled
            
            return true;
        }
        
        List<String> getMissingPermissions() {
            List<String> missing = new ArrayList<>();
            
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            
            return missing;
        }
    }
}