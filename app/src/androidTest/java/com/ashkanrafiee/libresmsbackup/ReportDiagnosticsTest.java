package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

/** Verifies diagnostic reports stay allow-listed and the app does not request contacts access. */
public class ReportDiagnosticsTest extends TestCase {
    public void testDiagnosticReportContainsOnlyTechnicalFields() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String content = ReportDiagnostics.create(context);
        assertTrue(content.contains("report_format=1"));
        assertTrue(content.contains("app_version="));
        assertTrue(content.contains("android_api="));
        assertFalse(content.contains("content://"));
        assertFalse(content.contains("/data/"));
        assertFalse(content.contains("READ_CONTACTS"));
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

    public void testReportTemplateHasFillInSections() {
        String body = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(R.string.report_issue_body);
        assertTrue(body.contains("\n\n"));
        assertTrue(body.contains("Problem description:"));
        assertTrue(body.contains("Steps to reproduce:"));
    }
}
