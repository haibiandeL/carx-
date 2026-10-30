package com.carx.snowcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Build;
import java.io.InputStream;

public class FloatingAssets {
    public boolean isAssetsLoaded = false;
    public Drawable cachedPigDrawable = null;
    public Drawable cachedRunPigDrawable = null;
    public Bitmap cachedBeonBitmap = null;
    public Bitmap[] cachedMenuIcons = new Bitmap[5];
    public String[] iconNames = {"texture.png", "user.png", "modules.png", "sky.png", "fps.png"};

    public void preloadAssets(Context context) {
        if (isAssetsLoaded) return;
        for (int i = 0; i < 5; i++) {
            cachedMenuIcons[i] = getBitmapFromAsset(context, iconNames[i]);
        }
        cachedBeonBitmap = getBitmapFromAsset(context, "beon.png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.graphics.ImageDecoder.Source pigSource = android.graphics.ImageDecoder.createSource(context.getAssets(), "pig.gif");
                cachedPigDrawable = android.graphics.ImageDecoder.decodeDrawable(pigSource);
                android.graphics.ImageDecoder.Source runPigSource = android.graphics.ImageDecoder.createSource(context.getAssets(), "run_pig.gif");
                cachedRunPigDrawable = android.graphics.ImageDecoder.decodeDrawable(runPigSource);
            } catch (Exception e) { e.printStackTrace(); }
        }
        isAssetsLoaded = true;
    }

    public Bitmap getBitmapFromAsset(Context context, String fileName) {
        try {
            InputStream is = context.getAssets().open(fileName);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            return bitmap;
        } catch (Exception e) { return null; }
    }
}
