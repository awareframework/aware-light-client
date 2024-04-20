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
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            ((Activity) getContext()).startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
        } else {
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

    private void performExportToPublicDirectory() {

        Context context = getContext();
        // Correct path to the external app-specific directory
        File publicDirectory = new File(context.getExternalFilesDir(null), "AWARE");

        if (!publicDirectory.exists()) {
            if (!publicDirectory.mkdirs()) {
                Log.e("export_data", "Failed to create directory: " + publicDirectory.getAbsolutePath());
                return;
            }
        }

        File[] files = publicDirectory.listFiles();
        if (files == null || files.length == 0) {
            Log.e("export_data", "No files found in directory: " + publicDirectory.getAbsolutePath());
        }

        Log.d("export_data", "resource folder path: \n" + publicDirectory.getAbsolutePath());


        File exportDestination = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        for (final File file : files) {
            Log.d("export_data", "File path: " + file.getAbsolutePath());
            File newFile = new File(exportDestination, file.getName());
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(newFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                out.flush();
            } catch (final IOException e) {
                Log.e("export_data", "Failed to export: " + file.getName(), e);
                continue;
            }
        }
    }
}
