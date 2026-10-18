package com.ashkanrafiee.libresmsbackup;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import com.ashkanrafiee.libresmsbackup.retention.RetentionDecider;
import com.ashkanrafiee.libresmsbackup.retention.RetentionPolicy;
import com.ashkanrafiee.libresmsbackup.retention.StoredBackup;
import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;

import java.io.IOException;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** SAF folder, calendar calculation, alarm, and retention policy for scheduled backups. */
final class ScheduleManager {
    static final String ACTION_RUN = "com.ashkanrafiee.libresmsbackup.RUN_SCHEDULED_BACKUP";
    static final int SCHEDULED_JOB_ID = 4817;
    static final int OFF = 0, DAILY = 1, WEEKLY = 2, MONTHLY = 3;
    private static final String PREFS = "scheduled_backup";
    private static final String FOLDER = "folder_uri";
    private static final String ENABLED = "enabled";
    private static final String MODE = "mode";
    private static final String HOUR = "hour";
    private static final String MINUTE = "minute";
    private static final String WEEKDAY = "weekday";
    private static final String MONTHDAY = "monthday";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_SUCCESS = "last_success";
    private static final String LAST_ERROR = "last_error";
    private static final String RESTRICTED_PAUSED = "restricted_schedule_paused";
    private static final String PAUSED_MODE = "restricted_paused_mode";
    private static final String PAUSED_HOUR = "restricted_paused_hour";
    private static final String PAUSED_MINUTE = "restricted_paused_minute";
    private static final String PAUSED_WEEKDAY = "restricted_paused_weekday";
    private static final String PAUSED_MONTHDAY = "restricted_paused_monthday";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US);

    private ScheduleManager() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static Uri folder(Context context) {
        String value = prefs(context).getString(FOLDER, null);
        return value == null ? null : Uri.parse(value);
    }

    static void saveFolder(Context context, Uri folder) {
        prefs(context).edit().putString(FOLDER, folder.toString()).apply();
    }

    static String folderLabel(Context context) {
        Uri value = folder(context);
        if (value == null) return context.getString(R.string.folder_not_selected);
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(value,
                    DocumentsContract.getTreeDocumentId(value));
            try (Cursor cursor = context.getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
            }
        } catch (Exception ignored) { }
        String path = Uri.decode(value.getPath());
        if (path == null) return context.getString(R.string.folder_selected);
        int colon = path.lastIndexOf(':'); if (colon >= 0) path = path.substring(colon + 1);
        int slash = path.lastIndexOf('/'); return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static RetentionPolicy retentionPolicy(Context context) {
        SharedPreferences p = prefs(context);
        boolean periodic = "periodic".equals(p.getString("retention_mode", "simple"));
        return new RetentionPolicy(periodic ? RetentionPolicy.Mode.PERIODIC : RetentionPolicy.Mode.SIMPLE,
                p.getInt("retention_keep", RetentionPolicy.DEFAULT_KEEP),
                p.getInt("retention_daily", RetentionPolicy.DEFAULT_DAILY),
                p.getInt("retention_weekly", RetentionPolicy.DEFAULT_WEEKLY),
                p.getInt("retention_monthly", RetentionPolicy.DEFAULT_MONTHLY));
    }

    static void saveRetentionPolicy(Context context, RetentionPolicy policy) {
        prefs(context).edit()
                .putString("retention_mode", policy.mode == RetentionPolicy.Mode.PERIODIC ? "periodic" : "simple")
                .putInt("retention_keep", policy.simpleKeep)
                .putInt("retention_daily", policy.dailyKeep)
                .putInt("retention_weekly", policy.weeklyKeep)
                .putInt("retention_monthly", policy.monthlyKeep)
                .apply();
    }

    static boolean enabled(Context context) { return prefs(context).getBoolean(ENABLED, false); }
    static int mode(Context context) { return prefs(context).getInt(MODE, OFF); }
    static int hour(Context context) { return prefs(context).getInt(HOUR, 7); }
    static int minute(Context context) { return prefs(context).getInt(MINUTE, 30); }
    static int weekday(Context context) { return prefs(context).getInt(WEEKDAY, Calendar.MONDAY); }
    static int monthday(Context context) { return prefs(context).getInt(MONTHDAY, 1); }

    static void enable(Context context, int mode, int hour, int minute, int weekday, int monthday) {
        prefs(context).edit().putBoolean(ENABLED, true).putInt(MODE, mode).putInt(HOUR, hour)
                .putInt(MINUTE, minute).putInt(WEEKDAY, weekday).putInt(MONTHDAY, monthday).apply();
        scheduleNext(context);
    }

    static void disable(Context context) {
        prefs(context).edit().putBoolean(ENABLED, false).putInt(MODE, OFF).apply();
        cancel(context);
        ScheduledBackupReceiver.cancelExactAlarmNotification(context);
    }

    static boolean restrictedSchedulePaused(Context context) {
        return prefs(context).getBoolean(RESTRICTED_PAUSED, false);
    }

    static void pauseForRestrictedMessages(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(RESTRICTED_PAUSED, false)) return;
        if (p.getBoolean(ENABLED, false) && p.getInt(MODE, OFF) != OFF) {
            p.edit().putBoolean(RESTRICTED_PAUSED, true)
                    .putInt(PAUSED_MODE, p.getInt(MODE, OFF))
                    .putInt(PAUSED_HOUR, p.getInt(HOUR, 7))
                    .putInt(PAUSED_MINUTE, p.getInt(MINUTE, 30))
                    .putInt(PAUSED_WEEKDAY, p.getInt(WEEKDAY, Calendar.MONDAY))
                    .putInt(PAUSED_MONTHDAY, p.getInt(MONTHDAY, 1)).apply();
        }
        disable(context);
    }

    static void restoreAfterRestrictedMessages(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(RESTRICTED_PAUSED, false)) return;
        p.edit().putBoolean(ENABLED, true)
                .putInt(MODE, p.getInt(PAUSED_MODE, OFF))
                .putInt(HOUR, p.getInt(PAUSED_HOUR, 7))
                .putInt(MINUTE, p.getInt(PAUSED_MINUTE, 30))
                .putInt(WEEKDAY, p.getInt(PAUSED_WEEKDAY, Calendar.MONDAY))
                .putInt(MONTHDAY, p.getInt(PAUSED_MONTHDAY, 1))
                .remove(RESTRICTED_PAUSED).remove(PAUSED_MODE).remove(PAUSED_HOUR)
                .remove(PAUSED_MINUTE).remove(PAUSED_WEEKDAY).remove(PAUSED_MONTHDAY).apply();
        reconcile(context);
    }

    static void reconcile(Context context) {
        if (enabled(context) && (BackupOptions.includeRestrictedMessages(context)
                || (!BackupOptions.includeSms(context) && !BackupOptions.includeCalls(context)))) {
            if (BackupOptions.includeRestrictedMessages(context)) pauseForRestrictedMessages(context);
            else disable(context);
        } else if (enabled(context)) {
            scheduleNext(context);
        }
    }

    static String label(Context context) {
        if (!enabled(context) || mode(context) == OFF) return context.getString(R.string.schedule_off);
        if (prefs(context).contains(LAST_ERROR)) return context.getString(R.string.schedule_attention);
        String time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                .format(dateAt(hour(context), minute(context)));
        if (mode(context) == DAILY) return context.getString(R.string.schedule_daily_at, time);
        if (mode(context) == WEEKLY) {
            String day = java.text.DateFormatSymbols.getInstance(Locale.getDefault()).getWeekdays()[weekday(context)];
            return context.getString(R.string.schedule_weekly_at, day, time);
        }
        return context.getString(R.string.schedule_monthly_at, monthday(context), time);
    }

    static Uri newBackupFile(Context context) throws IOException {
        Uri folder = folder(context);
        if (folder == null) throw new IOException("backup folder not configured");
        String base = "sms-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String extension = BackupOptions.encrypted(context)
                ? ArchiveFormat.ENCRYPTED_FILE_EXTENSION : ArchiveFormat.FILE_EXTENSION;
        String name = uniqueBackupName(context, folder, base, extension) + ".partial";
        try {
            // ACTION_OPEN_DOCUMENT_TREE returns a tree URI. createDocument needs the
            // corresponding document URI as its parent, even though both identify the
            // same folder to the user.
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder,
                    DocumentsContract.getTreeDocumentId(folder));
            Uri result = DocumentsContract.createDocument(context.getContentResolver(), parent,
                    "application/octet-stream", name);
            if (result == null) throw new IOException("cannot create backup file");
            return result;
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("backup folder is no longer accessible", e);
        }
    }

    static Uri publishBackupFile(Context context, Uri partial) throws IOException {
        if (partial == null) throw new IOException("backup file missing");
        try (Cursor cursor = context.getContentResolver().query(partial,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("cannot verify backup file");
            String current = cursor.getString(0);
            if (current == null || !current.endsWith(".partial")) throw new IOException("invalid partial backup file");
            Uri published = DocumentsContract.renameDocument(context.getContentResolver(), partial,
                    current.substring(0, current.length() - ".partial".length()));
            if (published == null) throw new IOException("cannot publish backup file");
            return published;
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("backup folder is no longer accessible", e);
        }
    }

    static void prune(Context context) {
        Uri folder = folder(context); if (folder == null) return;
        Uri children;
        try { children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder)); }
        catch (Exception e) { return; }
        Map<String, List<String>> filesBySet = new HashMap<>();
        List<String> stalePartials = new ArrayList<>();
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = context.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) return;
            int id = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String display = name < 0 ? null : cursor.getString(name);
                String archiveName = partialArchiveName(display);
                if (archiveName != null && isStalePartial(archiveName)) {
                    stalePartials.add(cursor.getString(id));
                    continue;
                }
                if (!isRetentionCandidate(display)) continue;
                String set = stripExtension(display);
                String documentId = cursor.getString(id);
                filesBySet.computeIfAbsent(set, ignored -> new ArrayList<>()).add(documentId);
            }
        } catch (Exception ignored) { return; }
        List<StoredBackup> stored = new ArrayList<>();
        for (String set : filesBySet.keySet()) {
            LocalDateTime timestamp = parseTimestamp(set);
            if (timestamp != null) stored.add(new StoredBackup(set, timestamp));
        }
        Set<String> keep = RetentionDecider.decide(stored, retentionPolicy(context), LocalDateTime.now());
        ContentResolver resolver = context.getContentResolver();
        for (String documentId : stalePartials) {
            try {
                DocumentsContract.deleteDocument(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(folder, documentId));
            } catch (Exception ignored) { }
        }
        for (Map.Entry<String, List<String>> entry : filesBySet.entrySet()) {
            if (keep.contains(entry.getKey())) continue;
            for (String documentId : entry.getValue()) {
                try {
                    Uri file = DocumentsContract.buildDocumentUriUsingTree(folder, documentId);
                    DocumentsContract.deleteDocument(resolver, file);
                } catch (Exception ignored) { }
            }
        }
    }

    private static boolean isRetentionCandidate(String name) {
        return name != null && name.startsWith("sms-backup-")
                && (name.endsWith(ArchiveFormat.FILE_EXTENSION) || name.endsWith(ArchiveFormat.ENCRYPTED_FILE_EXTENSION))
                && parseTimestamp(stripExtension(name)) != null;
    }

    private static String partialArchiveName(String name) {
        if (name == null || !name.endsWith(".partial")) return null;
        return name.substring(0, name.length() - ".partial".length());
    }

    private static boolean isStalePartial(String archiveName) {
        return isRetentionCandidate(archiveName)
                && parseTimestamp(stripExtension(archiveName)) != null
                && parseTimestamp(stripExtension(archiveName)).isBefore(LocalDateTime.now().minusHours(1));
    }

    private static String stripExtension(String name) {
        if (name.endsWith(ArchiveFormat.ENCRYPTED_FILE_EXTENSION))
            return name.substring(0, name.length() - ArchiveFormat.ENCRYPTED_FILE_EXTENSION.length());
        return name.substring(0, name.length() - ArchiveFormat.FILE_EXTENSION.length());
    }

    private static String uniqueBackupName(Context context, Uri folder, String base, String extension) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder,
                    DocumentsContract.getTreeDocumentId(folder));
            java.util.HashSet<String> names = new java.util.HashSet<>();
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null) while (cursor.moveToNext()) names.add(cursor.getString(0));
            }
            for (int suffix = 0; suffix < 100; suffix++) {
                String candidate = base + (suffix == 0 ? "" : "-" + suffix) + extension;
                if (!names.contains(candidate) && !names.contains(candidate + ".partial")) return candidate;
            }
        } catch (Exception ignored) { }
        return base + extension;
    }

    private static LocalDateTime parseTimestamp(String set) {
        if (!set.startsWith("sms-backup-")) return null;
        String value = set.substring("sms-backup-".length());
        // The timestamp is always 15 characters; an optional collision suffix
        // follows it and is ignored for the retention date.
        if (value.length() > 15) value = value.substring(0, 15);
        try { return LocalDateTime.parse(value, BACKUP_TIMESTAMP); }
        catch (DateTimeParseException ignored) { return null; }
    }

    static void scheduleNext(Context context) {
        if (!enabled(context) || mode(context) == OFF) return;
        long trigger = nextTrigger(System.currentTimeMillis(), mode(context), hour(context), minute(context), weekday(context), monthday(context));
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        try {
            PendingIntent pending = pendingIntent(context);
            boolean exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
            if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            else if (Build.VERSION.SDK_INT >= 23) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            else alarms.setExact(AlarmManager.RTC_WAKEUP, trigger, pending);
            if (exact) ScheduledBackupReceiver.cancelExactAlarmNotification(context);
            else ScheduledBackupReceiver.notifyExactAlarmNeeded(context);
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context));
            ScheduledBackupReceiver.notifyExactAlarmNeeded(context);
        }
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(pendingIntent(context));
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs != null) jobs.cancel(SCHEDULED_JOB_ID);
    }

    static boolean enqueueScheduledRun(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null) return false;
        try {
            JobInfo job = new JobInfo.Builder(SCHEDULED_JOB_ID,
                    new ComponentName(context, ScheduledBackupJobService.class))
                    .setMinimumLatency(1)
                    .setOverrideDeadline(1_000)
                    .build();
            return jobs.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static long nextTrigger(long now, int mode, int hour, int minute, int weekday, int monthday) {
        Calendar target = Calendar.getInstance(); target.setTimeInMillis(now);
        target.set(Calendar.HOUR_OF_DAY, hour); target.set(Calendar.MINUTE, minute); target.set(Calendar.SECOND, 0); target.set(Calendar.MILLISECOND, 0);
        if (mode == DAILY) { if (target.getTimeInMillis() <= now) target.add(Calendar.DAY_OF_YEAR, 1); return target.getTimeInMillis(); }
        if (mode == WEEKLY) {
            int delta = (weekday - target.get(Calendar.DAY_OF_WEEK) + 7) % 7;
            if (delta == 0 && target.getTimeInMillis() <= now) delta = 7;
            target.add(Calendar.DAY_OF_YEAR, delta); return target.getTimeInMillis();
        }
        if (mode == MONTHLY) {
            target.set(Calendar.DAY_OF_MONTH, Math.min(monthday, target.getActualMaximum(Calendar.DAY_OF_MONTH)));
            if (target.getTimeInMillis() <= now) {
                target.add(Calendar.MONTH, 1);
                target.set(Calendar.DAY_OF_MONTH, Math.min(monthday, target.getActualMaximum(Calendar.DAY_OF_MONTH)));
            }
            return target.getTimeInMillis();
        }
        return now + 60_000L;
    }

    private static Date dateAt(int hour, int minute) { Calendar calendar = Calendar.getInstance(); calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); return calendar.getTime(); }
    private static PendingIntent pendingIntent(Context context) { Intent intent = new Intent(context, ScheduledBackupReceiver.class).setAction(ACTION_RUN); return PendingIntent.getBroadcast(context, 401, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
}
