package com.carx.snowcontrol;

import android.app.Service;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * 全局悬浮小猪：显示在所有应用上层，可拖动；
 * 点击 → 呼出全局悬浮大菜单；长按 → 摸一摸互动。
 */
public class GlobalFloatService extends Service {

    private WindowManager wm;
    private ImageView pigView;
    private WindowManager.LayoutParams lp;
    private String currentGifName = "";
    private int pigSize;

    @Override
    public void onCreate() {
        super.onCreate();
        PigHax.ensure(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        float density = getResources().getDisplayMetrics().density;
        pigSize = (int)(64 * density);

        pigView = new ImageView(this);
        updateGif("pig.gif");
        pigView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pigView.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 18f);
                }
            });
            pigView.setClipToOutline(true);
        }

        lp = new WindowManager.LayoutParams(
                pigSize, pigSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int)(20 * density);
        lp.y = (int)(220 * density);

        setupTouch();
        try {
            wm.addView(pigView, lp);
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void setupTouch() {
        pigView.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY, startTouchX, startTouchY;
            private long startTime;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startTime = System.currentTimeMillis();
                        startX = lp.x; startY = lp.y;
                        startTouchX = (int) event.getRawX(); startTouchY = (int) event.getRawY();
                        isMoving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = Math.abs(event.getRawX() - startTouchX);
                        float my = Math.abs(event.getRawY() - startTouchY);
                        if (mx > 10 || my > 10) {
                            if (!isMoving) { updateGif("run_pig.gif"); isMoving = true; }
                        }
                        lp.x = startX + (int)(event.getRawX() - startTouchX);
                        lp.y = startY + (int)(event.getRawY() - startTouchY);
                        try { wm.updateViewLayout(pigView, lp); } catch (Exception e) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        updateGif("pig.gif");
                        long hold = System.currentTimeMillis() - startTime;
                        if (hold < 200 && !isMoving) {
                            // 点击 → 呼出/收起全局大菜单
                            PigHax.menu.toggleMenuGlobal(GlobalFloatService.this);
                        } else if (hold < 700 && !isMoving) {
                            String msg = PigHax.data.pet();
                            Toast.makeText(GlobalFloatService.this, "🐷 " + msg, Toast.LENGTH_SHORT).show();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void updateGif(String gifName) {
        if (currentGifName.equals(gifName)) return;
        currentGifName = gifName;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Drawable target = "run_pig.gif".equals(gifName) ? PigHax.assets.cachedRunPigDrawable : PigHax.assets.cachedPigDrawable;
                if (target != null) {
                    pigView.setImageDrawable(target);
                    if (target instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) target).start();
                }
            } else {
                pigView.setImageBitmap(PigHax.assets.getBitmapFromAsset(this, gifName.replace(".gif", ".png")));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void onDestroy() {
        try {
            if (PigHax.menu != null && PigHax.menu.currentMenu != null) PigHax.menu.hideMenu();
        } catch (Exception e) {}
        try { if (pigView != null) wm.removeView(pigView); } catch (Exception e) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}