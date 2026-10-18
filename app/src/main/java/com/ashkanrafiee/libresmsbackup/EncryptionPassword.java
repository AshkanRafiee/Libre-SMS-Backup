package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-wrapped password shared by manual and scheduled encrypted backups. */
final class EncryptionPassword {
    private static final String KEY_ALIAS = "libresmsbackup.manual.password.v1";
    private static final String PREFS = "backup_options";
    private static final String VALUE = "manual_password";
    private static final int TAG_BITS = 128;

    private EncryptionPassword() {}

    static void save(Context context, char[] password) throws GeneralSecurityException {
        byte[] plain = new String(password).getBytes(StandardCharsets.UTF_8);
        byte[] iv = null;
        byte[] ciphertext = null;
        byte[] packed = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            iv = cipher.getIV();
            ciphertext = cipher.doFinal(plain);
            packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            prefs(context).edit().putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            if (packed != null) Arrays.fill(packed, (byte) 0);
        }
    }

    static char[] load(Context context) throws GeneralSecurityException {
        String encoded = prefs(context).getString(VALUE, null);
        if (encoded == null) return null;
        byte[] packed = Base64.decode(encoded, Base64.DEFAULT);
        if (packed.length < 13) throw new GeneralSecurityException("invalid saved password");
        byte[] iv = Arrays.copyOfRange(packed, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(packed, 12, packed.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            try { return new String(plain, StandardCharsets.UTF_8).toCharArray(); }
            finally { Arrays.fill(plain, (byte) 0); }
        } finally {
            Arrays.fill(packed, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    static void clear(Context context) { prefs(context).edit().remove(VALUE).apply(); }

    static boolean isUsable(Context context) {
        if (!prefs(context).contains(VALUE)) return false;
        char[] password = null;
        try {
            password = load(context);
            return password != null && password.length > 0;
        } catch (GeneralSecurityException ignored) {
            return false;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try { store.load(null); } catch (java.io.IOException e) { throw new GeneralSecurityException(e); }
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
