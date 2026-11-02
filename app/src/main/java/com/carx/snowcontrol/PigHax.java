package com.carx.snowcontrol;

import android.content.Context;

/** 全局单例：应用内小猪与全局悬浮小猪共享同一套状态、资源与菜单 */
public class PigHax {
    public static FloatingData data = null;
    public static FloatingAssets assets = null;
    public static FloatingHUD hud = null;
    public static FloatingMenu menu = null;

    public static void ensure(Context context) {
        if (data == null) data = new FloatingData();
        if (assets == null) assets = new FloatingAssets();
        if (hud == null) hud = new FloatingHUD(data);
        if (menu == null) menu = new FloatingMenu(data, assets, hud);
        if (assets != null && context != null && !assets.isAssetsLoaded) {
            assets.preloadAssets(context);
        }
    }
}