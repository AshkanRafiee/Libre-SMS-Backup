package com.ashkanrafiee.libresmsbackup;

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
}
