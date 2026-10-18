package com.ashkanrafiee.libresmsbackup;

import android.content.Context;

/** Persisted scope and privacy choices used by manual and scheduled backups. */
final class BackupOptions {
    private static final String PREFS = "backup_options";
    private static final String SMS = "include_sms";
    private static final String CALLS = "include_calls";
    private static final String RESTRICTED_MESSAGES = "include_restricted_messages";
    private static final String ENCRYPTED = "encrypted";

    private BackupOptions() {}

    static boolean includeSms(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SMS, true); }
    static boolean includeCalls(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CALLS, true); }
    static boolean includeRestrictedMessages(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(RESTRICTED_MESSAGES, false);
    }
    // Encryption is opt-in. A fresh install must not claim to protect an archive
    // until the user enables it and chooses a password.
    static boolean encrypted(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENCRYPTED, false); }

    static void setIncludeSms(Context context, boolean value) { prefs(context).edit().putBoolean(SMS, value).apply(); }
    static void setIncludeCalls(Context context, boolean value) { prefs(context).edit().putBoolean(CALLS, value).apply(); }
    static void setIncludeRestrictedMessages(Context context, boolean value) {
        prefs(context).edit().putBoolean(RESTRICTED_MESSAGES, value).apply();
        if (value) ScheduleManager.pauseForRestrictedMessages(context);
        else ScheduleManager.restoreAfterRestrictedMessages(context);
    }
    static void setEncrypted(Context context, boolean value) { prefs(context).edit().putBoolean(ENCRYPTED, value).apply(); }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
