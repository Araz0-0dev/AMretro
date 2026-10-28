package com.amretro.mobile;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Pure Java download/extraction utilities. All destinations are app-controlled. */
final class FileOps {
    static final long MAX_DOWNLOAD = 1024L * 1024 * 1024;
    static final long MAX_UNPACKED = 1024L * 1024 * 1024;
    interface Progress { void report(long done, long total); }
    static String safeName(String name) {
        String n=name==null ? "" : name.replace('\\','/'); n=n.substring(n.lastIndexOf('/')+1);
        n=n.replaceAll("[^A-Za-z0-9._-]","_").replaceAll("^\\.+", "");
        if (n.length()>140) n=n.substring(n.length()-140);
        return n.isEmpty() ? "game.bin" : n;
    }
    static File child(File root,String relative) throws IOException {
        String n=relative==null ? "" : relative.replace('\\','/');
        if (n.isEmpty() || n.startsWith("/") || n.contains(":") || n.indexOf('\0')>=0) throw new IOException("مسیر فایل نامعتبر است.");
        for(String part:n.split("/")) if(part.isEmpty()||part.equals(".")||part.equals("..")) throw new IOException("مسیر فایل نامعتبر است.");
        File file=new File(root,n); String path=file.getCanonicalPath(), base=root.getCanonicalPath()+File.separator;
        if(!path.startsWith(base)) throw new IOException("مسیر فایل خارج از پوشه است."); return file;
    }
    static String sha256(File file) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256"); byte[] buf=new byte[65536];
        try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(buf))!=-1)d.update(buf,0,n);}
        StringBuilder s=new StringBuilder(); for(byte b:d.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
    }
    static void checkHash(File file,String hash) throws Exception {
        String h=hash==null?"":hash.trim();
        if(!h.isEmpty()&&!h.matches("(?i)[0-9a-f]{64}"))throw new IOException("SHA-256 ثبت‌شده نامعتبر است.");
        if(!h.isEmpty()&&!sha256(file).equalsIgnoreCase(h))throw new IOException("هش فایل با SHA-256 پنل مطابقت ندارد.");
    }
    // Return true only for a nonempty existing BIOS whose optional digest matches.
    // Conflicts are errors, never an instruction to overwrite the existing file.
    static boolean reuseBios(File target,String hash) throws Exception {
        if(!target.exists())return false;
        if(!target.isFile()||target.length()==0)throw new IOException("BIOS موجود معتبر نیست و بازنویسی نمی‌شود.");
        String expected=hash==null?"":hash.trim();
        if(!expected.isEmpty()&&!expected.matches("(?i)[0-9a-f]{64}"))throw new IOException("SHA-256 ثبت‌شده نامعتبر است.");
        if(!expected.isEmpty()&&!sha256(target).equalsIgnoreCase(expected))
            throw new IOException("BIOS موجود متفاوت است و بازنویسی نشد. برای تعویض، ابتدا آن را در مدیریت BIOS حذف کن.");
        return true;
    }
    /** Caller serializes access to the private system directory. Returns false on safe reuse. */
    static boolean installBios(File source,File target) throws Exception {
        if(!source.isFile()||source.length()==0)throw new IOException("فایل BIOS خالی یا نامعتبر است.");
        if(isCompressed(source))throw new IOException("BIOS باید فایل تکی و غیرفشرده باشد.");
        String sourceHash=sha256(source);
        if(reuseBios(target,sourceHash))return false;
        File parent=target.getParentFile();
        if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))throw new IOException("پوشهٔ BIOS ساخته نشد.");
        File staged=new File(parent,target.getName()+".new-"+UUID.randomUUID());
        try {
            try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(staged)){
                copy(in,out,MAX_DOWNLOAD,null);out.getFD().sync();
            }
            if(reuseBios(target,sourceHash))return false;
            if(!staged.renameTo(target))throw new IOException("نصب BIOS ممکن نشد و فایل قبلی بازنویسی نشد.");
            return true;
        } finally { staged.delete(); }
    }
    static void copy(InputStream in,OutputStream out,long limit,Progress progress) throws IOException {
        byte[] buf=new byte[65536];long total=0;int n;
        while((n=in.read(buf))!=-1){ if(n==0)continue;total+=n;if(total>limit)throw new IOException("فایل از سقف مجاز اندروید بزرگ‌تر است.");out.write(buf,0,n);if(progress!=null)progress.report(total,-1); }
    }
    static void download(String address,File part,String expected,Progress progress) throws Exception {
        if(expected!=null&&!expected.trim().isEmpty()&&!expected.matches("(?i)[0-9a-f]{64}")) throw new IOException("SHA-256 ثبت‌شده نامعتبر است.");
        long offset=part.exists()?part.length():0;
        // Without an expected digest, a changed server object cannot be safely resumed.
        if(offset>MAX_DOWNLOAD || (offset>0 && (expected==null || expected.trim().isEmpty()))) {
            if(!part.delete())throw new IOException("پاک‌کردن دانلود نیمه‌تمام ممکن نیست.");offset=0;
        }
        URL u=new URL(address);
        if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("در اندروید لینک دانلود امن HTTPS لازم است.");
        // A previous install can fail after a complete, verified download. Reuse it.
        if(offset>0 && expected!=null && !expected.isEmpty() && sha256(part).equalsIgnoreCase(expected)) {
            rejectHtml(part);if(progress!=null)progress.report(offset,offset);return;
        }
        HttpURLConnection connection=null;boolean restarted=false;
        for(int redirects=0;redirects<=5;redirects++){
            if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("در اندروید لینک دانلود امن HTTPS لازم است.");
            connection=(HttpURLConnection)u.openConnection();connection.setConnectTimeout(25000);connection.setReadTimeout(45000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","AMretro-Android/0.2");
            if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
            int code=connection.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("لینک تغییر مسیر نامعتبر دارد.");u=new URL(u,location);connection=null;continue;}
            if(code==416 && offset>0 && !restarted){
                connection.disconnect();if(!part.delete())throw new IOException("پاک‌کردن دانلود نیمه‌تمام ممکن نیست.");
                offset=0;restarted=true;u=new URL(address);connection=null;continue;
            }
            if(code!=200&&code!=206){connection.disconnect();throw new IOException("دانلود ناموفق شد (HTTP "+code+").");}
            if(code==206){String range=connection.getHeaderField("Content-Range");if(range==null||!range.toLowerCase(Locale.US).startsWith("bytes "+offset+"-")){connection.disconnect();throw new IOException("ادامهٔ دانلود معتبر نیست؛ دوباره تلاش کن.");}}
            else offset=0;
            break;
        }
        if(connection==null)throw new IOException("تعداد تغییر مسیرهای لینک زیاد است.");
        long content=connection.getContentLengthLong(),total=content>=0?content+offset:-1;
        if(total>MAX_DOWNLOAD){connection.disconnect();throw new IOException("فایل از سقف یک گیگابایت بزرگ‌تر است.");}
        File parent=part.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("پوشهٔ دانلود ساخته نشد.");
        try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(part,offset>0)){
            byte[] b=new byte[65536];int n;long received=offset;
            while((n=in.read(b))!=-1){if(n==0)continue;received+=n;if(received>MAX_DOWNLOAD)throw new IOException("فایل از سقف یک گیگابایت بزرگ‌تر است.");out.write(b,0,n);if(progress!=null)progress.report(received,total);}
            out.getFD().sync();
        } finally { connection.disconnect(); }
        if(part.length()==0)throw new IOException("فایل دانلودشده خالی است.");
        if(total>=0 && part.length()!=total)throw new IOException("دانلود کامل نشد؛ برای ادامه دوباره تلاش کن.");
        try{checkHash(part,expected);}catch(Exception e){part.delete();throw e;}
        rejectHtml(part);
    }
    /** Use distinct temporary files for each server, never combine mirror bytes. */
    static File downloadWithBackup(String primary,String backup,File part,String expected,String fileName,Progress progress,Runnable switching) throws Exception {
        try { download(primary,part,expected,progress); validateDownloadedFormat(part,fileName); return part; }
        catch(Exception first) {
            if(backup==null||backup.trim().isEmpty()||backup.equals(primary))throw first;
            if(switching!=null)switching.run();
            File mirror=new File(part.getParentFile(),part.getName()+".mirror");
            try {download(backup,mirror,expected,progress);validateDownloadedFormat(mirror,fileName);return mirror;}
            catch(Exception second){throw new IOException("دانلود از هر دو آدرس ناموفق بود؛ لینک‌ها و SHA-256 را بررسی کن.",second);}
        }
    }
    private static void validateDownloadedFormat(File file,String name) throws IOException {
        if(name!=null&&name.toLowerCase(Locale.US).endsWith(".zip")&&!isZip(file))throw new IOException("فایل ZIP دانلودشده معتبر نیست.");
    }
    static boolean archiveIsGame(String console){return "arcade".equals(console)||"neogeo".equals(console)||"naomi".equals(console)||"dos".equals(console);}
    private static void rejectHtml(File part) throws IOException {
        try(InputStream in=new FileInputStream(part)){
            byte[] first=new byte[256];int n=in.read(first);String sample=n>0?new String(first,0,n,"UTF-8").trim().toLowerCase(Locale.US):"";
            if(sample.startsWith("<!doctype html")||sample.startsWith("<html")){part.delete();throw new IOException("لینک به صفحهٔ وب اشاره می‌کند، نه فایل مستقیم.");}
        }
    }
    static boolean isZip(File f) throws IOException {
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[4];return in.read(b)==4&&b[0]=='P'&&b[1]=='K'&&((b[2]==3&&b[3]==4)||(b[2]==5&&b[3]==6));}
    }
    static boolean isCompressed(File file) throws IOException {
        byte[] b=new byte[8];int n;
        try(InputStream in=new FileInputStream(file)){n=in.read(b);}
        return n>=2&&(b[0]&255)==0x50&&(b[1]&255)==0x4b ||
               n>=6&&(b[0]&255)==0x37&&(b[1]&255)==0x7a&&(b[2]&255)==0xbc&&(b[3]&255)==0xaf&&(b[4]&255)==0x27&&(b[5]&255)==0x1c ||
               n>=6&&(b[0]&255)==0x52&&(b[1]&255)==0x61&&(b[2]&255)==0x72&&(b[3]&255)==0x21&&(b[4]&255)==0x1a&&(b[5]&255)==0x07;
    }
    static void unzip(File zip,File dest) throws IOException {
        if(!dest.exists()&&!dest.mkdirs())throw new IOException("ساخت پوشهٔ نصب ممکن نیست.");
        long expanded=0;int count=0;byte[] buf=new byte[65536];
        try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))){
            ZipEntry e;while((e=in.getNextEntry())!=null){
                if(++count>2048)throw new IOException("تعداد فایل‌های آرشیو بیش از حد است.");
                String n=e.getName().replace('\\','/');while(n.endsWith("/"))n=n.substring(0,n.length()-1);
                if(n.isEmpty())continue;File target=child(dest,n);
                if(e.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("ساخت پوشهٔ آرشیو ممکن نیست.");continue;}
                File parent=target.getParentFile();if(!parent.exists()&&!parent.mkdirs())throw new IOException("ساخت پوشهٔ آرشیو ممکن نیست.");
                if(target.exists())throw new IOException("نام تکراری در آرشیو وجود دارد.");
                try(OutputStream out=new FileOutputStream(target)){int nread;while((nread=in.read(buf))!=-1){expanded+=nread;if(expanded>MAX_UNPACKED)throw new IOException("حجم استخراج‌شده بیش از یک گیگابایت است.");out.write(buf,0,nread);}}
                in.closeEntry();
            }
        }
        if(count==0)throw new IOException("آرشیو خالی یا نامعتبر است.");
    }
    static String extensions(String console) {
        switch(console){case "gb":return "gb";case "gbc":return "gbc,gb";case "gba":return "gba";case "nes":return "nes";case "snes":return "sfc,smc";case "genesis":return "md,gen,smd,bin";case "n64":return "z64,n64,v64";case "ps1":return "m3u,cue,chd,pbp,bin";case "psp":return "iso,cso,chd";default:return "zip";}
    }
    // Manual import is intentionally restricted to the bundled cores. A .bin is
    // treated as a Mega Drive cartridge; PS1 disc images must use the catalog.
    static String importConsole(String name) {
        String n=name.toLowerCase(Locale.US);
        if(n.endsWith(".gb"))return "gb";if(n.endsWith(".gbc"))return "gbc";if(n.endsWith(".gba"))return "gba";
        if(n.endsWith(".nes"))return "nes";
        if(n.endsWith(".md")||n.endsWith(".gen")||n.endsWith(".smd")||n.endsWith(".bin"))return "genesis";
        return "";
    }
    static File chooseGame(File root,String console) throws IOException {return chooseGame(root,console,"");}
    static File chooseGame(File root,String console,String configuredExtensions) throws IOException {
        List<File> files=new ArrayList<>();collect(root,files);
        String configured=configuredExtensions==null?"":configuredExtensions.trim();
        String[] order=(configured.isEmpty()?extensions(console):configured).toLowerCase(Locale.US).split(",");
        for(String raw:order){
            String ext=raw.trim().replaceFirst("^\\.","");
            if(!ext.matches("[a-z0-9]{1,12}")||ext.equals("7z")||ext.equals("rar")||(ext.equals("zip")&&!archiveIsGame(console)))continue;
            for(File f:files)if(f.getName().toLowerCase(Locale.US).endsWith("."+ext))return f;
        }
        throw new IOException("فایل بازی سازگار در بسته پیدا نشد؛ پسوندهای کنسول را بررسی کن.");
    }
    static void collect(File dir,List<File> files) throws IOException {
        File[] entries=dir.listFiles();if(entries==null)throw new IOException("پوشهٔ بازی خوانده نشد.");
        Arrays.sort(entries,Comparator.comparing(File::getName));
        for(File f:entries){if(f.isDirectory())collect(f,files);else if(f.isFile())files.add(f);}
    }
    static void deleteTree(File f) { if(f==null||!f.exists())return; File[] files=f.listFiles();if(files!=null)for(File x:files)deleteTree(x);f.delete(); }
    private FileOps(){}
}
