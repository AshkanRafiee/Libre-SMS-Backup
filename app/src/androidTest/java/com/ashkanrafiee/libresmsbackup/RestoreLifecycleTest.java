package com.ashkanrafiee.libresmsbackup;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveCrypto;
import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/** Exercises the restore lifecycle without changing the device's SMS role or data. */
public class RestoreLifecycleTest extends TestCase {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;

    public void testSwitchBackMarkerIsHiddenUntilSmsRestoreStops() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("test_restore_sms_role", Context.MODE_PRIVATE);
        onMain(() -> {
            RestoreSession session = new RestoreSession(null);
            prefs.edit().clear().commit();
            try {
                assertTrue(session.armSmsSwitchBack(prefs, "example.sms"));
                assertTrue(prefs.getBoolean("switch_back_pending", false));
                assertEquals("restore_failed", prefs.getString("switch_back_reason", ""));
                session.step = RestoreSession.Step.ROLE_REQUEST;
                assertFalse(RestoreSession.hasPendingSmsSwitchBack(prefs));

                // The result callback starts the worker before the Activity resumes.
                session.step = RestoreSession.Step.WRITING;
                assertFalse(RestoreSession.hasPendingSmsSwitchBack(prefs));
                session.finish(R.string.error_generic);
                assertTrue(RestoreSession.hasPendingSmsSwitchBack(prefs));
            } finally {
                session.finish(0);
                prefs.edit().clear().commit();
            }
            return null;
        });
    }

    public void testRecreationRestoresSelectionAndKeepsPayloadForPermissionResult() throws Exception {
        File archive = File.createTempFile("restore-lifecycle-", ".lsb", context.getCacheDir());
        try {
            try (OutputStream raw = new FileOutputStream(archive);
                 OutputStream output = ArchiveCrypto.openOutput(raw, context, null, false)) {
                output.write(("{\"schema\":" + ArchiveFormat.PAYLOAD_SCHEMA + ",\"sms\":[{}],\"calls\":[{}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                instrumentation.getUiAutomation().grantRuntimePermission(
                        context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            }
            activity = (MainActivity) instrumentation.startActivitySync(
                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            onMain(() -> {
                activity.onActivityResult(MainActivity.REQUEST_OPEN, Activity.RESULT_OK,
                        new Intent().setData(Uri.fromFile(archive)));
                return null;
            });
            RestoreSession session = awaitSelection();
            ArchiveFormat.PreparedRestore payload = onMain(() -> {
                assertTrue(callsCheckbox().isChecked());
                callsCheckbox().setChecked(false);
                return session.prepared;
            });

            recreate();
            onMain(() -> {
                assertSame(session, activity.onRetainNonConfigurationInstance());
                assertSame(payload, session.prepared);
                assertFalse(callsCheckbox().isChecked());
                // Simulate an outstanding Android permission request without opening system UI.
                session.step = RestoreSession.Step.READ_PERMISSION;
                return null;
            });

            recreate();
            onMain(() -> {
                assertSame(payload, session.prepared);
                activity.onRequestPermissionsResult(MainActivity.REQUEST_RESTORE_PERMISSIONS,
                        new String[0], new int[0]); // An interrupted request returns empty arrays.
                assertNull(activity.onRetainNonConfigurationInstance());
                assertNull(session.prepared);
                return null;
            });
        } finally {
            if (activity != null) onMain(() -> { activity.finish(); return null; });
            archive.delete();
        }
    }

    private RestoreSession awaitSelection() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            RestoreSession selected = onMain(() -> {
                RestoreSession session = (RestoreSession) activity.onRetainNonConfigurationInstance();
                return session != null && session.step == RestoreSession.Step.SELECTION ? session : null;
            });
            if (selected != null) return selected;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Restore preview did not reach the selection dialog");
    }

    private void recreate() throws Exception {
        MainActivity old = activity;
        ArrayBlockingQueue<MainActivity> replacement = new ArrayBlockingQueue<>(1);
        ActivityLifecycleCallback callback = (candidate, stage) -> {
            if (candidate != old && candidate instanceof MainActivity && stage == Stage.RESUMED) {
                replacement.offer((MainActivity) candidate);
            }
        };
        onMain(() -> {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
            old.recreate();
            return null;
        });
        try {
            MainActivity recreated = replacement.poll(10, TimeUnit.SECONDS);
            assertNotNull("Activity did not resume after recreation", recreated);
            activity = recreated;
        } finally {
            onMain(() -> {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
                return null;
            });
        }
    }

    private CheckBox callsCheckbox() throws Exception {
        Field field = MainActivity.class.getDeclaredField("restoreDialog");
        field.setAccessible(true);
        AlertDialog dialog = (AlertDialog) field.get(activity);
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        CheckBox checkbox = findCheckbox(dialog.getWindow().getDecorView(),
                activity.getString(R.string.restore_calls_option, 1));
        assertNotNull(checkbox);
        return checkbox;
    }

    private static CheckBox findCheckbox(View view, String label) {
        if (view instanceof CheckBox && label.contentEquals(view.getContentDescription())) return (CheckBox) view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckBox found = findCheckbox(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private <T> T onMain(Callable<T> work) throws Exception {
        FutureTask<T> result = new FutureTask<>(work);
        instrumentation.runOnMainSync(result);
        return result.get();
    }
}
