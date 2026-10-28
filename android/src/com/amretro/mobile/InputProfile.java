package com.amretro.mobile;

import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/** Independent keyboard and controller bindings to the same player-one RetroPad. */
final class InputProfile {
    static final String[] IDS={"up","down","left","right","a","b","x","y","l","r","start","select"};
    static final String[] LABELS={"بالا / جلو","پایین / عقب","چپ","راست","A","B","X","Y","L","R","Start","Select"};
    static final int[] BITS={1<<4,1<<5,1<<6,1<<7,1<<8,1,1<<9,1<<1,1<<10,1<<11,1<<3,1<<2};
    static final int[] DEFAULT_KEYS={KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_K,KeyEvent.KEYCODE_J,KeyEvent.KEYCODE_I,KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_Q,KeyEvent.KEYCODE_E,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_SHIFT_RIGHT};
    static final int[] NUMPAD_KEYS={KeyEvent.KEYCODE_NUMPAD_8,KeyEvent.KEYCODE_NUMPAD_2,KeyEvent.KEYCODE_NUMPAD_4,KeyEvent.KEYCODE_NUMPAD_6,
            KeyEvent.KEYCODE_NUMPAD_3,KeyEvent.KEYCODE_NUMPAD_1,KeyEvent.KEYCODE_NUMPAD_9,KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT,KeyEvent.KEYCODE_NUMPAD_ADD,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_NUMPAD_0};
    static final int[] DEFAULT_PAD={KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_Y,KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_SELECT};
    private final int[] keys, pad;
    InputProfile(int[] keyboard) {this(keyboard,DEFAULT_PAD);}
    InputProfile(int[] keyboard,int[] controller) {
        if(keyboard.length!=IDS.length||controller.length!=IDS.length)throw new IllegalArgumentException("Incomplete input profile");
        keys=keyboard.clone();pad=controller.clone();
    }
    int configuredKey(int action){return keys[action];}
    int configuredPad(int action){return pad[action];}
    private static int lookup(int[] bindings,int code){
        for(int i=0;i<bindings.length;i++)if(bindings[i]==code)return BITS[i];
        return 0;
    }
    int keyboardMaskForKey(int code){
        int mapped=lookup(keys,code);if(mapped!=0)return mapped;
        // Arrow keys are a convenience fallback, not an extra saved keyboard profile.
        switch(code){case KeyEvent.KEYCODE_DPAD_UP:return 1<<4;case KeyEvent.KEYCODE_DPAD_DOWN:return 1<<5;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 1<<6;case KeyEvent.KEYCODE_DPAD_RIGHT:return 1<<7;default:return 0;}
    }
    int controllerMaskForKey(int code){return lookup(pad,code);}
    /** Legacy host-test helper; the activity uses the source-specific methods. */
    int maskForKey(int code){int key=keyboardMaskForKey(code);return key!=0?key:controllerMaskForKey(code);}
    int axesMask(float x,float y,float hatX,float hatY){
        int mask=0;final float threshold=.5f;
        if(x<=-threshold||hatX<=-threshold)mask|=controllerMaskForKey(KeyEvent.KEYCODE_DPAD_LEFT);
        if(x>=threshold||hatX>=threshold)mask|=controllerMaskForKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        if(y<=-threshold||hatY<=-threshold)mask|=controllerMaskForKey(KeyEvent.KEYCODE_DPAD_UP);
        if(y>=threshold||hatY>=threshold)mask|=controllerMaskForKey(KeyEvent.KEYCODE_DPAD_DOWN);
        return mask;
    }
    static boolean canBind(int code){
        return code>0 && code!=KeyEvent.KEYCODE_BACK && code!=KeyEvent.KEYCODE_HOME &&
            code!=KeyEvent.KEYCODE_POWER && code!=KeyEvent.KEYCODE_VOLUME_UP && code!=KeyEvent.KEYCODE_VOLUME_DOWN;
    }
    /** Track device + key, so releasing one controller never releases another. */
    static final class PressedKeys {
        private final Map<Long,Integer> pressed=new HashMap<>();
        private final InputProfile profile;
        PressedKeys(InputProfile profile){this.profile=profile;}
        private static long key(int device,int code){return ((long)device<<32)|(code&0xffffffffL);}
        boolean down(int code){return down(0,code,profile.maskForKey(code));}
        boolean up(int code){return up(0,code)||profile.maskForKey(code)!=0;}
        boolean down(int device,int code,int mask){if(mask==0)return false;pressed.put(key(device,code),mask);return true;}
        boolean up(int device,int code){return pressed.remove(key(device,code))!=null;}
        void clearDevice(int device){
            java.util.Iterator<Long> it=pressed.keySet().iterator();while(it.hasNext())if((int)(it.next()>>32)==device)it.remove();
        }
        void clear(){pressed.clear();}
        int mask(){int mask=0;for(int value:pressed.values())mask|=value;return mask;}
    }
}
