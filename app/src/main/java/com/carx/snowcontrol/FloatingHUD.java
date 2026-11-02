package com.carx.snowcontrol;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;

public class FloatingHUD {
    private LinearLayout arrayListContainer = null;
    private ValueAnimator rgbAnimator;
    private float globalColorOffset = 0f;
    private FloatingData data;

    public FloatingHUD(FloatingData data) {
        this.data = data;
    }

    public void initArrayList(Context context, ViewGroup rootView) {
        if (arrayListContainer == null) {
            arrayListContainer = new LinearLayout(context);
            arrayListContainer.setOrientation(LinearLayout.VERTICAL);
            arrayListContainer.setGravity(Gravity.END);
            arrayListContainer.setClickable(false);
            arrayListContainer.setFocusable(false);
            arrayListContainer.setLayoutTransition(null);

            FrameLayout.LayoutParams alParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alParams.gravity = Gravity.TOP | Gravity.END;
            float density = context.getResources().getDisplayMetrics().density;
            alParams.setMargins(0, (int)(5 * density), 0, 0);

            arrayListContainer.setVisibility(data.isArrayListEnabled ? View.VISIBLE : View.GONE);
            rootView.addView(arrayListContainer, alParams);
        }
    }

    public void startRgbAnimation() {
        if (rgbAnimator != null && rgbAnimator.isRunning()) return;
        rgbAnimator = ValueAnimator.ofFloat(0f, 1f);
        rgbAnimator.setDuration(2500);
        rgbAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rgbAnimator.setInterpolator(new LinearInterpolator());
        rgbAnimator.addUpdateListener(animation -> {
            globalColorOffset = (float) animation.getAnimatedValue();
            updateListColors();
        });
        rgbAnimator.start();
    }

    private void updateListColors() {
        if (arrayListContainer == null || arrayListContainer.getChildCount() == 0) return;
        int activeVisualIndex = 0;
        for (int i = 0; i < arrayListContainer.getChildCount(); i++) {
            View v = arrayListContainer.getChildAt(i);
            if (v instanceof LinearLayout && v.getTag() != null && !v.getTag().toString().startsWith("removing_")) {
                String featureName = v.getTag().toString().substring(4);
                float hue = (globalColorOffset * 360f + (activeVisualIndex * 15f)) % 360f;
                int currentColor = Color.HSVToColor(new float[]{hue, 1f, 1f});

                TextView tv = v.findViewWithTag("hud_text_" + featureName);
                if (tv != null) tv.setTextColor(currentColor);

                View bar = v.findViewWithTag("hud_bar_" + featureName);
                if (bar != null) bar.setBackgroundColor(currentColor);
                activeVisualIndex++;
            }
        }
    }

    // 【核心修改】：在这里统一修改列表条目的样式，增加灰色背景
    private LinearLayout createHudItemView(Context context, String feature, float density) {
        final LinearLayout itemContainer = new LinearLayout(context);
        itemContainer.setOrientation(LinearLayout.HORIZONTAL);
        itemContainer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        itemContainer.setTag("hud_" + feature);
        
        // --- 新增：给覆盖文字的区域添加灰色长方形背景 ---
        GradientDrawable bg = new GradientDrawable();
        // "#80555555" 中的 80 表示50%的半透明度，555555 则是灰色
        bg.setColor(Color.parseColor("#80555555")); 
        itemContainer.setBackground(bg);
        
        // 给容器左侧增加 6dp 的内边距，让灰底稍微往左延伸一点包裹文字，上下及右边保持0
        itemContainer.setPadding((int)(6 * density), 0, 0, 0);

        TextView tv = new TextView(context);
        tv.setTag("hud_text_" + feature); 
        tv.setText(data.getTrans(feature));
        tv.setTextSize(11f); 
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setIncludeFontPadding(false); 
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        tv.setShadowLayer(5f, 2f, 2f, Color.parseColor("#E6000000"));
        tv.setPadding(0, 0, (int)(4 * density), 0); 

        View rightBar = new View(context);
        rightBar.setTag("hud_bar_" + feature);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                (int)(3f * density), (int)(12f * density)
        );
        barParams.gravity = Gravity.CENTER_VERTICAL; 
        rightBar.setLayoutParams(barParams);

        itemContainer.addView(tv); 
        itemContainer.addView(rightBar);
        return itemContainer;
    }

    public void refreshArrayList(Context context) {
        if (arrayListContainer == null) return;
        if (!data.isArrayListEnabled) {
            arrayListContainer.setVisibility(View.GONE); return;
        } else {
            arrayListContainer.setVisibility(View.VISIBLE);
        }

        for (int i = 0; i < arrayListContainer.getChildCount(); i++) {
            View v = arrayListContainer.getChildAt(i);
            if (v instanceof LinearLayout && v.getTag() != null) {
                String featureName = v.getTag().toString().substring(4);
                TextView tv = v.findViewWithTag("hud_text_" + featureName);
                if (tv != null) animateTextChange(tv, data.getTrans(featureName));
            }
        }

        float density = context.getResources().getDisplayMetrics().density;
        final Paint textPaint = new Paint();
        textPaint.setTextSize(11f * density);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        Collections.sort(data.activeFeatures, (s1, s2) -> {
            float width1 = textPaint.measureText(data.getTrans(s1));
            float width2 = textPaint.measureText(data.getTrans(s2));
            return Float.compare(width2, width1);
        });

        for (int i = arrayListContainer.getChildCount() - 1; i >= 0; i--) {
            final View v = arrayListContainer.getChildAt(i);
            String tag = (String) v.getTag();
            if (tag != null && tag.startsWith("hud_")) {
                String featureName = tag.substring(4);
                if (!data.activeFeatures.contains(featureName)) {
                    v.setTag("removing_" + featureName);
                    final int startHeight = v.getHeight();
                    ValueAnimator removeAnim = ValueAnimator.ofFloat(0f, 1f);
                    removeAnim.setDuration(200);
                    removeAnim.setInterpolator(new AccelerateInterpolator());
                    removeAnim.addUpdateListener(anim -> {
                        float fraction = anim.getAnimatedFraction();
                        v.setTranslationX(150f * density * fraction); v.setAlpha(1f - fraction);
                        ViewGroup.LayoutParams lp = v.getLayoutParams();
                        if (lp != null) { lp.height = (int) (startHeight * (1f - fraction)); v.setLayoutParams(lp); }
                    });
                    removeAnim.addListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator animation) { arrayListContainer.removeView(v); }
                    });
                    removeAnim.start();
                }
            }
        }

        int desiredIndex = 0;
        for (int i = 0; i < data.activeFeatures.size(); i++) {
            String feature = data.activeFeatures.get(i);
            View existingView = arrayListContainer.findViewWithTag("hud_" + feature);

            while (desiredIndex < arrayListContainer.getChildCount()) {
                View child = arrayListContainer.getChildAt(desiredIndex);
                if (child.getTag() != null && child.getTag().toString().startsWith("removing_")) desiredIndex++;
                else break;
            }

            if (existingView == null) {
                LinearLayout itemContainer = createHudItemView(context, feature, density);
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (int) (18 * density));
                itemParams.gravity = Gravity.END;

                final int targetHeight = (int) (18 * density);
                itemParams.height = 0; 
                itemContainer.setAlpha(0f); 
                itemContainer.setTranslationX(150f * density);
                arrayListContainer.addView(itemContainer, desiredIndex, itemParams);

                ValueAnimator addAnim = ValueAnimator.ofFloat(0f, 1f);
                addAnim.setDuration(250); 
                addAnim.setInterpolator(new DecelerateInterpolator(1.5f));
                addAnim.addUpdateListener(anim -> {
                    float fraction = anim.getAnimatedFraction();
                    itemContainer.setTranslationX(150f * density * (1f - fraction)); 
                    itemContainer.setAlpha(fraction);
                    ViewGroup.LayoutParams lp = itemContainer.getLayoutParams();
                    if (lp != null) { 
                        lp.height = (int) (targetHeight * fraction); 
                        itemContainer.setLayoutParams(lp); 
                    }
                });
                addAnim.start();
                desiredIndex++;
            } else {
                if (arrayListContainer.indexOfChild(existingView) != desiredIndex) {
                    arrayListContainer.removeView(existingView); 
                    arrayListContainer.addView(existingView, desiredIndex);
                }
                desiredIndex++;
            }
        }
    }

    public void rebuildArrayListStaggered(Context context) {
        if (arrayListContainer == null) return;
        if (!data.isArrayListEnabled || data.activeFeatures.isEmpty()) {
            refreshArrayList(context); 
            return;
        }

        final float density = context.getResources().getDisplayMetrics().density;
        int childCount = arrayListContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = arrayListContainer.getChildAt(i);
            v.animate().translationX(150f * density).alpha(0f).setDuration(150).start();
        }

        arrayListContainer.postDelayed(() -> {
            arrayListContainer.removeAllViews();
            final Paint textPaint = new Paint();
            textPaint.setTextSize(11f * density);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

            Collections.sort(data.activeFeatures, (s1, s2) -> {
                float width1 = textPaint.measureText(data.getTrans(s1));
                float width2 = textPaint.measureText(data.getTrans(s2));
                return Float.compare(width2, width1);
            });

            for (int i = 0; i < data.activeFeatures.size(); i++) {
                String feature = data.activeFeatures.get(i);
                LinearLayout itemContainer = createHudItemView(context, feature, density);
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (int)(18 * density));
                itemParams.gravity = Gravity.END;

                itemContainer.setAlpha(0f); 
                itemContainer.setTranslationX(150f * density);
                arrayListContainer.addView(itemContainer, itemParams);

                itemContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setStartDelay(i * 45L) 
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
            }
        }, 150);
    }

    public void toggleArrayListStaggered(Context context, boolean isEnabled) {
        if (arrayListContainer == null) return;
        final float density = context.getResources().getDisplayMetrics().density;

        if (isEnabled) {
            arrayListContainer.setVisibility(View.VISIBLE);
            arrayListContainer.removeAllViews();

            if (data.activeFeatures.isEmpty()) return;

            final Paint textPaint = new Paint();
            textPaint.setTextSize(11f * density);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

            Collections.sort(data.activeFeatures, (s1, s2) -> {
                float width1 = textPaint.measureText(data.getTrans(s1));
                float width2 = textPaint.measureText(data.getTrans(s2));
                return Float.compare(width2, width1);
            });

            for (int i = 0; i < data.activeFeatures.size(); i++) {
                String feature = data.activeFeatures.get(i);
                LinearLayout itemContainer = createHudItemView(context, feature, density);
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (int)(18 * density));
                itemParams.gravity = Gravity.END;

                itemContainer.setAlpha(0f); 
                itemContainer.setTranslationX(150f * density);
                arrayListContainer.addView(itemContainer, itemParams);

                itemContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setStartDelay(i * 45L) 
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
            }
        } else {
            int childCount = arrayListContainer.getChildCount();
            if (childCount == 0) {
                arrayListContainer.setVisibility(View.GONE);
                return;
            }
            
            for (int i = 0; i < childCount; i++) {
                View v = arrayListContainer.getChildAt(i);
                v.animate()
                        .translationX(150f * density)
                        .alpha(0f)
                        .setStartDelay(i * 30L) 
                        .setDuration(200)
                        .setInterpolator(new AccelerateInterpolator())
                        .start();
            }
            
            long delayToHide = (childCount - 1) * 30L + 200L;
            arrayListContainer.postDelayed(() -> {
                if (!data.isArrayListEnabled) {
                    arrayListContainer.setVisibility(View.GONE);
                    arrayListContainer.removeAllViews();
                }
            }, delayToHide + 10);
        }
    }

    private void animateTextChange(final TextView tv, final String newText) {
        if (tv == null || tv.getText().toString().equals(newText)) return;
        
        tv.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(150)
            .withEndAction(() -> {
                tv.setText(newText);
                tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
            }).start();
    }

    public void showStackedToast(Context context, ViewGroup rootView, String title, String desc, boolean isEnabled, float density) {
        if (rootView == null) {
            // 全局悬浮模式下没有应用内 rootView，改用系统 Toast
            try {
                android.widget.Toast.makeText(context, title + "  ·  " + desc, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
            return;
        }
        LinearLayout toastContainer = rootView.findViewWithTag("toast_container");
        if (toastContainer == null) {
            toastContainer = new LinearLayout(context);
            toastContainer.setOrientation(LinearLayout.VERTICAL);
            toastContainer.setTag("toast_container");
            toastContainer.setClickable(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) toastContainer.setElevation(100f);

            LayoutTransition lt = new LayoutTransition();
            lt.enableTransitionType(LayoutTransition.CHANGING);
            lt.disableTransitionType(LayoutTransition.APPEARING); 
            lt.disableTransitionType(LayoutTransition.DISAPPEARING);
            lt.setDuration(250); 
            toastContainer.setLayoutTransition(lt);

            FrameLayout.LayoutParams tcParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int)(450 * density));
            tcParams.gravity = Gravity.BOTTOM | Gravity.END; 
            tcParams.setMargins(0, 0, 0, (int)(20 * density));
            rootView.addView(toastContainer, tcParams); 
            toastContainer.bringToFront();

            View spacer = new View(context);
            toastContainer.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        }

        final View toastView = createOptimizedToastItem(context, title, desc, isEnabled, density);
        LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        viewParams.topMargin = (int)(6 * density); 
        viewParams.gravity = Gravity.END;
        toastView.setAlpha(0f); 
        toastContainer.addView(toastView, viewParams);

        toastView.post(() -> {
            float viewWidth = toastView.getWidth(); 
            toastView.setTranslationX(viewWidth + 50);
            ObjectAnimator slideIn = ObjectAnimator.ofFloat(toastView, "translationX", 0f);
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(toastView, "alpha", 1f);
            AnimatorSet slideInSet = new AnimatorSet(); 
            slideInSet.playTogether(slideIn, fadeIn);
            slideInSet.setDuration(350); 
            slideInSet.setInterpolator(new DecelerateInterpolator(1.5f)); 
            slideInSet.start();

            toastView.postDelayed(() -> {
                ObjectAnimator slideOut = ObjectAnimator.ofFloat(toastView, "translationX", viewWidth + 50);
                ObjectAnimator fadeOut = ObjectAnimator.ofFloat(toastView, "alpha", 0f);
                AnimatorSet slideOutSet = new AnimatorSet(); 
                slideOutSet.playTogether(slideOut, fadeOut);
                slideOutSet.setDuration(300); 
                slideOutSet.setInterpolator(new AccelerateInterpolator(1.5f));
                slideOutSet.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        LinearLayout container = (LinearLayout) rootView.findViewWithTag("toast_container");
                        if(container != null) container.removeView(toastView);
                    }
                });
                slideOutSet.start();
            }, 2500);
        });
    }

    private View createOptimizedToastItem(Context context, String title, String desc, boolean isEnabled, float density) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL); 
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setMinimumWidth((int)(160 * density));

        GradientDrawable bg = new GradientDrawable(); 
        bg.setColor(Color.parseColor("#1C1C1C"));
        float r = 10 * density; 
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        bg.setStroke(2, Color.parseColor("#333333")); 
        container.setBackground(bg);
        container.setPadding((int)(14 * density), (int)(8 * density), (int)(16 * density), (int)(8 * density));

        View lineColorBar = new View(context);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams((int)(3 * density), (int)(24 * density));
        lineParams.setMargins(0, 0, (int)(10 * density), 0); 
        lineColorBar.setLayoutParams(lineParams);

        final GradientDrawable lineBg = new GradientDrawable(); 
        lineBg.setCornerRadius(30f);
        lineBg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); 
        lineColorBar.setBackground(lineBg);

        ValueAnimator flowAnimator = ValueAnimator.ofFloat(0f, 1f); 
        flowAnimator.setDuration(1500);
        flowAnimator.setRepeatCount(ValueAnimator.INFINITE); 
        flowAnimator.setInterpolator(new LinearInterpolator());
        flowAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue(); 
            int[] colors = new int[5];
            if (isEnabled) {
                for (int i = 0; i < 5; i++) {
                    float hue = (fraction * 360f + (i * 72f)) % 360f; 
                    colors[i] = Color.HSVToColor(new float[]{hue, 1f, 1f});
                }
            } else {
                for (int i = 0; i < 5; i++) {
                    float pos = (fraction + (i * 0.2f)) % 1.0f;
                    int brightness = (int) (80 + 100 * Math.sin(pos * Math.PI * 2));
                    brightness = Math.max(0, Math.min(255, brightness)); 
                    colors[i] = Color.rgb(brightness, brightness, brightness);
                }
            }
            lineBg.setColors(colors);
        });
        flowAnimator.start();

        lineColorBar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { flowAnimator.cancel(); }
        });

        LinearLayout textContainer = new LinearLayout(context); 
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(context); 
        titleView.setText(title); 
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15f); 
        titleView.setTypeface(null, Typeface.BOLD);

        TextView descView = new TextView(context); 
        descView.setText(desc); 
        descView.setTextColor(Color.parseColor("#888888")); 
        descView.setTextSize(11f);

        textContainer.addView(titleView); 
        textContainer.addView(descView);
        container.addView(lineColorBar); 
        container.addView(textContainer);
        return container;
    }
}
