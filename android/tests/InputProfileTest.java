package com.amretro.mobile;
import android.view.KeyEvent;

public class InputProfileTest {
    static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    public static void main(String[] args){
        InputProfile p=new InputProfile(InputProfile.DEFAULT_KEYS);
        check(p.maskForKey(KeyEvent.KEYCODE_W)==1<<4,"W is up");
        check(p.maskForKey(KeyEvent.KEYCODE_S)==1<<5,"S is down");
        check(p.maskForKey(KeyEvent.KEYCODE_A)==1<<6,"A is left");
        check(p.maskForKey(KeyEvent.KEYCODE_D)==1<<7,"D is right");
        check(p.maskForKey(KeyEvent.KEYCODE_J)==1,"J is RetroPad B");
        check(p.maskForKey(KeyEvent.KEYCODE_K)==1<<8,"K is RetroPad A");
        check(p.maskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<8,"controller alias");
        InputProfile.PressedKeys keys=new InputProfile.PressedKeys(p);
        check(keys.down(KeyEvent.KEYCODE_W),"press W");check(keys.down(KeyEvent.KEYCODE_DPAD_UP),"press arrow");
        keys.up(KeyEvent.KEYCODE_W);check(keys.mask()==1<<4,"releasing W keeps held arrow active");
        keys.up(KeyEvent.KEYCODE_DPAD_UP);check(keys.mask()==0,"release all aliases");
        check(!keys.down(KeyEvent.KEYCODE_SPACE),"unbound key ignored");
        int[] custom=InputProfile.DEFAULT_KEYS.clone();custom[0]=KeyEvent.KEYCODE_F;
        InputProfile changed=new InputProfile(custom);
        check(changed.maskForKey(KeyEvent.KEYCODE_W)==0,"old binding removed");
        check(changed.maskForKey(KeyEvent.KEYCODE_F)==1<<4,"custom up binding");
        custom[0]=KeyEvent.KEYCODE_BUTTON_A;
        check(new InputProfile(custom).maskForKey(KeyEvent.KEYCODE_BUTTON_A)==1<<4,"custom key beats fallback");
        check(!InputProfile.canBind(KeyEvent.KEYCODE_BACK),"back reserved");
        System.out.println("INPUT_PROFILE_TEST_OK WASD, controller aliases, simultaneous keys, remapping");
    }
}
