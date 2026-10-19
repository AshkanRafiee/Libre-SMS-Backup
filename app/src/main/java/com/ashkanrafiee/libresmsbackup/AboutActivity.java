package com.ashkanrafiee.libresmsbackup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Transparent project information and privacy page. */
public class AboutActivity extends Activity {
    private static final String WEBSITE = "https://libresmsbackup.ashkanrafiee.com/";
    private static final String SOURCE = "https://github.com/AshkanRafiee/Libre-SMS-Backup";
    private static final String ISSUES = SOURCE + "/issues";
    private static final String CONTACT_EMAIL = "libresmsbackup.eject095@passmail.net";
    private static final String CONTACT = "mailto:" + CONTACT_EMAIL;
    private static final String DONATION = WEBSITE + "#donate";

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private int color(int id) { return getResources().getColor(id); }

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemeMode.wrap(LocaleHelper.wrap(base)));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(color(R.color.bg));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); applyLocaleDirection(root);
        root.setPadding(dp(20), dp(14), dp(20), dp(14)); root.setBackgroundColor(color(R.color.bg));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top; int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            view.setPadding(dp(20), top + dp(14), dp(20), bottom + dp(14)); return insets;
        }); setContentView(root);

        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(rtl ? "›" : "‹", 34, color(R.color.text_primary)); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = text(getString(R.string.about_title), 21, color(R.color.text_primary));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2); titleParams.setMarginStart(dp(10)); bar.addView(title, titleParams);
        root.addView(bar, margins(0, 0, 0, 12));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18)); hero.setBackground(rounded(color(R.color.hero_grad_end), 22));
        TextView mark = text("LSB", 18, color(R.color.accent_ink)); mark.setGravity(Gravity.CENTER); mark.setTypeface(null, Typeface.BOLD); mark.setBackground(rounded(color(R.color.accent), 16));
        hero.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView appTitle = text(getString(R.string.app_name), 24, color(R.color.text_primary)); appTitle.setPadding(0, dp(14), 0, dp(2)); hero.addView(appTitle);
        hero.addView(text(getString(R.string.tagline_offline_encrypted), 13, color(R.color.subtitle)));
        body.addView(hero, margins(0, 0, 0, 14));
        body.addView(section(getString(R.string.about_section_heading), getString(R.string.about_section_body)), margins(0, 0, 0, 8));
        body.addView(info(getString(R.string.about_created_by_label), "Ashkan Rafiee", v -> open("https://ashkanrafiee.com")), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_license_label), "GNU General Public License v3.0", v -> open(SOURCE + "/blob/main/LICENSE")), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_source_label), "github.com/AshkanRafiee/Libre-SMS-Backup", v -> open(SOURCE)), margins(0, 0, 0, 6));
         body.addView(info(getString(R.string.about_website_label), "libresmsbackup.ashkanrafiee.com", v -> open(WEBSITE)), margins(0, 0, 0, 6));
         body.addView(info(getString(R.string.about_suggestions_label), "github.com/AshkanRafiee/Libre-SMS-Backup/issues", v -> open(ISSUES)), margins(0, 0, 0, 6));
         body.addView(info(getString(R.string.about_report_issue_label), getString(R.string.about_report_issue_value), v -> showReportDialog()), margins(0, 0, 0, 6));
         body.addView(info(getString(R.string.about_contact_label), "libresmsbackup.eject095@passmail.net", v -> open(CONTACT)), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_donate_label), "libresmsbackup.ashkanrafiee.com/#donate", v -> open(DONATION)), margins(0, 0, 0, 14));
        TextView footer = text(getString(R.string.about_footer, BuildConfig.VERSION_NAME), 11, color(R.color.text_tertiary)); footer.setGravity(Gravity.CENTER); body.addView(footer);
    }

    private TextView text(String value, float size, int textColor) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(textColor); if (Build.VERSION.SDK_INT >= 17) view.setTextDirection(View.TEXT_DIRECTION_LOCALE); return view; }
    private void applyLocaleDirection(View view) { if (Build.VERSION.SDK_INT >= 17) { view.setLayoutDirection(getResources().getConfiguration().getLayoutDirection()); view.setTextDirection(View.TEXT_DIRECTION_LOCALE); } }
    private TextView section(String heading, String body) { TextView view = text(heading + "\n" + body, 12, color(R.color.muted)); view.setLineSpacing(2, 1.05f); return view; }
    private LinearLayout info(String heading, String value, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(13), dp(16), dp(13)); box.setBackground(rounded(color(R.color.panel), 15));
        box.addView(text(heading, 12, color(R.color.muted)));
         TextView valueView = text(value, 14, click == null ? color(R.color.text_primary) : color(R.color.link)); if (Build.VERSION.SDK_INT >= 17) valueView.setTextDirection(View.TEXT_DIRECTION_LTR); valueView.setPadding(0, dp(5), 0, 0); valueView.setMaxLines(2); valueView.setEllipsize(TextUtils.TruncateAt.END);
        if (click != null) { valueView.setPaintFlags(valueView.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); box.setOnClickListener(click); }
        box.addView(valueView); return box;
    }
    private GradientDrawable rounded(int fill, float radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p; }
    private void open(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { android.widget.Toast.makeText(this, getString(R.string.about_no_browser), android.widget.Toast.LENGTH_SHORT).show(); } }

    private void showReportDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.report_issue_title))
                .setMessage(getString(R.string.report_issue_consent_message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setNeutralButton(getString(R.string.report_issue_without_diagnostics), (dialog, which) -> shareReport(false))
                .setPositiveButton(getString(R.string.report_issue_with_diagnostics), (dialog, which) -> shareReport(true))
                .show();
    }

    private void shareReport(boolean includeDiagnostics) {
        String body = getString(R.string.report_issue_body);
        try {
            if (includeDiagnostics) {
                body += "\n\n--- Anonymized diagnostics ---\n" + ReportDiagnostics.create(this);
            }
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_EMAIL, new String[]{CONTACT_EMAIL})
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_issue_subject))
                    .putExtra(Intent.EXTRA_TEXT, body);
            Intent chooser = Intent.createChooser(share, getString(R.string.report_issue_share_title));
            startActivity(chooser);
        } catch (Exception shareError) {
            try {
                Intent email = new Intent(Intent.ACTION_SENDTO, Uri.parse(CONTACT))
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_issue_subject))
                        .putExtra(Intent.EXTRA_TEXT, body);
                startActivity(email);
            } catch (Exception emailError) {
                Toast.makeText(this, getString(R.string.report_issue_no_app), Toast.LENGTH_LONG).show();
            }
        }
    }
}
