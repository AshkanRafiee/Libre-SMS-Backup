package com.ashkanrafiee.libresmsbackup;

import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

/** Verifies that the hero/content selection is persisted for every backup entry point. */
public class BackupOptionsTest extends TestCase {
    public void testEncryptionIsOffUntilExplicitlyEnabled() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("backup_options", android.content.Context.MODE_PRIVATE);
        boolean hadValue = preferences.contains("encrypted");
        boolean oldValue = BackupOptions.encrypted(context);
        try {
            preferences.edit().remove("encrypted").commit();
            assertFalse(BackupOptions.encrypted(context));
            BackupOptions.setEncrypted(context, true);
            assertTrue(BackupOptions.encrypted(context));
        } finally {
            if (hadValue) preferences.edit().putBoolean("encrypted", oldValue).commit();
            else preferences.edit().remove("encrypted").commit();
        }
    }

    public void testSelectedScopesPersist() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean oldSms = BackupOptions.includeSms(context);
        boolean oldCalls = BackupOptions.includeCalls(context);
        try {
            BackupOptions.setIncludeSms(context, false);
            BackupOptions.setIncludeCalls(context, true);
            assertFalse(BackupOptions.includeSms(context));
            assertTrue(BackupOptions.includeCalls(context));
        } finally {
            BackupOptions.setIncludeSms(context, oldSms);
            BackupOptions.setIncludeCalls(context, oldCalls);
        }
    }
}
