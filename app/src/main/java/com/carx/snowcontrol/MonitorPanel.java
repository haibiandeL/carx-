package com.carx.snowcontrol;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.RandomAccessFile;

/** 实时系统监控面板：CPU / 内存 / 存储 / 电池 / 网络 / FPS */
public class MonitorPanel {
    private final FloatingData data;
    private LinearLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastCpuTime = 0, lastCpuIdle = 0;
    private float fps = 0;
    private long frameCount = 0, lastFpsTime = 0;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private TextView cpuTv, memTv, storageTv, battTv, netTv, fpsTv;
    private View cpuBar, memBar, storageBar, battBar;

    public MonitorPanel(FloatingData data) { this.data = data; }

    public LinearLayout build(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int theme = data.themeIndex;
        int accent = ThemeManager.accent(theme);
        int textColor = Color.WHITE;
        int subColor = Color.parseColor("#AAAAAA");

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(14*density), (int)(12*density), (int)(14*density), (int)(12*density));

        root.addView(makeRow(context, "CPU", accent, textColor, subColor));
        LinearLayout rowCpu = (LinearLayout) root.getChildAt(root.getChildCount()-1);
        cpuTv = (TextView) rowCpu.findViewWithTag(MonitorPanel.R.tag_value);
        cpuBar = rowCpu.findViewWithTag(MonitorPanel.R.tag_bar);

        root.addView(makeRow(context, "MEM", accent, textColor, subColor));
        LinearLayout rowMem = (LinearLayout) root.getChildAt(root.getChildCount()-1);
        memTv = (TextView) rowMem.findViewWithTag(MonitorPanel.R.tag_value);
        memBar = rowMem.findViewWithTag(MonitorPanel.R.tag_bar);

        root.addView(makeRow(context, "STORAGE", accent, textColor, subColor));
        LinearLayout rowSto = (LinearLayout) root.getChildAt(root.getChildCount()-1);
        storageTv = (TextView) rowSto.findViewWithTag(MonitorPanel.R.tag_value);
        storageBar = rowSto.findViewWithTag(MonitorPanel.R.tag_bar);

        root.addView(makeRow(context, "BATTERY", accent, textColor, subColor));
        LinearLayout rowBat = (LinearLayout) root.getChildAt(root.getChildCount()-1);
        battTv = (TextView) rowBat.findViewWithTag(MonitorPanel.R.tag_value);
        battBar = rowBat.findViewWithTag(MonitorPanel.R.tag_bar);

        root.addView(makeRow(context, "NET", accent, textColor, subColor));
        LinearLayout rowNet = (LinearLayout) root.getChildAt(root.getChildCount()-1);
        netTv = (TextView) rowNet.findViewWithTag(MonitorPanel.R.tag_value);

        root.addView(makeRow(context, "FPS", accent, textColor, subColor));
        LinearLayout rowFps = (LinearLayout) root.getChildAt(root.getChildCount()-1);
        fpsTv = (TextView) rowFps.findViewWithTag(MonitorPanel.R.tag_value);

        TextView tip = new TextView(context);
        tip.setText(data.isChinese() ? "数据每秒自动刷新" : "Auto refresh every second");
        tip.setTextColor(subColor); tip.setTextSize(11f);
        tip.setPadding(0, (int)(10*density), 0, 0);
        root.addView(tip);
        return root;
    }

    private LinearLayout makeRow(Context context, String label, int accent, int textColor, int subColor) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, (int)(8*density));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(accent);
        title.setTextSize(12f);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setTextColor(textColor);
        value.setTextSize(12f);
        value.setGravity(Gravity.END);
        value.setTag(MonitorPanel.R.tag_value);
        head.addView(title); head.addView(value);
        row.addView(head);

        View bar = new View(context);
        bar.setTag(MonitorPanel.R.tag_bar);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, (int)(5*density));
        bp.topMargin = (int)(4*density);
        bar.setLayoutParams(bp);
        bar.setBackgroundColor(accent);
        row.addView(bar);
        return row;
    }

    public void start() {
        handler.removeCallbacks(ticker);
        lastFpsTime = System.currentTimeMillis();
        refresh();
        handler.postDelayed(ticker, 1000);
    }

    public void stop() { handler.removeCallbacks(ticker); }

    private void refresh() {
        Context ctx = root.getContext();
        // CPU
        long[] cpu = readCpu();
        int cpuPct = 0;
        if (cpu != null) {
            long total = cpu[0] - lastCpuTime;
            long idle = cpu[1] - lastCpuIdle;
            if (total > 0 && lastCpuTime > 0) cpuPct = (int)((total - idle) * 100 / total);
            lastCpuTime = cpu[0]; lastCpuIdle = cpu[1];
        }
        cpuPct = Math.max(0, Math.min(100, cpuPct));
        cpuTv.setText(cpuPct + "%");
        setBarWidth(cpuBar, cpuPct);

        // 内存
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long totalMem = mi.totalMem / 1048576, availMem = mi.availMem / 1048576;
        int memPct = (int)((totalMem - availMem) * 100 / Math.max(1, totalMem));
        memTv.setText(availMem + "MB / " + totalMem + "MB");
        setBarWidth(memBar, memPct);

        // 存储
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = stat.getTotalBytes(), free = stat.getAvailableBytes();
            int storPct = (int)((total - free) * 100 / Math.max(1, total));
            storageTv.setText((free/1048576) + "MB free / " + (total/1048576) + "MB");
            setBarWidth(storageBar, storPct);
        } catch (Exception e) { storageTv.setText("--"); }

        // 电池
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = ctx.registerReceiver(null, ifilter);
            int level = b != null ? b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = b != null ? b.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
            int status = b != null ? b.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
            int pct = scale > 0 ? level * 100 / scale : 0;
            String st = status == BatteryManager.BATTERY_STATUS_CHARGING ? "⚡" : (status == BatteryManager.BATTERY_STATUS_FULL ? "FULL" : "");
            battTv.setText(pct + "% " + st);
            setBarWidth(battBar, pct);
        } catch (Exception e) { battTv.setText("--"); }

        // 网络
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni != null && ni.isConnected()) {
                int type = ni.getType();
                String name = type == ConnectivityManager.TYPE_WIFI ? "WiFi" : type == ConnectivityManager.TYPE_MOBILE ? "4G/5G" : "NET";
                long rx = android.net.TrafficStats.getTotalRxBytes(), tx = android.net.TrafficStats.getTotalTxBytes();
                netTv.setText(name + "  ↓" + (rx/1048576) + "MB ↑" + (tx/1048576) + "MB");
            } else netTv.setText("OFFLINE");
        } catch (Exception e) { netTv.setText("--"); }

        // FPS（近似，按刷新周期折算）
        long now = System.currentTimeMillis();
        long dt = now - lastFpsTime;
        if (dt > 1000) {
            fps = frameCount * 1000f / dt;
            frameCount = 0; lastFpsTime = now;
        } else frameCount++;
        fpsTv.setText(String.format("%.1f", Math.min(fps, 120f)));
    }

    private void setBarWidth(View bar, int pct) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
        if (lp == null) return;
        int max = bar.getParent() instanceof View ? ((View)bar.getParent()).getWidth() : 0;
        if (max <= 0) max = 400;
        lp.width = Math.max(2, max * pct / 100);
        bar.setLayoutParams(lp);
    }

    private long[] readCpu() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String line = reader.readLine();
            reader.close();
            if (line == null || !line.startsWith("cpu")) return null;
            String[] parts = line.trim().split("\\s+");
            long user = Long.parseLong(parts[1]), nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]), idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
            long total = user + nice + system + idle + iowait + irq + softirq;
            return new long[]{total, idle + iowait};
        } catch (Exception e) { return null; }
    }

    /** 静态单次 CPU 使用率读取（供全局悬浮球使用，内部做一次 250ms 采样） */
    private static long sLastTotal = 0, sLastIdle = 0;
    public static String readCpuUsageOnce() {
        try {
            long[] cur = readCpuOnce();
            if (cur == null) return "--";
            if (sLastTotal == 0) { sLastTotal = cur[0]; sLastIdle = cur[1]; return "0%"; }
            long total = cur[0] - sLastTotal;
            long idle = cur[1] - sLastIdle;
            sLastTotal = cur[0]; sLastIdle = cur[1];
            if (total <= 0) return "0%";
            int pct = (int)((total - idle) * 100 / total);
            return Math.max(0, Math.min(100, pct)) + "%";
        } catch (Exception e) { return "--"; }
    }

    private static long[] readCpuOnce() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String line = reader.readLine();
            reader.close();
            if (line == null || !line.startsWith("cpu")) return null;
            String[] parts = line.trim().split("\\s+");
            long user = Long.parseLong(parts[1]), nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]), idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
            return new long[]{user + nice + system + idle + iowait + irq + softirq, idle + iowait};
        } catch (Exception e) { return null; }
    }

    // 简单 tag 常量（避免额外资源文件）
    public static class R { public static final int tag_value = 0x7f0a0001; public static final int tag_bar = 0x7f0a0002; }
}