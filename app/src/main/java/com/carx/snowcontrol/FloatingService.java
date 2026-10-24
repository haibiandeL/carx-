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
import android.widget.Button;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

public final class FloatingService extends Service {
    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams params;
    private DataOutputStream rootIn;
    private Process rootProcess;
    private boolean rootTried = false;
    private WebView webView;
    private TextView dragTitle;
    private boolean compact = false;

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
        shell.setBackgroundColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 21) shell.setElevation(dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(5), dp(6), dp(5));
        header.setBackgroundColor(Color.WHITE);
        dragTitle = new TextView(this);
        dragTitle.setText("❄ CarX 雪花控制器");
        dragTitle.setTextColor(Color.rgb(32, 39, 53));
        dragTitle.setTextSize(13);
        dragTitle.setTypeface(null, 1);
        dragTitle.setPadding(dp(4), dp(8), dp(4), dp(8));
        header.addView(dragTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button minimize = new Button(this);
        minimize.setText("缩小");
        minimize.setTextSize(11);
        minimize.setAllCaps(false);
        minimize.setMinWidth(0); minimize.setMinimumWidth(0);
        minimize.setPadding(dp(8), 0, dp(8), 0);
        header.addView(minimize, new LinearLayout.LayoutParams(-2, dp(38)));
        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(17);
        close.setAllCaps(false);
        close.setMinWidth(0); close.setMinimumWidth(0);
        close.setPadding(dp(8), 0, dp(8), 0);
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(38)));
        shell.addView(header, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(244, 246, 249));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new SliderBridge(), "CarXSnowBridge");
        shell.addView(webView, new LinearLayout.LayoutParams(-1, dp(450)));
        webView.loadUrl("file:///android_asset/slider.html");

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20; params.y = 160;
        overlay = shell;
        wm.addView(overlay, params);

        minimize.setOnClickListener(v -> toggleCompact());
        close.setOnClickListener(v -> stopSelf());
        dragTitle.setOnTouchListener(new View.OnTouchListener() {
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
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (compact) toggleCompact();
                    return true;
                }
                return true;
            }
        });
    }

    private void toggleCompact() {
        if (overlay == null || webView == null) return;
        compact = !compact;
        if (compact) {
            webView.setVisibility(View.GONE);
            dragTitle.setText("❄ CarX · 点此展开");
            params.width = dp(190);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        } else {
            webView.setVisibility(View.VISIBLE);
            dragTitle.setText("❄ CarX 雪花控制器");
            params.width = dp(340);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        overlay.animate().alpha(0.92f).setDuration(90).withEndAction(() -> {
            overlay.animate().alpha(1f).setDuration(130).start();
        }).start();
        try { wm.updateViewLayout(overlay, params); } catch (Exception ignored) {}
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

    public final class SliderBridge {
        @JavascriptInterface public void setMultiplier(String value) { writeMultiplier(value); }
        @JavascriptInterface public String getStatus() {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/local/tmp/carx_snow_status 2>/dev/null"});
                try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    p.waitFor();
                    return line == null ? "等待原生模块状态（确认模块已启用并重启游戏）" : line;
                }
            } catch (Exception e) {
                return "状态读取失败；请检查 SukiSU root 授权";
            } finally { if (p != null) p.destroy(); }
        }
        @JavascriptInterface public void closeOverlay() { stopSelf(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) {} overlay = null; }
        if (webView != null) { try { webView.destroy(); } catch (Exception ignored) {} webView = null; }
        if (rootIn != null) { try { rootIn.write("exit\n".getBytes(StandardCharsets.UTF_8)); rootIn.flush(); rootIn.close(); } catch (Exception ignored) {} }
        if (rootProcess != null) rootProcess.destroy();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
