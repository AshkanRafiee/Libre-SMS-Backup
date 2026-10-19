package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

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

    public void testReportAttachmentIsReadableOnlyThroughItsContentUri() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File report = ReportDiagnostics.create(context);
        ReportFileProvider provider = new ReportFileProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = context.getPackageName() + ".report-files";
        provider.attachInfo(context, info);
        Uri uri = ReportFileProvider.uriFor(report, info.authority);
        try {
            try (ParcelFileDescriptor descriptor = provider.openFile(uri, "r")) {
                assertNotNull(descriptor);
            }
            try {
                provider.openFile(uri, "w");
                fail("report attachment must be read-only");
            } catch (java.io.FileNotFoundException expected) { }
        } finally {
            report.delete();
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
