package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import junit.framework.TestCase;

/** Verifies diagnostic reports stay allow-listed and the app does not request contacts access. */
public class ReportDiagnosticsTest extends TestCase {
    public void testDiagnosticReportContainsOnlyTechnicalFields() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File report = ReportDiagnostics.create(context);
        try {
            String content = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
            assertTrue(content.contains("report_format=1"));
            assertTrue(content.contains("app_version="));
            assertTrue(content.contains("android_api="));
            assertFalse(content.contains("content://"));
            assertFalse(content.contains("/data/"));
            assertFalse(content.contains("READ_CONTACTS"));
        } finally {
            assertTrue(report.delete());
        }
    }

    public void testContactsPermissionIsNotRequested() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_PERMISSIONS);
        if (info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) {
                assertFalse("contacts permission must not be requested",
                        "android.permission.READ_CONTACTS".equals(permission));
            }
        }
    }
}
