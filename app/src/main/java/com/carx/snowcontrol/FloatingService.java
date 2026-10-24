package com.carx.snowcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class FloatingService extends Service {
    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams params;
    private DataOutputStream rootIn;
    private Process rootProcess;
    private boolean rootTried = false;

    @Override public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
    }

    private void startForegroundNotification() {
        String channel = "snow_slider";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(channel, "Snow slider", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        b.setContentTitle("CarX 雪花控制器").setContentText("悬浮滑块运行中").setSmallIcon(android.R.drawable.ic_menu_edit);
        startForeground(7822, b.build());
    }

    private void buildOverlay() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(14, 17, 24));
        TextView drag = new TextView(this);
        drag.setText("❄ CarX 雪花大小控制器    ⋮ 拖动此栏移动");
        drag.setTextColor(Color.WHITE);
        drag.setTextSize(13);
        drag.setPadding(12, 12, 12, 12);
        drag.setBackgroundColor(Color.rgb(35, 46, 64));
        shell.addView(drag, new LinearLayout.LayoutParams(-1, -2));
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.rgb(14, 17, 24));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new SliderBridge(), "CarXSnowBridge");
        shell.addView(web, new LinearLayout.LayoutParams(-1, dp(490)));
        web.loadUrl("file:///android_asset/slider.html");

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(dp(360), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20; params.y = 160;
        overlay = shell;
        wm.addView(overlay, params);

        drag.setOnTouchListener(new View.OnTouchListener() {
            private int sx, sy, wx, wy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sx = (int)e.getRawX(); sy = (int)e.getRawY(); wx = params.x; wy = params.y; return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = wx + (int)e.getRawX() - sx;
                    params.y = wy + (int)e.getRawY() - sy;
                    try { wm.updateViewLayout(overlay, params); } catch (Exception ignored) {}
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private synchronized boolean ensureRootShell() {
        if (rootIn != null) return true;
        if (rootTried && rootProcess == null) return false;
        rootTried = true;
        try {
            rootProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", "sh"});
            rootIn = new DataOutputStream(rootProcess.getOutputStream());
            return true;
        } catch (IOException ex) {
            rootProcess = null; rootIn = null; return false;
        }
    }

    private synchronized void writeMultiplier(String input) {
        float v;
        try { v = Float.parseFloat(input); } catch (Exception e) { return; }
        if (Float.isNaN(v) || Float.isInfinite(v)) return;
        v = Math.max(0.25f, Math.min(10.0f, v));
        String safe = String.format(java.util.Locale.US, "%.2f", v);
        if (!ensureRootShell()) {
            Toast.makeText(this, "未能取得 root shell；请检查 SukiSU 授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            rootIn.write(("printf '%s\\n' '" + safe + "' > /data/local/tmp/carx_snow_multiplier\n").getBytes(StandardCharsets.UTF_8));
            rootIn.flush();
        } catch (IOException ex) {
            rootIn = null; rootProcess = null;
        }
    }

    private synchronized void writeWeather(String input) {
        int preset;
        try { preset = Integer.parseInt(input); } catch (Exception e) { return; }
        if (preset < 0 || preset > 2) return;
        if (!ensureRootShell()) {
            Toast.makeText(this, "未能取得 root shell；请检查 SukiSU 授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            rootIn.write(("printf '%s\n' '" + preset + "' > /data/local/tmp/carx_snow_weather\n").getBytes(StandardCharsets.UTF_8));
            rootIn.flush();
        } catch (IOException ex) { rootIn = null; rootProcess = null; rootTried = false; }
    }

    public final class SliderBridge {
        @JavascriptInterface public void setMultiplier(String value) { writeMultiplier(value); }
        @JavascriptInterface public void setWeather(String value) { writeWeather(value); }
        @JavascriptInterface public void closeOverlay() { stopSelf(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) {} overlay = null; }
        if (rootIn != null) { try { rootIn.write("exit\n".getBytes(StandardCharsets.UTF_8)); rootIn.flush(); rootIn.close(); } catch (Exception ignored) {} }
        if (rootProcess != null) rootProcess.destroy();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
