package com.amretro.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Named keyboard profiles and individual profiles for connected controllers. */
final class ControlSettings {
    private static final String PREFS="input-profiles-v2",LEGACY="input-profile";
    static InputProfileStore store(Context c){return new InputProfileStore(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE),c.getSharedPreferences(LEGACY,Context.MODE_PRIVATE));}
    static InputProfile load(Context c){return store(c).activeKeyboard();}
    static boolean isController(InputDevice d){
        if(d==null)return false;int s=d.getSources();
        return (s&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD || (s&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK;
    }
    static boolean isController(KeyEvent event){
        if(event==null)return false;InputDevice d=event.getDevice();int s=event.getSource(),code=event.getKeyCode();
        boolean dedicated=(code>=KeyEvent.KEYCODE_BUTTON_A&&code<=KeyEvent.KEYCODE_BUTTON_MODE)||
            (code>=KeyEvent.KEYCODE_BUTTON_1&&code<=KeyEvent.KEYCODE_BUTTON_16);
        boolean dpad=code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_CENTER;
        boolean source=(s&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||
            (s&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK;
        if(!source&&!(dedicated&&isController(d)))return false;
        // Some composite devices expose GAMEPAD and an alphabetic keyboard on the
        // same input node; letter/numpad keys should still use the keyboard profile.
        return dedicated||dpad||d==null||d.getKeyboardType()!=InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }
    static String descriptor(InputDevice d){
        if(d==null)return "";String value=d.getDescriptor();
        if(value!=null&&!value.isEmpty())return value;
        // Fallback can group indistinguishable devices; never use transient id for saved settings.
        return "fallback:"+d.getVendorId()+":"+d.getProductId()+":"+d.getName();
    }
    static InputProfile forController(Context c,InputDevice d){return store(c).forController(descriptor(d));}
    private static String keyName(int code){return KeyEvent.keyCodeToString(code).replace("KEYCODE_","");}
    private static void toast(Activity a,String message){Toast.makeText(a,message,Toast.LENGTH_LONG).show();}
    private static void failed(Activity a,Exception e){toast(a,e.getMessage()==null?"عملیات ممکن نشد.":e.getMessage());}

    static void show(Activity a){
        String[] choices={"پروفایل‌های کیبورد", "دسته‌های متصل و تنظیم دکمه‌ها"};
        new AlertDialog.Builder(a).setTitle("کنترلرها و کیبورد")
            .setItems(choices,(d,w)->{if(w==0)keyboards(a);else controllers(a);})
            .setNegativeButton("بستن",(d,w)->{}).show();
    }
    private static void keyboards(Activity a){
        try{
            List<InputProfileStore.Keyboard> list=store(a).keyboards();String[] rows=new String[list.size()];
            for(int i=0;i<rows.length;i++)rows[i]=(list.get(i).active?"✓  ":"○  ")+list.get(i).name;
            new AlertDialog.Builder(a).setTitle("پروفایل‌های کیبورد")
                .setItems(rows,(d,w)->keyboardMenu(a,list.get(w).id))
                .setPositiveButton("افزودن پروفایل",(d,w)->newKeyboard(a))
                .setNegativeButton("بازگشت",(d,w)->show(a)).show();
        }catch(Exception e){failed(a,e);}
    }
    private static String keyboardName(Activity a,String id){
        for(InputProfileStore.Keyboard k:store(a).keyboards())if(k.id.equals(id))return k.name;
        return "کیبورد";
    }
    private static void keyboardMenu(Activity a,String id){
        String[] choices={"فعال‌کردن این پروفایل", "ویرایش کلیدها", "تغییر نام", "بازنشانی کلیدها به WASD", "حذف پروفایل"};
        new AlertDialog.Builder(a).setTitle(keyboardName(a,id)).setItems(choices,(d,w)->{
            try{
                if(w==0){store(a).activateKeyboard(id);toast(a,"پروفایل کیبورد فعال شد؛ در اجرای بعدی اعمال می‌شود.");keyboards(a);}
                else if(w==1)editKeyboard(a,id);
                else if(w==2)renameKeyboard(a,id);
                else if(w==3)new AlertDialog.Builder(a).setMessage("کلیدهای این پروفایل به WASD پیش‌فرض برگردند؟")
                    .setPositiveButton("بازنشانی",(x,y)->{try{store(a).resetKeyboard(id);editKeyboard(a,id);}catch(Exception e){failed(a,e);}})
                    .setNegativeButton("انصراف",(x,y)->keyboardMenu(a,id)).show();
                else new AlertDialog.Builder(a).setMessage("این پروفایل حذف شود؟")
                    .setPositiveButton("حذف",(x,y)->{try{store(a).deleteKeyboard(id);keyboards(a);}catch(Exception e){failed(a,e);keyboards(a);}})
                    .setNegativeButton("انصراف",(x,y)->keyboardMenu(a,id)).show();
            }catch(Exception e){failed(a,e);keyboards(a);}
        }).setNegativeButton("بازگشت",(d,w)->keyboards(a)).show();
    }
    private static void newKeyboard(Activity a){
        String[] types={"کپی از پروفایل فعال", "پروفایل آمادهٔ نامپد: ۸، ۲، ۴، ۶"};
        new AlertDialog.Builder(a).setTitle("نوع پروفایل تازه").setItems(types,(d,w)->promptName(a,null,w==1))
            .setNegativeButton("انصراف",(d,w)->keyboards(a)).show();
    }
    private static void renameKeyboard(Activity a,String id){promptName(a,id,false);}
    private static void promptName(Activity a,String id,boolean numpad){
        EditText input=new EditText(a);input.setSingleLine(true);input.setText(id==null?"کیبورد "+(store(a).keyboards().size()+1):keyboardName(a,id));
        new AlertDialog.Builder(a).setTitle(id==null?"نام پروفایل تازه":"تغییر نام پروفایل").setView(input)
            .setPositiveButton("ذخیره",(d,w)->{
                try{if(id==null){String made=store(a).addKeyboard(input.getText().toString(),numpad);toast(a,"پروفایل تازه فعال شد.");editKeyboard(a,made);}
                    else{store(a).renameKeyboard(id,input.getText().toString());keyboards(a);}}
                catch(Exception e){failed(a,e);keyboards(a);}
            }).setNegativeButton("انصراف",(d,w)->keyboards(a)).show();
    }
    private static void editKeyboard(Activity a,String id){
        try{
            int[] keys=store(a).keyboard(id);String[] rows=new String[keys.length];
            for(int i=0;i<rows.length;i++)rows[i]=InputProfile.LABELS[i]+"   ·   "+keyName(keys[i]);
            new AlertDialog.Builder(a).setTitle("کلیدها · "+keyboardName(a,id))
                .setItems(rows,(d,w)->captureKeyboard(a,id,w)).setNegativeButton("بازگشت",(d,w)->keyboardMenu(a,id)).show();
        }catch(Exception e){failed(a,e);keyboards(a);}
    }
    private static void captureKeyboard(Activity a,String id,int action){
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("کلید کیبورد: "+InputProfile.LABELS[action])
            .setMessage("کلید دلخواه کیبورد، مانند NUMPAD_8 یا W را فشار بده.")
            .setNegativeButton("انصراف",(d,w)->editKeyboard(a,id)).create();
        dialog.setOnKeyListener((d,code,event)->{
            if(code==KeyEvent.KEYCODE_BACK)return false;
            if(event.getAction()!=KeyEvent.ACTION_DOWN||event.getRepeatCount()!=0)return true;
            if(isController(event)){toast(a,"برای این پروفایل یک کلید کیبورد بزن.");return true;}
            try{store(a).bindKeyboard(id,action,code);dialog.dismiss();editKeyboard(a,id);}catch(Exception e){failed(a,e);}return true;
        });dialog.show();
    }
    private static final class Device {final String name,descriptor;Device(String n,String d){name=n;descriptor=d;}}
    private static List<Device> connected(){
        List<Device> out=new ArrayList<>();
        for(int id:InputDevice.getDeviceIds()){
            InputDevice d=InputDevice.getDevice(id);if(!isController(d))continue;
            String info=String.format(Locale.US,"%s · %04x:%04x",d.getName(),d.getVendorId(),d.getProductId());
            out.add(new Device(info,descriptor(d)));
        }
        return out;
    }
    private static void openBluetooth(Activity a){
        try{a.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}catch(Exception e){toast(a,"تنظیمات بلوتوث در دسترس نیست؛ از تنظیمات اندروید استفاده کن.");}
    }
    private static void controllers(Activity a){
        List<Device> devices=connected();String[] rows=new String[devices.size()];for(int i=0;i<rows.length;i++)rows[i]=devices.get(i).name;
        AlertDialog.Builder b=new AlertDialog.Builder(a).setTitle("دسته‌های شناسایی‌شده · بازیکن ۱")
            .setPositiveButton("تازه‌سازی",(d,w)->controllers(a)).setNeutralButton("تنظیمات بلوتوث",(d,w)->openBluetooth(a))
            .setNegativeButton("بازگشت",(d,w)->show(a));
        if(rows.length>0)b.setItems(rows,(d,w)->editController(a,devices.get(w)));
        else b.setMessage("هنوز دسته‌ای شناسایی نشده است. آن را با بلوتوث یا USB به اندروید وصل کن و تازه‌سازی را بزن.");
        b.show();
    }
    private static void editController(Activity a,Device device){
        try{
            int[] keys=store(a).controller(device.descriptor);String[] rows=new String[keys.length];
            for(int i=0;i<rows.length;i++)rows[i]=InputProfile.LABELS[i]+"   ·   "+keyName(keys[i]);
            new AlertDialog.Builder(a).setTitle(device.name)
                .setItems(rows,(d,w)->captureController(a,device,w))
                .setNeutralButton("بازنشانی",(d,w)->new AlertDialog.Builder(a).setMessage("نگاشت این دسته به حالت پیش‌فرض برگردد؟")
                    .setPositiveButton("بازنشانی",(x,y)->{try{store(a).resetController(device.descriptor);editController(a,device);}catch(Exception e){failed(a,e);}})
                    .setNegativeButton("انصراف",(x,y)->editController(a,device)).show())
                .setNegativeButton("بازگشت",(d,w)->controllers(a)).show();
        }catch(Exception e){failed(a,e);controllers(a);}
    }
    private static void captureController(Activity a,Device device,int action){
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("دکمهٔ دسته: "+InputProfile.LABELS[action])
            .setMessage("دکمه‌ای روی «"+device.name+"» فشار بده. برای استیک آنالوگ از نگاشت جهت پیش‌فرض استفاده می‌شود.")
            .setNegativeButton("انصراف",(d,w)->editController(a,device)).create();
        dialog.setOnKeyListener((d,code,event)->{
            if(code==KeyEvent.KEYCODE_BACK)return false;
            if(event.getAction()!=KeyEvent.ACTION_DOWN||event.getRepeatCount()!=0)return true;
            InputDevice source=event.getDevice();
            if(!isController(event)||!device.descriptor.equals(descriptor(source))){toast(a,"دکمه را روی همین دسته فشار بده.");return true;}
            try{store(a).bindController(device.descriptor,action,code);dialog.dismiss();editController(a,device);}catch(Exception e){failed(a,e);}return true;
        });dialog.show();
    }
    private ControlSettings(){}
}
