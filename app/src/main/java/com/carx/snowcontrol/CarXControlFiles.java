package com.carx.snowcontrol;
import android.content.Context;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
public final class CarXControlFiles { private static Process p; private static DataOutputStream in; private CarXControlFiles(){} public static synchronized void write(Context c,String name,String value){ if(!name.matches("carx_snow_(weather|time_minutes|wet_ground|enabled|multiplier)")) return; try { if(in==null){p=Runtime.getRuntime().exec(new String[]{"su","-c","sh"}); in=new DataOutputStream(p.getOutputStream());} in.write(("printf '%s\\n' '"+value.replace("'","")+"' > /data/local/tmp/"+name+"\n").getBytes(StandardCharsets.UTF_8)); in.flush(); } catch(Exception e){in=null;p=null; Toast.makeText(c,"Root 授权失败，请检查 SukiSU",Toast.LENGTH_SHORT).show();} } }
