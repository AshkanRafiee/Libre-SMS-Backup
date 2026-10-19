package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.os.Build;

import java.util.Locale;

/** Creates small allow-listed diagnostics without reading phone records or paths. */
final class ReportDiagnostics {
    private ReportDiagnostics() {}

    static String create(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("Libre SMS Backup diagnostic report\n");
        report.append("report_format=1\n");
        report.append("app_version=").append(safe(BuildConfig.VERSION_NAME)).append('\n');
        report.append("android_api=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("language=").append(safe(Locale.getDefault().toLanguageTag())).append('\n');
        report.append("sms_scope=").append(BackupOptions.includeSms(context)).append('\n');
        report.append("call_scope=").append(BackupOptions.includeCalls(context)).append('\n');
        report.append("encrypted_backups=").append(BackupOptions.encrypted(context)).append('\n');
        report.append("scheduled_backups=").append(ScheduleManager.enabled(context)).append('\n');
        report.append("last_scheduled_error=")
                .append(safe(ScheduleManager.prefs(context).getString("last_error", ""))).append('\n');
        report.append("last_manual_error=")
                .append(safe(ScheduleManager.prefs(context).getString("manual_last_error", ""))).append('\n');
        return report.toString();
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "none";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned.isEmpty() ? "none" : cleaned;
    }
}
