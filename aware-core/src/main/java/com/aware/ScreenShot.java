package com.aware;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncRequest;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import com.aware.providers.ScreenShot_Provider;
import com.aware.providers.ScreenText_Provider;
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
    public static final String CAPTURE_TIME_INTERVAL = "capture_time_interval";
    public static final String COMPRESS_RATE = "compress_rate";
    public static final String STATUS_SCREENSHOT_LOCAL_STORAGE = "status_screenshot_local_storage";
    private static String TAG;
    public static final String MEDIA_PROJECTION_RESULT_CODE = "MEDIA_PROJECTION_RESULT_CODE";
    public static final String MEDIA_PROJECTION_RESULT_DATA = "MEDIA_PROJECTION_RESULT_DATA";
    public static final String NOTIFICATION_CHANNEL_ID = "SCREEN_CAPTURE_CHANNEL";
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP_CAPTURE = "com.aware.ACTION_STOP_CAPTURE";
    public static final String ACTION_SCREENSHOT_SERVICE_STOPPED = "com.aware.ACTION_SCREENSHOT_SERVICE_STOPPED";
    public static final String ACTION_SCREENSHOT_STATUS = "com.aware.ACTION_SCREENSHOT_STATUS";
    public static final String EXTRA_SCREENSHOT_STATUS = "extra_screenshot_status";
    public static final String STATUS_RETRY_COUNT_EXCEEDED = "status_retry_count_exceeded";

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
    private boolean saveToLocalStorage = true;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
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
                    case Aware.ACTION_AWARE_SYNC_DATA:
                        Log.d(TAG, "Received ACTION_AWARE_SYNC_DATA broadcast");
                        Bundle sync = new Bundle();
                        sync.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                        sync.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

                        ContentResolver.requestSync(Aware.getAWAREAccount(context), ScreenShot_Provider.AUTHORITY, sync);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = ScreenText_Provider.getAuthority(this);

        TAG = "AWARE::Screenshot";


        REQUIRED_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);

        createNotificationChannel();
        registerScreenStateReceiver();
        if (Aware.DEBUG) Log.d(TAG, "Screenshot service created!");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent != null && ACTION_STOP_CAPTURE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (PERMISSIONS_OK) {
            DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

            Aware.setSetting(this, Aware_Preferences.STATUS_SCREENSHOT, true);
            if (Aware.DEBUG) Log.d(TAG, "Screenshot service active...");



            if (Aware.isStudy(this)) {
                Log.d(TAG, "Aware is study, trying to enable screenshot sync...");
                ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), ScreenShot_Provider.getAuthority(this), 1);
                ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), ScreenShot_Provider.getAuthority(this), true);
                long frequency = Long.parseLong(Aware.getSetting(this, Aware_Preferences.FREQUENCY_WEBSERVICE)) * 60;
                SyncRequest request = new SyncRequest.Builder()
                        .syncPeriodic(frequency, frequency / 3)
                        .setSyncAdapter(Aware.getAWAREAccount(this), ScreenShot_Provider.AUTHORITY)
                        .setSyncAdapter(Aware.getAWAREAccount(this), ScreenShot_Provider.getAuthority(this))
                        .setExtras(new Bundle()).build();
                ContentResolver.requestSync(request);
            }
        }

        int resultCode = intent.getIntExtra(MEDIA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(MEDIA_PROJECTION_RESULT_DATA);

        if (resultCode != Activity.RESULT_CANCELED && data != null) {
            mediaProjectionResultCode = resultCode;
            mediaProjectionResultData = data;
        }

        capture_delay = intent.getIntExtra(CAPTURE_TIME_INTERVAL, capture_delay);
        compressionRate = intent.getIntExtra(COMPRESS_RATE, compressionRate);
        saveToLocalStorage = intent.getBooleanExtra(STATUS_SCREENSHOT_LOCAL_STORAGE, saveToLocalStorage);

        if (mediaProjectionResultCode != 0 && mediaProjectionResultData != null) {
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
        filter.addAction(Aware.ACTION_AWARE_SYNC_DATA);
        filter.addAction(Aware.ACTION_AWARE_SYNC_CONFIG);
        filter.addAction(Aware.ACTION_QUIT_STUDY);
        registerReceiver(screenStateReceiver, filter);
    }

    private void startForegroundService(int resultCode, Intent data) {
        Intent stopSelf = new Intent(this, ScreenShot.class);
        stopSelf.setAction(ACTION_STOP_CAPTURE);
        PendingIntent pStopSelf = PendingIntent.getService(this, 0, stopSelf, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Capturing screen every " + (capture_delay / 1000) + " seconds...")
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
        private static final int MAX_RETRY_COUNT = 5;
        private int retryCount = 0;
        @Override
        public void run() {
            if (!isScreenOff) {
                long currentTime = SystemClock.elapsedRealtime();
                if (currentTime - lastCaptureTime >= capture_delay || retryCount > 0) {
                    Image image = imageReader.acquireLatestImage();
                    if (image != null) {
                        retryCount = 0;
                        lastCaptureTime = currentTime;
                        processImage(image, currentTime);
                        image.close();
                        handler.postDelayed(this, capture_delay);
                    } else {
                        Log.e(TAG, "Failed to capture image: image is null");
                        resetImageReader();
                        retryCount++;
                        if (retryCount > MAX_RETRY_COUNT) {
                            sendRetryExceededBroadcast();
                            return;
                        }
                        int retryDelay = Math.min(capture_delay, retryCount * 100);
                        handler.postDelayed(this, retryDelay);
                    }
                } else {
                    handler.postDelayed(this, capture_delay - (currentTime - lastCaptureTime));
                }
            }
        }
    };

    private void sendRetryExceededBroadcast() {
        Intent intent = new Intent(ACTION_SCREENSHOT_STATUS);
        intent.putExtra(EXTRA_SCREENSHOT_STATUS, STATUS_RETRY_COUNT_EXCEEDED);
        sendBroadcast(intent);
    }

    private void resetImageReader() {
        Log.d(TAG, "Resetting ImageReader");
        imageReader.setOnImageAvailableListener(null, null);
        imageReader.close();
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay.setSurface(imageReader.getSurface());
    }



    /**
     * Saves the bitmap to a file in the Downloads directory.
     *
     * @param bitmap The bitmap to save.
     */
    private void saveBitmap(Bitmap bitmap, long timestamp) {
        File downloadsDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aware-light/screenshot");
        if (!downloadsDirectory.exists() && !downloadsDirectory.mkdirs()) {
            Log.e(TAG, "Failed to create directory: " + downloadsDirectory.getAbsolutePath());
            return;
        }

        Log.d("Screenshot", "timestamp " + timestamp);

        String formattedTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(timestamp);
        Log.d("Screenshot", "formatted timestamp is " + formattedTimestamp);
        File path = new File(downloadsDirectory, "screenshot_" + formattedTimestamp + ".jpg");
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

    /**
     * Processes the captured image and saves it to a file.
     *
     * @param image The captured image.
     */
    private void processImage(Image image, long timestamp) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            if (saveToLocalStorage){
                saveBitmap(bitmap, timestamp);
            } else {
                byte[] imageData = convertBitmapToByteArray(bitmap);
                storeScreenshotMetadata(imageData, timestamp);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        }
    }

    private byte[] convertBitmapToByteArray(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, compressionRate, stream);
        return stream.toByteArray();
    }

    private void storeScreenshotMetadata(byte[] imageData, long timestamp) {
        ContentValues values = new ContentValues();
        values.put(ScreenShot_Provider.ScreenshotData.TIMESTAMP, timestamp);
        values.put(ScreenShot_Provider.ScreenshotData.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
        values.put(ScreenShot_Provider.ScreenshotData.IMAGE_DATA, imageData);

        getContentResolver().insert(ScreenShot_Provider.ScreenshotData.CONTENT_URI, values);
    }

    private void stopCapturing() {
        if (handler != null) {
            handler.removeCallbacks(captureRunnable);
        }
    }

    private void startCapturing() {
        if (handler != null) {
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
        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), ScreenShot_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                ScreenShot_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (Aware.DEBUG) Log.d(TAG, "Screenshot service terminated...");
    }

    @Override
    public boolean onUnbind(Intent intent) {

        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_SCREENSHOT).equals("true")) {
            try {
                if (screenStateReceiver != null) unregisterReceiver(screenStateReceiver);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Screenshot service unbind...");
            }
        }
        return super.onUnbind(intent);
    }
}
