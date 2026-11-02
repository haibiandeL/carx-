package com.carx.snowcontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** GameGuardian 联动 + 已安装应用列表 */
public class GGLauncher {

    public static final String GG_PACKAGE = "com.kar.vv";

    public static boolean isGGInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(GG_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 唤起 GG 并尝试附加目标游戏（GG 需开启外部启动支持） */
    public static void launchGG(Context context, String targetPackage) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(GG_PACKAGE, "com.kar.vv.ui.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (targetPackage != null && !targetPackage.isEmpty()) {
                intent.putExtra("package", targetPackage);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(GG_PACKAGE);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(fallback);
                }
            } catch (Exception e2) { /* 无法唤起 */ }
        }
    }

    /** 直接启动一个应用 */
    public static void launchApp(Context context, String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) { /* ignore */ }
    }

    /** 获取所有可启动的应用（带 label） */
    public static List<AppInfo> getLaunchableApps(Context context) {
        List<AppInfo> result = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
            for (ResolveInfo info : infos) {
                try {
                    if (info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg.equals(context.getPackageName())) continue;
                    CharSequence label = info.loadLabel(pm);
                    result.add(new AppInfo(pkg, label != null ? label.toString() : pkg));
                } catch (Exception e) { /* skip */ }
            }
            Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception e) { /* ignore */ }
        return result;
    }

    public static class AppInfo {
        public final String packageName;
        public final String label;
        public AppInfo(String pkg, String label) { this.packageName = pkg; this.label = label; }
    }
}