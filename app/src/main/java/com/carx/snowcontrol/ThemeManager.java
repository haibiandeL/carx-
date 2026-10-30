package com.carx.snowcontrol;

import android.graphics.Color;

/** 主题配色管理 */
public class ThemeManager {

    // bg, sideBg, accent, stroke, card, glow
    public static final String[][] PALETTES = {
            {"#1A1A1A", "#222222", "#9C5F54", "#2A2A2A", "#151515", "#3E3E3E"}, // Obsidian
            {"#0F2027", "#16222A", "#4FC3F7", "#1C313A", "#0B151B", "#2C5A6E"}, // Ocean
            {"#2B1B2D", "#3D2438", "#FF9A62", "#4A2C45", "#211523", "#6E4A5E"}, // Sunset
            {"#15251D", "#1E332A", "#66BB6A", "#2A4A3A", "#101C17", "#3E6E52"}  // Forest
    };

    public static int bg(int i)      { return Color.parseColor(PALETTES[i][0]); }
    public static int side(int i)    { return Color.parseColor(PALETTES[i][1]); }
    public static int accent(int i)  { return Color.parseColor(PALETTES[i][2]); }
    public static int stroke(int i)  { return Color.parseColor(PALETTES[i][3]); }
    public static int card(int i)    { return Color.parseColor(PALETTES[i][4]); }
    public static int glow(int i)    { return Color.parseColor(PALETTES[i][5]); }

    /** 亮度判断，决定文字颜色 */
    public static boolean isDarkTheme(int i) {
        return i != 2; // Sunset 偏亮，其余全暗
    }
}
