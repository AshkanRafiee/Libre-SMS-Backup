package com.ashkanrafiee.libresmsbackup;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Gives a scheduled backup a system-owned execution boundary after its alarm fires. */
public final class ScheduledBackupJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> running;

    @Override public boolean onStartJob(JobParameters params) {
        running = executor.submit(() -> {
            try {
                ScheduledBackupReceiver.runOnce(LocaleHelper.wrap(getApplicationContext()));
            } finally {
                jobFinished(params, Thread.currentThread().isInterrupted());
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Future<?> task = running;
        if (task != null) task.cancel(true);
        return true;
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
