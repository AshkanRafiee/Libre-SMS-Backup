package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

/** Verifies diagnostic reports stay allow-listed and the app does not request contacts access. */
public class ReportDiagnosticsTest extends TestCase {
    private static final String TEST_PREFS_PREFIX = "report_diagnostics_test_";
    private static final String LAST_RESTORE_ERROR = "last_restore_error";

    public void testDiagnosticReportContainsOnlyTechnicalFields() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String content = ReportDiagnostics.create(context);
        assertTrue(content.contains("report_format=1"));
        assertTrue(content.contains("app_version="));
        assertTrue(content.contains("android_version="));
        assertTrue(content.contains("android_api="));
        assertTrue(content.contains("device="));
        assertTrue(content.contains("rom="));
        assertTrue(content.contains("build_number="));
        assertTrue(content.contains("build_id="));
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

    public void testDiagnosticReportTracksAndClearsRestoreErrors() {
        Context context = diagnosticsTestContext();
        try {
            ReportDiagnostics.clearRestoreError(context);
            assertRestoreError(context, "none");

            assertRecordedRestoreError(context, ReportDiagnostics.RESTORE_AUTHENTICATION_FAILED,
                    "authentication_failed");
            assertRecordedRestoreError(context, ReportDiagnostics.RESTORE_INSPECTION_FAILED,
                    "inspection_failed");
            assertRecordedRestoreError(context, ReportDiagnostics.RESTORE_EXECUTION_FAILED,
                    "execution_failed");
            assertRecordedRestoreError(context, ReportDiagnostics.RESTORE_RECORDS_FAILED,
                    "records_failed");

            ReportDiagnostics.clearRestoreError(context);
            assertFalse(ScheduleManager.prefs(context).contains(LAST_RESTORE_ERROR));
            assertRestoreError(context, "none");
        } finally {
            ReportDiagnostics.clearRestoreError(context);
        }
    }

    public void testDiagnosticReportNeverUsesUnrecognizedRestoreErrors() {
        Context context = diagnosticsTestContext();
        SharedPreferences prefs = ScheduleManager.prefs(context);
        String rawError = "IOException: content://example/backup.lsb\ninjected=value";
        try {
            ReportDiagnostics.clearRestoreError(context);
            ReportDiagnostics.recordRestoreError(context, rawError);
            assertFalse(prefs.contains(LAST_RESTORE_ERROR));
            assertRestoreError(context, "none");

            ReportDiagnostics.recordRestoreError(context, ReportDiagnostics.RESTORE_INSPECTION_FAILED);
            ReportDiagnostics.recordRestoreError(context, "inspection_failed\ninjected=value");
            assertEquals("inspection_failed", prefs.getString(LAST_RESTORE_ERROR, null));
            assertRestoreError(context, "inspection_failed");

            prefs.edit().putString(LAST_RESTORE_ERROR, rawError).apply();
            assertRestoreError(context, "none");
            String report = ReportDiagnostics.create(context);
            assertFalse(report.contains("content://example/backup.lsb"));
            assertFalse(report.contains("injected=value"));
            assertFalse(report.contains("IOException"));

            prefs.edit().putInt(LAST_RESTORE_ERROR, 42).apply();
            assertRestoreError(context, "none");
        } finally {
            ReportDiagnostics.clearRestoreError(context);
        }
    }

    private static void assertRecordedRestoreError(Context context, String code, String expected) {
        ReportDiagnostics.recordRestoreError(context, code);
        assertEquals(expected, ScheduleManager.prefs(context).getString(LAST_RESTORE_ERROR, null));
        assertRestoreError(context, expected);
    }

    private static void assertRestoreError(Context context, String expected) {
        String report = ReportDiagnostics.create(context);
        assertTrue(report.contains("\n" + LAST_RESTORE_ERROR + "=" + expected + "\n"));
    }

    private static Context diagnosticsTestContext() {
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(TEST_PREFS_PREFIX + name, mode);
            }
        };
    }
}
