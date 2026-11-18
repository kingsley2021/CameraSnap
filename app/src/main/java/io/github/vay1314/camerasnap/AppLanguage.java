package io.github.vay1314.camerasnap;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

final class AppLanguage {
    private static final String PREFERENCE = "app_language";

    static String normalize(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        Locale locale = Locale.forLanguageTag(tag);
        switch (locale.getLanguage()) {
            case "zh":
                String script = locale.getScript();
                if ("Hant".equals(script)) return "zh-TW";
                if ("Hans".equals(script)) return "zh-CN";
                String region = locale.getCountry();
                return "TW".equals(region) || "HK".equals(region) || "MO".equals(region) ? "zh-TW" : "zh-CN";
            case "en": return "en";
            case "de": return "de";
            case "ru": return "ru";
            default: return "";
        }
    }

    static String get(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty() ? "" : normalize(locales.get(0).toLanguageTag());
        }
        return normalize(SnapConfig.prefs(context).getString(PREFERENCE, ""));
    }

    static Context wrap(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return context;
        String tag = get(context);
        if (tag.isEmpty()) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(LocaleList.forLanguageTags(tag));
        return context.createConfigurationContext(configuration);
    }

    static void select(Activity activity, String tag) {
        String selected = normalize(tag);
        if (selected.equals(get(activity))) return;
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(selected));
        } else {
            SnapConfig.prefs(activity).edit().putString(PREFERENCE, selected).apply();
            activity.recreate();
        }
    }
}
