package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Stores and applies the optional app-language override used by the Display menu. */
public final class LocaleHelper {
    private static final String PREFS = "display";
    private static final String KEY_LANGUAGE = "language";

    /** Empty means follow the device language. */
    public static final String[] SUPPORTED = {"", "en", "fa", "fr", "de", "tr", "es", "zh", "ar"};

    private LocaleHelper() {}

    public static String currentTag(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "");
    }

    public static void setLanguage(Context context, String tag) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, tag).apply();
    }

    public static Context wrap(Context base) {
        String tag = currentTag(base);
        if (tag == null || tag.isEmpty()) return base;
        Locale locale = forTag(tag);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList locales = new LocaleList(locale);
            LocaleList.setDefault(locales);
            configuration.setLocales(locales);
        } else {
            Locale.setDefault(locale);
            configuration.locale = locale;
        }
        if (Build.VERSION.SDK_INT >= 17) configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    public static Locale forTag(String tag) {
        return "zh".equals(tag) ? Locale.SIMPLIFIED_CHINESE : new Locale(tag);
    }

    public static String displayName(String tag) {
        switch (tag) {
            case "en": return "English";
            case "fa": return "فارسی";
            case "fr": return "Français";
            case "de": return "Deutsch";
            case "tr": return "Türkçe";
            case "es": return "Español";
            case "zh": return "中文";
            case "ar": return "العربية";
            default: return tag;
        }
    }
}
