package com.carx.snowcontrol;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** PigHax-inspired native floating UI, adapted for CarX controls only. */
public final class CarXNativeMenu extends LinearLayout {
    public interface Actions {
        void setMultiplier(float value);
        void setSnowEnabled(boolean enabled);
        void setWeather(int preset);
        void setGameTime(int minutes);
        void setWetGround(boolean enabled);
        void close();
    }

    private static final int BG = Color.rgb(26,26,26);
    private static final int SIDE = Color.rgb(34,34,34);
    private static final int ACCENT = Color.rgb(79,195,247);
    private static final int CARD = Color.rgb(21,21,21);
    private static final int STROKE = Color.rgb(42,49,55);
    private final Context context;
    private final Actions actions;
    private final FrameLayout content;
    private int selected = 0;
    private View active;

    public CarXNativeMenu(Context context, Actions actions) {
        super(context);
        this.context = context;
        this.actions = actions;
        setOrientation(HORIZONTAL);
        setPadding(dp(10), dp(10), dp(10), dp(10));
        setBackground(panel(BG, 34, STROKE, 1));
        setElevation(dp(18));

        LinearLayout side = new LinearLayout(context);
        side.setOrientation(VERTICAL);
        side.setPadding(dp(6), dp(8), dp(6), dp(8));
        side.setBackground(panel(SIDE, 27, STROKE, 1));
        addView(side, new LinearLayout.LayoutParams(0, -1, 2.15f));

        TextView brand = text("PigHax", 15, ACCENT, true);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(16), 0, dp(16));
        side.addView(brand, new LayoutParams(-1, -2));
        addDivider(side);

        content = new FrameLayout(context);
        content.setPadding(dp(10), dp(8), dp(8), dp(8));
        content.setBackground(panel(CARD, 28, STROKE, 1));
        LayoutParams cp = new LayoutParams(0, -1, 7.85f);
        cp.setMargins(dp(8), dp(4), dp(2), dp(4));
        addView(content, cp);

        String[] tabs = {"主页", "天气", "雪天", "设置"};
        for (int i=0; i<tabs.length; i++) {
            final int index=i;
            TextView item = text(tabs[i], 12, Color.WHITE, true);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(14), dp(2), dp(14));
            item.setBackground(panel(i==0 ? Color.rgb(29,55,68) : SIDE, 16, i==0 ? ACCENT : SIDE, i==0 ? 1 : 0));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, 0, 1);
            ip.setMargins(0, dp(5), 0, dp(5)); side.addView(item, ip);
            item.setOnClickListener(v -> showTab(index));
        }
        showTab(0);
    }

    private void showTab(int tab) {
        selected = tab;
        View next;
        if (tab == 0) next = homePage();
        else if (tab == 1) next = weatherPage();
        else if (tab == 2) next = snowPage(false);
        else next = settingsPage();
        if (active != null) content.removeView(active);
        active = next;
        next.setAlpha(0f); next.setScaleX(.97f); next.setScaleY(.97f);
        content.addView(next, new FrameLayout.LayoutParams(-1,-1));
        next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    private View homePage() {
        LinearLayout page = page();
        page.addView(title("CarX Street 控制中心"));
        page.addView(subtitle("原生悬浮界面 · PigHax UI 风格"));
        page.addView(card("天气控制", "调整游戏天气预设", "进入天气页面", v -> showTab(1)));
        page.addView(card("雪天设置", "独立控制雪花效果", "打开雪天二级菜单", v -> showTab(2)));
        page.addView(card("时间与环境", "时间、湿地面等原有控制", "进入设置页面", v -> showTab(3)));
        return page;
    }

    private View weatherPage() {
        LinearLayout page=page(); page.addView(title("天气")); page.addView(subtitle("选择 CarX 天气预设"));
        page.addView(actionCard("晴天", "使用晴天天气预设", v -> actions.setWeather(0)));
        page.addView(actionCard("Halloween", "使用 Halloween 天气预设", v -> actions.setWeather(1)));
        page.addView(actionCard("Winter", "使用 Winter 天气预设", v -> actions.setWeather(2)));
        return page;
    }

    private View snowPage(boolean ignored) {
        LinearLayout page=page();
        TextView back=text("‹  返回主菜单",12,ACCENT,true); back.setPadding(0,dp(4),0,dp(12));
        back.setOnClickListener(v -> showTab(0)); page.addView(back);
        page.addView(title("雪天设置")); page.addView(subtitle("仅控制雪天开关与雪花大小"));
        Switch enabled=new Switch(context); enabled.setText("雪天开关"); enabled.setTextColor(Color.WHITE); enabled.setTextSize(14); enabled.setPadding(dp(8),dp(12),dp(8),dp(12));
        enabled.setOnCheckedChangeListener((b,on)->actions.setSnowEnabled(on));
        page.addView(box(enabled));
        page.addView(sliderCard("雪花大小", "整体倍率", 25, 300, 100, value -> actions.setMultiplier(value/100f), true));
        return page;
    }

    private View settingsPage() {
        LinearLayout page=page(); page.addView(title("时间与环境")); page.addView(subtitle("保留 CarX 项目原有控制接口"));
        page.addView(sliderCard("游戏时间", "0:00 — 23:59", 0, 1439, 720, value -> actions.setGameTime(value), false));
        Switch wet=new Switch(context); wet.setText("湿地面开关"); wet.setTextColor(Color.WHITE); wet.setTextSize(14); wet.setPadding(dp(8),dp(12),dp(8),dp(12)); wet.setOnCheckedChangeListener((b,on)->actions.setWetGround(on)); page.addView(box(wet));
        return page;
    }

    private LinearLayout sliderCard(String heading,String detail,int min,int max,int initial,ValueChanged listener,boolean multiplier) {
        LinearLayout card=cardBase(); card.addView(text(heading,14,Color.WHITE,true)); card.addView(subtitle(detail));
        TextView value=text(multiplier?String.format(java.util.Locale.US,"%.2f×",initial/100f):formatTime(initial),13,ACCENT,true); value.setPadding(0,dp(8),0,0); card.addView(value);
        SeekBar seek=new SeekBar(context); seek.setMax(max-min); seek.setProgress(initial-min); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean user){int n=p+min; value.setText(multiplier?String.format(java.util.Locale.US,"%.2f×",n/100f):formatTime(n)); if(user)listener.changed(n);} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){} });
        card.addView(seek); return card;
    }
    private interface ValueChanged { void changed(int value); }
    private String formatTime(int minutes){return String.format(java.util.Locale.US,"%02d:%02d",minutes/60,minutes%60);}
    private View actionCard(String name,String desc,OnClickListener click){return card(name,desc,"应用",click);}
    private View card(String name,String desc,String action,OnClickListener click){LinearLayout c=cardBase(); c.addView(text(name,14,Color.WHITE,true)); c.addView(subtitle(desc)); TextView go=text(action+"  ›",12,ACCENT,true); go.setGravity(Gravity.RIGHT); go.setPadding(0,dp(10),dp(4),0); c.addView(go); c.setOnClickListener(click); return c;}
    private LinearLayout cardBase(){LinearLayout c=new LinearLayout(context); c.setOrientation(VERTICAL); c.setPadding(dp(13),dp(12),dp(13),dp(12)); c.setBackground(panel(Color.rgb(31,34,38),18,STROKE,1)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,dp(6)); c.setLayoutParams(p); return c;}
    private View box(View child){LinearLayout c=cardBase(); c.addView(child); return c;}
    private LinearLayout page(){ScrollView sc=new ScrollView(context); sc.setFillViewport(true); LinearLayout body=new LinearLayout(context); body.setOrientation(VERTICAL); body.setPadding(dp(4),dp(4),dp(4),dp(8)); sc.addView(body); sc.setTag(body); return new PageContainer(sc,body);}
    private final class PageContainer extends LinearLayout { PageContainer(View sc,LinearLayout body){super(context);setOrientation(VERTICAL);addView(sc,new LayoutParams(-1,-1));this.body=body;} final LinearLayout body; @Override public void addView(View v){body.addView(v);} }
    private TextView title(String s){TextView t=text(s,19,Color.WHITE,true); t.setPadding(dp(4),dp(8),0,dp(2)); return t;}
    private TextView subtitle(String s){TextView t=text(s,11,Color.LTGRAY,false);t.setPadding(dp(4),dp(3),0,dp(8));return t;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(context);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private void addDivider(LinearLayout p){View v=new View(context);v.setBackgroundColor(STROKE);p.addView(v,new LayoutParams(-1,dp(1)));}
    private GradientDrawable panel(int color,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
