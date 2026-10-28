package com.amretro.mobile;

import android.content.SharedPreferences;
import android.view.KeyEvent;
import java.util.*;

/** JVM test of persistence, migration, independent profiles and device bindings. */
public class InputProfileStoreTest {
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static final class MemoryPrefs implements SharedPreferences {
        final Map<String,Object> map=new HashMap<>();
        public Map<String,?> getAll(){return new HashMap<>(map);}
        public String getString(String key,String def){return map.containsKey(key)?(String)map.get(key):def;}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> def){return map.containsKey(key)?(Set<String>)map.get(key):def;}
        public int getInt(String key,int def){return map.containsKey(key)?(Integer)map.get(key):def;}
        public long getLong(String key,long def){return map.containsKey(key)?(Long)map.get(key):def;}
        public float getFloat(String key,float def){return map.containsKey(key)?(Float)map.get(key):def;}
        public boolean getBoolean(String key,boolean def){return map.containsKey(key)?(Boolean)map.get(key):def;}
        public boolean contains(String key){return map.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        public Editor edit(){return new MemoryEditor();}
        final class MemoryEditor implements Editor {
            final Map<String,Object> changes=new HashMap<>();boolean clear;
            public Editor putString(String k,String v){changes.put(k,v);return this;}
            public Editor putStringSet(String k,Set<String> v){changes.put(k,v);return this;}
            public Editor putInt(String k,int v){changes.put(k,v);return this;}
            public Editor putLong(String k,long v){changes.put(k,v);return this;}
            public Editor putFloat(String k,float v){changes.put(k,v);return this;}
            public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
            public Editor remove(String k){changes.put(k,null);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){if(clear)map.clear();for(Map.Entry<String,Object> e:changes.entrySet())if(e.getValue()==null)map.remove(e.getKey());else map.put(e.getKey(),e.getValue());return true;}
            public void apply(){commit();}
        }
    }
    public static void main(String[] args){
        MemoryPrefs data=new MemoryPrefs(),legacy=new MemoryPrefs();legacy.edit().putInt("up",KeyEvent.KEYCODE_F).commit();
        InputProfileStore store=new InputProfileStore(data,legacy);
        check(store.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_F)==1<<4,"legacy up migrated");
        check(store.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_W)==0,"old key does not remain bound");
        String one=store.activeId(),two=store.addKeyboard("کیبورد ۲",true);
        check(!one.equals(two)&&store.activeId().equals(two),"new profile activated");
        check(store.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_NUMPAD_8)==1<<4,"numpad 8 is up");
        check(store.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_NUMPAD_2)==1<<5,"numpad 2 is down");
        check(store.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_F)==0,"keyboard profiles independent");
        store.activateKeyboard(one);check(store.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_F)==1<<4,"switch restores profile one");
        store.bindKeyboard(two,0,KeyEvent.KEYCODE_NUMPAD_2);
        check(store.keyboard(two)[0]==KeyEvent.KEYCODE_NUMPAD_2&&store.keyboard(two)[1]==KeyEvent.KEYCODE_NUMPAD_8,"duplicate key swaps atomically");
        InputProfile a=store.forController("controller-A"),b=store.forController("controller-B");
        check(a.controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<8,"default pad A");
        store.bindController("controller-A",0,KeyEvent.KEYCODE_BUTTON_A);
        a=store.forController("controller-A");b=store.forController("controller-B");
        check(a.controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<4,"pad A separately remapped");
        check(b.controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<8,"pad B not changed");
        check(a.axesMask(-1f,0f,0f,0f)==1<<6,"left stick uses left binding");
        check(a.axesMask(0f,-1f,0f,0f)==1<<8,"up axis honors swapped D-pad mapping");
        check(b.axesMask(.1f,-.2f,0f,0f)==0,"dead zone");
        InputProfile.PressedKeys pressed=new InputProfile.PressedKeys(store.activeKeyboard());
        pressed.down(11,KeyEvent.KEYCODE_BUTTON_A,a.controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A));
        pressed.down(12,KeyEvent.KEYCODE_BUTTON_A,b.controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A));
        check(pressed.mask()==((1<<4)|(1<<8)),"both controllers held independently");
        pressed.up(11,KeyEvent.KEYCODE_BUTTON_A);check(pressed.mask()==1<<8,"release one keeps other pressed");
        pressed.clearDevice(12);check(pressed.mask()==0,"disconnect clears only that device");
        store.renameKeyboard(two,"نامپد");store.activateKeyboard(two);
        InputProfileStore restarted=new InputProfileStore(data,legacy);
        check(restarted.activeId().equals(two)&&restarted.keyboards().size()==2,"profiles persisted across repository reconstruction");
        check(restarted.controller("controller-A")[0]==KeyEvent.KEYCODE_BUTTON_A,"controller mapping persisted");
        restarted.deleteKeyboard(two);check(restarted.activeId().equals(one),"deleting active profile selects remaining");
        try{restarted.deleteKeyboard(one);throw new AssertionError("last profile deleted");}catch(IllegalArgumentException expected){}
        try{restarted.addKeyboard("\n",false);throw new AssertionError("invalid name accepted");}catch(IllegalArgumentException expected){}
        MemoryPrefs mixed=new MemoryPrefs(),old=new MemoryPrefs();old.edit().putInt("up",KeyEvent.KEYCODE_BUTTON_A).putInt("right",KeyEvent.KEYCODE_F).commit();
        InputProfileStore migrated=new InputProfileStore(mixed,old);
        check(migrated.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_W)==1<<4,"legacy pad binding restores available keyboard default");
        check(migrated.activeKeyboard().keyboardMaskForKey(KeyEvent.KEYCODE_F)==1<<7,"legacy keyboard binding remains");
        check(migrated.forController("any-device").controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<4,"legacy pad binding becomes device baseline");
        migrated.resetController("any-device");check(migrated.forController("any-device").controllerMaskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<8,"device reset restores standard pad");
        System.out.println("PROFILE_STORE_TEST_OK keyboard presets, mixed migration, numpad, swaps, distinct controllers, axis and reconnect");
    }
}
