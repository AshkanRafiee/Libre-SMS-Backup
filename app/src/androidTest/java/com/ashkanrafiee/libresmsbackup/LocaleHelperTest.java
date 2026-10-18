package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

/** Verifies that language choices also establish the matching layout direction. */
public class LocaleHelperTest extends TestCase {
    public void testArabicAndPersianUseRtlDirection() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE);
        boolean hadLanguage = preferences.contains("language");
        String oldLanguage = preferences.getString("language", "");
        try {
            LocaleHelper.setLanguage(context, "ar");
            assertEquals(View.LAYOUT_DIRECTION_RTL,
                    LocaleHelper.wrap(context).getResources().getConfiguration().getLayoutDirection());
            LocaleHelper.setLanguage(context, "fa");
            assertEquals(View.LAYOUT_DIRECTION_RTL,
                    LocaleHelper.wrap(context).getResources().getConfiguration().getLayoutDirection());
            LocaleHelper.setLanguage(context, "en");
            assertEquals(View.LAYOUT_DIRECTION_LTR,
                    LocaleHelper.wrap(context).getResources().getConfiguration().getLayoutDirection());
        } finally {
            if (hadLanguage) preferences.edit().putString("language", oldLanguage).commit();
            else preferences.edit().remove("language").commit();
        }
    }
}
