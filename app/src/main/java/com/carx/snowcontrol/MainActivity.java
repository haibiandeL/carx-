package com.carx.snowcontrol;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int OVERLAY_REQUEST = 7821;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 24);
        TextView title = new TextView(this);
        title.setText("CarX Street 雪花大小控制器\n目标版本：1.23 ARM64");
        title.setTextSize(21);
        root.addView(title);
        TextView note = new TextView(this);
        note.setPadding(0, 18, 0, 18);
        note.setText("此控制端负责悬浮滑块和写入倍率配置。只有配套原生注入模块在游戏进程中成功运行时，滑块才会改变实际雪花尺寸。当前研究包尚未包含已编译的注入模块。");
        root.addView(note);
        Button grant = new Button(this);
        grant.setText("1. 授予悬浮窗权限");
        root.addView(grant);
        Button start = new Button(this);
        start.setText("2. 显示悬浮滑块");
        root.addView(start);
        TextView status = new TextView(this);
        status.setPadding(0, 18, 0, 0);
        status.setText("倍率范围：0.25×–3.00×。悬浮窗可拖动。");
        root.addView(status);
        setContentView(root);

        grant.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, OVERLAY_REQUEST);
            } else status.setText("悬浮窗权限已授予。下一步启动滑块。");
        });
        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                status.setText("请先授予悬浮窗权限。");
                return;
            }
            Intent i = new Intent(this, FloatingService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            status.setText("悬浮滑块已请求启动。首次写入配置时，系统可能弹出 SukiSU root 授权提示。");
        });
    }
}
