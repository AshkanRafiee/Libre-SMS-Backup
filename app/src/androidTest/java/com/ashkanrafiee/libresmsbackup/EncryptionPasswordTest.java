package com.ashkanrafiee.libresmsbackup;

import androidx.test.platform.app.InstrumentationRegistry;

import java.util.Arrays;

import junit.framework.TestCase;

/** Verifies that the shared encryption password is protected by the device Keystore. */
public class EncryptionPasswordTest extends TestCase {
    public void testEncryptionPasswordRoundTrip() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EncryptionPassword.clear(context);
        char[] expected = "shared-encryption-password".toCharArray();
        EncryptionPassword.save(context, expected);
        char[] actual = EncryptionPassword.load(context);
        try { assertTrue(Arrays.equals(expected, actual)); }
        finally { Arrays.fill(expected, '\0'); if (actual != null) Arrays.fill(actual, '\0'); EncryptionPassword.clear(context); BackupOptions.setEncrypted(context, false); }
    }

    public void testManualEncryptionPasswordRoundTrip() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EncryptionPassword.clear(context);
        char[] expected = "manual-test-password".toCharArray();
        EncryptionPassword.save(context, expected);
        char[] actual = EncryptionPassword.load(context);
        try { assertTrue(Arrays.equals(expected, actual)); }
        finally { Arrays.fill(expected, '\0'); if (actual != null) Arrays.fill(actual, '\0'); EncryptionPassword.clear(context); BackupOptions.setEncrypted(context, false); }
    }
}
