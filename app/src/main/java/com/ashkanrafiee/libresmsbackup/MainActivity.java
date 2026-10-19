package com.ashkanrafiee.libresmsbackup;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;
import com.ashkanrafiee.libresmsbackup.archive.ArchiveCrypto;
import com.ashkanrafiee.libresmsbackup.data.RestoreEngine;
import com.ashkanrafiee.libresmsbackup.retention.RetentionPolicy;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, deliberately boring main screen: the app's sensitive data stays behind explicit actions. */
public class MainActivity extends Activity {
    private static ArchiveFormat.PreparedRestore retainedRestoreAcrossSmsRole;
    private static final int REQUEST_BACKUP_FOLDER = 10;
    private static final int REQUEST_OPEN = 11;
    private static final int REQUEST_BACKUP_PERMISSIONS = 12;
    private static final int REQUEST_RESTORE_PERMISSIONS = 13;
    private static final int REQUEST_SCHEDULE_PERMISSIONS = 15;
    private static final int REQUEST_SMS_ROLE = 16;
    private static final int REQUEST_SMS_SWITCH_BACK = 17;
    private static final int REQUEST_NOTIFICATIONS = 18;
    private static final int REQUEST_RESTORE_WRITE = 19;
    private static final String RESTRICTED_ROLE_PREFS = "restricted_sms_role";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView statusDetail;
    private ProgressBar progress;
    private Button backupButton;
    private Button restoreButton;
    private TextView folderValue;
    private TextView scheduleValue;
    private TextView retentionValue;
    private TextView scopeValue;
    private TextView batteryBanner;
    private Switch encryptionSwitch;
    private CheckBox includeSms;
    private CheckBox includeCalls;
    private boolean changingEncryption;
    private Uri selectedUri;
    private boolean pendingBackupFolder;
    private boolean pendingScheduleFolder;
    private ArchiveFormat.PreparedRestore pendingRestore;
    private boolean pendingRestoreAfterSmsRole;
    private boolean temporarilyChangedSmsRole;
    private boolean pendingRestoreSms;
    private boolean pendingRestoreCalls;
    private boolean pendingRestoreWritePermission;
    private boolean pendingBackupAfterSmsRole;
    private boolean restrictedSmsRoleRequestPending;
    private boolean restrictedSmsRoleChangedByUs;
    private String previousSmsPackage;
    private String restrictedPreviousSmsPackage;
    private boolean switchBackSettingsOpened;
    private boolean switchBackDialogShown;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private int color(int id) { return getResources().getColor(id); }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(ThemeMode.wrap(LocaleHelper.wrap(base)));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            selectedUri = state.getParcelable("selected_uri");
            pendingBackupFolder = state.getBoolean("pending_backup_folder", false);
            pendingScheduleFolder = state.getBoolean("pending_schedule_folder", false);
            pendingRestoreAfterSmsRole = state.getBoolean("pending_restore_sms_role", false);
            temporarilyChangedSmsRole = state.getBoolean("temporary_sms_role", false);
            pendingRestoreSms = state.getBoolean("pending_restore_sms", false);
            pendingRestoreCalls = state.getBoolean("pending_restore_calls", false);
            pendingRestoreWritePermission = state.getBoolean("pending_restore_write", false);
            pendingBackupAfterSmsRole = state.getBoolean("pending_backup_sms_role", false);
            restrictedSmsRoleRequestPending = state.getBoolean("restricted_sms_role_request", false);
            previousSmsPackage = state.getString("previous_sms_package");
            restrictedPreviousSmsPackage = state.getString("restricted_previous_package");
        }
        android.content.SharedPreferences rolePrefs = getSharedPreferences("restore_sms_role", MODE_PRIVATE);
        if (!temporarilyChangedSmsRole) temporarilyChangedSmsRole = rolePrefs.getBoolean("switch_back_pending", false);
        if (previousSmsPackage == null) previousSmsPackage = rolePrefs.getString("previous_package", null);
        SharedPreferences restrictedRolePrefs = getSharedPreferences(RESTRICTED_ROLE_PREFS, MODE_PRIVATE);
        if (!restrictedSmsRoleChangedByUs) {
            restrictedSmsRoleChangedByUs = restrictedRolePrefs.getBoolean("changed_by_us", false);
        }
        if (restrictedPreviousSmsPackage == null) {
            restrictedPreviousSmsPackage = restrictedRolePrefs.getString("previous_package", null);
        }
        if (pendingRestore == null && pendingRestoreAfterSmsRole && retainedRestoreAcrossSmsRole != null) {
            pendingRestore = retainedRestoreAcrossSmsRole;
        }
        getWindow().setStatusBarColor(color(R.color.bg));
        build();
        handleNotificationAction(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationAction(intent);
    }

    private void handleNotificationAction(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(ScheduledBackupReceiver.EXTRA_NOTIFICATION_ACTION);
        intent.removeExtra(ScheduledBackupReceiver.EXTRA_NOTIFICATION_ACTION);
        if (action == null || action.isEmpty()) return;
        if (ScheduledBackupReceiver.ACTION_FOLDER.equals(action)) chooseBackupFolder(false, false);
        else if (ScheduledBackupReceiver.ACTION_PERMISSION.equals(action)) explainAndRequestSchedulePermissions();
        else if (ScheduledBackupReceiver.ACTION_SMS_ACCESS.equals(action)) requestSmsAccessForSchedule();
        else if (ScheduledBackupReceiver.ACTION_ENCRYPTION.equals(action)) openEncryptionSetup();
        else if (ScheduledBackupReceiver.ACTION_SCOPE.equals(action)) chooseScope();
        else if (ScheduledBackupReceiver.ACTION_RESTRICTED_MESSAGES.equals(action)) chooseScope();
        else if (ScheduledBackupReceiver.ACTION_EXACT_ALARM.equals(action)) openExactAlarmSettings();
    }

    @Override protected void onDestroy() {
        if (pendingRestore != null) {
            if (pendingRestoreAfterSmsRole) {
                retainedRestoreAcrossSmsRole = pendingRestore;
            } else {
                pendingRestore.close();
                if (retainedRestoreAcrossSmsRole == pendingRestore) retainedRestoreAcrossSmsRole = null;
            }
            pendingRestore = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selectedUri != null) state.putParcelable("selected_uri", selectedUri);
        state.putBoolean("pending_backup_folder", pendingBackupFolder);
        state.putBoolean("pending_schedule_folder", pendingScheduleFolder);
        state.putBoolean("pending_restore_sms_role", pendingRestoreAfterSmsRole);
        state.putBoolean("temporary_sms_role", temporarilyChangedSmsRole);
        state.putBoolean("pending_restore_sms", pendingRestoreSms);
        state.putBoolean("pending_restore_calls", pendingRestoreCalls);
        state.putBoolean("pending_restore_write", pendingRestoreWritePermission);
        state.putBoolean("pending_backup_sms_role", pendingBackupAfterSmsRole);
        state.putBoolean("restricted_sms_role_request", restrictedSmsRoleRequestPending);
        if (previousSmsPackage != null) state.putString("previous_sms_package", previousSmsPackage);
        if (restrictedPreviousSmsPackage != null) state.putString("restricted_previous_package", restrictedPreviousSmsPackage);
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        applyLocaleDirection(root);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.setBackgroundColor(color(R.color.bg));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top; int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(20), top + dp(14), dp(20), bottom + dp(14));
            return insets;
        });
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        AppMarkView mark = new AppMarkView(this);
        header.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout name = new LinearLayout(this); name.setOrientation(LinearLayout.VERTICAL);
        name.addView(text(getString(R.string.app_name), 22, color(R.color.text_primary)));
        name.addView(text(getString(R.string.tagline_offline_encrypted), 12, color(R.color.muted)));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        nameParams.setMarginStart(dp(10)); header.addView(name, nameParams);
        body.addView(header, margins(0, 0, 0, 14));

        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(14));
        hero.setBackground(gradient(new int[]{color(R.color.hero_grad_start), color(R.color.hero_grad_end)}, 22));
        FrameLayout heroTop = new FrameLayout(this);
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(getString(R.string.hero_label), 10, color(R.color.link)));
        status = text(getString(R.string.status_ready), 18, color(R.color.text_primary));
        status.setPadding(0, dp(5), 0, dp(2)); status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END); words.addView(status);
        statusDetail = text("", 12, color(R.color.subtitle));
        statusDetail.setMaxLines(3); statusDetail.setEllipsize(TextUtils.TruncateAt.END);
        statusDetail.setPadding(0, dp(1), 0, dp(2)); statusDetail.setVisibility(View.GONE);
        words.addView(statusDetail);
        progress = new ProgressBar(this); progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        words.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
        words.addView(text(getString(R.string.hero_tagline), 12, color(R.color.subtitle)));
        heroTop.addView(words, new FrameLayout.LayoutParams(-1, -2));
        hero.addView(heroTop);
        backupButton = button(getString(R.string.backup_now), true);
        backupButton.setOnClickListener(v -> beginBackup());
        LinearLayout.LayoutParams backupParams = new LinearLayout.LayoutParams(-1, dp(48));
        backupParams.setMargins(0, dp(14), 0, 0);
        hero.addView(backupButton, backupParams);
        body.addView(hero, margins(0, 0, 0, 16));

        batteryBanner = text("", 12, color(R.color.amber));
        batteryBanner.setBackground(rounded(color(R.color.panel), 15));
        batteryBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        batteryBanner.setOnClickListener(v -> openBatterySettings());
        batteryBanner.setVisibility(View.GONE);
        body.addView(batteryBanner, margins(0, 0, 0, 8));

        body.addView(text(getString(R.string.section_settings), 10, color(R.color.muted)), margins(0, 0, 0, 8));
        folderValue = text(ScheduleManager.folderLabel(this), 13, color(R.color.link));
        body.addView(setting(getString(R.string.folder_title), getString(R.string.folder_subtitle), folderValue,
                v -> chooseBackupFolder(false, false)), margins(0, 0, 0, 8));
        scheduleValue = text(ScheduleManager.label(this), 13, color(R.color.link));
        body.addView(setting(getString(R.string.schedule_title), getString(R.string.schedule_subtitle), scheduleValue,
                v -> configureSchedule()), margins(0, 0, 0, 8));
        retentionValue = text(retentionLabel(this), 13, color(R.color.link));
        body.addView(setting(getString(R.string.retention_title), getString(R.string.retention_subtitle), retentionValue,
                v -> retentionDialog()), margins(0, 0, 0, 8));
        encryptionSwitch = new Switch(this);
        encryptionSwitch.setChecked(BackupOptions.encrypted(this));
        encryptionSwitch.setOnCheckedChangeListener((button, checked) -> encryptionChanged(checked));
        body.addView(settingToggle(getString(R.string.encryption_title), getString(R.string.encryption_subtitle), encryptionSwitch), margins(0, 0, 0, 14));
        scopeValue = text(scopeLabel(), 13, color(R.color.link));
        body.addView(setting(getString(R.string.scope_title), getString(R.string.scope_subtitle), scopeValue,
                v -> chooseScope()), margins(0, 0, 0, 14));

        body.addView(text(getString(R.string.section_restore), 10, color(R.color.muted)), margins(0, 8, 0, 8));
        body.addView(text(getString(R.string.restore_note), 11, color(R.color.amber)), margins(0, 0, 0, 8));
        restoreButton = button(getString(R.string.restore_button), false);
        restoreButton.setText(getString(R.string.restore_button) + (isRtl() ? "  ‹" : "  ›"));
        restoreButton.setTextSize(16);
        restoreButton.setOnClickListener(v -> beginRestore());
        body.addView(restoreButton, new LinearLayout.LayoutParams(-1, dp(68)));

        LinearLayout footer = new LinearLayout(this); footer.setGravity(Gravity.CENTER);
        TextView about = text(getString(R.string.footer_about), 11, color(R.color.link));
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        TextView separator = text("  ·  ", 11, color(R.color.text_tertiary));
        TextView display = text(getString(R.string.footer_display), 11, color(R.color.link));
        display.setOnClickListener(v -> showDisplayMenu());
        footer.addView(about); footer.addView(separator); footer.addView(display);
        body.addView(footer, margins(0, 14, 0, 0));
        Space space = new Space(this); body.addView(space, new LinearLayout.LayoutParams(1, 0, 1));
        refreshStatus(); refreshBatteryBanner();
    }

    private TextView text(String value, float size, int textColor) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(textColor);
        if (Build.VERSION.SDK_INT >= 17) view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        return view;
    }

    private boolean isRtl() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private void applyLocaleDirection(View view) {
        if (Build.VERSION.SDK_INT >= 17) {
            view.setLayoutDirection(isRtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        }
    }

    private void setStatus(String headline, String detail) {
        status.setText(headline);
        if (detail == null || detail.isEmpty()) {
            statusDetail.setVisibility(View.GONE);
        } else {
            statusDetail.setText(detail);
            statusDetail.setVisibility(View.VISIBLE);
        }
    }

    private CheckBox checkbox(String label, boolean checked, int tint) {
        CheckBox box = new CheckBox(this);
        box.setText(label); box.setTextSize(13); box.setTextColor(color(R.color.text_primary)); box.setChecked(checked);
        if (android.os.Build.VERSION.SDK_INT >= 21) box.setButtonTintList(android.content.res.ColorStateList.valueOf(tint));
        return box;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this); button.setText(value); button.setTextSize(14); button.setAllCaps(false);
        if (Build.VERSION.SDK_INT >= 17) button.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        // Keep the primary label readable on both palettes; some DeviceDefault dark themes
        // otherwise apply their own disabled/contrast ColorStateList to a custom background.
        button.setTextColor(primary ? android.graphics.Color.rgb(10, 14, 24) : color(R.color.button_text));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setPadding(0, 0, 0, 0);
        button.setBackgroundResource(primary ? R.drawable.btn_mint : R.drawable.btn_surface);
        button.setTextColor(primary ? android.graphics.Color.rgb(10, 14, 24) : color(R.color.button_text));
        button.setStateListAnimator(null); return button;
    }

    private GradientDrawable rounded(int fill, float radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); return drawable;
    }

    private GradientDrawable gradient(int[] fills, float radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, fills);
        drawable.setCornerRadius(dp(radius)); return drawable;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }

    private void beginBackup() {
        if (!BackupOptions.includeSms(this) && !BackupOptions.includeCalls(this)
                && !BackupOptions.includeRestrictedMessages(this)) {
            Toast.makeText(this, getString(R.string.backup_choose_content), Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensureRestrictedMessagesSmsRole()) return;
        if (ScheduleManager.folder(this) == null) {
            pendingBackupFolder = true;
            chooseBackupFolder(true, false);
            return;
        }
        String[] required = permissionsForScopes(false);
        if (!hasPermissions(required)) {
            requestPermissions(required, REQUEST_BACKUP_PERMISSIONS);
        } else startManualBackupFlow();
    }

    private void startManualBackupFlow() {
        if (!ensureRestrictedMessagesSmsRole()) return;
        if (ScheduleManager.folder(this) == null) {
            pendingBackupFolder = true;
            chooseBackupFolder(true, false);
            return;
        }
        if (!BackupOptions.encrypted(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.backup_unencrypted_title))
                    .setMessage(getString(R.string.backup_unencrypted_message))
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .setPositiveButton(getString(R.string.backup_unencrypted_action), (dialog, which) -> createManualBackup())
                    .show();
            return;
        }
        if (BackupOptions.encrypted(this) && !EncryptionPassword.isUsable(this)) {
            askManualEncryptionPassword(this::createManualBackup, true);
        } else createManualBackup();
    }

    private void createManualBackup() {
        if (!ensureRestrictedMessagesSmsRole()) return;
        try {
            selectedUri = ScheduleManager.newBackupFile(this);
            runBackup(null);
        } catch (Exception error) {
            offerRestrictedSmsRoleSwitchBack();
            Toast.makeText(this, getString(R.string.error_no_destination), Toast.LENGTH_LONG).show();
        }
    }

    private void beginRestore() {
        chooseSource();
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        boolean granted = true; for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) granted = false;
        if (!granted) {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show();
            if (requestCode == REQUEST_BACKUP_PERMISSIONS) offerRestrictedSmsRoleSwitchBack();
            if (requestCode == REQUEST_RESTORE_PERMISSIONS || requestCode == REQUEST_RESTORE_WRITE) {
                pendingRestoreWritePermission = false;
                pendingRestoreSms = false;
                pendingRestoreCalls = false;
                if (pendingRestore != null) closePendingRestore(pendingRestore);
                return;
            }
            return;
        }
        if (requestCode == REQUEST_BACKUP_PERMISSIONS) startManualBackupFlow();
        else if (requestCode == REQUEST_RESTORE_PERMISSIONS && pendingRestore != null) {
            startSelectedRestore(pendingRestore, pendingRestoreSms, pendingRestoreCalls);
        }
        else if (requestCode == REQUEST_RESTORE_WRITE && pendingRestore != null) {
            pendingRestoreWritePermission = false;
            ArchiveFormat.PreparedRestore prepared = pendingRestore;
            pendingRestore = null;
            continueSelectedRestore(prepared, pendingRestoreSms, pendingRestoreCalls);
            pendingRestoreSms = false;
            pendingRestoreCalls = false;
        }
        else if (requestCode == REQUEST_SCHEDULE_PERMISSIONS) {
            if (ScheduleManager.folder(this) == null) chooseBackupFolder(false, true);
            else showScheduleDialog();
        }
    }

    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
    }

    private void chooseSource() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("application/octet-stream");
        intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, REQUEST_OPEN);
    }

    private void chooseBackupFolder(boolean afterBackup, boolean afterSchedule) {
        pendingBackupFolder = afterBackup;
        pendingScheduleFolder = afterSchedule;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_BACKUP_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SMS_ROLE) {
            handleSmsRoleResult(resultCode);
            return;
        }
        if (requestCode == REQUEST_SMS_SWITCH_BACK) {
            if (!isDefaultSmsApp()) {
                temporarilyChangedSmsRole = false;
                previousSmsPackage = null;
                getSharedPreferences("restore_sms_role", MODE_PRIVATE).edit().clear().apply();
                restrictedSmsRoleChangedByUs = false;
                restrictedPreviousSmsPackage = null;
                getSharedPreferences(RESTRICTED_ROLE_PREFS, MODE_PRIVATE).edit().clear().apply();
            } else {
                Toast.makeText(this, getString(R.string.restore_switch_back_not_done), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_BACKUP_FOLDER) {
                pendingBackupFolder = false;
                pendingScheduleFolder = false;
                offerRestrictedSmsRoleSwitchBack();
            }
            return;
        }
        selectedUri = data.getData();
        if (requestCode == REQUEST_OPEN) {
            try (java.io.InputStream input = getContentResolver().openInputStream(selectedUri)) {
                if (ArchiveCrypto.requiresPassword(input)) askPassword(false);
                else inspectRestore(new char[0]);
            } catch (Exception error) {
                Toast.makeText(this, getString(R.string.error_wrong_password), Toast.LENGTH_LONG).show();
            }
        }
        else if (requestCode == REQUEST_BACKUP_FOLDER) {
            try {
                getContentResolver().takePersistableUriPermission(selectedUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                ScheduleManager.saveFolder(this, selectedUri);
                boolean resumeBackup = pendingBackupFolder;
                boolean resumeSchedule = pendingScheduleFolder;
                pendingBackupFolder = false;
                pendingScheduleFolder = false;
                refreshFolder();
                if (resumeBackup) beginBackup();
                else if (resumeSchedule) showScheduleDialog();
                refreshSchedule();
            } catch (SecurityException e) {
                Toast.makeText(this, getString(R.string.error_no_destination), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void configureSchedule() {
        if (BackupOptions.includeRestrictedMessages(this)) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.restricted_schedule_disabled_title))
                    .setMessage(getString(R.string.restricted_schedule_disabled_message))
                    .setPositiveButton(getString(R.string.action_continue), null).show();
            return;
        }
        if (!BackupOptions.includeSms(this) && !BackupOptions.includeCalls(this)) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.scope_dialog_title))
                    .setMessage(getString(R.string.schedule_scope_message))
                    .setPositiveButton(getString(R.string.action_continue), (d, w) -> chooseScope()).show();
            return;
        }
        if (BackupOptions.encrypted(this) && !EncryptionPassword.isUsable(this)) {
            openEncryptionSetup(this::continueScheduleConfiguration);
            return;
        }
        if (!BackupOptions.encrypted(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.schedule_encryption_optional_title))
                    .setMessage(getString(R.string.schedule_encryption_optional_message))
                    .setNegativeButton(getString(R.string.schedule_encryption_unencrypted_action),
                            (dialog, which) -> continueScheduleConfiguration())
                    .setPositiveButton(getString(R.string.schedule_encryption_enable_action),
                            (dialog, which) -> openEncryptionSetup(this::continueScheduleConfiguration))
                    .show();
            return;
        }
        continueScheduleConfiguration();
    }

    private void continueScheduleConfiguration() {
        if (!hasPermissions(permissionsForScopes(false))) {
            explainAndRequestSchedulePermissions();
            return;
        }
        if (ScheduleManager.folder(this) == null) chooseBackupFolder(false, true);
        else showScheduleDialog();
    }

    private void showScheduleDialog() {
        String[] options = {
                getString(R.string.schedule_off),
                getString(R.string.schedule_daily),
                getString(R.string.schedule_weekly),
                getString(R.string.schedule_monthly)
        };
        int current = ScheduleManager.mode(this);
        new AlertDialog.Builder(this).setTitle(getString(R.string.schedule_dialog_title))
                .setSingleChoiceItems(options, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == ScheduleManager.OFF) {
                        ScheduleManager.disable(this); refreshSchedule(); refreshBatteryBanner();
                    } else if (which == ScheduleManager.DAILY) {
                        chooseScheduleTime(ScheduleManager.DAILY, 0, 1);
                    } else if (which == ScheduleManager.WEEKLY) {
                        chooseScheduleWeekday();
                    } else {
                        chooseScheduleMonthday();
                    }
                }).setNegativeButton(getString(R.string.action_cancel), null).show();
    }

    private void chooseScheduleWeekday() {
        String[] weekdays = java.text.DateFormatSymbols.getInstance(Locale.getDefault()).getWeekdays();
        String[] labels = new String[7]; for (int i = 0; i < 7; i++) labels[i] = weekdays[i + 1];
        new AlertDialog.Builder(this).setTitle(getString(R.string.schedule_choose_day))
                .setSingleChoiceItems(labels, Math.max(0, ScheduleManager.weekday(this) - 1), (dialog, which) -> {
                    dialog.dismiss(); chooseScheduleTime(ScheduleManager.WEEKLY, which + 1, 1);
                }).setNegativeButton(getString(R.string.action_cancel), null).show();
    }

    private void chooseScheduleMonthday() {
        NumberPicker picker = new NumberPicker(this); picker.setMinValue(1); picker.setMaxValue(31); picker.setValue(ScheduleManager.monthday(this));
        LinearLayout wrapper = new LinearLayout(this); wrapper.setGravity(Gravity.CENTER); wrapper.setPadding(dp(24), 0, dp(24), 0); wrapper.addView(picker);
        new AlertDialog.Builder(this).setTitle(getString(R.string.schedule_choose_month_day)).setMessage(getString(R.string.schedule_month_day_hint))
                .setView(wrapper).setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(getString(R.string.action_continue), (dialog, which) -> chooseScheduleTime(ScheduleManager.MONTHLY, 1, picker.getValue())).show();
    }

    private void chooseScheduleTime(int mode, int weekday, int monthday) {
        TimePickerDialog picker = new TimePickerDialog(this, (view, hour, minute) -> {
            ScheduleManager.enable(this, mode, hour, minute, weekday, monthday);
            refreshSchedule(); refreshBatteryBanner(); maybeRequestBatteryExemption();
        }, ScheduleManager.hour(this), ScheduleManager.minute(this), android.text.format.DateFormat.is24HourFormat(this));
        picker.setTitle(getString(R.string.schedule_time)); picker.show();
    }

    private void refreshSchedule() { if (scheduleValue != null) scheduleValue.setText(ScheduleManager.label(this)); }
    private void refreshFolder() { if (folderValue != null) folderValue.setText(ScheduleManager.folderLabel(this)); }
    private void refreshRetention() { if (retentionValue != null) retentionValue.setText(retentionLabel(this)); }
    private void refreshScope() { if (scopeValue != null) scopeValue.setText(scopeLabel()); }

    private String scopeLabel() {
        boolean sms = BackupOptions.includeSms(this), calls = BackupOptions.includeCalls(this);
        boolean restricted = BackupOptions.includeRestrictedMessages(this);
        if (!sms && !calls && !restricted) return getString(R.string.scope_none);
        ArrayList<String> selected = new ArrayList<>();
        if (sms) selected.add(getString(R.string.scope_sms_short));
        if (calls) selected.add(getString(R.string.scope_calls_short));
        if (restricted) selected.add(getString(R.string.scope_restricted_short));
        return TextUtils.join(getString(R.string.scope_separator), selected);
    }

    private void chooseScope() {
        String[] labels = {getString(R.string.scope_sms_short), getString(R.string.scope_calls_short),
                getString(R.string.scope_restricted_short)};
        boolean[] selected = {BackupOptions.includeSms(this), BackupOptions.includeCalls(this),
                BackupOptions.includeRestrictedMessages(this)};
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), 0, dp(22), 0);
        content.addView(text(getString(R.string.scope_dialog_message), 12, color(R.color.muted)),
                new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < labels.length; i++) {
            CheckBox choice = new CheckBox(this);
            choice.setText(labels[i]);
            choice.setTextSize(14);
            choice.setTextColor(color(R.color.text_primary));
            choice.setChecked(selected[i]);
            final int index = i;
            choice.setOnCheckedChangeListener((button, checked) -> selected[index] = checked);
            content.addView(choice, new LinearLayout.LayoutParams(-1, dp(52)));
            String subtitle = i == 0 ? getString(R.string.scope_sms_subtitle)
                    : i == 1 ? getString(R.string.scope_calls_subtitle)
                    : getString(R.string.restricted_messages_subtitle);
            TextView subtitleView = text(subtitle, 11, color(R.color.muted));
            subtitleView.setPadding(dp(12), 0, dp(12), dp(8));
            content.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
            if (i == 2) {
                TextView warning = text(getString(R.string.restricted_scope_warning), 12, color(R.color.amber));
                warning.setPadding(dp(12), 0, dp(12), dp(8));
                warning.setVisibility(selected[2] ? View.VISIBLE : View.GONE);
                choice.setOnCheckedChangeListener((button, checked) -> {
                    selected[index] = checked;
                    warning.setVisibility(checked ? View.VISIBLE : View.GONE);
                });
                content.addView(warning, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        new AlertDialog.Builder(this).setTitle(getString(R.string.scope_dialog_title))
                .setView(content)
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(getString(R.string.action_save), (dialog, which) -> {
                    boolean scheduleWasEnabled = ScheduleManager.enabled(this);
                    boolean restrictedWasSelected = BackupOptions.includeRestrictedMessages(this);
                    boolean hadPausedSchedule = ScheduleManager.restrictedSchedulePaused(this);
                    if (selected[2]) selected[0] = true;
                    BackupOptions.setIncludeSms(this, selected[0]);
                    BackupOptions.setIncludeCalls(this, selected[1]);
                    BackupOptions.setIncludeRestrictedMessages(this, selected[2]);
                    if (!selected[2]) {
                        offerRestrictedSmsRoleSwitchBack();
                        restrictedSmsRoleChangedByUs = false;
                        restrictedPreviousSmsPackage = null;
                        getSharedPreferences(RESTRICTED_ROLE_PREFS, MODE_PRIVATE).edit().clear().apply();
                    }
                    refreshScope();
                    refreshSchedule();
                    if (selected[2] && scheduleWasEnabled) {
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.restricted_schedule_disabled_title))
                                .setMessage(getString(R.string.restricted_schedule_disabled_message))
                                .setPositiveButton(getString(R.string.action_continue), null).show();
                    } else if (!selected[2] && restrictedWasSelected && hadPausedSchedule
                            && ScheduleManager.enabled(this)) {
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.restricted_schedule_restored_title))
                                .setMessage(getString(R.string.restricted_schedule_restored_message))
                                .setPositiveButton(getString(R.string.action_continue), null).show();
                    }
                    if (selected[2] && !isDefaultSmsApp()) {
                        new Handler(Looper.getMainLooper()).post(() -> promptForRestrictedMessagesSmsRole(false));
                    }
                }).show();
    }

    private String[] permissionsForScopes(boolean restore) {
        List<String> permissions = new ArrayList<>();
        if (BackupOptions.includeSms(this) || BackupOptions.includeRestrictedMessages(this))
            permissions.add(Manifest.permission.READ_SMS);
        if (BackupOptions.includeCalls(this)) permissions.add(Manifest.permission.READ_CALL_LOG);
        if (restore && BackupOptions.includeCalls(this)) permissions.add(Manifest.permission.WRITE_CALL_LOG);
        return permissions.toArray(new String[0]);
    }

    private void explainAndRequestSchedulePermissions() {
        String[] required = permissionsForScopes(false);
        if (required.length == 0) { chooseScope(); return; }
        new AlertDialog.Builder(this).setTitle(getString(R.string.permission_title))
                .setMessage(getString(R.string.permission_backup_message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(getString(R.string.action_continue), (dialog, which) ->
                        requestPermissions(required, REQUEST_SCHEDULE_PERMISSIONS)).show();
    }

    private void openEncryptionSetup() { openEncryptionSetup(null); }

    private void openEncryptionSetup(Runnable afterSaved) {
        if (encryptionSwitch == null) return;
        if (!BackupOptions.encrypted(this)) {
            changingEncryption = true;
            encryptionSwitch.setChecked(true);
            changingEncryption = false;
        }
        if (!EncryptionPassword.isUsable(this)) {
            askManualEncryptionPassword(afterSaved == null ? () -> {} : afterSaved, true);
        } else if (afterSaved != null) {
            afterSaved.run();
        }
    }

    private void openExactAlarmSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
            else openSettings();
        } catch (Exception ignored) { openSettings(); }
    }

    private void requestSmsAccessForSchedule() {
        if (isDefaultSmsApp()) {
            openSettings();
        } else if (!requestDefaultSmsRole()) {
            openSettings();
        }
    }

    private boolean ensureRestrictedMessagesSmsRole() {
        if (!BackupOptions.includeRestrictedMessages(this) || isDefaultSmsApp()) return true;
        promptForRestrictedMessagesSmsRole(true);
        return false;
    }

    private void promptForRestrictedMessagesSmsRole(boolean resumeBackup) {
        if (isDefaultSmsApp()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.restricted_default_sms_title))
                .setMessage(getString(R.string.restricted_default_sms_message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(getString(R.string.restricted_default_sms_action), (dialog, which) -> {
                    pendingBackupAfterSmsRole = resumeBackup;
                    restrictedSmsRoleRequestPending = true;
                    restrictedPreviousSmsPackage = defaultSmsPackage();
                    if (!requestDefaultSmsRole()) {
                        pendingBackupAfterSmsRole = false;
                        restrictedSmsRoleRequestPending = false;
                        showRestrictedSmsRoleFailure();
                    }
                }).show();
    }

    private void showRestrictedSmsRoleFailure() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.restricted_default_sms_unavailable_title))
                .setMessage(getString(R.string.restricted_default_sms_unavailable_message))
                .setPositiveButton(getString(R.string.action_ok), null)
                .show();
    }

    private void offerRestrictedSmsRoleSwitchBack() {
        if (!restrictedSmsRoleChangedByUs || !isDefaultSmsApp()) return;
        SharedPreferences.Editor editor = getSharedPreferences("restore_sms_role", MODE_PRIVATE).edit()
                .putBoolean("switch_back_pending", true)
                .putString("switch_back_reason", "restricted");
        if (restrictedPreviousSmsPackage != null) editor.putString("previous_package", restrictedPreviousSmsPackage);
        editor.apply();
        if (!switchBackDialogShown) showSwitchBackDialog();
    }

    private void refreshStatus() {
        if (status == null) return;
        SharedPreferences prefs = ScheduleManager.prefs(this);
        long manualSuccess = prefs.getLong("manual_last_success", 0);
        long manualAttempt = prefs.getLong("manual_last_attempt", 0);
        long scheduledSuccess = prefs.getLong("last_success", 0);
        long scheduledAttempt = prefs.getLong("last_attempt", 0);
        String scheduledError = prefs.getString("last_error", "");
        String manualError = prefs.getString("manual_last_error", "");
        if (!scheduledError.isEmpty() && scheduledAttempt >= scheduledSuccess
                && scheduledAttempt >= manualSuccess && scheduledAttempt > 0) {
                    setStatus(getString(R.string.status_backup_failed, formatStatusTime(scheduledAttempt)),
                            getString(R.string.status_detail_error, getString(R.string.status_scheduled_backup), scheduledError));
        } else if (!manualError.isEmpty() && manualAttempt >= manualSuccess
                && manualAttempt >= scheduledSuccess && manualAttempt > 0) {
                    setStatus(getString(R.string.status_backup_failed, formatStatusTime(manualAttempt)),
                            getString(R.string.status_detail_error, getString(R.string.status_manual_backup), manualError));
        } else {
            long latest = Math.max(manualSuccess, scheduledSuccess);
            if (latest > 0) {
                String source = scheduledSuccess >= manualSuccess
                        ? getString(R.string.status_scheduled_backup)
                        : getString(R.string.status_manual_backup);
                setStatus(getString(R.string.status_last_backup, formatStatusTime(latest)), source);
            } else {
                setStatus(getString(R.string.status_ready), null);
            }
        }
    }

    private String formatStatusTime(long time) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT, Locale.getDefault()).format(new Date(time));
    }

    @Override protected void onResume() {
        super.onResume();
        ScheduleManager.reconcile(this);
        maybeOpenPendingSmsSwitchBack();
        refreshStatus(); refreshFolder(); refreshSchedule(); refreshRetention(); refreshScope(); refreshBatteryBanner();
    }

    private void maybeOpenPendingSmsSwitchBack() {
        if (pendingRestoreAfterSmsRole || pendingRestore != null || switchBackSettingsOpened) return;
        android.content.SharedPreferences rolePrefs = getSharedPreferences("restore_sms_role", MODE_PRIVATE);
        if (rolePrefs.getBoolean("switch_back_pending", false) && isDefaultSmsApp()) {
            if (!switchBackDialogShown) showSwitchBackDialog();
        }
    }

    private String retentionLabel(Context context) {
        RetentionPolicy policy = ScheduleManager.retentionPolicy(context);
        if (policy.keepAll()) return context.getString(R.string.retention_all_sets);
        if (policy.mode == RetentionPolicy.Mode.PERIODIC) {
            return context.getString(R.string.retention_periodic_summary,
                    context.getString(R.string.retention_periodic_title),
                    policy.dailyKeep, policy.weeklyKeep, policy.monthlyKeep);
        }
        return context.getResources().getQuantityString(R.plurals.backup_sets_count,
                policy.simpleKeep, policy.simpleKeep);
    }

    private void retentionDialog() {
        RetentionPolicy current = ScheduleManager.retentionPolicy(this);
        final AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout titleBox = new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(24), dp(20), dp(24), 0);
        titleBox.addView(text(getString(R.string.retention_dialog_title), 18, color(R.color.text_primary)));
        TextView currentValue = text(getString(R.string.retention_current, retentionLabel(this)), 12, color(R.color.muted));
        currentValue.setPadding(0, dp(6), 0, 0); titleBox.addView(currentValue);

        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);
        form.addView(optionRow(getString(R.string.retention_simple_title), getString(R.string.retention_simple_tag),
                getString(R.string.retention_note_simple), R.color.good,
                getString(R.string.retention_explain_simple), current.mode == RetentionPolicy.Mode.SIMPLE,
                v -> { if (dialog[0] != null) dialog[0].dismiss(); keepCountDialog(); }));
        View divider = new View(this); divider.setBackgroundColor(color(R.color.button_surface));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(dp(4), dp(6), dp(4), dp(6)); form.addView(divider, dividerParams);
        form.addView(optionRow(getString(R.string.retention_periodic_title), getString(R.string.retention_advanced),
                getString(R.string.retention_note_advanced), R.color.amber,
                getString(R.string.retention_explain_periodic), current.mode == RetentionPolicy.Mode.PERIODIC,
                v -> { if (dialog[0] != null) dialog[0].dismiss(); periodicRetentionDialog(); }));
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.addView(form);
        dialog[0] = new AlertDialog.Builder(this).setCustomTitle(titleBox).setView(scroll)
                .setNegativeButton(getString(R.string.action_cancel), null).create();
        dialog[0].show();
    }

    private LinearLayout optionRow(String title, String tag, String note, int noteColor,
            String subtitle, boolean selected, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(2), dp(6), dp(2), dp(6));
        RadioButton radio = new RadioButton(this); radio.setChecked(selected); radio.setClickable(false); radio.setFocusable(false);
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        if (note != null) words.addView(text(note, 11, color(noteColor)));
        LinearLayout titleLine = new LinearLayout(this); titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.addView(text(title, 14, color(R.color.text_primary)), new LinearLayout.LayoutParams(0, -2, 1));
        titleLine.addView(text(tag, 11, color(R.color.muted))); words.addView(titleLine);
        words.addView(text(subtitle, 11, color(R.color.muted)));
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1);
        wordParams.setMarginEnd(dp(12)); row.addView(words, wordParams);
        row.addView(radio, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, -2));
        row.setOnClickListener(onClick); return row;
    }

    private void keepCountDialog() {
        RetentionPolicy current = ScheduleManager.retentionPolicy(this);
        int[] values = {1, 3, 5, 10, RetentionPolicy.KEEP_ALL};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] == RetentionPolicy.KEEP_ALL
                    ? getString(R.string.retention_all_sets)
                    : getResources().getQuantityString(R.plurals.backup_sets_count, values[i], values[i]);
        }
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current.simpleKeep) checked = i;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0); form.addView(text(getString(R.string.retention_keep_title), 18, color(R.color.text_primary)));
        RadioGroup group = new RadioGroup(this); final int[] selected = {checked};
        for (int i = 0; i < values.length; i++) {
            final int option = i; RadioButton button = new RadioButton(this); button.setText(labels[i]);
            button.setTextColor(color(R.color.text_primary)); button.setTextSize(14); button.setId(2000 + i);
            button.setPadding(0, dp(4), 0, dp(4)); button.setOnClickListener(v -> selected[0] = option);
            group.addView(button); if (i == checked) group.check(button.getId());
        }
        group.setPadding(0, dp(10), 0, 0); form.addView(group);
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll)
                .setNegativeButton(getString(R.string.action_back), (d, w) -> { d.dismiss(); retentionDialog(); })
                .setPositiveButton(getString(R.string.action_save), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ScheduleManager.saveRetentionPolicy(this, new RetentionPolicy(RetentionPolicy.Mode.SIMPLE,
                    values[selected[0]], current.dailyKeep, current.weeklyKeep, current.monthlyKeep));
            refreshRetention(); dialog.dismiss();
        })); dialog.show();
    }

    private void periodicRetentionDialog() {
        RetentionPolicy current = ScheduleManager.retentionPolicy(this);
        int[] daily = {current.dailyKeep}, weekly = {current.weeklyKeep}, monthly = {current.monthlyKeep};
        final int[][] presets = {{7, 0, 0}, {7, 4, 0}, {7, 4, 3}, {7, 4, 12}};
        final String[] presetNames = {getString(R.string.retention_last_week), getString(R.string.retention_last_month),
                getString(R.string.retention_last_three_months), getString(R.string.retention_last_year)};
        int checkedPreset = presetIndex(presets, daily[0], weekly[0], monthly[0]);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        LinearLayout warning = new LinearLayout(this); warning.setGravity(Gravity.CENTER_VERTICAL);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10)); warning.setBackground(rounded(color(R.color.warning_bg), 12));
        warning.addView(text("⚠", 16, color(R.color.warning_ink)), new LinearLayout.LayoutParams(dp(28), -2));
        warning.addView(text(getString(R.string.retention_warning), 12, color(R.color.warning_ink)), new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(warning); form.addView(text(getString(R.string.retention_periodic_hint), 12, color(R.color.muted)));
        TextView dailyValue = text(String.valueOf(daily[0]), 18, color(R.color.text_primary)); dailyValue.setGravity(Gravity.CENTER);
        TextView weeklyValue = text(String.valueOf(weekly[0]), 18, color(R.color.text_primary)); weeklyValue.setGravity(Gravity.CENTER);
        TextView monthlyValue = text(String.valueOf(monthly[0]), 18, color(R.color.text_primary)); monthlyValue.setGravity(Gravity.CENTER);
        RadioGroup presetsGroup = new RadioGroup(this);
        Runnable refresh = () -> { dailyValue.setText(String.valueOf(daily[0])); weeklyValue.setText(String.valueOf(weekly[0])); monthlyValue.setText(String.valueOf(monthly[0])); int index = presetIndex(presets, daily[0], weekly[0], monthly[0]); if (index >= 0) presetsGroup.check(1000 + index); else presetsGroup.clearCheck(); };
        for (int i = 0; i < presets.length; i++) { final int option = i; RadioButton button = new RadioButton(this); button.setText(presetNames[i]); button.setTextColor(color(R.color.text_primary)); button.setTextSize(14); button.setId(1000 + i); button.setPadding(0, dp(4), 0, dp(4)); button.setOnClickListener(v -> { daily[0] = presets[option][0]; weekly[0] = presets[option][1]; monthly[0] = presets[option][2]; refresh.run(); }); presetsGroup.addView(button); }
        if (checkedPreset >= 0) presetsGroup.check(1000 + checkedPreset); form.addView(presetsGroup);
        TextView advancedToggle = text(getString(R.string.retention_advanced), 12, color(R.color.link)); advancedToggle.setPadding(0, dp(8), 0, 0); form.addView(advancedToggle);
        LinearLayout advanced = new LinearLayout(this); advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(stepperRow(getString(R.string.retention_daily), getString(R.string.retention_daily_hint), daily, dailyValue, refresh));
        advanced.addView(stepperRow(getString(R.string.retention_weekly), getString(R.string.retention_weekly_hint), weekly, weeklyValue, refresh));
        advanced.addView(stepperRow(getString(R.string.retention_monthly), getString(R.string.retention_monthly_hint), monthly, monthlyValue, refresh));
        form.addView(advanced); advanced.setVisibility(checkedPreset < 0 ? View.VISIBLE : View.GONE);
        advancedToggle.setOnClickListener(v -> advanced.setVisibility(advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll)
                .setNegativeButton(getString(R.string.action_back), (d, w) -> { d.dismiss(); retentionDialog(); })
                .setPositiveButton(getString(R.string.action_save), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (daily[0] + weekly[0] + monthly[0] == 0) { Toast.makeText(this, getString(R.string.retention_need_one), Toast.LENGTH_LONG).show(); return; }
            ScheduleManager.saveRetentionPolicy(this, new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, current.simpleKeep, daily[0], weekly[0], monthly[0])); refreshRetention(); dialog.dismiss();
        })); dialog.show();
    }

    private int presetIndex(int[][] presets, int daily, int weekly, int monthly) {
        for (int i = 0; i < presets.length; i++) if (presets[i][0] == daily && presets[i][1] == weekly && presets[i][2] == monthly) return i;
        return -1;
    }

    private LinearLayout stepperRow(String title, String hint, int[] holder, TextView value, Runnable onChange) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(14), dp(10), dp(14), dp(10)); row.setBackground(rounded(color(R.color.panel), 13));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); words.addView(text(title, 14, color(R.color.text_primary))); words.addView(text(hint, 11, color(R.color.muted)));
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1); wordParams.setMarginEnd(dp(10)); row.addView(words, wordParams);
        TextView minus = stepButton("−"), plus = stepButton("+");
        minus.setOnClickListener(v -> { if (holder[0] > 0) { holder[0]--; value.setText(String.valueOf(holder[0])); onChange.run(); } });
        plus.setOnClickListener(v -> { if (holder[0] < 99) { holder[0]++; value.setText(String.valueOf(holder[0])); onChange.run(); } });
        row.addView(minus, new LinearLayout.LayoutParams(dp(46), dp(42))); row.addView(value, new LinearLayout.LayoutParams(dp(48), dp(42))); row.addView(plus, new LinearLayout.LayoutParams(dp(46), dp(42)));
        return row;
    }

    private TextView stepButton(String glyph) { TextView button = text(glyph, 22, color(R.color.accent_ink)); button.setGravity(Gravity.CENTER); button.setBackground(rounded(color(R.color.accent), 12)); return button; }

    private void askManualEncryptionPassword(Runnable saved, boolean revertOnCancel) {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), 0, dp(22), 0);
        EditText password = new EditText(this); password.setHint(getString(R.string.password_hint));
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(password, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText repeat = new EditText(this); repeat.setHint(getString(R.string.password_repeat_hint));
        repeat.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(repeat, new LinearLayout.LayoutParams(-1, dp(52)));
        Runnable revert = () -> {
            if (revertOnCancel) {
                changingEncryption = true;
                encryptionSwitch.setChecked(false);
                changingEncryption = false;
                BackupOptions.setEncrypted(this, false);
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.encryption_set_password_title))
                .setMessage(getString(R.string.encryption_set_password_message))
                .setView(fields)
                .setNegativeButton(getString(R.string.action_cancel), (d, w) -> revert.run())
                .setPositiveButton(getString(R.string.action_continue), null).create();
        dialog.setOnCancelListener(d -> revert.run());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.length() < 8) { password.setError(getString(R.string.password_error_length)); return; }
            if (!value.equals(repeat.getText().toString())) { repeat.setError(getString(R.string.password_error_mismatch)); return; }
            char[] secret = value.toCharArray();
            try {
                EncryptionPassword.save(this, secret);
                BackupOptions.setEncrypted(this, true);
                dialog.dismiss();
                saved.run();
            } catch (Exception error) {
                revert.run();
                Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_LONG).show();
            } finally {
                java.util.Arrays.fill(secret, '\0');
                password.setText(""); repeat.setText("");
            }
        }));
        dialog.show();
    }

    private void encryptionChanged(boolean enabled) {
        if (changingEncryption) return;
        if (enabled) {
            if (EncryptionPassword.isUsable(this)) {
                BackupOptions.setEncrypted(this, true);
            } else {
                askManualEncryptionPassword(() -> {}, true);
            }
            return;
        }
        new AlertDialog.Builder(this).setTitle(getString(R.string.encryption_disable_title))
                .setMessage(getString(R.string.encryption_disable_message))
                .setNegativeButton(getString(R.string.action_cancel), (dialog, which) -> {
                    changingEncryption = true; encryptionSwitch.setChecked(true); changingEncryption = false;
                })
                .setPositiveButton(getString(R.string.encryption_disable_action), (dialog, which) -> {
                    BackupOptions.setEncrypted(this, false);
                    EncryptionPassword.clear(this);
                    ScheduleManager.reconcile(this);
                    refreshSchedule();
                }).setOnCancelListener(dialog -> {
                    changingEncryption = true; encryptionSwitch.setChecked(true); changingEncryption = false;
                }).show();
    }

    private void showDisplayMenu() {
        final AlertDialog[] menu = new AlertDialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), 0);

        TextView themeValue = text(themeLabel(), 13, color(R.color.link));
        content.addView(setting(getString(R.string.display_theme),
                getString(R.string.display_theme_subtitle), themeValue,
                v -> { if (menu[0] != null) menu[0].dismiss(); showThemeDialog(); }),
                new LinearLayout.LayoutParams(-1, -2));

        TextView languageValue = text(languageLabel(), 13, color(R.color.link));
        LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(-1, -2);
        languageParams.setMargins(0, dp(8), 0, 0);
        content.addView(setting(getString(R.string.display_language),
                getString(R.string.display_language_subtitle), languageValue,
                v -> { if (menu[0] != null) menu[0].dismiss(); languageDialog(); }), languageParams);

        menu[0] = new AlertDialog.Builder(this).setTitle(getString(R.string.display_title))
                .setView(content).setNegativeButton(getString(R.string.action_cancel), null).create();
        menu[0].show();
    }

    private String themeLabel() {
        switch (ThemeMode.get(this)) {
            case ThemeMode.LIGHT: return getString(R.string.display_light);
            case ThemeMode.DARK: return getString(R.string.display_dark);
            default: return getString(R.string.display_system);
        }
    }

    private String languageLabel() {
        String tag = LocaleHelper.currentTag(this);
        return tag == null || tag.isEmpty() ? getString(R.string.language_system_default)
                : LocaleHelper.displayName(tag);
    }

    private void showThemeDialog() {
        String[] choices = {getString(R.string.display_system), getString(R.string.display_light), getString(R.string.display_dark)};
        new AlertDialog.Builder(this).setTitle(getString(R.string.display_dialog_title))
                .setSingleChoiceItems(choices, ThemeMode.get(this), (dialog, which) -> {
                    ThemeMode.set(this, which);
                    dialog.dismiss();
                    recreate();
                }).setNegativeButton(getString(R.string.action_cancel), null).show();
    }

    private void languageDialog() {
        String[] tags = LocaleHelper.SUPPORTED;
        String[] labels = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            labels[i] = tags[i].isEmpty() ? getString(R.string.language_system_default)
                    : LocaleHelper.displayName(tags[i]);
        }
        String current = LocaleHelper.currentTag(this);
        int checked = 0;
        for (int i = 0; i < tags.length; i++) if (tags[i].equals(current)) { checked = i; break; }
        new AlertDialog.Builder(this).setTitle(getString(R.string.display_language))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    LocaleHelper.setLanguage(this, tags[which]);
                    dialog.dismiss(); recreate();
                }).setNegativeButton(getString(R.string.action_cancel), null).show();
    }

    private boolean batteryOptimizationActive() {
        if (Build.VERSION.SDK_INT < 23) return false;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && !power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refreshBatteryBanner() {
        if (batteryBanner == null) return;
        boolean show = ScheduleManager.enabled(this) && batteryOptimizationActive();
        batteryBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) batteryBanner.setText(getString(R.string.battery_banner));
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT < 23 || !batteryOptimizationActive()) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignoredAgain) {
                openSettings();
            }
        }
    }

    private void maybeRequestBatteryExemption() {
        if (!batteryOptimizationActive()) return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.battery_nudge_title)).setMessage(getString(R.string.battery_nudge_message))
                .setNegativeButton(getString(R.string.battery_action_later), null)
                .setPositiveButton(getString(R.string.battery_action_allow), (d, w) -> openBatterySettings()).show();
    }

    private LinearLayout setting(String title, String subtitle, TextView value, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this); box.setGravity(Gravity.CENTER_VERTICAL); box.setPadding(dp(isRtl() ? 10 : 14), dp(12), dp(isRtl() ? 14 : 10), dp(12)); box.setBackground(rounded(color(R.color.panel), 15));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 15, color(R.color.text_primary))); words.addView(text(subtitle, 11, color(R.color.muted)));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.setMarginEnd(dp(8)); box.addView(words, wordsParams);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); value.setMaxLines(2); value.setEllipsize(TextUtils.TruncateAt.END); value.setPadding(isRtl() ? dp(8) : 0, 0, isRtl() ? 0 : dp(8), 0);
        box.addView(value, new LinearLayout.LayoutParams(dp(120), dp(44))); TextView arrow = text(isRtl() ? "‹" : "›", 24, color(R.color.muted)); arrow.setGravity(Gravity.CENTER); box.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(44))); box.setOnClickListener(click);
        return box;
    }

    private LinearLayout settingToggle(String title, String subtitle, Switch toggle) {
        LinearLayout box = new LinearLayout(this); box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(isRtl() ? 10 : 14), dp(12), dp(isRtl() ? 14 : 10), dp(12)); box.setBackground(rounded(color(R.color.panel), 15));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 15, color(R.color.text_primary))); words.addView(text(subtitle, 11, color(R.color.muted)));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.setMarginEnd(dp(8)); box.addView(words, wordsParams);
        box.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(44)));
        return box;
    }

    private void askPassword(boolean creating) {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), 0, dp(22), 0);
        EditText password = new EditText(this); password.setHint(getString(R.string.password_hint));
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(password, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText repeat = null;
        if (creating) {
            repeat = new EditText(this); repeat.setHint(getString(R.string.password_repeat_hint));
            repeat.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            fields.addView(repeat, new LinearLayout.LayoutParams(-1, dp(52)));
        }
        final EditText repeatField = repeat;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(creating ? getString(R.string.dialog_backup_password_title) : getString(R.string.dialog_restore_password_title))
                .setMessage(creating ? getString(R.string.password_message_backup) : getString(R.string.password_message_restore))
                .setView(fields).setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(creating ? getString(R.string.action_continue) : getString(R.string.action_restore), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String first = password.getText().toString();
            if (creating && first.length() < 8) { password.setError(getString(R.string.password_error_length)); return; }
            if (creating && !first.equals(repeatField.getText().toString())) {
                repeatField.setError(getString(R.string.password_error_mismatch)); return;
            }
            dialog.dismiss();
            char[] secret = first.toCharArray();
            if (creating) runBackup(secret); else inspectRestore(secret);
            password.setText(""); if (repeatField != null) repeatField.setText("");
        }));
        dialog.show();
    }

    private void setBusy(boolean busy, String message) {
        setStatus(message, null); progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        backupButton.setEnabled(!busy); restoreButton.setEnabled(!busy);
        if (includeSms != null) includeSms.setEnabled(!busy);
        if (includeCalls != null) includeCalls.setEnabled(!busy);
        if (encryptionSwitch != null) encryptionSwitch.setEnabled(!busy);
    }

    private void runBackup(char[] password) {
        final Uri initialDestination = selectedUri;
        if (initialDestination == null) {
            Toast.makeText(this, getString(R.string.error_no_destination), Toast.LENGTH_LONG).show();
            return;
        }
        final Uri[] destination = {initialDestination};
        setBusy(true, getString(R.string.status_backing_up));
        executor.execute(() -> {
            char[] secret = password;
            long attempt = System.currentTimeMillis();
            ScheduleManager.prefs(this).edit().putLong("manual_last_attempt", attempt).apply();
            try {
                if (BackupOptions.encrypted(this)) secret = EncryptionPassword.load(this);
                if (BackupOptions.encrypted(this) && secret == null) throw new IllegalStateException(getString(R.string.encryption_password_missing));
                ArchiveFormat.BackupSummary summary = ArchiveFormat.create(this, destination[0], secret,
                        BackupOptions.encrypted(this), BackupOptions.includeSms(this), BackupOptions.includeCalls(this),
                        BackupOptions.includeRestrictedMessages(this));
                destination[0] = ScheduleManager.publishBackupFile(this, destination[0]);
                try { ScheduleManager.prune(this); } catch (Exception ignored) { }
                ScheduleManager.prefs(this).edit()
                        .putLong("manual_last_success", System.currentTimeMillis())
                        .remove("manual_last_error").apply();
                runOnUiThread(() -> {
                    String completed = BackupSummaryFormatter.format(this, summary);
                    setBusy(false, completed);
                    Toast.makeText(this, getString(R.string.notice_backup_complete_title), Toast.LENGTH_SHORT).show();
                    if (BackupOptions.includeRestrictedMessages(this)
                            && restrictedSmsRoleChangedByUs && isDefaultSmsApp()) {
                        SharedPreferences rolePrefs = getSharedPreferences("restore_sms_role", MODE_PRIVATE);
                        SharedPreferences.Editor editor = rolePrefs.edit()
                                .putBoolean("switch_back_pending", true)
                                .putString("switch_back_reason", "backup");
                        String previous = restrictedPreviousSmsPackage;
                        if (previous != null) editor.putString("previous_package", previous);
                        editor.apply();
                        showSwitchBackDialog();
                    }
                });
            } catch (Exception error) {
                try { getContentResolver().delete(destination[0], null, null); } catch (Exception ignored) { }
                final String failure = error instanceof SmsAccess.RestrictedException
                        ? getString(R.string.error_sms_restricted) : getString(R.string.error_generic);
                ScheduleManager.prefs(this).edit().putString("manual_last_error", failure).apply();
                runOnUiThread(() -> { setBusy(false, failure);
                    setStatus(getString(R.string.status_backup_failed, formatStatusTime(attempt)),
                            getString(R.string.status_detail_error, getString(R.string.status_manual_backup), failure));
                    Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
                    offerRestrictedSmsRoleSwitchBack(); });
            }
        });
    }

    private void inspectRestore(char[] password) {
        final Uri source = selectedUri;
        setBusy(true, getString(R.string.status_checking_restore));
        executor.execute(() -> {
            try {
                ArchiveFormat.PreparedRestore prepared = ArchiveFormat.prepareRestore(this, source, password);
                runOnUiThread(() -> {
                    setBusy(false, getString(R.string.status_ready));
                    pendingRestore = prepared;
                    showRestoreSelection(prepared);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { setBusy(false, getString(R.string.error_wrong_password));
                    Toast.makeText(this, getString(R.string.notice_failed_title), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void showRestoreSelection(ArchiveFormat.PreparedRestore prepared) {
        int smsCount = prepared.summary.smsCount;
        int callsCount = prepared.summary.callsCount;
        int messageCount = smsCount + prepared.summary.mmsCount + prepared.summary.rcsCount;
        if (messageCount == 0 && callsCount == 0) {
            prepared.close();
            if (pendingRestore == prepared) pendingRestore = null;
            Toast.makeText(this, getString(R.string.restore_no_records), Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), 0, dp(22), 0);
        content.addView(text(getString(R.string.restore_choose_message), 12, color(R.color.muted)),
                new LinearLayout.LayoutParams(-1, -2));
        if (!prepared.encrypted) {
            TextView warning = text(getString(R.string.restore_unencrypted_warning), 12, color(R.color.call_orange));
            LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(-1, -2);
            warningParams.setMargins(0, dp(8), 0, 0);
            content.addView(warning, warningParams);
        }

        String smsLabel = prepared.summary.mmsCount > 0 || prepared.summary.rcsCount > 0
                ? getString(R.string.restore_messages_option, messageCount)
                : getString(R.string.restore_sms_option, smsCount);
        String callsLabel = getString(R.string.restore_calls_option, callsCount);
        CheckBox sms = new CheckBox(this);
        CheckBox calls = new CheckBox(this);
        sms.setChecked(messageCount > 0);
        calls.setChecked(callsCount > 0);
        sms.setContentDescription(smsLabel);
        calls.setContentDescription(callsLabel);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            sms.setButtonTintList(android.content.res.ColorStateList.valueOf(color(R.color.sms_blue)));
            calls.setButtonTintList(android.content.res.ColorStateList.valueOf(color(R.color.call_orange)));
        }
        sms.setEnabled(smsCount > 0);
        calls.setEnabled(callsCount > 0);
        LinearLayout smsRow = restoreOptionRow(sms, smsLabel);
        LinearLayout callsRow = restoreOptionRow(calls, callsLabel);
        content.addView(smsRow, new LinearLayout.LayoutParams(-1, dp(52)));
        content.addView(callsRow, new LinearLayout.LayoutParams(-1, dp(52)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_choose_title))
                .setView(content)
                .setNegativeButton(getString(R.string.action_cancel), (d, w) -> closePendingRestore(prepared))
                .setPositiveButton(getString(R.string.action_restore), null)
                .create();
        dialog.setOnCancelListener(d -> closePendingRestore(prepared));
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!sms.isChecked() && !calls.isChecked()) {
                Toast.makeText(this, getString(R.string.restore_choose_one), Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            startSelectedRestore(prepared, sms.isChecked(), calls.isChecked());
        }));
        dialog.show();
    }

    private LinearLayout restoreOptionRow(CheckBox box, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(box, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView words = text(label, 14, color(R.color.text_primary));
        row.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        row.setOnClickListener(v -> box.toggle());
        return row;
    }

    private void closePendingRestore(ArchiveFormat.PreparedRestore prepared) {
        prepared.close();
        if (retainedRestoreAcrossSmsRole == prepared) retainedRestoreAcrossSmsRole = null;
        if (pendingRestore == prepared) pendingRestore = null;
    }

    private void startSelectedRestore(ArchiveFormat.PreparedRestore prepared,
            boolean restoreSms, boolean restoreCalls) {
        String[] readPermissions = restoreReadPermissions(restoreSms, restoreCalls);
        if (!hasPermissions(readPermissions)) {
            pendingRestore = prepared;
            pendingRestoreSms = restoreSms;
            pendingRestoreCalls = restoreCalls;
            pendingRestoreWritePermission = false;
            requestPermissions(readPermissions, REQUEST_RESTORE_PERMISSIONS);
            return;
        }
        if (restoreCalls && checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            pendingRestore = prepared;
            pendingRestoreSms = restoreSms;
            pendingRestoreCalls = restoreCalls;
            pendingRestoreWritePermission = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_CALL_LOG}, REQUEST_RESTORE_WRITE);
            return;
        }
        continueSelectedRestore(prepared, restoreSms, restoreCalls);
    }

    private String[] restoreReadPermissions(boolean restoreSms, boolean restoreCalls) {
        List<String> permissions = new ArrayList<>();
        if (restoreSms) permissions.add(Manifest.permission.READ_SMS);
        if (restoreSms) permissions.add(Manifest.permission.READ_CONTACTS);
        if (restoreCalls) permissions.add(Manifest.permission.READ_CALL_LOG);
        return permissions.toArray(new String[0]);
    }

    private void continueSelectedRestore(ArchiveFormat.PreparedRestore prepared,
            boolean restoreSms, boolean restoreCalls) {
        if (!restoreSms || isDefaultSmsApp()) {
            if (pendingRestore == prepared) pendingRestore = null;
            runRestore(prepared, restoreSms, restoreCalls);
            return;
        }
        pendingRestoreSms = restoreSms;
        pendingRestoreCalls = restoreCalls;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_default_sms_title))
                .setMessage(getString(R.string.restore_default_sms_message))
                .setNegativeButton(getString(R.string.restore_default_sms_cancel), (d, w) -> cancelPendingSmsRestore())
                .setPositiveButton(getString(R.string.restore_default_sms_action), (d, w) -> {
                    previousSmsPackage = defaultSmsPackage();
                    pendingRestoreAfterSmsRole = true;
                    temporarilyChangedSmsRole = true;
                    getSharedPreferences("restore_sms_role", MODE_PRIVATE).edit()
                            .putBoolean("switch_back_pending", true)
                            .putString("previous_package", previousSmsPackage).apply();
                    if (!requestDefaultSmsRole()) {
                        cancelPendingSmsRestore();
                        showSmsRoleFailure();
                    }
                }).create();
        dialog.setOnCancelListener(d -> cancelPendingSmsRestore());
        dialog.show();
    }

    private void cancelPendingSmsRestore() {
        pendingRestoreAfterSmsRole = false;
        pendingRestoreSms = false;
        pendingRestoreCalls = false;
        pendingRestoreWritePermission = false;
        temporarilyChangedSmsRole = false;
        previousSmsPackage = null;
        getSharedPreferences("restore_sms_role", MODE_PRIVATE).edit().clear().apply();
        if (pendingRestore != null) closePendingRestore(pendingRestore);
    }

    private void showSmsRoleFailure() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_default_sms_unavailable_title))
                .setMessage(getString(R.string.restore_default_sms_unavailable_message))
                .setPositiveButton(getString(R.string.action_ok), null)
                .show();
    }

    private String defaultSmsPackage() {
        try { return Telephony.Sms.getDefaultSmsPackage(this); }
        catch (Exception ignored) { return null; }
    }

    private boolean isDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roles = (RoleManager) getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)
                        && roles.isRoleHeld(RoleManager.ROLE_SMS)) return true;
            } catch (RuntimeException ignored) { }
        }
        return getPackageName().equals(defaultSmsPackage());
    }

    private boolean requestDefaultSmsRole() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = (RoleManager) getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_SMS_ROLE);
                    return true;
                }
            }
            Intent change = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
            change.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
            startActivityForResult(change, REQUEST_SMS_ROLE);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void handleSmsRoleResult(int resultCode) {
        if (pendingRestoreAfterSmsRole && pendingRestore != null) {
            // The result code can arrive before the role/provider state has settled;
            // verify the role itself before changing any phone data.
            checkSmsRoleAndContinue(0);
        } else if (pendingRestoreAfterSmsRole) {
            cancelPendingSmsRestore();
            showSmsRoleFailure();
        } else if (restrictedSmsRoleRequestPending) {
            checkRestrictedSmsRoleAndContinue(0);
        }
    }

    private void checkRestrictedSmsRoleAndContinue(int attempt) {
        if (!restrictedSmsRoleRequestPending) return;
        if (isDefaultSmsApp()) {
            restrictedSmsRoleRequestPending = false;
            restrictedSmsRoleChangedByUs = true;
            SharedPreferences.Editor editor = getSharedPreferences(RESTRICTED_ROLE_PREFS, MODE_PRIVATE).edit()
                    .putBoolean("changed_by_us", true);
            if (restrictedPreviousSmsPackage == null) editor.remove("previous_package");
            else editor.putString("previous_package", restrictedPreviousSmsPackage);
            editor.apply();
            if (pendingBackupAfterSmsRole) {
                pendingBackupAfterSmsRole = false;
                startManualBackupFlow();
            }
        } else if (attempt < 5) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> checkRestrictedSmsRoleAndContinue(attempt + 1), 250L);
        } else {
            pendingBackupAfterSmsRole = false;
            restrictedSmsRoleRequestPending = false;
            showRestrictedSmsRoleFailure();
        }
    }

    private void continueSmsRestore() {
        if (!pendingRestoreAfterSmsRole || pendingRestore == null) return;
        pendingRestoreAfterSmsRole = false;
        ArchiveFormat.PreparedRestore prepared = pendingRestore;
        pendingRestore = null;
        if (retainedRestoreAcrossSmsRole == prepared) retainedRestoreAcrossSmsRole = null;
        runRestore(prepared, pendingRestoreSms, pendingRestoreCalls);
        pendingRestoreSms = false;
        pendingRestoreCalls = false;
    }

    private void checkSmsRoleAndContinue(int attempt) {
        if (!pendingRestoreAfterSmsRole || pendingRestore == null) return;
        if (isDefaultSmsApp()) {
            pendingRestoreAfterSmsRole = false;
            ArchiveFormat.PreparedRestore prepared = pendingRestore;
            pendingRestore = null;
            if (retainedRestoreAcrossSmsRole == prepared) retainedRestoreAcrossSmsRole = null;
            runRestore(prepared, pendingRestoreSms, pendingRestoreCalls);
            pendingRestoreSms = false;
            pendingRestoreCalls = false;
        } else if (attempt < 5) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> checkSmsRoleAndContinue(attempt + 1), 250L);
        } else {
            cancelPendingSmsRestore();
            showSmsRoleFailure();
        }
    }

    private void openPreviousSmsAppChooser() {
        try {
            Intent selector = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            startActivityForResult(selector, REQUEST_SMS_SWITCH_BACK);
            switchBackSettingsOpened = true;
        } catch (Exception ignored) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), REQUEST_SMS_SWITCH_BACK);
                switchBackSettingsOpened = true;
            } catch (Exception ignoredAgain) {
                openSettings();
            }
        }
    }

    private void showSwitchBackDialog() {
        switchBackDialogShown = true;
        SharedPreferences rolePrefs = getSharedPreferences("restore_sms_role", MODE_PRIVATE);
        String reason = rolePrefs.getString("switch_back_reason", "restore");
        boolean backup = "backup".equals(reason);
        boolean restricted = "restricted".equals(reason);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(backup ? R.string.backup_switch_back_title
                        : restricted ? R.string.restricted_switch_back_title : R.string.restore_switch_back_title))
                .setMessage(getString(backup ? R.string.backup_switch_back_message
                        : restricted ? R.string.restricted_switch_back_message : R.string.restore_switch_back_message))
                .setNegativeButton(getString(R.string.restore_switch_back_later), null)
                .setPositiveButton(getString(backup ? R.string.backup_switch_back_action
                        : restricted ? R.string.restricted_switch_back_action : R.string.restore_switch_back_action),
                        (d, w) -> openPreviousSmsAppChooser())
                .create();
        dialog.show();
    }

    private void openSmsSettingsAfterRestore() {
        getSharedPreferences("restore_sms_role", MODE_PRIVATE).edit()
                .putString("switch_back_reason", "restore").apply();
        showSwitchBackDialog();
    }

    private void runRestore(ArchiveFormat.PreparedRestore prepared,
            boolean restoreSms, boolean restoreCalls) {
        // SMS restore may have started with this app already holding the role
        // after an earlier restore attempt. The user still explicitly asked to
        // choose their preferred SMS app again after every SMS restore.
        final boolean needsSmsSwitchBack = restoreSms;
        setBusy(true, getString(R.string.status_restoring));
        executor.execute(() -> {
            try {
                RestoreEngine.RestoreSummary summary = prepared.restore(this, restoreSms, restoreCalls);
                runOnUiThread(() -> {
                    int restoredMessages = summary.smsAdded + summary.mmsAdded + summary.rcsAdded;
                    int restoreText = summary.mmsAdded > 0 || summary.rcsAdded > 0
                            ? (summary.failed == 0 ? R.string.restore_summary_extended : R.string.restore_summary_extended_failed)
                            : (summary.failed == 0 ? R.string.restore_summary : R.string.restore_summary_failed);
                    setBusy(false, summary.mmsAdded > 0 || summary.rcsAdded > 0
                            ? getString(restoreText, summary.smsAdded, summary.callsAdded, summary.mmsAdded, summary.rcsAdded,
                            summary.failed == 0 ? summary.duplicates : summary.failed)
                            : getString(restoreText, summary.smsAdded, summary.callsAdded,
                            summary.failed == 0 ? summary.duplicates : summary.failed));
                    Toast.makeText(this, getString(R.string.notice_restore_complete_title), Toast.LENGTH_SHORT).show();
                    if (needsSmsSwitchBack) {
                        getSharedPreferences("restore_sms_role", MODE_PRIVATE)
                                .edit().putBoolean("switch_back_pending", true).apply();
                        openSmsSettingsAfterRestore();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> { setBusy(false, getString(R.string.error_generic));
                    Toast.makeText(this, getString(R.string.notice_failed_title), Toast.LENGTH_LONG).show(); });
            } finally {
                prepared.close();
            }
        });
    }
}
