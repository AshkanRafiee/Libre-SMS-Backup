package com.ashkanrafiee.libresmsbackup;

import android.app.Notification;
import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

/** Keeps scheduled notifications aligned with the summaries shown after manual backups. */
public class BackupSummaryFormatterTest extends TestCase {
    private Context context;

    @Override protected void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    public void testFormatsSmsAndCallsWhenNoMmsOrRcsWereFound() {
        assertEquals(context.getString(R.string.backup_summary, 12, 4),
                BackupSummaryFormatter.format(context, 12, 4, 0, 0));
    }

    public void testIncludesMmsAndRcsWhenTheyWereFound() {
        assertEquals(context.getString(R.string.backup_summary_extended, 12, 4, 2, 1),
                BackupSummaryFormatter.format(context, 12, 4, 2, 1));
    }

    public void testScheduledResultNotificationsKeepLimitAndOmissionDetailsExpandable() {
        String limit = context.getString(R.string.backup_error_metadata_limit);
        Notification failure = ScheduledBackupReceiver.buildResultNotification(context,
                R.string.notification_backup_failed_title, limit, ScheduledBackupReceiver.ACTION_SCOPE);
        assertNotificationText(failure, R.string.notification_backup_failed_title, limit);

        String warning = BackupSummaryFormatter.omittedAttachments(context, 2);
        Notification omitted = ScheduledBackupReceiver.buildResultNotification(context,
                R.string.backup_saved_with_omissions, warning, null);
        assertNotificationText(omitted, R.string.backup_saved_with_omissions, warning);
    }

    private void assertNotificationText(Notification notification, int title, String message) {
        assertEquals(context.getString(title), String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals(message, String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TEXT)));
        assertEquals(message, String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)));
        assertNotNull(notification.contentIntent);
    }
}
