package com.amretro.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class GameActivity extends Activity implements SurfaceHolder.Callback, InputManager.InputDeviceListener {
    private static final Object CORE_SESSION_LOCK=new Object();
    private SurfaceView surface;
    private FrameLayout root;
    private volatile boolean running, resumed, surfaceReady, menuPaused, userPaused;
    private final ConcurrentLinkedQueue<Integer> commands = new ConcurrentLinkedQueue<>();
    private volatile int touchButtons, physicalButtons;
    private InputProfile.PressedKeys pressedKeys;
    private InputProfile keyboardProfile;
    private InputProfileStore inputProfiles;
    private final Map<String,InputProfile> controllerProfiles=new HashMap<>();
    private final Map<Integer,Integer> axisButtons=new HashMap<>();
    private volatile AudioTrack audioTrack;
    private Thread player;
    private String gameId, romPath, console;
    private long token;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        immersive();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gameId = getIntent().getStringExtra("id");
        if (gameId == null || !gameId.matches("[A-Za-z0-9_-]{1,100}")) { finish(); return; }
        GameStore store = new GameStore(this); JSONObject game = store.get(gameId); store.close();
        if (game == null) { finish(); return; }
        romPath = game.optString("rom_path"); console = game.optString("console_id");
        inputProfiles=ControlSettings.store(this);keyboardProfile=inputProfiles.activeKeyboard();
        pressedKeys=new InputProfile.PressedKeys(keyboardProfile);
        InputManager manager=(InputManager)getSystemService(INPUT_SERVICE);if(manager!=null)manager.registerInputDeviceListener(this,null);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this); surface.getHolder().setFormat(PixelFormat.RGBA_8888); surface.getHolder().addCallback(this);
        FrameLayout.LayoutParams screen = new FrameLayout.LayoutParams(1, 1, Gravity.CENTER); root.addView(surface, screen);
        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> resizeSurface());
        TouchControls controls = new TouchControls(this, console, value -> touchButtons=value);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        Button menu = new Button(this); menu.setText("منو ☰"); menu.setTextColor(Color.WHITE); menu.setTextSize(12); menu.setAllCaps(false); menu.setBackgroundColor(0x88313b53);
        FrameLayout.LayoutParams close = new FrameLayout.LayoutParams(dp(88), dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL); close.topMargin=dp(8); root.addView(menu, close);
        menu.setOnClickListener(v -> showMenu()); setContentView(root);
    }
    private void notifyUser(String text) { runOnUiThread(() -> Toast.makeText(this,text,Toast.LENGTH_LONG).show()); }
    private void showMenu() {
        if (menuPaused) return;
        menuPaused=true; clearPhysical();touchButtons=0;
        String[] items={"ادامهٔ بازی",userPaused?"ادامه از توقف":"توقف موقت",
            "ذخیرهٔ حالت · جایگاه ۱","بارگذاری حالت · جایگاه ۱",
            "ذخیرهٔ حالت · جایگاه ۲","بارگذاری حالت · جایگاه ۲",
            "ذخیرهٔ حالت · جایگاه ۳","بارگذاری حالت · جایگاه ۳",
            "شروع دوبارهٔ بازی","خروج از بازی"};
        final boolean[] nested={false};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("منوی بازی").setItems(items,(d,which) -> {
            if(which==1) { userPaused=!userPaused; notifyUser(userPaused?"بازی متوقف شد؛ از منو ادامه بده.":"بازی ادامه پیدا کرد."); }
            else if(which>=2 && which<=7) {
                int index=which-2,slot=index/2+1;commands.add((index%2==0?0:10)+slot);
            } else if(which==8) {
                nested[0]=true;
                AlertDialog confirm=new AlertDialog.Builder(this).setTitle("شروع دوباره؟")
                    .setMessage("پیشرفت ذخیره‌نشدهٔ فعلی از دست می‌رود.")
                    .setPositiveButton("شروع دوباره",(x,w)->commands.add(20)).setNegativeButton("انصراف",(x,w)->{}).create();
                confirm.setOnDismissListener(x -> menuPaused=false); confirm.show();
            } else if(which==9) finish();
        }).setNegativeButton("بستن",(d,w)->{}).create();
        dialog.setOnDismissListener(d -> {if(!nested[0])menuPaused=false;});dialog.show();
    }
    @Override public void onBackPressed() { showMenu(); }
    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    private void resizeSurface() {
        int w=root.getWidth(), h=root.getHeight(); if (w<=0||h<=0) return;
        float ratio="gba".equals(console) ? 1.5f : ("nes".equals(console)||"genesis".equals(console)) ? 4f/3f : 10f/9f;
        int sw=Math.min(w,(int)(h*ratio)), sh=Math.min(h,(int)(w/ratio));
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if (p.width!=sw || p.height!=sh) { p.width=sw; p.height=sh; surface.setLayoutParams(p); }
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { NativeEmulator.surface(holder.getSurface()); surfaceReady=true; maybeStart(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady=false; stopPlayer(); NativeEmulator.surface(null); }
    @Override protected void onResume() { super.onResume(); resumed=true; maybeStart(); }
    @Override protected void onPause() { resumed=false; stopPlayer(); super.onPause(); }
    private void maybeStart() {
        if (!resumed || !surfaceReady || running || (player != null && player.isAlive())) return;
        running=true; final long myToken=++token;
        player=new Thread(() -> play(myToken), "amretro-emulation"); player.start();
    }
    private void stopPlayer() {
        running=false;
        AudioTrack track=audioTrack;
        if (track!=null) try { track.pause(); track.flush(); } catch (Exception ignored) { }
        Thread p=player;
        if (p!=null && p!=Thread.currentThread()) try { p.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        touchButtons=0;clearPhysical();
    }
    private boolean processCommands(File states) {
        boolean resetAudio=false; Integer command;
        while((command=commands.poll())!=null) {
            boolean load=command>=11 && command<=13, save=command>=1 && command<=3;
            if(save || load) {
                int slot=load?command-10:command;
                File file=new File(states,gameId+"-slot-"+slot+".state");
                boolean ok=load?NativeEmulator.loadState(file.getAbsolutePath()):NativeEmulator.saveState(file.getAbsolutePath());
                if(ok){notifyUser((load?"حالت بارگذاری شد؛ جایگاه ":"حالت ذخیره شد؛ جایگاه ")+slot);if(load)resetAudio=true;}
                else notifyUser((load?"بارگذاری حالت ممکن نشد: ":"ذخیرهٔ حالت ممکن نشد: ")+NativeEmulator.error());
            } else if(command==20) {
                if(NativeEmulator.reset()){notifyUser("بازی از ابتدا شروع شد.");resetAudio=true;}
                else notifyUser("شروع دوباره ممکن نشد: "+NativeEmulator.error());
            }
        }
        return resetAudio;
    }
    private void play(long myToken) {
        synchronized(CORE_SESSION_LOCK) {
            if(!running) { runOnUiThread(() -> { if(token==myToken){player=null;if(resumed&&surfaceReady&&!isFinishing())maybeStart();} }); return; }
            playLocked(myToken);
        }
    }
    private void playLocked(long myToken) {
        boolean opened=false, failed=false, preserveOldAuto=false; long start=0, pausedSince=0, pausedTotal=0;
        File states=new File(getFilesDir(),"states"), autoState=new File(states,gameId+"-autosuspend.state");
        try {
            File system=new File(getFilesDir(),"system"), saves=new File(getFilesDir(),"saves");
            if((!system.isDirectory()&&!system.mkdirs())||(!saves.isDirectory()&&!saves.mkdirs())||(!states.isDirectory()&&!states.mkdirs())) throw new IllegalStateException("Cannot create game data directories");
            String coreName;
            switch(console) {
                case "gb": case "gbc": case "gba": coreName="libmgba_libretro.so"; break;
                case "nes": coreName="libnes_libretro.so"; break;
                case "genesis": coreName="libares_md_libretro.so"; break;
                default: throw new IllegalArgumentException("Unsupported internal console");
            }
            String core=new File(getApplicationInfo().nativeLibraryDir,coreName).getAbsolutePath();
            String save=new File(saves,gameId+".srm").getAbsolutePath();
            opened=NativeEmulator.open(core,romPath,system.getAbsolutePath(),save);
            if (!opened) {
                String error=NativeEmulator.error();
                runOnUiThread(() -> { Toast.makeText(this,"بازی باز نشد: "+error,Toast.LENGTH_LONG).show(); finish(); }); return;
            }
            if(autoState.isFile()) {
                if(NativeEmulator.loadState(autoState.getAbsolutePath())) { autoState.delete(); notifyUser("بازی از حالت تعلیق ادامه پیدا کرد."); }
                else {
                    File rejected=new File(states,gameId+"-incompatible-"+System.currentTimeMillis()+".state");
                    preserveOldAuto=!autoState.renameTo(rejected);
                    notifyUser("حالت تعلیق با این بازی یا نسخهٔ هسته سازگار نبود؛ فایل نگه داشته شد.");
                }
            }
            start=SystemClock.elapsedRealtime();
            int inputRate=(int)Math.round(NativeEmulator.rate()), outputRate=48000;
            int minimum=AudioTrack.getMinBufferSize(outputRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            if (minimum<=0) { outputRate=44100; minimum=AudioTrack.getMinBufferSize(outputRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT); }
            if (minimum>0) {
                AudioTrack a=new AudioTrack(AudioManager.STREAM_MUSIC,outputRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,outputRate*4/8),AudioTrack.MODE_STREAM);
                if (a.getState()==AudioTrack.STATE_INITIALIZED) { audioTrack=a; a.play(); } else a.release();
            }
            StereoResampler resampler=new StereoResampler(inputRate,outputRate);
            double fps=NativeEmulator.fps(); long next=System.nanoTime();
            while (running) {
                AudioTrack a=audioTrack;
                if(processCommands(states)) {
                    if(a!=null) try { a.pause(); a.flush(); if(!menuPaused&&!userPaused)a.play(); } catch(Exception ignored) { }
                    resampler=new StereoResampler(inputRate,outputRate); next=System.nanoTime();
                }
                long now=SystemClock.elapsedRealtime();
                if(menuPaused || userPaused) {
                    if(pausedSince==0) { pausedSince=now; if(a!=null)try {a.pause();a.flush();}catch(Exception ignored){} }
                    try { Thread.sleep(20); } catch(InterruptedException e) {Thread.currentThread().interrupt();break;}
                    next=System.nanoTime();continue;
                }
                if(pausedSince!=0) {
                    pausedTotal+=now-pausedSince; pausedSince=0;
                    if(a!=null)try {a.play();}catch(Exception ignored){}
                    resampler=new StereoResampler(inputRate,outputRate);next=System.nanoTime();
                }
                NativeEmulator.frame(touchButtons|physicalButtons);
                short[] data=NativeEmulator.audio();
                if (a!=null && data.length>0) {
                    if (inputRate!=outputRate) data=resampler.convert(data);
                    int offset=0;
                    while (running && offset<data.length) { int n=a.write(data,offset,data.length-offset); if (n<=0) break; offset+=n; }
                } else {
                    next+=(long)(1_000_000_000d/fps); long wait=next-System.nanoTime();
                    if (wait>0) { try { Thread.sleep(wait/1_000_000L,(int)(wait%1_000_000L)); } catch (InterruptedException ignored) { break; } }
                    else if (wait < -1_000_000_000L) next=System.nanoTime();
                }
            }
        } catch (Throwable ex) {
            failed=true;
            runOnUiThread(() -> { Toast.makeText(this,"خطا در اجرای بازی؛ فایل یا هسته را بررسی کن.",Toast.LENGTH_LONG).show(); finish(); });
        } finally {
            long end=SystemClock.elapsedRealtime(); if(pausedSince!=0)pausedTotal+=end-pausedSince;
            AudioTrack a=audioTrack; audioTrack=null;
            if (a!=null) try { a.pause(); a.flush(); a.release(); } catch (Exception ignored) { }
            if (opened) {
                if(!isFinishing()&&!failed&&!preserveOldAuto && !NativeEmulator.saveState(autoState.getAbsolutePath())) notifyUser("ذخیرهٔ خودکار حالت تعلیق ممکن نشد؛ از ذخیرهٔ داخل بازی استفاده کن.");
                NativeEmulator.close();
                long duration=start>0?Math.max(0,end-start-pausedTotal):0;
                GameStore store=new GameStore(this); store.record(gameId,duration); store.close();
            }
            commands.clear();
            runOnUiThread(() -> { if (token==myToken) { running=false; player=null; if (resumed && surfaceReady && !isFinishing()) maybeStart(); } });
        }
    }
    private void clearPhysical(){
        if(pressedKeys!=null)pressedKeys.clear();axisButtons.clear();physicalButtons=0;
    }
    private void updatePhysical(){
        int mask=pressedKeys==null?0:pressedKeys.mask();for(int value:axisButtons.values())mask|=value;physicalButtons=mask;
    }
    private InputProfile controllerProfile(InputDevice device){
        String descriptor=ControlSettings.descriptor(device);InputProfile profile=controllerProfiles.get(descriptor);
        if(profile==null){
            try{profile=inputProfiles.forController(descriptor);}catch(Exception e){profile=keyboardProfile;}
            controllerProfiles.put(descriptor,profile);
        }
        return profile;
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        InputDevice device=event.getDevice();boolean pad=ControlSettings.isController(event) && device!=null;
        InputProfile profile=pad?controllerProfile(device):keyboardProfile;
        int mask=profile==null?0:(pad?profile.controllerMaskForKey(code):profile.keyboardMaskForKey(code));
        if(pressedKeys!=null && pressedKeys.down(event.getDeviceId(),code,mask)){updatePhysical();return true;}
        return super.onKeyDown(code,event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if(pressedKeys!=null && pressedKeys.up(event.getDeviceId(),code)){updatePhysical();return true;}
        return super.onKeyUp(code,event);
    }
    @Override public void onInputDeviceAdded(int id) { }
    @Override public void onInputDeviceChanged(int id) { onInputDeviceRemoved(id); }
    @Override public void onInputDeviceRemoved(int id) {
        if(pressedKeys!=null)pressedKeys.clearDevice(id);axisButtons.remove(id);updatePhysical();
    }
    @Override protected void onDestroy(){
        InputManager manager=(InputManager)getSystemService(INPUT_SERVICE);if(manager!=null)manager.unregisterInputDeviceListener(this);
        super.onDestroy();
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event){
        InputDevice device=event.getDevice();
        if(event.getAction()==MotionEvent.ACTION_MOVE && ControlSettings.isController(device) &&
           (event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK){
            InputProfile p=controllerProfile(device);
            int mask=p.axesMask(event.getAxisValue(MotionEvent.AXIS_X),event.getAxisValue(MotionEvent.AXIS_Y),
                                event.getAxisValue(MotionEvent.AXIS_HAT_X),event.getAxisValue(MotionEvent.AXIS_HAT_Y));
            if(mask==0)axisButtons.remove(event.getDeviceId());else axisButtons.put(event.getDeviceId(),mask);
            updatePhysical();return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
