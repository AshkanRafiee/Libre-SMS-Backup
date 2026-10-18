package com.ashkanrafiee.libresmsbackup;

import androidx.test.platform.app.InstrumentationRegistry;

import android.app.job.JobScheduler;

import java.util.Calendar;
import java.util.Map;

import junit.framework.TestCase;

/** Calendar tests prevent daylight/timezone changes from silently disabling schedules. */
public class ScheduleManagerTest extends TestCase {
    public void testDailyMovesToTomorrowAfterTheConfiguredTime() {
        Calendar now = Calendar.getInstance(); now.set(2026, Calendar.JANUARY, 10, 8, 0, 0); now.set(Calendar.MILLISECOND, 0);
        long next = ScheduleManager.nextTrigger(now.getTimeInMillis(), ScheduleManager.DAILY, 7, 30, Calendar.MONDAY, 1);
        Calendar result = Calendar.getInstance(); result.setTimeInMillis(next);
        assertEquals(Calendar.JANUARY, result.get(Calendar.MONTH)); assertEquals(11, result.get(Calendar.DAY_OF_MONTH));
        assertEquals(7, result.get(Calendar.HOUR_OF_DAY)); assertEquals(30, result.get(Calendar.MINUTE));
    }

    public void testMonthlyUsesTheLastAvailableDayInShortMonths() {
        Calendar now = Calendar.getInstance(); now.set(2026, Calendar.FEBRUARY, 1, 7, 0, 0); now.set(Calendar.MILLISECOND, 0);
        long next = ScheduleManager.nextTrigger(now.getTimeInMillis(), ScheduleManager.MONTHLY, 7, 30, Calendar.MONDAY, 31);
        Calendar result = Calendar.getInstance(); result.setTimeInMillis(next);
        assertEquals(Calendar.FEBRUARY, result.get(Calendar.MONTH)); assertEquals(28, result.get(Calendar.DAY_OF_MONTH));
    }

    public void testScheduledRunUsesDurableJobQueue() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            assertTrue(ScheduleManager.enqueueScheduledRun(context));
            JobScheduler jobs = (JobScheduler) context.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE);
            boolean found = false;
            if (jobs != null) for (android.app.job.JobInfo job : jobs.getAllPendingJobs()) {
                if (job.getId() == ScheduleManager.SCHEDULED_JOB_ID) { found = true; break; }
            }
            assertTrue(found);
        } finally {
            ScheduleManager.cancel(context);
        }
    }

    public void testUnencryptedScheduleRemainsEnabled() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences schedule = ScheduleManager.prefs(context);
        android.content.SharedPreferences options = context.getSharedPreferences("backup_options", android.content.Context.MODE_PRIVATE);
        Map<String, ?> oldSchedule = schedule.getAll();
        Map<String, ?> oldOptions = options.getAll();
        try {
            ScheduleManager.disable(context);
            BackupOptions.setIncludeRestrictedMessages(context, false);
            BackupOptions.setEncrypted(context, false);
            ScheduleManager.enable(context, ScheduleManager.DAILY, 6, 15, Calendar.MONDAY, 1);
            ScheduleManager.reconcile(context);
            assertTrue(ScheduleManager.enabled(context));
        } finally {
            ScheduleManager.cancel(context);
            restorePreferences(schedule, oldSchedule);
            restorePreferences(options, oldOptions);
            ScheduleManager.reconcile(context);
        }
    }

    public void testRestrictedMessagesPauseAndRestoreThePreviousSchedule() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences schedule = ScheduleManager.prefs(context);
        android.content.SharedPreferences options = context.getSharedPreferences("backup_options", android.content.Context.MODE_PRIVATE);
        Map<String, ?> oldSchedule = schedule.getAll();
        Map<String, ?> oldOptions = options.getAll();
        boolean hadPassword = options.contains("manual_password");
        try {
            ScheduleManager.disable(context);
            BackupOptions.setIncludeRestrictedMessages(context, false);
            BackupOptions.setEncrypted(context, true);
            EncryptionPassword.save(context, "test-password".toCharArray());
            ScheduleManager.enable(context, ScheduleManager.DAILY, 6, 15, Calendar.MONDAY, 1);

            BackupOptions.setIncludeRestrictedMessages(context, true);
            assertFalse(ScheduleManager.enabled(context));
            assertTrue(ScheduleManager.restrictedSchedulePaused(context));

            BackupOptions.setIncludeRestrictedMessages(context, false);
            assertFalse(ScheduleManager.restrictedSchedulePaused(context));
            assertEquals(ScheduleManager.DAILY, ScheduleManager.mode(context));
            assertEquals(6, ScheduleManager.hour(context));
            assertEquals(15, ScheduleManager.minute(context));
        } finally {
            ScheduleManager.cancel(context);
            if (!hadPassword) EncryptionPassword.clear(context);
            restorePreferences(schedule, oldSchedule);
            restorePreferences(options, oldOptions);
            ScheduleManager.reconcile(context);
        }
    }

    private void restorePreferences(android.content.SharedPreferences preferences, Map<String, ?> values) {
        android.content.SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof java.util.Set) {
                // SharedPreferences only exposes String sets in this project.
                @SuppressWarnings("unchecked") java.util.Set<String> strings = (java.util.Set<String>) value;
                editor.putStringSet(entry.getKey(), strings);
            }
        }
        editor.commit();
    }
}
