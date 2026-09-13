package com.example.xunleivrplayer;

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

/** 用 Android Keystore 对 refresh_token 做本机加密保存；用户密码永不落盘。 */
class SecureStore {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "xunlei_vr_bridge_key";
    private final SharedPreferences p;

    SecureStore(Context c) { p = c.getSharedPreferences("bridge", Context.MODE_PRIVATE); }

    void putPlain(String key, String value) { p.edit().putString(key, value == null ? "" : value).apply(); }
    String getPlain(String key, String def) { return p.getString(key, def); }

    void putSecret(String key, String value) throws Exception {
        SecretKey k = key();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] enc = c.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        String blob = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
        p.edit().putString(key, blob).apply();
    }

    String getSecret(String key) {
        try {
            String blob = p.getString(key, "");
            if (blob == null || !blob.contains(":")) return "";
            String[] x = blob.split(":", 2);
            byte[] iv = Base64.decode(x[0], Base64.NO_WRAP);
            byte[] enc = Base64.decode(x[1], Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    void clearLogin() { p.edit().remove("refresh").remove("username").remove("deviceId").apply(); }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }
}
