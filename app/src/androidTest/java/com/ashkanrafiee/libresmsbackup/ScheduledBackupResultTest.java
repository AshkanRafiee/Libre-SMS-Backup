package com.ashkanrafiee.libresmsbackup;

import android.app.NotificationManager;
import android.app.Notification;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.service.notification.StatusBarNotification;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;

import junit.framework.TestCase;

public class ScheduledBackupResultTest extends TestCase {
    public void testScheduledLimitFailureAndOmissionsSurviveBlockedNotifications() throws Exception {
        exerciseSchedule(false);
    }

    public void testScheduledLimitFailureAndOmissionsPostResultNotifications() throws Exception {
        exerciseSchedule(true);
    }

    private static void exerciseSchedule(boolean notifications) throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NotificationManager manager = notifications ? target.getSystemService(NotificationManager.class) : null;
        if (notifications && !manager.areNotificationsEnabled()) return;
        File file = File.createTempFile("scheduled-notify-", ".lsb", target.getCacheDir());
        String prefix = file.getName();
        TestProvider provider = new TestProvider(file);
        ContentResolver resolver = ContentResolver.wrap(provider);
        Context context = new ContextWrapper(target) {
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
            @Override public int checkSelfPermission(String permission) {
                return PackageManager.PERMISSION_GRANTED;
            }
            @Override public Object getSystemService(String name) {
                return Context.NOTIFICATION_SERVICE.equals(name) ? manager : null;
            }
        };
        try {
            exerciseSchedule(context, provider, manager);
        } finally {
            if (manager != null) manager.cancel(402);
            target.deleteSharedPreferences(prefix + "scheduled_backup");
            target.deleteSharedPreferences(prefix + "backup_options");
            file.delete();
        }
    }

    private static void exerciseSchedule(Context context, TestProvider provider, NotificationManager manager) {
        SharedPreferences schedule = ScheduleManager.prefs(context);
        schedule.edit().putBoolean("enabled", true).apply(); // Keep mode off so the test does not arm an alarm.
        ScheduleManager.saveFolder(context, Uri.parse("content://test.backups/tree/root"));
        BackupOptions.setIncludeCalls(context, false);
        provider.wideRecord = true;
        ScheduledBackupReceiver.runOnce(context);
        String failure = context.getString(R.string.backup_error_metadata_limit);
        assertEquals(failure, schedule.getString("last_error", null));
        assertFalse(schedule.contains("last_success"));
        assertEquals(0, provider.published);
        assertEquals(1, provider.deleted);
        if (manager != null) assertPosted(manager, context.getString(R.string.notification_backup_failed_title), failure);

        provider.wideRecord = false;
        provider.missingAttachment = true;
        ScheduledBackupReceiver.runOnce(context);
        assertFalse(schedule.contains("last_error"));
        assertTrue(schedule.contains("last_success"));
        assertEquals(1, schedule.getInt("last_omitted_attachments", -1));
        assertEquals(1, provider.published);
        if (manager != null) assertPosted(manager, context.getString(R.string.backup_saved_with_omissions),
                BackupSummaryFormatter.omittedAttachments(context, 1));

        provider.missingAttachment = false;
        ScheduledBackupReceiver.runOnce(context);
        assertEquals(0, schedule.getInt("last_omitted_attachments", -1));
        assertEquals(2, provider.published);
    }

    private static void assertPosted(NotificationManager manager, String title, String message) {
        // Posting is asynchronous; transact with the service until it reports the matching result.
        long deadline = android.os.SystemClock.elapsedRealtime() + 2500;
        do {
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                Bundle extras = active.getNotification().extras;
                if (active.getId() == 402 && title.equals(String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE)))
                        && String.valueOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).contains(message)) return;
            }
            android.os.SystemClock.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        fail("scheduled backup notification did not contain the expected result");
    }

    private static final class TestProvider extends ContentProvider {
        private static final Uri PARTIAL = Uri.parse("content://test.backups/tree/root/document/partial");
        private final File file;
        private String partialName;
        boolean wideRecord;
        boolean missingAttachment;
        int published;
        int deleted;

        TestProvider(File file) { this.file = file; }

        @Override public boolean onCreate() { return true; }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            Bundle result = new Bundle();
            if ("android:createDocument".equals(method)) {
                partialName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                result.putParcelable("uri", PARTIAL);
            } else if ("android:renameDocument".equals(method)) {
                published++;
                result.putParcelable("uri", Uri.parse("content://test.backups/tree/root/document/saved"));
            }
            return result;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (PARTIAL.equals(uri)) return row(projection, partialName);
            if ("test.backups".equals(uri.getAuthority())) return new MatrixCursor(projection);
            switch (uri.toString()) {
                case "content://sms":
                    if (!wideRecord) return new MatrixCursor(new String[]{"body"});
                    char[] body = new char[4 * 1024 * 1024];
                    Arrays.fill(body, 'a');
                    return row(new String[]{"body"}, new String(body));
                case "content://mms":
                    return missingAttachment ? row(new String[]{"_id"}, 1L) : new MatrixCursor(new String[]{"_id"});
                case "content://mms/1/part":
                    return row(new String[]{"_id", "ct"}, 2L, "image/png");
                case "content://mms/1/addr":
                    return new MatrixCursor(new String[]{"address"});
                default: return null;
            }
        }

        private static MatrixCursor row(String[] columns, Object... values) {
            MatrixCursor cursor = new MatrixCursor(columns);
            cursor.addRow(values);
            return cursor;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (PARTIAL.equals(uri)) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
            throw new FileNotFoundException();
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            assertEquals(PARTIAL, uri);
            deleted++;
            return 1;
        }

        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
