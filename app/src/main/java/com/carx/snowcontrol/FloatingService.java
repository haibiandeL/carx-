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
import android.widget.FrameLayout;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
        panelBg.setColor(Color.WHITE); panelBg.setCornerRadius(dp(20));
        root.setBackground(panelBg);
        root.setClipToOutline(true);
        root.setElevation(dp(8));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(4), dp(2), dp(4), 0);
        TextView drag = new TextView(this);
        drag.setText("❄ CarX 控制器  ⋮");
        drag.setTextColor(Color.rgb(32, 33, 36));
        drag.setTextSize(13);
        drag.setPadding(dp(10), dp(10), dp(6), dp(10));
        drag.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(drag, new LinearLayout.LayoutParams(0, -2, 1));
        TextView collapse = new TextView(this);
        collapse.setText("−");
        collapse.setTextColor(Color.rgb(37, 99, 235));
        collapse.setTextSize(24);
        collapse.setGravity(Gravity.CENTER);
        collapse.setBackgroundColor(Color.WHITE);
        bar.addView(collapse, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        WebView web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new SliderBridge(), "CarXSnowBridge");
        root.addView(web, new LinearLayout.LayoutParams(-1, dp(490)));
        web.loadUrl("file:///android_asset/slider.html");

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(dp(330), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 12; params.y = 140;
        overlay = root;
        wm.addView(overlay, params);

        final boolean[] collapsed = {false};
        collapse.setOnClickListener(v -> {
            collapsed[0] = !collapsed[0];
            web.setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
            collapse.setText(collapsed[0] ? "❄" : "−");
            collapse.setTextSize(collapsed[0] ? 24 : 24);
            collapse.setContentDescription(collapsed[0] ? "展开雪花控制器" : "收起控制器");
            collapse.setBackgroundColor(collapsed[0] ? Color.rgb(239, 246, 255) : Color.WHITE);
            params.width = collapsed[0] ? dp(142) : dp(330);
            try { wm.updateViewLayout(overlay, params); } catch (Exception ignored) {}
        });
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

    private synchronized void writeSnowEnabled(String input) {
        String value = "1".equals(input) ? "1" : "0";
        if (!ensureRootShell()) { Toast.makeText(this, "未能取得 root shell；请检查 SukiSU 授权", Toast.LENGTH_SHORT).show(); return; }
        try { rootIn.write(("printf '%s\\n' '" + value + "' > /data/local/tmp/carx_snow_enabled\n").getBytes(StandardCharsets.UTF_8)); rootIn.flush(); }
        catch (IOException ex) { rootIn = null; rootProcess = null; rootTried = false; }
    }

    private synchronized void writeWetGround(String input) {
        String value = "1".equals(input) ? "1" : "0";
        if (!ensureRootShell()) { Toast.makeText(this, "未能取得 root shell；请检查 SukiSU 授权", Toast.LENGTH_SHORT).show(); return; }
        try { rootIn.write(("printf '%s\\n' '" + value + "' > /data/local/tmp/carx_snow_wet_ground\n").getBytes(StandardCharsets.UTF_8)); rootIn.flush(); }
        catch (IOException ex) { rootIn = null; rootProcess = null; rootTried = false; }
    }

    private synchronized void writeWeather(String input) {
        int preset;
        try { preset = Integer.parseInt(input); } catch (Exception e) { return; }
        if (preset < 0 || preset > 4) return;
        if (!ensureRootShell()) {
            Toast.makeText(this, "未能取得 root shell；请检查 SukiSU 授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            rootIn.write(("printf '%s\n' '" + preset + "' > /data/local/tmp/carx_snow_weather\n").getBytes(StandardCharsets.UTF_8));
            rootIn.flush();
        } catch (IOException ex) { rootIn = null; rootProcess = null; rootTried = false; }
    }

    private synchronized void writeGameTime(String input) {
        int minutes;
        try { minutes = Integer.parseInt(input); } catch (Exception e) { return; }
        if (minutes < 0 || minutes > 1439) return;
        if (!ensureRootShell()) {
            Toast.makeText(this, "未能取得 root shell；请检查 SukiSU 授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            rootIn.write(("printf '%s\\n' '" + minutes + "' > /data/local/tmp/carx_snow_time_minutes\n").getBytes(StandardCharsets.UTF_8));
            rootIn.flush();
        } catch (IOException ex) { rootIn = null; rootProcess = null; rootTried = false; }
    }

    public final class SliderBridge {
        @JavascriptInterface public void setMultiplier(String value) { writeMultiplier(value); }
        @JavascriptInterface public void setWeather(String value) { writeWeather(value); }
        @JavascriptInterface public void setWetGround(String value) { writeWetGround(value); }
        @JavascriptInterface public void setSnowEnabled(String value) { writeSnowEnabled(value); }
        @JavascriptInterface public void setGameTime(String value) { writeGameTime(value); }
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
