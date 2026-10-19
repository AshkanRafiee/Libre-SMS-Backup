package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Creates a small allow-listed diagnostic file without reading phone records or paths. */
final class ReportDiagnostics {
    private static final String DIRECTORY = "reports";
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private ReportDiagnostics() {}

    static File create(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("cannot create report cache");
        removeOldReports(directory);
        File report = new File(directory, "report-" + UUID.randomUUID() + ".txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8)) {
            writer.write("Libre SMS Backup diagnostic report\n");
            writer.write("report_format=1\n");
            writer.write("app_version=" + safe(BuildConfig.VERSION_NAME) + "\n");
            writer.write("android_api=" + Build.VERSION.SDK_INT + "\n");
            writer.write("language=" + safe(Locale.getDefault().toLanguageTag()) + "\n");
            writer.write("sms_scope=" + BackupOptions.includeSms(context) + "\n");
            writer.write("call_scope=" + BackupOptions.includeCalls(context) + "\n");
            writer.write("encrypted_backups=" + BackupOptions.encrypted(context) + "\n");
            writer.write("scheduled_backups=" + ScheduleManager.enabled(context) + "\n");
            writer.write("last_scheduled_error=" + safe(ScheduleManager.prefs(context).getString("last_error", "")) + "\n");
            writer.write("last_manual_error=" + safe(ScheduleManager.prefs(context).getString("manual_last_error", "")) + "\n");
        }
        return report;
    }

    private static void removeOldReports(File directory) {
        File[] files = directory.listFiles((file, name) -> name.startsWith("report-") && name.endsWith(".txt"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (File file : files) if (file.lastModified() < cutoff) file.delete();
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "none";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned.isEmpty() ? "none" : cleaned;
    }
}
