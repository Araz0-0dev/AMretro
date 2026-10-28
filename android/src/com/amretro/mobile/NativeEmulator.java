package com.amretro.mobile;

import android.view.Surface;

final class NativeEmulator {
    static { System.loadLibrary("amretro"); }
    static native void surface(Surface surface);
    static native boolean open(String core, String rom, String systemDir, String saveFile);
    static native String error();
    static native void frame(int buttons);
    static native short[] audio();
    static native double rate();
    static native double fps();
    // These operations execute on the emulation thread, never on the UI thread.
    static native boolean saveState(String path);
    static native boolean loadState(String path);
    static native boolean reset();
    static native void close();
    private NativeEmulator() {}
}
