package com.amretro.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** RetroAchievements web keys stay in private preferences, encrypted by Android Keystore. */
final class SecretBox {
    private static final String ALIAS="AMretro_RA_WebKey";
    private final SharedPreferences prefs;
    SecretBox(Context c) { prefs=c.getSharedPreferences("private-settings",Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            g.generateKey();
        }
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    void save(String user,String secret) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        String value=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(secret.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        prefs.edit().putString("ra_user",user).putString("ra_key",value).apply();
    }
    String username(){ return prefs.getString("ra_user",""); }
    boolean hasKey(){ return !prefs.getString("ra_key","").isEmpty(); }
    String secret() throws Exception {
        String value=prefs.getString("ra_key","");String[] p=value.split(":",2);if(p.length!=2)return "";
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    void clear(){prefs.edit().remove("ra_user").remove("ra_key").apply();}
}
