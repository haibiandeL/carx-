package com.carx.snowcontrol;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class PiggyOverlayController {
    private ImageView imageView;
    private FrameLayout.LayoutParams params;
    private String currentGifName = "";

    // 引入管理器（共享全局单例）
    private FloatingData data;
    private FloatingAssets assets;
    private FloatingHUD hud;
    private FloatingMenu menu;

    public PiggyOverlayController() {
        PigHax.ensure(null);
        data = PigHax.data;
        assets = PigHax.assets;
        hud = PigHax.hud;
        menu = PigHax.menu;
    }

    public void showFloatingWindow(final Context context, final ViewGroup rootView) {
        PigHax.ensure(context);
        data = PigHax.data;
        assets = PigHax.assets;
        hud = PigHax.hud;
        menu = PigHax.menu;

        assets.preloadAssets(context);
        
        hud.initArrayList(context, rootView);
        hud.startRgbAnimation();

        imageView = new ImageView(context);
        updateGif(context, "pig.gif");
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 30f);
                }
            });
            imageView.setClipToOutline(true);
        }

        params = new FrameLayout.LayoutParams(160, 160);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = 100;
        params.topMargin = 100;

        setupTouchListener(context, rootView);
        rootView.addView(imageView, params);
    }

    private void setupTouchListener(final Context context, final ViewGroup rootView) {
        imageView.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY, initialTouchX, initialTouchY;
            private long startTime;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startTime = System.currentTimeMillis();
                        initialX = params.leftMargin; initialY = params.topMargin;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        isMoving = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float moveX = Math.abs(event.getRawX() - initialTouchX);
                        float moveY = Math.abs(event.getRawY() - initialTouchY);
                        if (moveX > 10 || moveY > 10) {
                            if (!isMoving) { updateGif(context, "run_pig.gif"); isMoving = true; }
                        }
                        params.leftMargin = (int) (initialX + (event.getRawX() - initialTouchX));
                        params.topMargin = (int) (initialY + (event.getRawY() - initialTouchY));
                        imageView.setLayoutParams(params); return true;
                    case MotionEvent.ACTION_UP:
                        updateGif(context, "pig.gif");
                        long hold = System.currentTimeMillis() - startTime;
                        if (hold < 200) menu.toggleMenu(context, rootView);
                        else if (hold < 700 && !isMoving) {
                            String msg = data.pet();
                            float d = context.getResources().getDisplayMetrics().density;
                            hud.showStackedToast(context, rootView, data.isChinese() ? "🐷 小猪" : "🐷 Piggy", msg, true, d);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void updateGif(Context context, String gifName) {
        if (currentGifName.equals(gifName)) return;
        currentGifName = gifName;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Drawable targetDrawable = null;
                if ("pig.gif".equals(gifName)) targetDrawable = assets.cachedPigDrawable;
                else if ("run_pig.gif".equals(gifName)) targetDrawable = assets.cachedRunPigDrawable;
                
                if (targetDrawable != null) {
                    imageView.setImageDrawable(targetDrawable);
                    if (targetDrawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) targetDrawable).start();
                }
            } else {
                imageView.setImageBitmap(assets.getBitmapFromAsset(context, gifName.replace(".gif", ".png")));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
