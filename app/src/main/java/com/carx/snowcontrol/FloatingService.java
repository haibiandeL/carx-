package com.carx.snowcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.view.WindowManager;

/** CarX overlay service. The touchable window is limited to the pig icon; it no longer spans the screen. */
public final class FloatingService extends Service {
    private PiggyOverlayController pig;

    @Override public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        PigHax.ensure(this);
        pig = new PiggyOverlayController();
        pig.showFloatingWindow(this);
    }

    private void startForegroundNotification() {
        String channel = "snow_slider";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(channel, "CarX PigHax UI", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        b.setContentTitle("CarX 控制面板").setContentText("PigHax 悬浮菜单运行中")
                .setSmallIcon(android.R.drawable.ic_menu_edit);
        startForeground(7822, b.build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        if (pig != null) { pig.dismiss(); pig = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
