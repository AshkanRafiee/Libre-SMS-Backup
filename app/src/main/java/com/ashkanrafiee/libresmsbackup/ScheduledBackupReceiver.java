package com.ashkanrafiee.libresmsbackup;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Performs one scheduled run and re-arms the next one after every outcome. */
public final class ScheduledBackupReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "scheduled_backups";
    static final String EXTRA_NOTIFICATION_ACTION = "notification_action";
    static final String ACTION_FOLDER = "folder";
    static final String ACTION_PERMISSION = "permission";
    static final String ACTION_SMS_ACCESS = "sms_access";
    static final String ACTION_ENCRYPTION = "encryption";
    static final String ACTION_SCOPE = "scope";
    static final String ACTION_RESTRICTED_MESSAGES = "restricted_messages";
    static final String ACTION_EXACT_ALARM = "exact_alarm";
    private static final int NOTIFICATION_RESULT = 402;
    private static final int NOTIFICATION_EXACT_ALARM = 403;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object RUN_LOCK = new Object();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ScheduleManager.ACTION_RUN.equals(intent.getAction())) {
            ScheduleManager.scheduleNext(context.getApplicationContext());
            return;
        }
        final Context app = LocaleHelper.wrap(context.getApplicationContext());
        // Arm the next one-shot alarm before starting work. If the process is
        // killed during provider I/O, the schedule still has a future trigger.
        ScheduleManager.scheduleNext(app);
        ScheduleManager.prefs(app).edit().putLong("last_attempt", System.currentTimeMillis()).commit();
        if (ScheduleManager.enqueueScheduledRun(app)) return;
        runInReceiver(app);
    }

    private void runInReceiver(Context app) {
        final PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (power != null) {
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreSmsBackup:scheduled");
                    wakeLock.acquire(10 * 60 * 1000L);
                }
                runOnce(app);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                pending.finish();
            }
        });
    }

    static void runOnce(Context context) {
        ScheduleManager.prefs(context).edit().putLong("last_attempt", System.currentTimeMillis()).apply();
        Uri destination = null;
        char[] password = null;
        boolean published = false;
        try {
            synchronized (RUN_LOCK) {
                if (cancelled(context)) return;
                boolean includeSms = BackupOptions.includeSms(context);
                boolean includeCalls = BackupOptions.includeCalls(context);
                boolean includeRestrictedMessages = BackupOptions.includeRestrictedMessages(context);
                if (!includeSms && !includeCalls && !includeRestrictedMessages) {
                    fail(context, context.getString(R.string.scheduled_error_scope), ACTION_SCOPE);
                    return;
                }
                if (includeRestrictedMessages) {
                    ScheduleManager.disable(context);
                    fail(context, context.getString(R.string.scheduled_error_restricted_messages), ACTION_RESTRICTED_MESSAGES);
                    return;
                }
                if ((includeSms && context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                        || (includeCalls && context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)) {
                    fail(context, context.getString(R.string.scheduled_error_permission), ACTION_PERMISSION);
                    return;
                }
                if (includeRestrictedMessages && SmsAccess.isRestricted(context)) {
                    fail(context, context.getString(R.string.scheduled_error_sms_restricted), ACTION_SMS_ACCESS);
                    return;
                }
                boolean encrypted = BackupOptions.encrypted(context);
                if (encrypted) {
                    // Scheduled encrypted backups use the same password configured in the
                    // Encryption setting. There is no second schedule-only secret.
                    password = EncryptionPassword.load(context);
                    if (password == null) {
                        fail(context, context.getString(R.string.scheduled_error_password), ACTION_ENCRYPTION);
                        return;
                    }
                }
                destination = ScheduleManager.newBackupFile(context);
                ArchiveFormat.BackupSummary summary = ArchiveFormat.create(context, destination, password, encrypted,
                        includeSms, includeCalls, includeRestrictedMessages);
                if (cancelled(context)) {
                    deleteDestination(context, destination);
                    return;
                }
                destination = ScheduleManager.publishBackupFile(context, destination);
                published = true;
                if (cancelled(context)) return;
                ScheduleManager.prune(context);
                ScheduleManager.prefs(context).edit().putLong("last_success", System.currentTimeMillis()).remove("last_error").apply();
                notifyUser(context, true, BackupSummaryFormatter.format(context, summary), null);
            }
        } catch (Exception error) {
            if (!published) deleteDestination(context, destination);
            if (cancelled(context)) return;
            if (error instanceof SmsAccess.RestrictedException) {
                fail(context, context.getString(R.string.scheduled_error_sms_restricted), ACTION_SMS_ACCESS);
            } else {
                String detail = error.getMessage();
                String action = detail != null && (detail.contains("folder") || detail.contains("destination"))
                        ? ACTION_FOLDER : null;
                fail(context, context.getString(R.string.notification_backup_failed_body), action);
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            ScheduleManager.scheduleNext(context);
        }
    }

    private static boolean cancelled(Context context) {
        return Thread.currentThread().isInterrupted() || !ScheduleManager.enabled(context);
    }

    private static void deleteDestination(Context context, Uri destination) {
        if (destination != null) try { context.getContentResolver().delete(destination, null, null); } catch (Exception ignored) { }
    }

    private static void fail(Context context, String message, String action) {
        ScheduleManager.prefs(context).edit().putString("last_error", message).apply();
        notifyUser(context, false, message, action);
    }

    private static void notifyUser(Context context, boolean success, String message, String action) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        createChannel(context, manager);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(context, MainActivity.class);
        if (action != null) open.putExtra(EXTRA_NOTIFICATION_ACTION, action);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, CHANNEL)
                : new android.app.Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(success
                ? R.string.notification_backup_complete_title : R.string.notification_backup_failed_title))
                .setContentText(message).setAutoCancel(true).setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_RESULT,
                        open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.notify(NOTIFICATION_RESULT, builder.build());
    }

    static void notifyExactAlarmNeeded(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        createChannel(context, manager);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent settings = new Intent(context, MainActivity.class).putExtra(EXTRA_NOTIFICATION_ACTION, ACTION_EXACT_ALARM);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, CHANNEL) : new android.app.Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_backup_failed_title))
                .setContentText(context.getString(R.string.schedule_exact_needed))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_EXACT_ALARM, settings,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.notify(NOTIFICATION_EXACT_ALARM, builder.build());
    }

    static void cancelExactAlarmNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_EXACT_ALARM);
    }

    private static void createChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT));
    }
}
