package com.carx.snowcontrol;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class FloatingMenu {
    public View currentMenu = null;
    public boolean isAnimating = false;

    private FloatingData data;
    private FloatingAssets assets;
    private FloatingHUD hud;
    private MonitorPanel monitor;

    private final String[] menuItems = {"Home", "CarX", "Tools", "Monitor", "Games", "Settings"};
    private View[] panels = new View[6];

    // ===== 双宿主：应用内 rootView / 全局悬浮窗(WindowManager) =====
    private ViewGroup hostRoot = null;
    private WindowManager globalWm = null;
    private boolean globalMode = false;
    private FrameLayout rightFrame = null;
    private View currentRightView = null;
    private boolean isPanelSwitching = false;
    private View appsPanel = null;

    public FloatingMenu(FloatingData data, FloatingAssets assets, FloatingHUD hud) {
        this.data = data;
        this.assets = assets;
        this.hud = hud;
    }

    public void toggleMenu(Context context, ViewGroup rootView) {
        if (isAnimating) return;
        if (currentMenu == null) {
            if (data.globalMenuEnabled && hasOverlayPermission(context)) showMenuGlobal(context);
            else showMenu(context, rootView);
        } else hideMenu();
    }

    /** 全局悬浮小猪点击入口：直接切换全局菜单显示/隐藏 */
    public void toggleMenuGlobal(Context context) {
        if (isAnimating) return;
        if (currentMenu == null) {
            if (!hasOverlayPermission(context)) return;
            showMenuGlobal(context);
        } else {
            hideMenu();
        }
    }

    private boolean hasOverlayPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public void showMenu(Context context, ViewGroup rootView) {
        hostRoot = rootView;
        globalMode = false;
        globalWm = null;
        showMenuInternal(context);
    }

    public void showMenuGlobal(Context context) {
        hostRoot = null;
        globalMode = true;
        globalWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        showMenuInternal(context);
    }

    private void rebuildMenu(Context context) {
        if (monitor != null) monitor.stop();
        if (currentMenu != null) removeMenuFromHost(currentMenu);
        currentMenu = null;
        isAnimating = false;
        showMenuInternal(context);
    }

    private void showMenuInternal(final Context context) {
        isAnimating = true;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        float density = metrics.density;
        int theme = data.themeIndex;
        final int accent = ThemeManager.accent(theme);
        final int cardColor = ThemeManager.card(theme);
        final int strokeColor = ThemeManager.stroke(theme);
        final int glowColor = ThemeManager.glow(theme);

        int menuWidth = (int) (metrics.widthPixels * 0.60);
        int menuHeight;
        if (assets.cachedBeonBitmap != null) {
            int rightAreaWidth = (int) (menuWidth * 0.8) - 20;
            float ratio = (float) assets.cachedBeonBitmap.getWidth() / assets.cachedBeonBitmap.getHeight();
            menuHeight = (int) (rightAreaWidth / ratio) + 40;
        } else menuHeight = 420;
        menuHeight = Math.max(400, menuHeight);

        final LinearLayout menuLayout = new LinearLayout(context);
        menuLayout.setOrientation(LinearLayout.HORIZONTAL);
        menuLayout.setBackground(createGlowBackground(ThemeManager.PALETTES[theme][0], 50f, 1, ThemeManager.PALETTES[theme][3]));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            menuLayout.setElevation(40f);
            menuLayout.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 50f); }
            });
            menuLayout.setClipToOutline(true);
        }

        // ========== 左侧导航栏 ==========
        LinearLayout sideBar = new LinearLayout(context);
        sideBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        sideParams.setMargins(15, 15, 10, 15);
        sideBar.setLayoutParams(sideParams);
        GradientDrawable sideBg = new GradientDrawable();
        sideBg.setColor(ThemeManager.side(theme));
        sideBg.setCornerRadius(40f);
        sideBar.setBackground(sideBg);

        LinearLayout cancelBtnBox = new LinearLayout(context);
        cancelBtnBox.setOrientation(LinearLayout.VERTICAL);
        cancelBtnBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.setMargins(5, 5, 5, 0);
        cancelBtnBox.setPadding(10, 35, 10, 35);
        cancelBtnBox.setLayoutParams(boxParams);
        cancelBtnBox.setBackground(createGlowBackground(ThemeManager.PALETTES[theme][4], 25f, 1, ThemeManager.PALETTES[theme][3]));

        TextView cancelTxt = new TextView(context);
        cancelTxt.setText("PigHax");
        cancelTxt.setTextColor(accent);
        cancelTxt.setTextSize(14f);
        cancelTxt.setTypeface(null, Typeface.BOLD);
        cancelTxt.setGravity(Gravity.CENTER);
        cancelBtnBox.addView(cancelTxt);
        cancelBtnBox.setOnClickListener(v -> hideMenu());
        sideBar.addView(cancelBtnBox);

        LinearLayout optionsBox = new LinearLayout(context);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams optionsBoxParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        optionsBoxParams.setMargins(5, 0, 5, 5);
        optionsBox.setPadding(10, 15, 10, 15);
        optionsBox.setLayoutParams(optionsBoxParams);
        optionsBox.setBackground(createGlowBackground(ThemeManager.PALETTES[theme][4], 25f, 1, ThemeManager.PALETTES[theme][3]));

        FrameLayout rightFrame = new FrameLayout(context);
        this.rightFrame = rightFrame;
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 8f);
        rightParams.setMargins(0, 30, 10, 30);
        rightFrame.setLayoutParams(rightParams);
        rightFrame.setBackground(createGlowBackground(ThemeManager.PALETTES[theme][4], 42f, 2, ThemeManager.PALETTES[theme][5]));

        // ========== 面板 0：Home ==========
        FrameLayout homePanel = new FrameLayout(context);
        ImageView imgView = new ImageView(context);
        if (assets.cachedBeonBitmap != null) imgView.setImageBitmap(assets.cachedBeonBitmap);
        imgView.setScaleType(ImageView.ScaleType.FIT_XY);
        imgView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            imgView.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 40f); }
            });
            imgView.setClipToOutline(true);
        }
        homePanel.addView(imgView);
        LinearLayout homeCard = createHomeCard(context, density, accent);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.BOTTOM | Gravity.START;
        cardParams.setMargins((int)(14*density), 0, 0, (int)(14*density));
        homePanel.addView(homeCard, cardParams);

        // ========== 面板 1：CarX 控制（保留 PigHax 原生卡片体系） ==========
        ScrollView cheatsScroll = createScrollView(context, null);
        LinearLayout cheatsContainer = createContainer(context);
        buildCarXControls(context, cheatsContainer, density, accent, cardColor, strokeColor); 
        cheatsScroll.addView(cheatsContainer);

        // ========== 面板 2：Tools ==========
        ScrollView toolsScroll = createScrollView(context, null);
        LinearLayout toolsContainer = createContainer(context);

        boolean ggInstalled = GGLauncher.isGGInstalled(context);
        toolsContainer.addView(createBigButton(context, "🎮 GameGuardian", 
                ggInstalled ? (data.isChinese() ? "选择游戏 → 一键唤起 GG" : "Pick a game → launch GG") 
                            : (data.isChinese() ? "未检测到 GG（com.kar.vv）" : "GG not found (com.kar.vv)"),
                accent, cardColor, strokeColor, density, v -> {
                    if (!GGLauncher.isGGInstalled(context)) {
                        hud.showStackedToast(context, hostRoot, "GameGuardian", data.isChinese() ? "未安装，请先安装 GG 修改器" : "GG not installed", false, density);
                        return;
                    }
                    showAppPicker(context, true, density);
                }));

        toolsContainer.addView(createSwitchItem(context, "GlobalMenu", density, accent, cardColor, strokeColor));
        toolsContainer.addView(createSwitchItem(context, "GlobalFloat", density, accent, cardColor, strokeColor));

        toolsContainer.addView(createBigButton(context, "⚡ Quick Launch",
                data.isChinese() ? "快速启动任意应用" : "Launch any installed app",
                accent, cardColor, strokeColor, density, v -> showAppPicker(context, false, density)));

        toolsContainer.addView(createBigButton(context, "💤 About",
                data.isChinese() ? "猪猪侠 PigHax v1.2 · 悬浮小猪伙伴" : "PigHax v1.2 · floating piggy buddy",
                accent, cardColor, strokeColor, density, v ->
                        hud.showStackedToast(context, hostRoot, "PigHax", "v1.2 · made with ♥", true, density)));
        toolsScroll.addView(toolsContainer);

        // ========== 面板 3：Monitor ==========
        ScrollView monitorScroll = createScrollView(context, null);
        monitor = new MonitorPanel(data);
        LinearLayout monitorContent = monitor.build(context);
        monitorScroll.addView(monitorContent);

        // ========== 面板 4：Games ==========
        FrameLayout gamesFrame = buildGamesPanel(context, density, accent, cardColor, strokeColor, theme);

        // ========== 面板 5：Settings ==========
        ScrollView settingsScroll = createScrollView(context, null);
        LinearLayout settingsContainer = createContainer(context);
        settingsContainer.addView(createGroupTitle(context, "Theme", accent, density));
        for (int i = 0; i < 4; i++) {
            settingsContainer.addView(createThemeRow(context, i, density));
        }
        settingsContainer.addView(createGroupTitle(context, "Language", accent, density));
        settingsContainer.addView(createSwitchItem(context, "Language", density, accent, cardColor, strokeColor));
        settingsContainer.addView(createGroupTitle(context, "About", accent, density));
        TextView aboutTv = new TextView(context);
        aboutTv.setText(data.isChinese() ? "猪猪侠 PigHax v1.2\n悬浮小猪伙伴 · 游戏工具 · 系统监控 · 摸鱼游戏" : "PigHax v1.2\nFloating piggy · game tools · system monitor · mini game");
        aboutTv.setTextColor(Color.parseColor("#AAAAAA"));
        aboutTv.setTextSize(11f);
        aboutTv.setPadding((int)(6*density), (int)(4*density), 0, 0);
        settingsContainer.addView(aboutTv);
        settingsScroll.addView(settingsContainer);

        // ========== 面板数组 + 可见性 ==========
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(9, 9, 9, 9);
        panels[0] = homePanel;
        panels[1] = cheatsScroll;
        panels[2] = toolsScroll;
        panels[3] = monitorScroll;
        panels[4] = gamesFrame;
        panels[5] = settingsScroll;
        for (View p : panels) {
            p.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                p.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 40f); }
                });
                p.setClipToOutline(true);
            }
        }

        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == data.lastSelectedTabIndex ? View.VISIBLE : View.GONE);
            rightFrame.addView(panels[i]);
        }

        // ========== 侧边栏 tab ==========
        final ImageView[] optionIcons = new ImageView[6];
        final TextView[] optionTexts = new TextView[6];
        currentRightView = panels[data.lastSelectedTabIndex];
        isPanelSwitching = false;

        for (int i = 0; i < 6; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            ImageView iconView = new ImageView(context);
            if (i < 5 && assets.cachedMenuIcons[i] != null) iconView.setImageBitmap(assets.cachedMenuIcons[i]);
            iconView.setLayoutParams(new LinearLayout.LayoutParams((int)(18 * density), (int)(18 * density)));

            TextView itemTxt = new TextView(context);
            itemTxt.setTag("tab_text_" + menuItems[i]);
            itemTxt.setText(i == 1 ? "CarX" : data.getTrans(menuItems[i]));
            itemTxt.setTextColor(Color.WHITE);
            itemTxt.setTextSize(11f);
            itemTxt.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams tParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tParams.leftMargin = (int)(5 * density);
            itemTxt.setLayoutParams(tParams);

            if (i == data.lastSelectedTabIndex) { iconView.setAlpha(1.0f); itemTxt.setVisibility(View.VISIBLE); }
            else { iconView.setAlpha(0.5f); itemTxt.setVisibility(View.GONE); }

            row.addView(iconView);
            row.addView(itemTxt);
            optionIcons[i] = iconView;
            optionTexts[i] = itemTxt;

            final int currentIndex = i;
            row.setOnClickListener(v -> {
                if (data.lastSelectedTabIndex == currentIndex || isPanelSwitching) return;
                optionTexts[data.lastSelectedTabIndex].setVisibility(View.GONE);
                optionIcons[data.lastSelectedTabIndex].animate().alpha(0.5f).setDuration(200).start();
                optionTexts[currentIndex].setVisibility(View.VISIBLE);
                optionIcons[currentIndex].animate().alpha(1.0f).setDuration(200).start();
                data.lastSelectedTabIndex = currentIndex;

                View targetView = panels[currentIndex];
                if (currentRightView != targetView) {
                    isPanelSwitching = true;
                    final View viewToHide = currentRightView;
                    viewToHide.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(120).setInterpolator(new AccelerateInterpolator())
                            .withEndAction(() -> {
                                viewToHide.setVisibility(View.GONE);
                                viewToHide.setScaleX(1f); viewToHide.setScaleY(1f);
                                targetView.setVisibility(View.VISIBLE);
                                targetView.setAlpha(0f); targetView.setScaleX(0.95f); targetView.setScaleY(0.95f);
                                targetView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator(1.5f))
                                        .withEndAction(() -> isPanelSwitching = false).start();
                            }).start();
                    currentRightView = targetView;
                }
            });
            optionsBox.addView(row);
        }
        sideBar.addView(optionsBox);
        menuLayout.addView(sideBar);
        menuLayout.addView(rightFrame);
        FrameLayout.LayoutParams mParams = new FrameLayout.LayoutParams(menuWidth, menuHeight);
        mParams.gravity = Gravity.CENTER;
        menuLayout.setLayoutParams(mParams);
        currentMenu = menuLayout;
        addMenuToHost(menuLayout, menuWidth, menuHeight);

        animateMenu(menuLayout, 0f, 1f, new OvershootInterpolator(), 700);
        if (monitor != null) monitor.start();
    }


    private void buildCarXControls(Context context, LinearLayout host, float density, int accent, int cardColor, int strokeColor) {
        host.addView(createGroupTitle(context, "Weather & Time", accent, density));
        host.addView(createBigButton(context, "☀ 天气预设", "晴天 / Halloween / Winter / 阴天 / 雨天", accent, cardColor, strokeColor, density, v -> {
            final String[] names = {"晴天", "Halloween", "Winter", "阴天", "雨天"};
            new AlertDialog.Builder(context).setTitle("选择天气").setItems(names, (d, which) -> CarXControlFiles.write(context, "carx_snow_weather", String.valueOf(which))).show();
        }));
        host.addView(createBigButton(context, "◷ 游戏时间", "设置游戏内分钟数（0–1439）", accent, cardColor, strokeColor, density, v -> {
            android.widget.EditText input = new android.widget.EditText(context); input.setInputType(2); input.setHint("例如 720");
            new AlertDialog.Builder(context).setTitle("游戏时间").setView(input).setPositiveButton("应用", (d, w) -> { try { int n=Integer.parseInt(input.getText().toString()); if(n>=0&&n<=1439) CarXControlFiles.write(context,"carx_snow_time_minutes",String.valueOf(n)); } catch(Exception ignored){} }).setNegativeButton("取消",null).show();
        }));
        host.addView(createGroupTitle(context, "Ground", accent, density));
        host.addView(createBigButton(context, "💧 湿地面", "写入湿地面控制开关", accent, cardColor, strokeColor, density, v -> {
            final String[] names={"关闭","开启"}; new AlertDialog.Builder(context).setTitle("湿地面").setItems(names,(d,w)->CarXControlFiles.write(context,"carx_snow_wet_ground",w==1?"1":"0")).show();
        }));
        host.addView(createGroupTitle(context, "Snow", accent, density));
        host.addView(createBigButton(context, "❄ 雪天设置", "进入雪天二级菜单：开关与雪花大小", accent, cardColor, strokeColor, density, v -> showSnowSubmenu(context, density, accent, cardColor, strokeColor)));
    }

    private void showSnowSubmenu(Context context, float density, int accent, int cardColor, int strokeColor) {
        if (rightFrame == null) return;
        final LinearLayout page = createContainer(context);
        page.addView(createBigButton(context, "← 返回", "返回 CarX 控制", accent, cardColor, strokeColor, density, v -> switchToPanel(panels[1])));
        page.addView(createGroupTitle(context, "Snow weather", accent, density));
        page.addView(createBigButton(context, "❄ 雪天开关", "开启 / 关闭雪花粒子", accent, cardColor, strokeColor, density, v -> {
            final String[] names={"关闭雪天","开启雪天"}; new AlertDialog.Builder(context).setTitle("雪天开关").setItems(names,(d,w)->CarXControlFiles.write(context,"carx_snow_enabled",w==1?"1":"0")).show();
        }));
        LinearLayout sliderCard = new LinearLayout(context); sliderCard.setOrientation(LinearLayout.VERTICAL); sliderCard.setPadding((int)(14*density),(int)(12*density),(int)(14*density),(int)(12*density));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(cardColor); bg.setCornerRadius(24*density); bg.setStroke((int)Math.max(1,density),strokeColor); sliderCard.setBackground(bg);
        TextView label = new TextView(context); label.setText("雪花大小"); label.setTextColor(Color.WHITE); label.setTextSize(13); sliderCard.addView(label);
        android.widget.SeekBar seek = new android.widget.SeekBar(context); seek.setMax(275); seek.setProgress(75); sliderCard.addView(seek);
        TextView value = new TextView(context); value.setText("1.00×"); value.setTextColor(accent); value.setTextSize(12); sliderCard.addView(value);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(android.widget.SeekBar b,int p,boolean user){float f=0.25f+p/100f; value.setText(String.format(java.util.Locale.US,"%.2f×",f)); if(user) CarXControlFiles.write(context,"carx_snow_multiplier",String.format(java.util.Locale.US,"%.2f",f));} public void onStartTrackingTouch(android.widget.SeekBar b){} public void onStopTrackingTouch(android.widget.SeekBar b){} });
        page.addView(sliderCard);
        page.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rightFrame.addView(page);
        page.setVisibility(View.GONE);
        switchToPanel(page);
    }

    public void hideMenu() {
        if (currentMenu == null) return;
        if (monitor != null) monitor.stop();
        isAnimating = true;
        final View menuToHide = currentMenu;
        currentMenu = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) menuToHide.setElevation(0f);
        menuToHide.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        float cameraDist = 8000 * menuToHide.getContext().getResources().getDisplayMetrics().density;
        menuToHide.setCameraDistance(cameraDist);
        menuToHide.setPivotX(menuToHide.getWidth() / 2f);
        menuToHide.setPivotY(menuToHide.getHeight());

        ObjectAnimator fallBack = ObjectAnimator.ofFloat(menuToHide, "rotationX", 0f, 85f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(menuToHide, "scaleX", 1f, 0.75f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(menuToHide, "scaleY", 1f, 0.75f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(menuToHide, "alpha", 1f, 0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.setDuration(600);
        animatorSet.setInterpolator(new AccelerateInterpolator());
        animatorSet.playTogether(fallBack, scaleX, scaleY, alpha);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                menuToHide.setLayerType(View.LAYER_TYPE_NONE, null);
                removeMenuFromHost(menuToHide);
                isAnimating = false;
            }
        });
        animatorSet.start();
    }

    // ===== 双宿主挂载/卸载 =====
    private void addMenuToHost(View menu, int width, int height) {
        try {
            if (globalMode && globalWm != null) {
                WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                        width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                wlp.gravity = Gravity.CENTER;
                globalWm.addView(menu, wlp);
            } else if (hostRoot != null) {
                FrameLayout.LayoutParams mParams = new FrameLayout.LayoutParams(width, height);
                mParams.gravity = Gravity.CENTER;
                hostRoot.addView(menu, mParams);
            }
        } catch (Exception e) {
            // 悬浮权限异常（极罕见，打开前已预检）：静默降级，不强行回退
            globalMode = false;
        }
    }

    private void removeMenuFromHost(View menu) {
        try {
            if (globalMode && globalWm != null) globalWm.removeView(menu);
            else if (hostRoot != null) hostRoot.removeView(menu);
        } catch (Exception e) {}
    }

    // ========== 应用列表（内联面板，避免 Dialog 在 Service 场景崩溃） ==========
    private void showAppPicker(final Context context, final boolean ggMode, final float density) {
        showAppListPanel(context, ggMode, density);
    }

    private void showAppListPanel(final Context context, final boolean ggMode, final float density) {
        if (appsPanel == null) {
            appsPanel = buildAppsPanel(context, density);
            appsPanel.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            rightFrame.addView(appsPanel);
            appsPanel.setVisibility(View.GONE);
        }
        fillAppsPanel(context, ggMode, density);
        switchToPanel(appsPanel);
    }

    private void switchToPanel(View target) {
        if (currentRightView == target || isPanelSwitching) return;
        isPanelSwitching = true;
        final View viewToHide = currentRightView;
        viewToHide.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(120).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    viewToHide.setVisibility(View.GONE);
                    viewToHide.setScaleX(1f); viewToHide.setScaleY(1f);
                    target.setVisibility(View.VISIBLE);
                    target.setAlpha(0f); target.setScaleX(0.95f); target.setScaleY(0.95f);
                    target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator(1.5f))
                            .withEndAction(() -> isPanelSwitching = false).start();
                }).start();
        currentRightView = target;
    }

    private View buildAppsPanel(Context context, float density) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int)(8*density), (int)(6*density), (int)(8*density), (int)(6*density));

        // 顶部返回条
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding((int)(8*density), (int)(6*density), (int)(8*density), (int)(6*density));

        TextView backBtn = new TextView(context);
        backBtn.setText("‹ " + (data.isChinese() ? "返回" : "Back"));
        backBtn.setTextColor(ThemeManager.accent(data.themeIndex));
        backBtn.setTextSize(14f);
        backBtn.setTypeface(null, Typeface.BOLD);
        backBtn.setOnClickListener(v -> {
            data.lastSelectedTabIndex = 2; // Tools
            switchToPanel(panels[2]);
        });
        topBar.addView(backBtn);

        TextView titleTv = new TextView(context);
        titleTv.setText(data.isChinese() ? "选择应用" : "Pick an app");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(14f);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(titleTv);

        // 空占位（右对齐）
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams((int)(60*density), 1));
        topBar.addView(spacer);

        panel.addView(topBar);

        ScrollView sv = createScrollView(context, null);
        LinearLayout container = createContainer(context);
        sv.addView(container);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 40f); }
            });
            panel.setClipToOutline(true);
        }
        return panel;
    }

    private void fillAppsPanel(Context context, boolean ggMode, float density) {
        LinearLayout panel = (LinearLayout) appsPanel;
        ScrollView sv = (ScrollView) panel.getChildAt(1);
        LinearLayout container = (LinearLayout) sv.getChildAt(0);
        container.removeAllViews();

        java.util.List<GGLauncher.AppInfo> apps = GGLauncher.getLaunchableApps(context);
        if (apps.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(data.isChinese() ? "没有可用的应用" : "No apps found");
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setTextSize(12f);
            empty.setGravity(Gravity.CENTER);
            container.addView(empty);
            return;
        }
        for (final GGLauncher.AppInfo app : apps) {
            container.addView(createAppRow(context, app, ggMode, density));
        }
    }

    private View createAppRow(final Context context, final GGLauncher.AppInfo app, final boolean ggMode, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(50*density));
        params.bottomMargin = (int)(5*density);
        row.setLayoutParams(params);
        row.setPadding((int)(14*density), (int)(6*density), (int)(14*density), (int)(6*density));
        row.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeManager.card(data.themeIndex));
        bg.setCornerRadius(20f);
        bg.setStroke(2, ThemeManager.stroke(data.themeIndex));
        row.setBackground(bg);

        TextView nameTv = new TextView(context);
        nameTv.setText(app.label);
        nameTv.setTextColor(Color.WHITE);
        nameTv.setTextSize(13f);
        nameTv.setTypeface(null, Typeface.BOLD);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(nameTv);

        TextView pkgTv = new TextView(context);
        pkgTv.setText(app.packageName);
        pkgTv.setTextColor(Color.parseColor("#777777"));
        pkgTv.setTextSize(9f);
        pkgTv.setSingleLine(true);
        pkgTv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(pkgTv);

        row.setOnClickListener(v -> {
            if (ggMode) {
                GGLauncher.launchGG(context, app.packageName);
                hud.showStackedToast(context, hostRoot, "GameGuardian", data.isChinese() ? "正在唤起 GG…" : "Launching GG…", true, density);
            } else {
                GGLauncher.launchApp(context, app.packageName);
            }
        });
        return row;
    }

    // ========== Home 状态卡片 ==========
    private LinearLayout createHomeCard(final Context context, float density, final int accent) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(16*density), (int)(10*density), (int)(16*density), (int)(10*density));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(18 * density);
        bg.setStroke((int)(1.5f*density), accent);
        card.setBackground(bg);

        final TextView moodTv = new TextView(context);
        moodTv.setTextSize(16f);
        moodTv.setTypeface(null, Typeface.BOLD);
        moodTv.setTextColor(Color.WHITE);
        card.addView(moodTv);

        final TextView affTv = new TextView(context);
        affTv.setTextSize(12f);
        affTv.setTextColor(Color.parseColor("#CCCCCC"));
        affTv.setPadding(0, (int)(3*density), 0, 0);
        card.addView(affTv);

        TextView feedBtn = new TextView(context);
        feedBtn.setText(data.isChinese() ? "🍎 喂食" : "🍎 Feed");
        feedBtn.setTextColor(Color.WHITE);
        feedBtn.setTextSize(13f);
        feedBtn.setTypeface(null, Typeface.BOLD);
        feedBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fbParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int)(34*density));
        fbParams.topMargin = (int)(10*density);
        feedBtn.setLayoutParams(fbParams);
        GradientDrawable fbBg = new GradientDrawable();
        fbBg.setColor(accent);
        fbBg.setCornerRadius(17 * density);
        feedBtn.setBackground(fbBg);
        feedBtn.setPadding((int)(18*density), 0, (int)(18*density), 0);
        card.addView(feedBtn);

        refreshHomeCard(moodTv, affTv);
        feedBtn.setOnClickListener(v -> {
            boolean fed = data.feed();
            hud.showStackedToast(context, hostRoot, data.isChinese() ? "🍎 喂食" : "🍎 Feed",
                    fed ? (data.isChinese() ? "小猪吃饱了！好感 +5" : "Piggy is full! +5") 
                        : (data.isChinese() ? "小猪还在消化…" : "Piggy is still digesting…"),
                    fed, density);
            refreshHomeCard(moodTv, affTv);
        });
        return card;
    }

    private void refreshHomeCard(TextView moodTv, TextView affTv) {
        moodTv.setText(data.getMoodEmoji() + "  " + data.getTrans("Home") + " · " + (data.isChinese() ? "心情" : "Mood") + ": " + data.getMood());
        affTv.setText("❤ " + (data.isChinese() ? "好感度" : "Affection") + ": " + data.petAffection + "   " + (data.isChinese() ? "点击小猪摸摸它" : "Tap the piggy to pet it"));
    }

    // ========== 分组标题 ==========
    private TextView createGroupTitle(Context context, String key, int accent, float density) {
        TextView tv = new TextView(context);
        tv.setText(data.getTrans(key));
        tv.setTextColor(accent);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextSize(13f);
        tv.setPadding((int)(6*density), (int)(12*density), 0, (int)(2*density));
        return tv;
    }

    // ========== 大按钮 ==========
    private View createBigButton(Context context, String title, String desc, int accent, int cardColor, int strokeColor, float density, View.OnClickListener listener) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(56*density));
        params.bottomMargin = (int)(8*density);
        container.setLayoutParams(params);
        container.setPadding((int)(14*density), 0, (int)(14*density), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(24f);
        bg.setStroke(2, strokeColor);
        container.setBackground(bg);

        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setGravity(Gravity.CENTER_VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setIncludeFontPadding(false);

        TextView descView = new TextView(context);
        descView.setText(desc);
        descView.setTextColor(Color.parseColor("#999999"));
        descView.setTextSize(10f);
        descView.setSingleLine(true);
        descView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = (int)(2*density);
        descView.setLayoutParams(descParams);

        textContainer.addView(titleView);
        textContainer.addView(descView);

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextColor(accent);
        arrow.setTextSize(24f);
        container.addView(textContainer);
        container.addView(arrow);
        container.setOnClickListener(listener);
        return container;
    }

    // ========== 主题选择行 ==========
    private View createThemeRow(final Context context, final int index, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(44*density));
        params.bottomMargin = (int)(4*density);
        row.setLayoutParams(params);
        row.setPadding((int)(12*density), 0, (int)(12*density), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(16 * density);
        row.setBackground(bg);

        View swatch = new View(context);
        LinearLayout.LayoutParams swParams = new LinearLayout.LayoutParams((int)(22*density), (int)(22*density));
        swParams.setMargins(0, 0, (int)(10*density), 0);
        swatch.setLayoutParams(swParams);
        GradientDrawable swBg = new GradientDrawable();
        swBg.setShape(GradientDrawable.OVAL);
        swBg.setColor(ThemeManager.accent(index));
        swBg.setStroke((int)(2*density), Color.WHITE);
        swatch.setBackground(swBg);
        row.addView(swatch);

        TextView nameTv = new TextView(context);
        nameTv.setText(FloatingData.THEME_NAMES[index]);
        nameTv.setTextColor(Color.WHITE);
        nameTv.setTextSize(13f);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(nameTv);

        TextView checkTv = new TextView(context);
        checkTv.setText(data.themeIndex == index ? "✓" : "");
        checkTv.setTextColor(ThemeManager.accent(index));
        checkTv.setTextSize(16f);
        checkTv.setTypeface(null, Typeface.BOLD);
        row.addView(checkTv);

        row.setOnClickListener(v -> {
            data.themeIndex = index;
            rebuildMenu(context);
        });
        return row;
    }

    // ========== Games 面板 ==========
    private FrameLayout buildGamesPanel(final Context context, final float density,
                                        final int accent, int cardColor, int strokeColor, int theme) {
        FrameLayout frame = new FrameLayout(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding((int)(8*density), (int)(6*density), (int)(8*density), (int)(6*density));
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部：得分 + 控制按钮
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView scoreTv = new TextView(context);
        scoreTv.setText("🐍 " + (data.isChinese() ? "得分" : "Score") + ": 0");
        scoreTv.setTextColor(accent);
        scoreTv.setTextSize(14f);
        scoreTv.setTypeface(null, Typeface.BOLD);
        scoreTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(scoreTv);

        final TextView startBtn = makeSmallBtn(context, data.isChinese() ? "开始" : "Start", accent, density);
        header.addView(startBtn);

        final TextView resetBtn = makeSmallBtn(context, "↺", accent, density);
        header.addView(resetBtn);
        layout.addView(header);

        // 蛇
        final SnakeGame snake = new SnakeGame(context);
        snake.setThemeIndex(theme);
        snake.setListener(new SnakeGame.Listener() {
            @Override public void onScore(int score) {
                scoreTv.setText("🐍 " + (data.isChinese() ? "得分" : "Score") + ": " + score);
            }
            @Override public void onGameOver(int score) {
                startBtn.setText(data.isChinese() ? "开始" : "Start");
                hud.showStackedToast(context, hostRoot, "🐍 " + (data.isChinese() ? "游戏结束" : "Game Over"),
                        (data.isChinese() ? "得分: " : "Score: ") + score, false, density);
            }
        });

        startBtn.setOnClickListener(v -> {
            snake.start();
            startBtn.setText(data.isChinese() ? "暂停" : "Pause");
        });
        resetBtn.setOnClickListener(v -> {
            snake.reset();
            scoreTv.setText("🐍 " + (data.isChinese() ? "得分" : "Score") + ": 0");
        });
        layout.addView(snake, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 方向控制条
        LinearLayout dpad = new LinearLayout(context);
        dpad.setOrientation(LinearLayout.HORIZONTAL);
        dpad.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dpadParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(40*density));
        dpadParams.topMargin = (int)(6*density);
        dpad.setLayoutParams(dpadParams);

        String[] dirs = {"◀", "▲", "▼", "▶"};
        final int[][] dirVec = {{-1,0},{0,-1},{0,1},{1,0}};
        for (int i = 0; i < 4; i++) {
            final int[] vec = dirVec[i];
            TextView btn = makeSmallBtn(context, dirs[i], accent, density);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams((int)(56*density), (int)(34*density));
            bp.setMargins((int)(6*density), 0, (int)(6*density), 0);
            btn.setLayoutParams(bp);
            btn.setOnClickListener(v -> snake.setDirection(vec[0], vec[1]));
            dpad.addView(btn);
        }
        layout.addView(dpad);

        TextView hint = new TextView(context);
        hint.setText(data.isChinese() ? "或直接在蛇屏上滑动控制方向" : "Swipe on the board to steer too");
        hint.setTextColor(Color.parseColor("#666666"));
        hint.setTextSize(9f);
        hint.setGravity(Gravity.CENTER);
        layout.addView(hint);

        frame.addView(layout);
        return frame;
    }

    private TextView makeSmallBtn(Context context, String text, int accent, float density) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int)(10*density), 0, (int)(10*density), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent);
        bg.setCornerRadius(12 * density);
        btn.setBackground(bg);
        return btn;
    }

    // ========== 开关条目（主题化） ==========
    private View createSwitchItem(final Context context, final String titleKey,
                                  float density, final int accent, int cardColor, int strokeColor) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(50*density));
        params.bottomMargin = (int)(6*density);
        container.setLayoutParams(params);
        container.setPadding((int)(14*density), 0, (int)(14*density), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(24f);
        bg.setStroke(2, strokeColor);
        container.setBackground(bg);

        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setGravity(Gravity.CENTER_VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView titleView = new TextView(context);
        titleView.setTag("switch_title_" + titleKey);
        titleView.setText(data.getTrans(titleKey));
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(13f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setIncludeFontPadding(false);

        TextView descView = new TextView(context);
        descView.setTag("switch_desc_" + titleKey);
        descView.setText(data.getDescTrans(titleKey));
        descView.setTextColor(Color.parseColor("#888888"));
        descView.setTextSize(10f);
        descView.setSingleLine(true);
        descView.setEllipsize(TextUtils.TruncateAt.END);
        descView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = (int)(2*density);
        descView.setLayoutParams(descParams);

        textContainer.addView(titleView);
        textContainer.addView(descView);

        FrameLayout switchBg = new FrameLayout(context);
        int switchWidth = (int)(46*density);
        int switchHeight = (int)(24*density);
        switchBg.setLayoutParams(new LinearLayout.LayoutParams(switchWidth, switchHeight));
        final GradientDrawable trackDrawable = new GradientDrawable();
        trackDrawable.setCornerRadius(switchHeight / 2f);
        switchBg.setBackground(trackDrawable);

        final View knob = new View(context);
        int knobSize = (int)(20*density);
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(knobSize, knobSize);
        knobParams.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        knobParams.leftMargin = (int)(2*density);
        knob.setLayoutParams(knobParams);
        GradientDrawable knobDrawable = new GradientDrawable();
        knobDrawable.setShape(GradientDrawable.OVAL);
        knobDrawable.setColor(Color.WHITE);
        knob.setBackground(knobDrawable);
        switchBg.addView(knob);

        boolean initState = titleKey.equals("ArrayList") ? data.isArrayListEnabled
                : titleKey.equals("GlobalFloat") ? data.globalFloatEnabled
                : titleKey.equals("GlobalMenu") ? data.globalMenuEnabled
                : data.featureStates.getOrDefault(titleKey, false);
        final boolean[] isChecked = {initState};
        final int colorOff = strokeColor, colorOn = accent;
        float maxTranslationX = switchWidth - knobSize - (4 * density);
        knob.setTranslationX(initState ? maxTranslationX : 0f);
        trackDrawable.setColor(initState ? colorOn : colorOff);

        container.setOnClickListener(v -> {
            isChecked[0] = !isChecked[0];
            boolean newState = isChecked[0];
            ObjectAnimator animX = ObjectAnimator.ofFloat(knob, "translationX", newState ? maxTranslationX : 0f);
            animX.setDuration(250);
            animX.setInterpolator(new OvershootInterpolator(1.0f));
            animX.start();
            trackDrawable.setColor(newState ? colorOn : colorOff);

            if (titleKey.equals("ArrayList")) {
                data.isArrayListEnabled = newState;
                hud.toggleArrayListStaggered(context, newState);
            } else if (titleKey.equals("GlobalMenu")) {
                data.globalMenuEnabled = newState;
                data.featureStates.put(titleKey, newState);
                if (newState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    hud.showStackedToast(context, hostRoot, "MeowBox", data.isChinese() ? "需要悬浮窗权限，正在跳转…" : "Overlay permission required", false, density);
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception e) {}
                }
            } else if (titleKey.equals("GlobalFloat")) {
                data.globalFloatEnabled = newState;
                data.featureStates.put(titleKey, newState);
                toggleGlobalFloat(context, newState, density);
            } else if (titleKey.equals("Language")) {
                data.featureStates.put(titleKey, newState);
                rebuildMenu(context);
            } else {
                data.featureStates.put(titleKey, newState);
                if (newState) { if (!data.activeFeatures.contains(titleKey)) data.activeFeatures.add(titleKey); }
                else data.activeFeatures.remove(titleKey);
                hud.refreshArrayList(context);
            }
            if (!titleKey.equals("Language")) {
                hud.showStackedToast(context, hostRoot, data.getTrans(titleKey),
                        newState ? (data.isChinese() ? "已开启" : "Enabled") : (data.isChinese() ? "已关闭" : "Disabled"),
                        newState, density);
            }
        });

        container.addView(textContainer);
        container.addView(switchBg);
        return container;
    }

    private void toggleGlobalFloat(Context context, boolean enable, float density) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                data.globalFloatEnabled = false;
                data.featureStates.put("GlobalFloat", false);
                hud.showStackedToast(context, hostRoot, "MeowBox", data.isChinese() ? "需要悬浮窗权限，正在跳转…" : "Overlay permission required", false, density);
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {}
                return;
            }
            context.startService(new Intent(context, GlobalFloatService.class));
        } else {
            context.stopService(new Intent(context, GlobalFloatService.class));
        }
    }

    private LayerDrawable createGlowBackground(String coreColorHex, float cornerRadius, int strokeWidth, String strokeColorHex) {
        int glowWidth = 7;
        int layersCount = 4;
        android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[layersCount + 1];
        for (int i = 0; i < layersCount; i++) {
            GradientDrawable glowLayer = new GradientDrawable();
            glowLayer.setColor(Color.argb((int)(255 * (0.015f + 0.01f * i)), 220, 230, 245));
            glowLayer.setCornerRadius(cornerRadius + (layersCount - i) * 1.5f);
            layers[i] = glowLayer;
        }
        GradientDrawable core = new GradientDrawable();
        core.setColor(Color.parseColor(coreColorHex));
        core.setCornerRadius(cornerRadius);
        if (strokeWidth > 0) core.setStroke(strokeWidth, Color.parseColor(strokeColorHex));
        layers[layersCount] = core;
        LayerDrawable layerDrawable = new LayerDrawable(layers);
        for (int i = 0; i < layersCount; i++) {
            int inset = (int)(glowWidth * ((float) i / layersCount));
            layerDrawable.setLayerInset(i, inset, inset, inset, inset);
        }
        layerDrawable.setLayerInset(layersCount, glowWidth, glowWidth, glowWidth, glowWidth);
        return layerDrawable;
    }

    private ScrollView createScrollView(Context context, FrameLayout.LayoutParams params) {
        ScrollView sv = new ScrollView(context);
        if (params != null) sv.setLayoutParams(params);
        sv.setVerticalScrollBarEnabled(false);
        return sv;
    }

    private LinearLayout createContainer(Context context) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(10, 10, 10, 10);
        return ll;
    }

    private void animateMenu(View view, float start, float end, Interpolator interpolator, int duration) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", start, end);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", start, end);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", start, end);
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.setDuration(duration);
        animatorSet.setInterpolator(interpolator);
        animatorSet.playTogether(scaleX, scaleY, alpha);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { isAnimating = false; }
        });
        animatorSet.start();
    }
}