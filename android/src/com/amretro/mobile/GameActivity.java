package com.amretro.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;

public final class GameActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;
    private FrameLayout root;
    private volatile boolean running, resumed, surfaceReady;
    private volatile int touchButtons, physicalButtons;
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
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this); surface.getHolder().setFormat(PixelFormat.RGBA_8888); surface.getHolder().addCallback(this);
        FrameLayout.LayoutParams screen = new FrameLayout.LayoutParams(1, 1, Gravity.CENTER); root.addView(surface, screen);
        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> resizeSurface());
        TouchControls controls = new TouchControls(this, value -> touchButtons=value);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        Button exit = new Button(this); exit.setText("بازگشت"); exit.setTextColor(Color.WHITE); exit.setTextSize(12); exit.setAllCaps(false); exit.setBackgroundColor(0x88313b53);
        FrameLayout.LayoutParams close = new FrameLayout.LayoutParams(dp(88), dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL); close.topMargin=dp(8); root.addView(exit, close);
        exit.setOnClickListener(v -> finish()); setContentView(root);
    }
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
        float ratio="gba".equals(console) ? 1.5f : 10f/9f;
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
        touchButtons=physicalButtons=0;
    }
    private void play(long myToken) {
        boolean opened=false; long start=0;
        try {
            File system=new File(getFilesDir(), "system"); File saves=new File(getFilesDir(), "saves"); system.mkdirs(); saves.mkdirs();
            String core=new File(getApplicationInfo().nativeLibraryDir,"libmgba_libretro.so").getAbsolutePath();
            String save=new File(saves,gameId+".srm").getAbsolutePath();
            opened=NativeEmulator.open(core,romPath,system.getAbsolutePath(),save);
            if (!opened) {
                String error=NativeEmulator.error();
                runOnUiThread(() -> { Toast.makeText(this,"بازی باز نشد: "+error,Toast.LENGTH_LONG).show(); finish(); }); return;
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
                NativeEmulator.frame(touchButtons|physicalButtons);
                short[] data=NativeEmulator.audio(); AudioTrack a=audioTrack;
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
            runOnUiThread(() -> { Toast.makeText(this,"خطا در اجرای بازی؛ فایل یا هسته را بررسی کن.",Toast.LENGTH_LONG).show(); finish(); });
        } finally {
            AudioTrack a=audioTrack; audioTrack=null;
            if (a!=null) try { a.pause(); a.flush(); a.release(); } catch (Exception ignored) { }
            if (opened) {
                NativeEmulator.close();
                long duration=Math.max(0,SystemClock.elapsedRealtime()-start);
                GameStore store=new GameStore(this); store.record(gameId,duration); store.close();
            }
            runOnUiThread(() -> { if (token==myToken) { running=false; player=null; if (resumed && surfaceReady && !isFinishing()) maybeStart(); } });
        }
    }
    private int keyBit(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:return 1<<4; case KeyEvent.KEYCODE_DPAD_DOWN:return 1<<5;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 1<<6; case KeyEvent.KEYCODE_DPAD_RIGHT:return 1<<7;
            case KeyEvent.KEYCODE_BUTTON_A:return 1<<8; case KeyEvent.KEYCODE_BUTTON_B:return 1;
            case KeyEvent.KEYCODE_BUTTON_START:return 1<<3; case KeyEvent.KEYCODE_BUTTON_SELECT:return 1<<2;
            case KeyEvent.KEYCODE_BUTTON_L1:return 1<<10; case KeyEvent.KEYCODE_BUTTON_R1:return 1<<11;
            default:return 0;
        }
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) { int bit=keyBit(code); if(bit!=0){ physicalButtons|=bit; return true; } return super.onKeyDown(code,event); }
    @Override public boolean onKeyUp(int code, KeyEvent event) { int bit=keyBit(code); if(bit!=0){ physicalButtons&=~bit; return true; } return super.onKeyUp(code,event); }
}
