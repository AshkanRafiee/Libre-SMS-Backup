package com.ashkanrafiee.libresmsbackup;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.IOException;

/** Detects Android builds that hide SMS rows from non-default SMS apps. */
public final class SmsAccess {
    private static final String RESTRICTED_MESSAGES_OP = "android:read_restricted_messages";

    private SmsAccess() {}

    public static boolean isRestricted(Context context) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.unsafeCheckOpNoThrow(RESTRICTED_MESSAGES_OP,
                    Process.myUid(), context.getPackageName());
            return mode != AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException ignored) {
            // Older/OEM builds may not expose this diagnostic app-op.
            return false;
        }
    }

    public static void requireReadable(Context context) throws RestrictedException {
        if (isRestricted(context)) throw new RestrictedException();
    }

    public static final class RestrictedException extends IOException {
        RestrictedException() { super("restricted SMS access"); }
    }
}
