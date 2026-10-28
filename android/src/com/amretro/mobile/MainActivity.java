package com.amretro.mobile;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String API="https://retro-hub-68aa04.am0u0dev.workers.dev";
    private static final int PICK_GAME=41, PICK_BIOS=42;
    private static final Object BIOS_INSTALL_LOCK=new Object();
    private String pendingBiosName;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WebView web;
    private GameStore store;
    private volatile long lastProgress;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);if(saved!=null)pendingBiosName=saved.getString("pendingBiosName");store=new GameStore(this);web=new WebView(this);
        web.setBackgroundColor(0xff0a0e19);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(true);s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsConfirm(WebView view,String url,String text,JsResult result){
                new android.app.AlertDialog.Builder(MainActivity.this).setMessage(text).setPositiveButton("بله",(d,w)->result.confirm()).setNegativeButton("خیر",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return true;}
            @Override public void onPageFinished(WebView v,String url){if(url.equals("file:///android_asset/index.html")){sendLibrary();new Thread(() -> sync(),"catalog-sync").start();}}
        });
        setContentView(web);web.loadUrl("file:///android_asset/index.html");
    }
    @Override protected void onResume(){super.onResume();if(web!=null)sendLibrary();confirmExternalSession();}
    private void confirmExternalSession(){
        SharedPreferences prefs=getSharedPreferences("external-session",MODE_PRIVATE);String pending=prefs.getString("id","");long since=prefs.getLong("since",0);
        if(pending.isEmpty()||since<=0)return;prefs.edit().clear().apply();long duration=SystemClock.elapsedRealtime()-since;
        if(duration<0||duration>24L*60*60*1000||store.get(pending)==null)return;
        new android.app.AlertDialog.Builder(this).setTitle("ثبت فعالیت محلی")
            .setMessage("آیا بازی در RetroArch اجرا شد؟ زمان از هنگام خروج از AMretro تا بازگشت، تقریبی است و ممکن است زمان خارج از بازی را هم شامل شود. این تروفی رسمی RetroAchievements نیست.")
            .setPositiveButton("بله، ثبت کن",(d,w)->{store.record(pending,duration);sendLibrary();message("فعالیت محلی ثبت شد.");})
            .setNegativeButton("خیر",(d,w)->{}).show();
    }
    @Override protected void onSaveInstanceState(Bundle out){if(pendingBiosName!=null)out.putString("pendingBiosName",pendingBiosName);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){worker.shutdown();if(store!=null)store.close();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();}super.onDestroy();}
    private void send(String type,Object data){
        String value=data instanceof JSONObject||data instanceof JSONArray?data.toString():JSONObject.quote(String.valueOf(data));
        WebView target=web;if(target!=null)target.post(() -> target.evaluateJavascript("if(typeof window.fromNative==='function')window.fromNative("+JSONObject.quote(type)+","+value+");",null));
    }
    private void sendLibrary(){try{send("library",store.all());}catch(Exception ignored){}}
    private void message(String text){send("message",text);}
    private static String field(JSONObject o,String key){String v=o.optString(key,"");return "null".equals(v)?"":v;}
    private static String id(String raw) throws IOException {if(raw==null||!raw.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("شناسهٔ بازی معتبر نیست.");return raw;}
    private static boolean nativeConsole(String c){return "gb".equals(c)||"gbc".equals(c)||"gba".equals(c)||"nes".equals(c)||"genesis".equals(c);}

    public final class Bridge {
        @JavascriptInterface public void syncCatalog(){worker.execute(() -> sync());}
        @JavascriptInterface public void library(){sendLibrary();}
        @JavascriptInterface public void importGame(){runOnUiThread(() -> {
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
            try{startActivityForResult(i,PICK_GAME);}catch(Exception e){message("فایل‌گزین اندروید در دسترس نیست.");}
        });}
        @JavascriptInterface public void install(String raw){worker.execute(() -> installCatalog(raw));}
        @JavascriptInterface public void play(String raw){runOnUiThread(() -> {
            try{JSONObject game=store.get(id(raw));if(game==null)throw new IOException("ابتدا بازی را نصب کن.");if(!nativeConsole(field(game,"console_id")))throw new IOException("برای این کنسول از دکمهٔ RetroArch استفاده کن.");
                Intent i=new Intent(MainActivity.this,GameActivity.class);i.putExtra("id",raw);startActivity(i);
            }catch(Exception e){message(e.getMessage());}
        });}
        @JavascriptInterface public void retroarch(String raw){worker.execute(() -> launchRetroArch(raw));}
        @JavascriptInterface public void trophy(String raw){try{JSONObject g=store.get(id(raw));if(g!=null){SecretBox vault=new SecretBox(MainActivity.this);g.put("ra_user",vault.username());g.put("ra_has_key",vault.hasKey());send("trophy",g);}}catch(Exception e){message("خواندن تروفی‌ها ممکن نشد.");}}
        @JavascriptInterface public void fetchRa(String raw,String user,String key,String gameId){worker.execute(() -> fetchAchievements(raw,user,key,gameId));}
        @JavascriptInterface public void clearRa(){new SecretBox(MainActivity.this).clear();message("کلید RetroAchievements از این دستگاه پاک شد.");}
        @JavascriptInterface public void openRaHelp(){runOnUiThread(() -> {try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://retroachievements.org/controlpanel.php")));}catch(Exception e){message("مرورگر در دسترس نیست.");}});}
        @JavascriptInterface public void openRetroSite(){runOnUiThread(() -> {try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.retroarch.com/")));}catch(Exception e){message("مرورگر در دسترس نیست.");}});}
        @JavascriptInterface public void configureControls(){runOnUiThread(() -> ControlSettings.show(MainActivity.this));}
        @JavascriptInterface public void manageBios(){runOnUiThread(() -> showBiosManager());}
        @JavascriptInterface public void showLicenses(){runOnUiThread(() -> {
            try(InputStream in=getAssets().open("legal/THIRD-PARTY-LICENSES.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                FileOps.copy(in,out,256*1024,null);
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("مجوزهای اجزای متن‌باز").setMessage(out.toString("UTF-8")).setPositiveButton("بستن",(d,w)->{}).show();
            }catch(Exception e){message("نمایش مجوزها ممکن نشد.");}
        });}
        @JavascriptInterface public void removeGame(String raw){worker.execute(() -> remove(raw));}
    }
    private static String readUrl(String address,int max) throws Exception {
        URL url=new URL(address);HttpURLConnection c=null;
        for(int redirect=0;redirect<=5;redirect++){
            if(!"https".equalsIgnoreCase(url.getProtocol()))throw new IOException("اتصال امن HTTPS لازم است.");
            c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","AMretro-Android/0.2");
            int code=c.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("تغییر مسیر نامعتبر است.");url=new URL(url,location);c=null;continue;}
            if(code<200||code>=300){c.disconnect();throw new IOException("خطای سرویس (HTTP "+code+").");}break;
        }
        if(c==null)throw new IOException("تعداد تغییر مسیر زیاد است.");
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){FileOps.copy(in,out,max,null);return out.toString("UTF-8");}finally{c.disconnect();}
    }
    private void sync(){
        try {
            JSONArray all=new JSONArray();int total=1;
            for(int page=1;page<=100 && all.length()<total;page++){
                JSONObject o=new JSONObject(readUrl(API+"/api/games?limit=100&page="+page,8*1024*1024));JSONArray part=o.getJSONArray("data");total=o.optInt("total",part.length());
                for(int i=0;i<part.length();i++)all.put(part.getJSONObject(i));if(part.length()==0)break;
            }
            send("catalog",all);
        }catch(Exception e){send("catalogError","ارتباط با کاتالوگ برقرار نشد؛ بازی‌های نصب‌شده همچنان در کتابخانه هستند.");}
    }
    private void ensureBios(String console) throws Exception {
        synchronized(BIOS_INSTALL_LOCK) { ensureBiosLocked(console); }
    }
    private void ensureBiosLocked(String console) throws Exception {
        JSONObject o=new JSONObject(readUrl(API+"/api/bios?console_id="+Uri.encode(console),4*1024*1024));JSONArray list=o.getJSONArray("data");
        File system=new File(getFilesDir(),"system");if(!system.exists()&&!system.mkdirs())throw new IOException("پوشهٔ BIOS ساخته نشد.");
        for(int i=0;i<list.length();i++){
            JSONObject b=list.getJSONObject(i);String bid=id(field(b,"id")), name=FileOps.safeName(field(b,"file_name")), path=field(b,"system_path"), hash=field(b,"sha256");
            String lower=name.toLowerCase(Locale.US);if(lower.endsWith(".zip")||lower.endsWith(".7z")||lower.endsWith(".rar"))throw new IOException("BIOS فشرده در نسخهٔ اندروید پشتیبانی نمی‌شود؛ فایل تکی ثبت کن.");
            if(path.isEmpty())path=name;else if(!new File(path).getName().contains("."))path=path.replaceAll("/+$","")+"/"+name;
            File target=FileOps.child(system,path);
            if(FileOps.reuseBios(target,hash))continue;
            message("در حال دریافت BIOS منتشرشده برای کنسول…");
            File temp=new File(getCacheDir(),"bios-"+bid+".part");FileOps.download(field(b,"download_url"),temp,hash,null);
            // The same staged, non-overwriting installer handles panel and local BIOS files.
            FileOps.installBios(temp,target);
            temp.delete();
        }
    }
    private void progress(long done,long total){long now=SystemClock.elapsedRealtime();if(now-lastProgress<400)return;lastProgress=now;message(total>0?"دانلود: "+(done*100/total)+"٪":"در حال دانلود بازی…");}
    private void installCatalog(String raw){
        try{
            String gameId=id(raw);if(store.get(gameId)!=null)throw new IOException("این بازی از قبل در کتابخانه است.");
            JSONObject g=new JSONObject(readUrl(API+"/api/games/"+gameId,4*1024*1024)).getJSONObject("data");String console=field(g,"console_id");
            message("در حال بررسی BIOS کنسول…");ensureBios(console);
            String name=FileOps.safeName(field(g,"file_name"));if(name.equals("game.bin"))name=FileOps.safeName(Uri.parse(field(g,"download_url")).getLastPathSegment());
            if(name.toLowerCase(Locale.US).endsWith(".7z")||name.toLowerCase(Locale.US).endsWith(".rar"))throw new IOException("در اندروید ZIP و فایل خام پشتیبانی می‌شوند؛ 7z و RAR هنوز نه.");
            File temp=new File(getCacheDir(),"game-"+gameId+".part");message("در حال دانلود بازی…");FileOps.download(field(g,"download_url"),temp,field(g,"sha256"),this::progress);
            installCopied(temp,name,gameId,field(g,"title"),console,field(g,"console_core"),field(g,"cover_url"));temp.delete();
            sendLibrary();message("بازی نصب شد. "+(nativeConsole(console)?"با دکمه‌های لمسی آمادهٔ اجراست.":"برای اجرا به RetroArch و هستهٔ مربوط نیاز است."));
        }catch(Exception e){message(e.getMessage()==null?"نصب بازی ناموفق بود.":e.getMessage());}
    }
    private void installCopied(File input,String fileName,String gameId,String title,String console,String core,String cover) throws Exception {
        File games=new File(getFilesDir(),"games"),finalDir=new File(games,gameId),stage=new File(getCacheDir(),"stage-"+gameId);
        if(finalDir.exists())throw new IOException("پوشهٔ بازی از قبل وجود دارد و بازنویسی نمی‌شود.");FileOps.deleteTree(stage);if(!stage.mkdirs())throw new IOException("ساخت پوشهٔ نصب ممکن نشد.");
        try{
            boolean zip=FileOps.isZip(input);
            if(zip&&!"arcade".equals(console))FileOps.unzip(input,stage);
            else {if(fileName.toLowerCase(Locale.US).endsWith(".zip")&&!zip)throw new IOException("فایل ZIP معتبر نیست.");try(InputStream in=new FileInputStream(input);OutputStream out=new FileOutputStream(new File(stage,FileOps.safeName(fileName)))){FileOps.copy(in,out,FileOps.MAX_DOWNLOAD,null);}}
            File selected=FileOps.chooseGame(stage,console);String relative=stage.toURI().relativize(selected.toURI()).getPath();if(relative.isEmpty())throw new IOException("مسیر بازی پیدا نشد.");
            if(!games.exists()&&!games.mkdirs())throw new IOException("ساخت کتابخانه ممکن نشد.");
            if(!stage.renameTo(finalDir))throw new IOException("انتقال بازی به کتابخانه ممکن نشد.");
            try{store.add(gameId,title.isEmpty()?fileName:title,console,core.isEmpty()?"mgba":core,new File(finalDir,relative).getAbsolutePath(),cover);}catch(Exception e){FileOps.deleteTree(finalDir);throw e;}
        }finally{FileOps.deleteTree(stage);}
    }
    private void showBiosManager(){
        String[] labels={"Game Boy · gb_bios.bin", "Game Boy Color · gbc_bios.bin", "Game Boy Advance · gba_bios.bin",
            "PlayStation ژاپن · scph5500.bin", "PlayStation آمریکا · scph5501.bin", "PlayStation اروپا · scph5502.bin",
            "کنسول دیگر · با نام فایل اصلی", "نمایش فایل‌های موجود"};
        String[] names={"gb_bios.bin","gbc_bios.bin","gba_bios.bin","scph5500.bin","scph5501.bin","scph5502.bin",""};
        new android.app.AlertDialog.Builder(this).setTitle("مدیریت BIOS شخصی")
            .setItems(labels,(d,which)->{
                if(which==labels.length-1){showBiosFiles();return;}
                pendingBiosName=names[which];Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
                try{startActivityForResult(i,PICK_BIOS);}catch(Exception e){pendingBiosName=null;message("فایل‌گزین اندروید در دسترس نیست.");}
            }).setNegativeButton("بستن",(d,w)->{}).show();
    }
    private void showBiosFiles(){
        try {
            File root=new File(getFilesDir(),"system");List<File> files=new ArrayList<>();if(root.isDirectory())FileOps.collect(root,files);
            if(files.isEmpty()){new android.app.AlertDialog.Builder(this).setTitle("BIOSهای موجود").setMessage("هنوز BIOS شخصی وجود ندارد.").setPositiveButton("بستن",(d,w)->{}).show();return;}
            String[] rows=new String[files.size()];
            for(int i=0;i<rows.length;i++){File f=files.get(i);rows[i]=root.toURI().relativize(f.toURI()).getPath()+"  ·  "+f.length()+" bytes";}
            new android.app.AlertDialog.Builder(this).setTitle("BIOSهای خصوصی · برای حذف انتخاب کن")
                .setItems(rows,(d,which)->new android.app.AlertDialog.Builder(this).setTitle("حذف این نسخه از BIOS؟")
                    .setMessage(rows[which]+"\n\nفقط نسخهٔ خصوصی AMretro حذف می‌شود؛ فایل اصلی انتخاب‌شده در دستگاه پاک نمی‌شود.")
                    .setPositiveButton("حذف",(x,y)->{
                        synchronized(BIOS_INSTALL_LOCK){if(files.get(which).delete())message("نسخهٔ خصوصی BIOS حذف شد.");else message("حذف BIOS ممکن نشد.");}
                        showBiosFiles();
                    }).setNegativeButton("انصراف",(x,y)->showBiosFiles()).show())
                .setNegativeButton("بستن",(d,w)->{}).show();
        }catch(Exception e){message("خواندن فهرست BIOS ممکن نشد.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_BIOS){
            String name=pendingBiosName;pendingBiosName=null;
            if(name!=null&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();worker.execute(() -> importBiosUri(uri,name));}
            return;
        }
        if(request!=PICK_GAME||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();worker.execute(() -> importUri(uri));
    }
    private void importBiosUri(Uri uri,String selectedName){
        File temp=new File(getCacheDir(),"bios-personal-"+UUID.randomUUID()+".part");
        try {
            String original="";
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())original=c.getString(0);}
            if(original==null||original.trim().isEmpty())throw new IOException("نام فایل BIOS مشخص نیست.");
            String lower=original.toLowerCase(Locale.US);
            if(lower.endsWith(".zip")||lower.endsWith(".7z")||lower.endsWith(".rar"))throw new IOException("BIOS باید فایل تکی و غیرفشرده باشد.");
            String name=selectedName.isEmpty()?FileOps.safeName(original):selectedName;
            try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException("فایل BIOS انتخاب‌شده خوانده نشد.");FileOps.copy(in,out,64L*1024*1024,null);out.getFD().sync();
            }
            long size=temp.length();
            if(("gb_bios.bin".equals(name)&&size!=256)||("gbc_bios.bin".equals(name)&&size!=2304)||
               ("gba_bios.bin".equals(name)&&size!=16384)||(name.startsWith("scph550")&&size!=524288))
                throw new IOException("اندازهٔ فایل با BIOS انتخاب‌شده سازگار نیست؛ فایل و کنسول را بررسی کن.");
            synchronized(BIOS_INSTALL_LOCK){
                File root=new File(getFilesDir(),"system");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("پوشهٔ BIOS ساخته نشد.");
                boolean installed=FileOps.installBios(temp,FileOps.child(root,name));
                message(installed?"BIOS به پوشهٔ خصوصی برنامه اضافه شد؛ تنها هسته‌های سازگار از آن استفاده می‌کنند.":"همین BIOS از قبل موجود بود؛ دوباره نصب نشد.");
            }
        }catch(Exception e){message(e.getMessage()==null?"افزودن BIOS ممکن نشد.":e.getMessage());}
        finally{temp.delete();}
    }
    private void importUri(Uri uri){
        String gameId="local-"+UUID.randomUUID().toString();File temp=new File(getCacheDir(),gameId+".part"),stage=new File(getCacheDir(),"inspect-"+gameId);
        try{
            String name="game.gba";
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
            name=FileOps.safeName(name);String lower=name.toLowerCase(Locale.US);
            if(FileOps.importConsole(lower).isEmpty()&&!lower.endsWith(".zip"))throw new IOException("برای افزودن دستی، فایل GB، GBC، GBA، NES، Mega Drive یا ZIP انتخاب کن.");
            message("در حال بررسی فایل انتخاب‌شده…");try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("فایل انتخاب‌شده خوانده نشد.");FileOps.copy(in,out,FileOps.MAX_DOWNLOAD,null);}
            String console;
            if(lower.endsWith(".zip")){
                if(!FileOps.isZip(temp))throw new IOException("آرشیو ZIP معتبر نیست.");FileOps.unzip(temp,stage);List<File> files=new ArrayList<>();FileOps.collect(stage,files);
                Set<String> kinds=new HashSet<>();for(File f:files){String kind=FileOps.importConsole(f.getName());if(!kind.isEmpty())kinds.add(kind);}
                if(kinds.size()!=1)throw new IOException("ZIP باید بازی‌های فقط یک کنسول پشتیبانی‌شده را داشته باشد.");console=kinds.iterator().next();
            }else console=FileOps.importConsole(lower);
            message("در حال بررسی BIOS منتشرشده…");ensureBios(console);
            String title=name.replaceFirst("(?i)\\.(zip|gb|gbc|gba|nes|md|gen|smd|bin)$","");
            String retroCore="nes".equals(console)?"fceumm":"genesis".equals(console)?"genesis_plus_gx":"mgba";
            installCopied(temp,name,gameId,title,console,retroCore,"");
            sendLibrary();message("بازی به کتابخانه اضافه شد.");
        }catch(Exception e){message(e.getMessage()==null?"افزودن فایل ناموفق بود.":e.getMessage());}
        finally{temp.delete();FileOps.deleteTree(stage);}
    }
    private void remove(String raw){
        try{String gameId=id(raw);JSONObject g=store.get(gameId);if(g==null)return;store.remove(gameId);FileOps.deleteTree(new File(new File(getFilesDir(),"games"),gameId));sendLibrary();message("بازی از کتابخانه حذف شد؛ فایل ذخیرهٔ آن نگه داشته شد.");}
        catch(Exception e){message("حذف بازی ممکن نشد.");}
    }
    private static String streamHash(InputStream in) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}
    private void exportFile(File source,String relativeDir) throws Exception {
        if(Build.VERSION.SDK_INT<29)throw new IOException("ارسال خودکار فایل به RetroArch به اندروید ۱۰ یا جدیدتر نیاز دارد.");
        String folder=Environment.DIRECTORY_DOWNLOADS+"/AMretro/"+relativeDir; if(!folder.endsWith("/"))folder+="/";
        ContentResolver resolver=getContentResolver();Uri collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI;String name=source.getName();
        String[] cols={MediaStore.Downloads._ID};String where=MediaStore.Downloads.DISPLAY_NAME+"=? AND "+MediaStore.Downloads.RELATIVE_PATH+"=?";
        try(Cursor c=resolver.query(collection,cols,where,new String[]{name,folder},null)){
            if(c!=null&&c.moveToFirst()){
                Uri existing=ContentUris.withAppendedId(collection,c.getLong(0));
                try(InputStream in=resolver.openInputStream(existing)){if(in==null||!streamHash(in).equals(FileOps.sha256(source)))throw new IOException("فایل متفاوتی در پوشهٔ عمومی AMretro وجود دارد؛ بازنویسی نشد.");}
                return;
            }
        }
        ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");v.put(MediaStore.Downloads.RELATIVE_PATH,folder);v.put(MediaStore.Downloads.IS_PENDING,1);
        Uri target=resolver.insert(collection,v);if(target==null)throw new IOException("ذخیرهٔ فایل عمومی ممکن نشد.");
        try(InputStream in=new FileInputStream(source);OutputStream out=resolver.openOutputStream(target,"w")){if(out==null)throw new IOException("نوشتن فایل عمومی ممکن نشد.");FileOps.copy(in,out,FileOps.MAX_DOWNLOAD,null);}
        catch(Exception e){resolver.delete(target,null,null);throw e;}
        v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(target,v,null,null);
    }
    private String retroarchPackage(){
        for(String p:new String[]{"com.retroarch.aarch64","com.retroarch","com.retroarch.ra32"})try{getPackageManager().getPackageInfo(p,0);return p;}catch(Exception ignored){}
        return "";
    }
    private void launchRetroArch(String raw){
        try{
            String gameId=id(raw);JSONObject g=store.get(gameId);if(g==null)throw new IOException("ابتدا بازی را نصب کن.");String pkg=retroarchPackage();if(pkg.isEmpty())throw new IOException("RetroArch نصب نیست؛ ابتدا آن را جداگانه نصب کن.");
            String core=field(g,"core");if(!core.matches("[A-Za-z0-9_-]+"))throw new IOException("نام هستهٔ RetroArch معتبر نیست.");
            message("در حال آماده‌سازی فایل برای RetroArch…");File gameDir=new File(new File(getFilesDir(),"games"),gameId);List<File> files=new ArrayList<>();FileOps.collect(gameDir,files);
            String selectedRelative="";String selected=new File(field(g,"rom_path")).getCanonicalPath();
            for(File f:files){String rel=gameDir.toURI().relativize(f.toURI()).getPath();int slash=rel.lastIndexOf('/');String sub=slash<0?"":rel.substring(0,slash+1);exportFile(f,"Games/"+gameId+"/"+sub);if(f.getCanonicalPath().equals(selected))selectedRelative=rel;}
            if(selectedRelative.isEmpty())throw new IOException("فایل بازی پیدا نشد.");
            File system=new File(getFilesDir(),"system");if(system.isDirectory()){List<File> bios=new ArrayList<>();FileOps.collect(system,bios);for(File f:bios){String rel=system.toURI().relativize(f.toURI()).getPath();int slash=rel.lastIndexOf('/');exportFile(f,"System/"+(slash<0?"":rel.substring(0,slash+1)));}}
            String rom=new File(Environment.getExternalStorageDirectory(),"Download/AMretro/Games/"+gameId+"/"+selectedRelative).getAbsolutePath();
            Intent intent=new Intent(Intent.ACTION_MAIN);intent.setClassName(pkg,"com.retroarch.browser.retroactivity.RetroActivityFuture");intent.putExtra("ROM",rom);intent.putExtra("LIBRETRO","/data/data/"+pkg+"/cores/"+core+"_libretro_android.so");
            runOnUiThread(() -> {try{
                getSharedPreferences("external-session",MODE_PRIVATE).edit().putString("id",gameId).putLong("since",SystemClock.elapsedRealtime()).apply();
                startActivity(intent);
            }catch(Exception e){getSharedPreferences("external-session",MODE_PRIVATE).edit().clear().apply();message("باز کردن RetroArch ممکن نشد؛ نسخه و مسیر هسته را بررسی کن.");}});
        }catch(Exception e){message(e.getMessage()==null?"آماده‌سازی RetroArch ناموفق بود.":e.getMessage());}
    }
    private void fetchAchievements(String raw,String user,String key,String raId){
        try{
            String gameId=id(raw);JSONObject game=store.get(gameId);if(game==null)throw new IOException();
            user=user==null?"":user.trim();key=key==null?"":key.trim();if(user.length()>80||user.isEmpty())throw new IOException();
            int ra=Integer.parseInt(raId);if(ra<1)throw new IOException();SecretBox vault=new SecretBox(this);
            if(!key.isEmpty())vault.save(user,key);else {if(!user.equals(vault.username()))throw new IOException();key=vault.secret();}
            if(key.isEmpty())throw new IOException();store.setRaId(gameId,ra);
            String url="https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php?u="+Uri.encode(user)+"&g="+ra+"&y="+Uri.encode(key);
            JSONObject response=new JSONObject(readUrl(url,8*1024*1024));JSONObject achievements=response.optJSONObject("Achievements");
            if(achievements==null)throw new IOException();JSONArray rows=new JSONArray();Iterator<String> keys=achievements.keys();
            while(keys.hasNext()){
                JSONObject a=achievements.optJSONObject(keys.next());if(a==null)continue;JSONObject row=new JSONObject();
                row.put("title",field(a,"Title"));row.put("description",field(a,"Description"));row.put("points",a.optInt("Points",0));
                row.put("earned",!field(a,"DateEarned").isEmpty()||!field(a,"DateEarnedHardcore").isEmpty());row.put("hardcore",!field(a,"DateEarnedHardcore").isEmpty());rows.put(row);
            }
            JSONObject out=new JSONObject();out.put("id",gameId);out.put("title",field(response,"Title"));out.put("console",field(response,"ConsoleName"));out.put("achievements",rows);send("ra",out);
        }catch(Exception e){message("دریافت دستاوردهای RetroAchievements ناموفق بود؛ نام، Web API Key، شناسهٔ بازی و اینترنت را بررسی کن.");}
    }
}
