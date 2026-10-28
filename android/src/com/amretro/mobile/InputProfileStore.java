package com.amretro.mobile;

import android.content.SharedPreferences;
import android.view.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Local keyboard presets and per-controller button assignments. */
final class InputProfileStore {
    static final int MAX_PROFILES=32;
    static final class Keyboard {
        final String id,name;final boolean active;
        Keyboard(String id,String name,boolean active){this.id=id;this.name=name;this.active=active;}
    }
    private final SharedPreferences prefs;
    InputProfileStore(SharedPreferences prefs,SharedPreferences legacy){
        this.prefs=prefs;
        if(!prefs.contains("keyboard_ids")){
            // The old profile mixed keyboard and gamepad codes. Preserve keyboard
            // assignments and use recognizable pad codes as a baseline for all devices.
            SharedPreferences.Editor e=prefs.edit().putString("keyboard_ids","default").putString("keyboard_active","default")
                .putString("kb_default_name","کیبورد ۱");
            int[] old=new int[InputProfile.IDS.length],pad=InputProfile.DEFAULT_PAD.clone();
            for(int i=0;i<old.length;i++)old[i]=legacy.getInt(InputProfile.IDS[i],InputProfile.DEFAULT_KEYS[i]);
            for(int i=0;i<old.length;i++)if(isPadCode(old[i])){
                int previous=pad[i];for(int j=0;j<pad.length;j++)if(j!=i&&pad[j]==old[i])pad[j]=previous;pad[i]=old[i];
            }
            for(int i=0;i<old.length;i++){
                int value=old[i];
                if(isDedicatedPadCode(value)){
                    value=InputProfile.DEFAULT_KEYS[i];
                    for(int j=0;j<old.length;j++)if(j!=i&&!isDedicatedPadCode(old[j])&&old[j]==value){value=0;break;}
                }
                e.putInt(key("default",i),value).putInt("pad_base_"+InputProfile.IDS[i],pad[i]);
            }
            if(!e.commit())throw new IllegalStateException("ذخیرهٔ پروفایل اولیه ممکن نشد.");
        }
    }
    private static boolean isDedicatedPadCode(int code){
        return (code>=KeyEvent.KEYCODE_BUTTON_A&&code<=KeyEvent.KEYCODE_BUTTON_MODE)||
               (code>=KeyEvent.KEYCODE_BUTTON_1&&code<=KeyEvent.KEYCODE_BUTTON_16);
    }
    private static boolean isPadCode(int code){return isDedicatedPadCode(code)||
        (code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_CENTER);}
    private static String key(String id,int action){return "kb_"+id+"_"+InputProfile.IDS[action];}
    private List<String> ids(){
        List<String> out=new ArrayList<>();String encoded=prefs.getString("keyboard_ids","");
        for(String id:encoded.split(","))if(id.matches("default|[0-9a-f-]{36}")&&!out.contains(id))out.add(id);
        if(out.isEmpty())throw new IllegalStateException("فهرست پروفایل‌های کیبورد نامعتبر است.");return out;
    }
    private static String joined(List<String> ids){StringBuilder b=new StringBuilder();for(String id:ids){if(b.length()>0)b.append(',');b.append(id);}return b.toString();}
    private void require(String id){if(!ids().contains(id))throw new IllegalArgumentException("پروفایل پیدا نشد.");}
    private static void nameValid(String name){
        if(name==null||name.trim().isEmpty()||name.trim().length()>40)throw new IllegalArgumentException("نام باید بین ۱ تا ۴۰ نویسه باشد.");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IllegalArgumentException("نام پروفایل معتبر نیست.");
    }
    private static void commit(SharedPreferences.Editor e){if(!e.commit())throw new IllegalStateException("ذخیرهٔ پروفایل ممکن نشد.");}
    String activeId(){List<String> all=ids();String id=prefs.getString("keyboard_active",all.get(0));return all.contains(id)?id:all.get(0);}
    List<Keyboard> keyboards(){
        String active=activeId();List<Keyboard> out=new ArrayList<>();
        for(String id:ids())out.add(new Keyboard(id,prefs.getString("kb_"+id+"_name","کیبورد"),id.equals(active)));
        return out;
    }
    int[] keyboard(String id){
        require(id);int[] out=InputProfile.DEFAULT_KEYS.clone();for(int i=0;i<out.length;i++)out[i]=prefs.getInt(key(id,i),out[i]);return out;
    }
    InputProfile activeKeyboard(){return new InputProfile(keyboard(activeId()));}
    String addKeyboard(String name,boolean numpad){
        nameValid(name);List<String> all=ids();if(all.size()>=MAX_PROFILES)throw new IllegalArgumentException("حداکثر ۳۲ پروفایل کیبورد مجاز است.");
        for(Keyboard k:keyboards())if(k.name.equalsIgnoreCase(name.trim()))throw new IllegalArgumentException("این نام از قبل وجود دارد.");
        String id=UUID.randomUUID().toString();all.add(id);int[] keys=numpad?InputProfile.NUMPAD_KEYS:keyboard(activeId());
        SharedPreferences.Editor e=prefs.edit().putString("keyboard_ids",joined(all)).putString("keyboard_active",id).putString("kb_"+id+"_name",name.trim());
        for(int i=0;i<keys.length;i++)e.putInt(key(id,i),keys[i]);commit(e);return id;
    }
    void activateKeyboard(String id){require(id);commit(prefs.edit().putString("keyboard_active",id));}
    void renameKeyboard(String id,String name){
        require(id);nameValid(name);for(Keyboard k:keyboards())if(!k.id.equals(id)&&k.name.equalsIgnoreCase(name.trim()))throw new IllegalArgumentException("این نام از قبل وجود دارد.");
        commit(prefs.edit().putString("kb_"+id+"_name",name.trim()));
    }
    void deleteKeyboard(String id){
        require(id);List<String> all=ids();if(all.size()==1)throw new IllegalArgumentException("حداقل یک پروفایل باید باقی بماند.");
        all.remove(id);SharedPreferences.Editor e=prefs.edit().putString("keyboard_ids",joined(all)).remove("kb_"+id+"_name");
        for(int i=0;i<InputProfile.IDS.length;i++)e.remove(key(id,i));if(id.equals(activeId()))e.putString("keyboard_active",all.get(0));commit(e);
    }
    private static void validBinding(int action,int code){
        if(action<0||action>=InputProfile.IDS.length||!InputProfile.canBind(code))throw new IllegalArgumentException("این کلید قابل استفاده نیست.");
    }
    void bindKeyboard(String id,int action,int code){
        int[] all=keyboard(id);validBinding(action,code);SharedPreferences.Editor e=prefs.edit();
        // Swap rather than leaving two logical actions on the same physical key.
        for(int i=0;i<all.length;i++)if(i!=action&&all[i]==code)e.putInt(key(id,i),all[action]);
        commit(e.putInt(key(id,action),code));
    }
    void resetKeyboard(String id){require(id);SharedPreferences.Editor e=prefs.edit();for(int i=0;i<InputProfile.IDS.length;i++)e.putInt(key(id,i),InputProfile.DEFAULT_KEYS[i]);commit(e);}
    private static String deviceKey(String descriptor){
        if(descriptor==null||descriptor.isEmpty())throw new IllegalArgumentException("شناسهٔ پایدار دسته در دسترس نیست.");
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:digest)b.append(String.format(Locale.US,"%02x",x&255));return b.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String padKey(String device,int action){return "pad_"+device+"_"+InputProfile.IDS[action];}
    int[] controller(String descriptor){
        String id=deviceKey(descriptor);int[] out=InputProfile.DEFAULT_PAD.clone();for(int i=0;i<out.length;i++){
            int baseline=prefs.getInt("pad_base_"+InputProfile.IDS[i],out[i]);out[i]=prefs.getInt(padKey(id,i),baseline);
        }return out;
    }
    InputProfile forController(String descriptor){return new InputProfile(keyboard(activeId()),controller(descriptor));}
    void bindController(String descriptor,int action,int code){
        int[] all=controller(descriptor);validBinding(action,code);String id=deviceKey(descriptor);SharedPreferences.Editor e=prefs.edit();
        for(int i=0;i<all.length;i++)if(i!=action&&all[i]==code)e.putInt(padKey(id,i),all[action]);
        commit(e.putInt(padKey(id,action),code));
    }
    void resetController(String descriptor){String id=deviceKey(descriptor);SharedPreferences.Editor e=prefs.edit();for(int i=0;i<InputProfile.IDS.length;i++)e.putInt(padKey(id,i),InputProfile.DEFAULT_PAD[i]);commit(e);}
}
