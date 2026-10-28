package com.amretro.mobile;

import android.view.KeyEvent;
import java.util.HashSet;
import java.util.Set;

/** One logical RetroPad profile shared by all bundled libretro cores. */
final class InputProfile {
    static final String[] IDS={"up","down","left","right","a","b","x","y","l","r","start","select"};
    static final String[] LABELS={"بالا / جلو","پایین / عقب","چپ","راست","A","B","X","Y","L","R","Start","Select"};
    static final int[] BITS={1<<4,1<<5,1<<6,1<<7,1<<8,1,1<<9,1<<1,1<<10,1<<11,1<<3,1<<2};
    static final int[] DEFAULT_KEYS={KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_K,KeyEvent.KEYCODE_J,KeyEvent.KEYCODE_I,KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_Q,KeyEvent.KEYCODE_E,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_SHIFT_RIGHT};
    private final int[] keys;
    InputProfile(int[] configured) {
        if(configured.length!=IDS.length)throw new IllegalArgumentException("Incomplete input profile");
        keys=configured.clone();
    }
    int configuredKey(int action) {return keys[action];}
    int maskForKey(int code) {
        // A custom assignment takes priority over the default controller aliases.
        for(int i=0;i<keys.length;i++)if(keys[i]==code)return BITS[i];
        switch(code) {
            case KeyEvent.KEYCODE_DPAD_UP:return 1<<4;case KeyEvent.KEYCODE_DPAD_DOWN:return 1<<5;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 1<<6;case KeyEvent.KEYCODE_DPAD_RIGHT:return 1<<7;
            case KeyEvent.KEYCODE_BUTTON_A:return 1<<8;case KeyEvent.KEYCODE_BUTTON_B:return 1;
            case KeyEvent.KEYCODE_BUTTON_X:return 1<<1;case KeyEvent.KEYCODE_BUTTON_Y:return 1<<9;
            case KeyEvent.KEYCODE_BUTTON_START:return 1<<3;case KeyEvent.KEYCODE_BUTTON_SELECT:return 1<<2;
            case KeyEvent.KEYCODE_BUTTON_L1:return 1<<10;case KeyEvent.KEYCODE_BUTTON_R1:return 1<<11;
            default:return 0;
        }
    }
    static boolean canBind(int code) {
        return code>0 && code!=KeyEvent.KEYCODE_BACK && code!=KeyEvent.KEYCODE_HOME &&
            code!=KeyEvent.KEYCODE_POWER && code!=KeyEvent.KEYCODE_VOLUME_UP && code!=KeyEvent.KEYCODE_VOLUME_DOWN;
    }
    static final class PressedKeys {
        private final Set<Integer> pressed=new HashSet<>();
        private final InputProfile profile;
        PressedKeys(InputProfile profile){this.profile=profile;}
        boolean down(int code){if(profile.maskForKey(code)==0)return false;pressed.add(code);return true;}
        boolean up(int code){boolean known=pressed.remove(code);return known||profile.maskForKey(code)!=0;}
        void clear(){pressed.clear();}
        int mask(){int mask=0;for(int key:pressed)mask|=profile.maskForKey(key);return mask;}
    }
}
