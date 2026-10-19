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
        return report.toString();
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
