package com.muse.slxcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keep-alive for a live round (user, 2026-10-23): while the display
 * feed is running, this foreground service keeps the process (and
 * a partial wakelock) alive so GPS updates and the BLE feed keep
 * flowing with the phone locked or the app backgrounded. It holds
 * no round state of its own — the engine stays in GpsActivity; if
 * the process dies, everything stops together (START_NOT_STICKY).
 */
public class RoundService extends Service {

    private static final String CHANNEL_ID = "round";
    private static final int NOTIF_ID = 7;
    private static final long WAKELOCK_MS = 6L * 60 * 60 * 1000; // safety cap

    private static boolean running = false;
    private PowerManager.WakeLock wakeLock;

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Round in progress", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, GpsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SLX round in progress")
                .setContentText("GPS and the X3 display feed stay live with the screen off.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slx:round");
            wakeLock.acquire(WAKELOCK_MS);
        }
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
