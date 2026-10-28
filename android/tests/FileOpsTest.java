package com.amretro.mobile;
import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

public class FileOpsTest {
    static final byte[] HTTP_DATA="gba-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static String lastRange;
    static final class FakeHttps extends HttpURLConnection {
        private String range;
        FakeHttps(URL u){super(u);}
        @Override public void setRequestProperty(String key,String value){if("Range".equals(key))range=value;}
        private int offset(){return range==null?0:Integer.parseInt(range.substring(6,range.length()-1));}
        @Override public int getResponseCode(){lastRange=range;return range==null?200:(offset()>=HTTP_DATA.length?416:206);}
        @Override public String getHeaderField(String key){return "Content-Range".equals(key)&&range!=null?"bytes "+offset()+"-"+(HTTP_DATA.length-1)+"/"+HTTP_DATA.length:null;}
        @Override public long getContentLengthLong(){return HTTP_DATA.length-offset();}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(HTTP_DATA,offset(),HTTP_DATA.length-offset());}
        @Override public void disconnect(){} @Override public boolean usingProxy(){return false;} @Override public void connect(){}
    }
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static void zip(File f,String path,String data)throws Exception{try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){z.putNextEntry(new ZipEntry(path));z.write(data.getBytes("UTF-8"));z.closeEntry();}}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("amretro-android-files").toFile();
        try{
            check(FileOps.safeName("../../hello.zip").equals("hello.zip"),"safe name");
            try{FileOps.child(root,"../escape");throw new AssertionError("traversal accepted");}catch(IOException good){}
            File good=new File(root,"good.zip");zip(good,"folder/game.gba","gba-test");File dest=new File(root,"install");FileOps.unzip(good,dest);
            check(FileOps.chooseGame(dest,"gba").getName().equals("game.gba"),"select game");
            File bad=new File(root,"bad.zip");zip(bad,"../escape.gb","bad");try{FileOps.unzip(bad,new File(root,"bad"));throw new AssertionError("zip slip accepted");}catch(IOException expected){}
            check(!new File(root,"escape.gb").exists(),"escaped file");
            File data=new File(dest,"folder/game.gba");String hash=FileOps.sha256(data);FileOps.checkHash(data,hash);
            try{FileOps.checkHash(data,"0".repeat(64));throw new AssertionError("hash mismatch accepted");}catch(IOException expected){}
            short[] samples=new short[131072*2];for(int i=0;i<samples.length;i++)samples[i]=(short)i;
            StereoResampler whole=new StereoResampler(131072,48000);short[] a=whole.convert(samples);check(a.length==96000,"output rate");
            StereoResampler partial=new StereoResampler(131072,48000);short[] one=partial.convert(Arrays.copyOfRange(samples,0,20000)),two=partial.convert(Arrays.copyOfRange(samples,20000,samples.length));
            short[] joined=new short[one.length+two.length];System.arraycopy(one,0,joined,0,one.length);System.arraycopy(two,0,joined,one.length,two.length);check(Arrays.equals(a,joined),"stream phase");
            try{FileOps.download("http://example.com/test",new File(root,"a.part"),"",null);throw new AssertionError("insecure URL accepted");}catch(IOException expected){}
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){@Override protected URLConnection openConnection(URL u){return new FakeHttps(u);}}:null);
            File complete=new File(root,"expected.bin");Files.write(complete.toPath(),HTTP_DATA);String expectedHash=FileOps.sha256(complete);
            File resume=new File(root,"resume.part");Files.write(resume.toPath(),Arrays.copyOf(HTTP_DATA,3));
            FileOps.download("https://fixture.test/game",resume,expectedHash,null);
            check("bytes=3-".equals(lastRange)&&Arrays.equals(Files.readAllBytes(resume.toPath()),HTTP_DATA),"verified resume");
            File restart=new File(root,"restart.part");Files.write(restart.toPath(),new byte[]{99,99,99});
            FileOps.download("https://fixture.test/game",restart,"",null);
            check(lastRange==null&&Arrays.equals(Files.readAllBytes(restart.toPath()),HTTP_DATA),"unverified partial restarted");
            File stale=new File(root,"stale.part");Files.write(stale.toPath(),new byte[HTTP_DATA.length+1]);
            FileOps.download("https://fixture.test/game",stale,expectedHash,null);
            check(lastRange==null&&Arrays.equals(Files.readAllBytes(stale.toPath()),HTTP_DATA),"HTTP 416 restarted");
            File done=new File(root,"complete.part");Files.write(done.toPath(),HTTP_DATA);lastRange="not-requested";
            FileOps.download("https://fixture.test/game",done,expectedHash,null);check("not-requested".equals(lastRange),"verified complete file reused");
            try{FileOps.download("http://fixture.test/game",done,expectedHash,null);throw new AssertionError("insecure URL accepted for complete file");}catch(IOException expected){}
            System.out.println("FILEOPS_TEST_OK zip-slip, checksum, HTTPS, resume/restart/416, complete reuse, resampler phase");
        }finally{FileOps.deleteTree(root);}
    }
}
