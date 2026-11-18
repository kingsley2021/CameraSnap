package io.github.vay1314.camerasnap;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

public final class SnapService extends Service {
    private static final String CHANNEL = "street_snap";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::requestStop;
    private PowerManager.WakeLock wakeLock;
    private CaptureEngine engine;
    private PowerManager power;
    private boolean thermalRegistered;
    private boolean receiverRegistered;
    private boolean closing, restartAfterClose;
    private int activeMode = SnapConfig.OFF;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = status -> {
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && engine != null && !closing)
            engine.stop(AppText.of(R.string.thermal_stop));
    };
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { requestStop(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        updateNotificationChannel();
        power = getSystemService(PowerManager.class);
        try {
            power.addThermalStatusListener(getMainExecutor(), thermalListener);
            thermalRegistered = true;
        } catch (RuntimeException e) { Log.w(HookEntry.TAG, "Thermal monitoring unavailable", e); }
    }

    private void updateNotificationChannel() {
        Context localized = AppLanguage.wrap(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, localized.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification captureNotification(int mode) {
        Context localized = AppLanguage.wrap(this);
        Intent stop = new Intent(this, SnapService.class).setAction(SnapConfig.ACTION_STOP);
        PendingIntent action = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(localized.getString(mode == SnapConfig.VIDEO ? R.string.notification_video : R.string.notification_photo))
                .setContentText(localized.getString(R.string.notification_stop_hint))
                .setOngoing(true).addAction(new Notification.Action.Builder(null, localized.getString(R.string.stop), action).build()).build();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateNotificationChannel();
        if (engine != null)
            getSystemService(NotificationManager.class).notify(1, captureNotification(activeMode));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && SnapConfig.ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (intent == null || !SnapConfig.ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SnapConfig config = SnapConfig.read(this);
        try {
            Notification notification = captureNotification(config.mode);
            if (Build.VERSION.SDK_INT >= 30) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                if (config.mode == SnapConfig.VIDEO) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(1, notification, types);
            } else startForeground(1, notification);
            if (config.mode == SnapConfig.OFF || power.isInteractive() ||
                    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    (config.mode == SnapConfig.VIDEO && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
                SnapConfig.status(this, R.string.start_check);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (engine != null) {
                if (closing) restartAfterClose = true;
                return START_NOT_STICKY;
            }
            if (isThermalConstrained()) {
                SnapConfig.status(this, R.string.start_hot);
                stopSelf();
                return START_NOT_STICKY;
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraSnap:Capture");
            wakeLock.acquire(SnapConfig.MAX_SESSION_MS + 15_000);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(stopReceiver, filter);
            receiverRegistered = true;
            main.postDelayed(timeout, SnapConfig.MAX_SESSION_MS);
            activeMode = config.mode;
            engine = new CaptureEngine(this, config, this::requestStop);
            engine.start();
        } catch (Exception e) {
            Log.e(HookEntry.TAG, "Capture start failed", e);
            SnapConfig.status(this, R.string.start_failed, AppText.error(e));
            requestStop();
        }
        return START_NOT_STICKY;
    }

    private void requestStop() {
        restartAfterClose = false;
        if (engine == null) { stopSelf(); return; }
        if (closing) return;
        closing = true;
        main.removeCallbacks(timeout);
        engine.close(() -> {
            engine = null;
            closing = false;

            if (restartAfterClose) {
                restartAfterClose = false;
                if (receiverRegistered) { unregisterReceiver(stopReceiver); receiverRegistered = false; }
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                onStartCommand(new Intent(SnapConfig.ACTION_START), 0, 0);
            } else stopSelf();
        });
    }

    private boolean isThermalConstrained() {
        try { return power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE; }
        catch (RuntimeException e) { Log.w(HookEntry.TAG, "Thermal status unavailable", e); return false; }
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (engine != null) engine.close();
        if (thermalRegistered) {
            try { power.removeThermalStatusListener(thermalListener); }
            catch (RuntimeException e) { Log.w(HookEntry.TAG, "Cannot remove thermal listener", e); }
            thermalRegistered = false;
        }
        if (receiverRegistered) unregisterReceiver(stopReceiver);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return new Binder(); }
}
