package com.sxrong.smsforwarder;

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

final class Settings {
    private final SharedPreferences prefs;
    Settings(Context context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    void enabled(boolean value) { if(enabled()==value) return; prefs.edit().putBoolean("enabled", value).apply(); AppChanges.publish(); }
    String chat() { return prefs.getString("chat", ""); }
    String chatName() { return prefs.getString("chat_name", ""); }
    String chatDisplay() { return chatName().isEmpty()?chat():chatName()+"("+chat()+")"; }
    boolean configured() { return !prefs.getString("token", "").isEmpty() && !chat().isEmpty(); }
    boolean backgroundReviewed() { return prefs.getBoolean("background_reviewed", false); }
    void backgroundReviewed(boolean value) { if(backgroundReviewed()==value) return; prefs.edit().putBoolean("background_reviewed", value).apply(); AppChanges.publish(); }
    boolean smsRequested() { return prefs.getBoolean("sms_requested", false); }
    void smsRequested(boolean value) { prefs.edit().putBoolean("sms_requested", value).apply(); }
    String status() { return prefs.getString("status", "暂无发送记录"); }
    void status(String value) { if(status().equals(value)) return; prefs.edit().putString("status", value).apply(); AppChanges.publish(); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("telegram-token")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("telegram-token", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey("telegram-token", null);
    }
    String token() throws Exception {
        String encrypted = prefs.getString("token", "");
        if (encrypted.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    void save(String token, String chat, String chatName) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("token", encrypted).putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("chat", chat).putString("chat_name", chatName).commit()) throw new IllegalStateException("无法保存配置");
        AppChanges.publish();
    }
}
