package com.ashkanrafiee.libresmsbackup;

import android.content.Context;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;

/** Formats the record counts reported after a backup has been written. */
final class BackupSummaryFormatter {
    private BackupSummaryFormatter() {}

    static String format(Context context, ArchiveFormat.BackupSummary summary) {
        return format(context, summary.smsCount, summary.callCount, summary.mmsCount, summary.rcsCount);
    }

    static String format(Context context, int smsCount, int callCount, int mmsCount, int rcsCount) {
        if (mmsCount > 0 || rcsCount > 0) {
            return context.getString(R.string.backup_summary_extended, smsCount, callCount, mmsCount, rcsCount);
        }
        return context.getString(R.string.backup_summary, smsCount, callCount);
    }
}
