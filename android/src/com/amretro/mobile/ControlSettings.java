package com.amretro.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.widget.Toast;

/** Persistent global hardware-key bindings; virtual touch controls stay separate. */
final class ControlSettings {
    private static final String PREFS="input-profile";
    static InputProfile load(Context context) {
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        int[] keys=InputProfile.DEFAULT_KEYS.clone();
        for(int i=0;i<keys.length;i++)keys[i]=prefs.getInt(InputProfile.IDS[i],keys[i]);
        return new InputProfile(keys);
    }
    private static String keyName(int code){return KeyEvent.keyCodeToString(code).replace("KEYCODE_","");}
    static void show(Activity activity) {
        InputProfile profile=load(activity);
        String[] rows=new String[InputProfile.IDS.length];
        for(int i=0;i<rows.length;i++)rows[i]=InputProfile.LABELS[i]+"   ·   "+keyName(profile.configuredKey(i));
        new AlertDialog.Builder(activity).setTitle("تنظیم یکپارچهٔ کلیدها")
            .setItems(rows,(d,index)->capture(activity,index))
            .setNeutralButton("بازنشانی",(d,w)->new AlertDialog.Builder(activity)
                .setMessage("همهٔ کلیدهای دلخواه به حالت پیش‌فرض برگردند؟")
                .setPositiveButton("بازنشانی",(x,y)->{activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();show(activity);})
                .setNegativeButton("انصراف",(x,y)->show(activity)).show())
            .setNegativeButton("بستن",(d,w)->{}).show();
    }
    private static void capture(Activity activity,int action) {
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("کلید: "+InputProfile.LABELS[action])
            .setMessage("یک کلید صفحه‌کلید یا دکمهٔ دسته را فشار بده. برای انصراف از دکمهٔ بازگشت استفاده کن.")
            .setNegativeButton("انصراف",(d,w)->show(activity)).create();
        dialog.setOnKeyListener((d,code,event)->{
            if(code==KeyEvent.KEYCODE_BACK)return false;
            if(event.getAction()!=KeyEvent.ACTION_DOWN || event.getRepeatCount()!=0)return true;
            if(!InputProfile.canBind(code)){Toast.makeText(activity,"این کلید قابل استفاده نیست.",Toast.LENGTH_SHORT).show();return true;}
            InputProfile current=load(activity);
            for(int i=0;i<InputProfile.IDS.length;i++)if(i!=action && current.configuredKey(i)==code){
                Toast.makeText(activity,"این کلید برای «"+InputProfile.LABELS[i]+"» استفاده شده است.",Toast.LENGTH_LONG).show();return true;
            }
            activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(InputProfile.IDS[action],code).apply();
            dialog.dismiss();show(activity);return true;
        });
        dialog.show();
    }
    private ControlSettings(){}
}
