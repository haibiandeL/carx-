package com.carx.snowcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** CarX floating controller using a native PigHax-inspired UI. */
public final class FloatingService extends Service {
    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams params;
    private DataOutputStream rootIn;
    private Process rootProcess;
    private boolean rootTried=false;

    @Override public void onCreate(){super.onCreate();startForegroundNotification();wm=(WindowManager)getSystemService(WINDOW_SERVICE);buildOverlay();}
    private void startForegroundNotification(){String channel="snow_slider";if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(channel,"CarX floating menu",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);b.setContentTitle("CarX 控制中心").setContentText("原生悬浮菜单运行中").setSmallIcon(android.R.drawable.ic_menu_edit);startForeground(7822,b.build());}

    private void buildOverlay(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(3),dp(6),dp(3));bar.setBackground(round(Color.rgb(24,29,34),dp(18)));
        TextView drag=new TextView(this);drag.setText("❄  CarX  ·  PigHax UI");drag.setTextColor(Color.WHITE);drag.setTextSize(13);drag.setPadding(dp(8),dp(8),dp(8),dp(8));bar.addView(drag,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView collapse=new TextView(this);collapse.setText("−");collapse.setTextColor(Color.rgb(79,195,247));collapse.setTextSize(23);collapse.setGravity(Gravity.CENTER);bar.addView(collapse,new LinearLayout.LayoutParams(dp(42),dp(42)));
        shell.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        CarXNativeMenu menu=new CarXNativeMenu(this,new CarXNativeMenu.Actions(){
            public void setMultiplier(float v){writeNumber("/data/local/tmp/carx_snow_multiplier",String.format(Locale.US,"%.2f",v));}
            public void setSnowEnabled(boolean on){writeNumber("/data/local/tmp/carx_snow_enabled",on?"1":"0");}
            public void setWeather(int preset){if(preset>=0&&preset<=2)writeNumber("/data/local/tmp/carx_snow_weather",String.valueOf(preset));}
            public void setGameTime(int minutes){if(minutes>=0&&minutes<=1439)writeNumber("/data/local/tmp/carx_snow_time_minutes",String.valueOf(minutes));}
            public void setWetGround(boolean on){writeNumber("/data/local/tmp/carx_snow_wet_ground",on?"1":"0");}
            public void close(){stopSelf();}
        });
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,0,1);mp.topMargin=dp(7);shell.addView(menu,mp);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        int width=(int)(getResources().getDisplayMetrics().widthPixels*.92f);int height=(int)(getResources().getDisplayMetrics().heightPixels*.68f);
        params=new WindowManager.LayoutParams(width,height,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);params.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;params.y=dp(70);
        overlay=shell;wm.addView(overlay,params);
        final boolean[] collapsed={false};collapse.setOnClickListener(v->{collapsed[0]=!collapsed[0];menu.setVisibility(collapsed[0]?View.GONE:View.VISIBLE);collapse.setText(collapsed[0]?"❄":"−");params.height=collapsed[0]?dp(52):(int)(getResources().getDisplayMetrics().heightPixels*.68f);try{wm.updateViewLayout(overlay,params);}catch(Exception ignored){}});
        drag.setOnTouchListener(new View.OnTouchListener(){int sx,sy,wx,wy;public boolean onTouch(View v,MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){sx=(int)e.getRawX();sy=(int)e.getRawY();wx=params.x;wy=params.y;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){params.x=wx+(int)e.getRawX()-sx;params.y=wy+(int)e.getRawY()-sy;try{wm.updateViewLayout(overlay,params);}catch(Exception ignored){}return true;}return e.getAction()==MotionEvent.ACTION_UP;}});
    }
    private android.graphics.drawable.GradientDrawable round(int color,float radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private synchronized boolean ensureRootShell(){if(rootIn!=null)return true;if(rootTried&&rootProcess==null)return false;rootTried=true;try{rootProcess=Runtime.getRuntime().exec(new String[]{"su","-c","sh"});rootIn=new DataOutputStream(rootProcess.getOutputStream());return true;}catch(IOException e){rootProcess=null;rootIn=null;return false;}}
    private synchronized void writeNumber(String path,String value){if(!ensureRootShell()){Toast.makeText(this,"未能取得 root shell；请检查 SukiSU 授权",Toast.LENGTH_SHORT).show();return;}try{rootIn.write(("printf '%s\\n' '"+value+"' > "+path+"\n").getBytes(StandardCharsets.UTF_8));rootIn.flush();}catch(IOException e){rootIn=null;rootProcess=null;rootTried=false;}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public void onDestroy(){if(overlay!=null){try{wm.removeView(overlay);}catch(Exception ignored){}overlay=null;}if(rootIn!=null){try{rootIn.write("exit\n".getBytes(StandardCharsets.UTF_8));rootIn.flush();rootIn.close();}catch(Exception ignored){}}if(rootProcess!=null)rootProcess.destroy();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
