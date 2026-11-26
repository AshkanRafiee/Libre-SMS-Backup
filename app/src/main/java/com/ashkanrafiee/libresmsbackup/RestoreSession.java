package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveCrypto;
import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;
import com.ashkanrafiee.libresmsbackup.data.RestoreEngine;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** A single in-memory restore. Only the SMS switch-back obligation survives process death. */
final class RestoreSession {
    enum Step {
        HEADER, PASSWORD, INSPECTING, SELECTION, READ_PERMISSION, WRITE_PERMISSION,
        ROLE_PROMPT, ROLE_REQUEST, ROLE_CHECK, WRITING, DONE
    }

    // Another MainActivity can be launched while a role request or restore is in progress.
    // Only one session may own the pending SMS switch-back marker; accessed on the main thread.
    private static RestoreSession smsRoleOwner;
    private static final Set<Runnable> smsRoleReleaseListeners = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Uri source;
    Step step = Step.HEADER;
    ArchiveFormat.PreparedRestore prepared;
    boolean sms;
    boolean calls;
    long smsRoleCheckDeadline;
    boolean smsRoleResultAccepted;
    int completedRecords;
    int totalRecords;
    RestoreEngine.RestoreSummary summary;
    boolean previewFailed;
    int error;
    Runnable onChange;
    private boolean abandoned;
    boolean smsRecoveryArmed;

    RestoreSession(Uri source) {
        this.source = source;
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    boolean isWorking() {
        return step == Step.HEADER || step == Step.INSPECTING || step == Step.WRITING;
    }

    static boolean isSmsRestoreActive() {
        return smsRoleOwner != null;
    }

    static boolean hasPendingSmsSwitchBack(SharedPreferences prefs) {
        return !isSmsRestoreActive() && prefs.getBoolean("switch_back_pending", false);
    }

    static void addSmsRoleReleaseListener(Runnable listener) {
        smsRoleReleaseListeners.add(listener);
    }

    static void removeSmsRoleReleaseListener(Runnable listener) {
        smsRoleReleaseListeners.remove(listener);
    }

    boolean armSmsSwitchBack(SharedPreferences prefs, String previousPackage) {
        if (smsRoleOwner == this) return true;
        if (smsRoleOwner != null) return false;
        SharedPreferences.Editor editor = prefs.edit().putBoolean("switch_back_pending", true)
                .putString("switch_back_reason", "restore_failed");
        if (previousPackage != null) editor.putString("previous_package", previousPackage);
        // Save the recovery reminder before Android can grant the role.
        if (!editor.commit()) return false;
        smsRoleOwner = this;
        smsRecoveryArmed = true;
        return true;
    }

    private void releaseSmsSwitchBack() {
        if (smsRoleOwner != this) return;
        smsRoleOwner = null;
        // Let completion update its own UI before another resumed Activity checks the marker.
        main.post(() -> {
            for (Runnable listener : smsRoleReleaseListeners) listener.run();
        });
    }

    void probe(Context application, Executor executor) {
        changed();
        executor.execute(() -> {
            boolean needsPassword;
            try (InputStream input = application.getContentResolver().openInputStream(source)) {
                needsPassword = ArchiveCrypto.requiresPassword(input);
            } catch (Exception failure) {
                main.post(() -> finishPreviewFailure(application, failure));
                return;
            }
            main.post(() -> {
                if (abandoned) return;
                if (needsPassword) {
                    step = Step.PASSWORD;
                    changed();
                } else {
                    inspect(application, executor, new char[0]);
                }
            });
        });
    }

    void inspect(Context application, Executor executor, char[] password) {
        step = Step.INSPECTING;
        changed();
        executor.execute(() -> {
            try {
                ArchiveFormat.PreparedRestore result = ArchiveFormat.prepareRestore(application, source, password);
                main.post(() -> {
                    if (abandoned) {
                        result.close();
                        return;
                    }
                    prepared = result;
                    sms = result.summary.smsCount + result.summary.mmsCount + result.summary.rcsCount > 0;
                    calls = result.summary.callsCount > 0;
                    if (!sms && !calls) finish(R.string.restore_no_records);
                    else {
                        step = Step.SELECTION;
                        changed();
                    }
                });
            } catch (Exception failure) {
                main.post(() -> finishPreviewFailure(application, failure));
            }
        });
    }

    private void finishPreviewFailure(Context application, Exception failure) {
        if (abandoned) return;
        previewFailed = true;
        int message;
        String code;
        if (failure instanceof ArchiveCrypto.PayloadTooLargeException) {
            ArchiveCrypto.PayloadTooLargeException sizeFailure = (ArchiveCrypto.PayloadTooLargeException) failure;
            message = sizeFailure.heapLimited ? R.string.restore_error_encrypted_too_large
                    : R.string.restore_error_archive_too_large;
            code = sizeFailure.encrypted ? ReportDiagnostics.RESTORE_ENCRYPTED_ARCHIVE_TOO_LARGE
                    : ReportDiagnostics.RESTORE_ARCHIVE_TOO_LARGE;
        } else if (ArchiveCrypto.isAuthenticationFailure(failure)) {
            message = R.string.error_wrong_password;
            code = ReportDiagnostics.RESTORE_AUTHENTICATION_FAILED;
        } else {
            message = R.string.error_no_source;
            code = ReportDiagnostics.RESTORE_INSPECTION_FAILED;
        }
        ReportDiagnostics.recordRestoreError(application, code);
        finish(message);
    }

    void write(Context application, Executor executor) {
        step = Step.WRITING;
        ArchiveFormat.PreparedRestore payload = prepared;
        prepared = null; // The worker owns the payload from here on.
        final boolean includeSms = sms;
        final boolean includeCalls = calls;
        totalRecords = (includeSms ? payload.summary.smsCount + payload.summary.mmsCount
                + payload.summary.rcsCount : 0) + (includeCalls ? payload.summary.callsCount : 0);
        changed();
        executor.execute(() -> {
            RestoreEngine.RestoreSummary result;
            long[] lastProgress = {0};
            try (ArchiveFormat.PreparedRestore owned = payload) {
                result = owned.restore(application, includeSms, includeCalls, (completed, total) -> {
                    long now = SystemClock.uptimeMillis();
                    if (completed != total && completed > 1 && now - lastProgress[0] < 100) return;
                    lastProgress[0] = now;
                    main.post(() -> {
                        completedRecords = completed;
                        totalRecords = total;
                        changed();
                    });
                });
            } catch (Exception failure) {
                ReportDiagnostics.recordRestoreError(application, ReportDiagnostics.RESTORE_EXECUTION_FAILED);
                main.post(() -> finish(R.string.error_generic));
                return;
            }
            if (result.failed == 0) ReportDiagnostics.clearRestoreError(application);
            else ReportDiagnostics.recordRestoreError(application, ReportDiagnostics.RESTORE_RECORDS_FAILED);
            if (includeSms) {
                // Record the outcome even if the Activity no longer exists.
                application.getSharedPreferences(MainActivity.RESTORE_SMS_ROLE_PREFS, Context.MODE_PRIVATE)
                        .edit().putString("switch_back_reason", result.failed == 0 ? "restore" : "restricted").apply();
            }
            main.post(() -> {
                summary = result;
                finish(0);
            });
        });
    }

    void finish(int error) {
        releaseSmsSwitchBack();
        if (abandoned) return;
        this.error = error;
        step = Step.DONE;
        changed();
    }

    void abandon() {
        onChange = null;
        abandoned = true;
        if (step != Step.WRITING) releaseSmsSwitchBack();
        if (prepared != null) {
            prepared.close();
            prepared = null;
        }
    }
}
