package com.carx.snowcontrol;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;

/** Small, independently touchable pig window. Blank screen areas remain owned by the app/game below. */
public final class PiggyOverlayController {
    private ImageView imageView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private String currentGifName = "";
    private FloatingAssets assets;
    private FloatingData data;
    private FloatingHUD hud;
    private FloatingMenu menu;

    public PiggyOverlayController() {
        PigHax.ensure(null);
        data = PigHax.data; assets = PigHax.assets; hud = PigHax.hud; menu = PigHax.menu;
    }

    public void showFloatingWindow(final Context context) {
        PigHax.ensure(context);
        data = PigHax.data; assets = PigHax.assets; hud = PigHax.hud; menu = PigHax.menu;
        assets.preloadAssets(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 30f);
                }
            });
            imageView.setClipToOutline(true);
        }
        updateGif(context, "pig.gif");
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        // IMPORTANT: this window is only 64dp square, never MATCH_PARENT.
        int size = dp(context, 64);
        windowParams = new WindowManager.LayoutParams(size, size, type, flags, android.graphics.PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = dp(context, 100); windowParams.y = dp(context, 100);
        setupTouchListener(context);
        windowManager.addView(imageView, windowParams);
    }

    private void setupTouchListener(final Context context) {
        imageView.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downWindowX, downWindowY;
            private long downTime;
            private boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downTime = System.currentTimeMillis();
                        downRawX = event.getRawX(); downRawY = event.getRawY();
                        downWindowX = windowParams.x; downWindowY = windowParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(context, 5) || Math.abs(dy) > dp(context, 5)) {
                            moved = true; updateGif(context, "run_pig.gif");
                            windowParams.x = downWindowX + (int) dx;
                            windowParams.y = downWindowY + (int) dy;
                            try { windowManager.updateViewLayout(imageView, windowParams); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        updateGif(context, "pig.gif");
                        if (!moved && System.currentTimeMillis() - downTime < 350) {
                            // The full menu is its own correctly-sized overlay window.
                            menu.toggleMenuGlobal(context);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL: updateGif(context, "pig.gif"); return true;
                }
                return true;
            }
        });
    }

    private int dp(Context c, float value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateGif(Context context, String gifName) {
        if (imageView == null || currentGifName.equals(gifName)) return;
        currentGifName = gifName;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Drawable drawable = "pig.gif".equals(gifName) ? assets.cachedPigDrawable : assets.cachedRunPigDrawable;
                if (drawable != null) {
                    imageView.setImageDrawable(drawable);
                    if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).start();
                }
            } else {
                imageView.setImageBitmap(assets.getBitmapFromAsset(context, gifName.replace(".gif", ".png")));
            }
        } catch (Exception ignored) {}
    }

    public void dismiss() {
        if (imageView != null && windowManager != null) {
            try { windowManager.removeView(imageView); } catch (Exception ignored) {}
            imageView = null;
        }
    }
}
