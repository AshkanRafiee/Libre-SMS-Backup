package com.ashkanrafiee.libresmsbackup;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/** Stores the display choice without adding a UI framework dependency. */
final class ThemeMode {
    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;
    private static final String PREFS = "display";
    private static final String MODE = "theme_mode";

    private ThemeMode() {}

    static int get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MODE, SYSTEM);
    }

    static void set(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(MODE, mode).apply();
        if (Build.VERSION.SDK_INT >= 31) {
            UiModeManager manager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            if (manager != null) {
                manager.setApplicationNightMode(mode == DARK ? UiModeManager.MODE_NIGHT_YES
                        : mode == LIGHT ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_AUTO);
            }
        }
    }

    static Context wrap(Context base) {
        int mode = get(base);
        if (mode == SYSTEM) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(configuration);
    }
}
