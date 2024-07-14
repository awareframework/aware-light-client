package com.aware;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.aware.providers.Screenshot_Provider;
import com.aware.utils.Aware_Sensor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenShot extends Aware_Sensor {
    private static final String TAG = "ScreenShot";
    public static final String MEDIA_PROJECTION_RESULT_CODE = "MEDIA_PROJECTION_RESULT_CODE";
    public static final String MEDIA_PROJECTION_RESULT_DATA = "MEDIA_PROJECTION_RESULT_DATA";
    public static final String NOTIFICATION_CHANNEL_ID = "SCREEN_CAPTURE_CHANNEL";
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP_CAPTURE = "com.aware.ACTION_STOP_CAPTURE";
    public static final String ACTION_SCREENSHOT_SERVICE_STOPPED = "com.aware.ACTION_SCREENSHOT_SERVICE_STOPPED";
    public static int mediaProjectionResultCode;
    public static Intent mediaProjectionResultData;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private WindowManager windowManager;
    private ImageReader imageReader;
    private Handler handler;
    private HandlerThread handlerThread;
    private int width;
    private int height;
    private int density;
    private boolean isScreenOff = false;
    private long lastCaptureTime = 0;
    private int capture_delay = 3000;
    private int compressionRate = 20;

    private BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case Intent.ACTION_SCREEN_OFF:
                        Log.d(TAG, "Screen off detected");
                        isScreenOff = true;
                        stopCapturing();
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        Log.d(TAG, "Screen on detected");
                        isScreenOff = false;
                        startCapturing();
                        break;
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = ""; // Set this if needed

        REQUIRED_PERMISSIONS.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        REQUIRED_PERMISSIONS.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);

        createNotificationChannel();
        registerScreenStateReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_CAPTURE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(MEDIA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(MEDIA_PROJECTION_RESULT_DATA);

        if (resultCode != Activity.RESULT_CANCELED && data != null) {
            mediaProjectionResultCode = resultCode;
            mediaProjectionResultData = data;
            startForegroundService(resultCode, data);
        } else if (mediaProjectionResultCode != 0 && mediaProjectionResultData != null) {
            startForegroundService(mediaProjectionResultCode, mediaProjectionResultData);
        } else {
            stopSelf();
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Channel for screen capture");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void registerScreenStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter);
    }

    private void startForegroundService(int resultCode, Intent data) {
        Intent stopSelf = new Intent(this, ScreenShot.class);
        stopSelf.setAction(ACTION_STOP_CAPTURE);
        PendingIntent pStopSelf = PendingIntent.getService(this, 0, stopSelf, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Capturing screen every 5 seconds...")
                .setSmallIcon(R.drawable.ic_stat_deny)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(R.drawable.ic_stat_deny, "Stop Capture", pStopSelf)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "Foreground service started");

        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);

        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection stopped");
                cleanupResources();
                stopSelf();
            }
        }, handler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
        Log.d(TAG, "Virtual display created");

        handlerThread = new HandlerThread("ScreenCaptureThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        handler.post(captureRunnable);
    }

    private final Runnable captureRunnable = new Runnable() {
        private int retryCount = 0;
        @Override
        public void run() {
            if (!isScreenOff) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCaptureTime >= capture_delay || retryCount > 0) {
                    Image image = imageReader.acquireLatestImage();
                    if (image != null) {
                        retryCount = 0;
                        lastCaptureTime = currentTime;
                        processImage(image);
                        image.close();
                        handler.postDelayed(this, capture_delay);
                    } else {
                        Log.e(TAG, "Failed to capture image: image is null");
                        resetImageReader();
                        retryCount++;
                        int retryDelay = Math.min(capture_delay, retryCount * 100);
                        handler.postDelayed(this, retryDelay);
                    }
                } else {
                    handler.postDelayed(this, 100);
                }
            }
        }
    };

    private void resetImageReader() {
        Log.d(TAG, "Resetting ImageReader");
        imageReader.setOnImageAvailableListener(null, null);
        imageReader.close();
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay.setSurface(imageReader.getSurface());
    }

    /**
     * Processes the captured image and saves it to a file.
     *
     * @param image The captured image.
     */
    private void processImage(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            saveBitmap(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        }
    }

    /**
     * Saves the bitmap to a file in the Downloads directory.
     *
     * @param bitmap The bitmap to save.
     */
    private void saveBitmap(Bitmap bitmap) {
        File downloadsDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aware-light/screenshot");
        if (!downloadsDirectory.exists() && !downloadsDirectory.mkdirs()) {
            Log.e(TAG, "Failed to create directory: " + downloadsDirectory.getAbsolutePath());
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File path = new File(downloadsDirectory, "screenshot_" + timestamp + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(path)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, compressionRate, fos); // Use the selected compression rate
            fos.flush();
            Log.d(TAG, "Screenshot saved to " + path.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving screenshot", e);
        }

        long fileSizeInBytes = path.length();
        long fileSizeInKB = fileSizeInBytes / 1024;
        long fileSizeInMB = fileSizeInKB / 1024;

        Log.d(TAG, "File size: " + fileSizeInBytes + " bytes");
        Log.d(TAG, "File size: " + fileSizeInKB + " KB");
        Log.d(TAG, "File size: " + fileSizeInMB + " MB");
    }

//    private void processImage(Image image) {
//        try {
//            Image.Plane[] planes = image.getPlanes();
//            ByteBuffer buffer = planes[0].getBuffer();
//            int pixelStride = planes[0].getPixelStride();
//            int rowStride = planes[0].getRowStride();
//            int rowPadding = rowStride - pixelStride * width;
//
//            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
//            bitmap.copyPixelsFromBuffer(buffer);
//
//            byte[] imageData = convertBitmapToByteArray(bitmap);
//            storeScreenshotMetadata(imageData);
//        } catch (Exception e) {
//            Log.e(TAG, "Error processing image", e);
//        }
//    }
//
//    private byte[] convertBitmapToByteArray(Bitmap bitmap) {
//        ByteArrayOutputStream stream = new ByteArrayOutputStream();
//        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
//        return stream.toByteArray();
//    }
//
//    private void storeScreenshotMetadata(byte[] imageData) {
//        ContentValues values = new ContentValues();
//        values.put(Screenshot_Provider.ScreenshotData.TIMESTAMP, System.currentTimeMillis());
//        values.put(Screenshot_Provider.ScreenshotData.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
//        values.put(Screenshot_Provider.ScreenshotData.IMAGE_DATA, imageData);
//
//        getContentResolver().insert(Screenshot_Provider.ScreenshotData.CONTENT_URI, values);
//    }

    private void stopCapturing() {
        if (handler != null && captureRunnable != null) {
            handler.removeCallbacks(captureRunnable);
        }
    }

    private void startCapturing() {
        if (handler != null && captureRunnable != null) {
            handler.post(captureRunnable);
        }
    }

    private void cleanupResources() {
        Log.d(TAG, "Cleaning up resources");
        stopCapturing();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }

        // Broadcast that the service has stopped
        Intent intent = new Intent(ACTION_SCREENSHOT_SERVICE_STOPPED);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(screenStateReceiver);
        cleanupResources();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void requestScreenshotPermission() {
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent permissionIntent = projectionManager.createScreenCaptureIntent();
        permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(permissionIntent);
    }
}
