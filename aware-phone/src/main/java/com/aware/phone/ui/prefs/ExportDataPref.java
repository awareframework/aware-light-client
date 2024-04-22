package com.aware.phone.ui.prefs;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import com.aware.phone.R;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import android.app.Activity;
import android.content.pm.PackageManager;
import com.aware.phone.ui.Aware_Light_Client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ExportDataPref extends Preference {
    public static final int REQUEST_CODE_OPEN_DIRECTORY = 1000;
    public static final int REQUEST_CODE_STORAGE_PERMISSION = 1001;

    public ExportDataPref(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_export_data);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.pref_export_data, parent, false);

        view.findViewById(R.id.btn_export_study_data).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(getContext(), "Exporting data...", Toast.LENGTH_SHORT).show();
                Log.d("export_data", "click on the btn");
                handleExportAction();
            }


        });

        return view;
    }
    private void handleExportAction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d("export_data", "API version is above 28");
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            ((Activity) getContext()).startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
        } else {

            Log.d("export_data", "API version is below 28");
            if (ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                performExportToPublicDirectory();
            } else {
                ActivityCompat.requestPermissions(
                        (Activity) getContext(),
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CODE_STORAGE_PERMISSION);
            }
        }
    }

    /**
     * Checks if the application is running on an emulator.
     *
     * @return true if the application is running on an emulator, false otherwise.
     */
    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }

    /**
     * Retrieves the appropriate directory for the application's database file storage based on
     * several conditions such as running environment (emulator or real device) and configuration settings.
     *
     * @return The File object pointing to the appropriate directory for database storage.
     */
    protected File getDatabaseFileFolder(){
        Context context = getContext();
        // Correct path to the external app-specific directory

        File dataDirectory;
        if (context.getResources().getBoolean(com.aware.R.bool.internalstorage)) {

            dataDirectory = context.getFilesDir();
        } else if (!context.getResources().getBoolean(com.aware.R.bool.standalone)) {
            // sdcard/AWARE/ (shareable, does not delete when uninstalling)
            dataDirectory = new File(Environment.getExternalStoragePublicDirectory("AWARE").toString());
        } else {
            if (isEmulator()) {
                dataDirectory =  context.getFilesDir();
            } else {
                // sdcard/Android/<app_package_name>/AWARE/ (not shareable, deletes when uninstalling package)
                dataDirectory = new File(ContextCompat.getExternalFilesDirs(context, null)[0] + "/AWARE");
            }
        }

        return dataDirectory;
    }

    /**
     * Exports all files from the application-specific directory to a public directory. (Below Android API 28)
     * This method attempts to export all files located in the app's private database file folder
     * to a designated public directory within the device's "Downloads" directory.
     */
    private void performExportToPublicDirectory() {
        File dataDirectory = getDatabaseFileFolder();

        if (!dataDirectory.exists() && !dataDirectory.mkdirs()) {
            // Log.e("export_data", "Failed to create directory: " + dataDirectory.getAbsolutePath());

            return;
        }

        File[] files = dataDirectory.listFiles();
        if (files == null || files.length == 0) {
            // Log.e("export_data", "No files found in directory: " + dataDirectory.getAbsolutePath());

            return;
        }

        // Log.d("export_data", "Resource folder path: \n" + dataDirectory.getAbsolutePath());

        // Setting the export destination to Download/AWARE
        File exportDestination = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AWARE");
        if (!exportDestination.exists() && !exportDestination.mkdirs()) {
            // Log.e("export_data", "Failed to create export directory: " + exportDestination.getAbsolutePath());

            return;
        }

        for (final File file : files) {

            File newFile = new File(exportDestination, file.getName());
            // Log.d("export_data", "Exporting File path: " + file.getAbsolutePath() + " to " + newFile.getAbsolutePath());
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(newFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                out.flush();
            } catch (IOException e) {
                 Log.e("export_data", "Failed to export: " + file.getName(), e);
            }
        }

        Toast.makeText(getContext(), "Data saved at Downloads/AWARE", Toast.LENGTH_SHORT).show();

    }

}
