package com.android.mycamera.utils;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public final class LocaleUtils {

    private LocaleUtils() {
    }

    public static void applySavedLanguage(Context context) {
        applyAppLanguage(new SettingsManager(context).getAppLanguage());
    }

    public static void applyAppLanguage(String language) {
        LocaleListCompat locales = getLocales(language);
        if (!AppCompatDelegate.getApplicationLocales().equals(locales)) {
            AppCompatDelegate.setApplicationLocales(locales);
        }
    }

    private static LocaleListCompat getLocales(String language) {
        if (SettingsManager.LANGUAGE_CHINESE.equals(language)
                || SettingsManager.LANGUAGE_ENGLISH.equals(language)) {
            return LocaleListCompat.forLanguageTags(language);
        }
        return LocaleListCompat.getEmptyLocaleList();
    }
}
