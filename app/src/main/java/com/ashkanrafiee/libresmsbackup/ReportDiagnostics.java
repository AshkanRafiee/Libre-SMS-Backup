package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.os.Build;

import java.util.Locale;

/** Creates small allow-listed diagnostics without reading phone records or paths. */
final class ReportDiagnostics {
    static final String RESTORE_AUTHENTICATION_FAILED = "authentication_failed";
    static final String RESTORE_INSPECTION_FAILED = "inspection_failed";
    static final String RESTORE_ARCHIVE_TOO_LARGE = "archive_too_large";
    static final String RESTORE_ENCRYPTED_ARCHIVE_TOO_LARGE = "encrypted_archive_too_large";
    static final String RESTORE_EXECUTION_FAILED = "execution_failed";
    static final String RESTORE_RECORDS_FAILED = "records_failed";
    private static final String LAST_RESTORE_ERROR = "last_restore_error";

    private ReportDiagnostics() {}

    static void recordRestoreError(Context context, String code) {
        if (isRestoreErrorCode(code)) {
            ScheduleManager.prefs(context).edit().putString(LAST_RESTORE_ERROR, code).apply();
        }
    }

    static void clearRestoreError(Context context) {
        ScheduleManager.prefs(context).edit().remove(LAST_RESTORE_ERROR).apply();
    }

    static String create(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("Libre SMS Backup diagnostic report\n");
        report.append("report_format=1\n");
        report.append("app_version=").append(safe(BuildConfig.VERSION_NAME)).append('\n');
        report.append("android_version=").append(safe(Build.VERSION.RELEASE)).append('\n');
        report.append("android_api=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("device=").append(safe(deviceName())).append('\n');
        report.append("rom=").append(safe(romName())).append('\n');
        report.append("build_number=").append(safe(Build.DISPLAY)).append('\n');
        report.append("build_id=").append(safe(Build.ID)).append('\n');
        report.append("language=").append(safe(Locale.getDefault().toLanguageTag())).append('\n');
        report.append("sms_scope=").append(BackupOptions.includeSms(context)).append('\n');
        report.append("call_scope=").append(BackupOptions.includeCalls(context)).append('\n');
        report.append("encrypted_backups=").append(BackupOptions.encrypted(context)).append('\n');
        report.append("scheduled_backups=").append(ScheduleManager.enabled(context)).append('\n');
        report.append("last_scheduled_error=")
                .append(safe(ScheduleManager.prefs(context).getString("last_error", ""))).append('\n');
        report.append("last_manual_error=")
                .append(safe(ScheduleManager.prefs(context).getString("manual_last_error", ""))).append('\n');
        report.append(LAST_RESTORE_ERROR).append('=').append(lastRestoreError(context)).append('\n');
        return report.toString();
    }

    private static String lastRestoreError(Context context) {
        try {
            String code = ScheduleManager.prefs(context).getString(LAST_RESTORE_ERROR, null);
            if (isRestoreErrorCode(code)) return code;
        } catch (ClassCastException ignored) {
            // A value of the wrong preference type is not a restore status.
        }
        return "none";
    }

    private static boolean isRestoreErrorCode(String code) {
        return RESTORE_AUTHENTICATION_FAILED.equals(code)
                || RESTORE_INSPECTION_FAILED.equals(code)
                || RESTORE_ARCHIVE_TOO_LARGE.equals(code)
                || RESTORE_ENCRYPTED_ARCHIVE_TOO_LARGE.equals(code)
                || RESTORE_EXECUTION_FAILED.equals(code)
                || RESTORE_RECORDS_FAILED.equals(code);
    }

    private static String deviceName() {
        return ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " "
                + (Build.MODEL == null ? "" : Build.MODEL)).trim();
    }

    private static String romName() {
        String[][] properties = {
                {"ro.mi.os.version.name", "HyperOS"},
                {"ro.miui.ui.version.name", "MIUI"},
                {"ro.build.version.emui", "EMUI/HarmonyOS"},
                {"ro.build.version.oneui", "One UI"},
                {"ro.vendor.build.version.sem_oneui", "One UI"},
                {"ro.oxygen.version", "OxygenOS"},
                {"ro.build.version.oplusrom", "ColorOS"},
                {"ro.build.version.coloros", "ColorOS"},
                {"ro.build.version.realmeui", "Realme UI"},
                {"ro.xos.version", "XOS"},
                {"ro.hios.version", "HiOS"},
                {"ro.vivo.os.build.display.id", "FunTouch OS"},
                {"ro.lineage.version", "LineageOS"}
        };
        for (String[] property : properties) {
            String value = systemProperty(property[0]);
            if (value != null && !value.trim().isEmpty()) return property[1] + " " + value.trim();
        }
        return "unknown";
    }

    private static String systemProperty(String name) {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class);
            return (String) get.invoke(null, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "none";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned.isEmpty() ? "none" : cleaned;
    }
}
