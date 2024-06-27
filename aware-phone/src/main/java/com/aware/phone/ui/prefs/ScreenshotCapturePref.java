package com.aware.phone.ui.prefs;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.aware.phone.R;
import com.aware.phone.ui.Services.ScreenshotCaptureService;

public class ScreenshotCapturePref extends Preference {
    public static final int REQUEST_CODE_SCREENSHOT = 1002;
    private MediaProjectionManager projectionManager;
    private static final String TAG = "ScreenshotCapture";

    public ScreenshotCapturePref(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_screenshot_capture);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.pref_screenshot_capture, parent, false);

        view.findViewById(R.id.btn_enable_screenshot_capture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(getContext(), "Preparing to capture screenshot...", Toast.LENGTH_SHORT).show();
                handleScreenshotAction();
            }
        });

        return view;
    }


    /**
     * Handles the screenshot action by starting the screen capture intent.
     */
    private void handleScreenshotAction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            projectionManager = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            Intent intent = projectionManager.createScreenCaptureIntent();
            ((Activity) getContext()).startActivityForResult(intent, REQUEST_CODE_SCREENSHOT);
        } else {
            Toast.makeText(getContext(), "Screenshot capture requires Android Lollipop or higher.", Toast.LENGTH_SHORT).show();
        }
    }

}
