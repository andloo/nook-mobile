package com.nook.mobile.data;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多语言管理（FR-50 / §4.4）。
 * 从 assets/i18n 加载 6 种语言的扁平 key-value 词典，提供查值与占位符替换。
 * 应用级单例、按需加载：仅加载「当前语言 + 英文回退」两份词典
 * （原实现每个界面都新建实例并解析全部 6 份，重复占用内存与 IO）。
 * 对应桌面版 main.js 的 translations / i18n() / changeLang()。
 */
public final class I18nManager {

    private static final String TAG = "I18nManager";

    /** 语言代码 → 资源文件名。 */
    private static final Map<String, String> LANG_FILES = new LinkedHashMap<>();

    static {
        LANG_FILES.put("en", "Nook_English.json");
        LANG_FILES.put("es", "Nook_Spanish.json");
        LANG_FILES.put("de", "Nook_German.json");
        LANG_FILES.put("it", "Nook_Italian.json");
        LANG_FILES.put("fr", "Nook_French.json");
        LANG_FILES.put("cn", "Nook_Chinese.json");
    }

    private static final String DEFAULT_LANG = "en";

    /** 词典 JSON 类型（TypeToken 的泛型签名需随 R8 保留，见 proguard-rules.pro）。 */
    private static final Type DICT_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private static volatile I18nManager instance;

    /** 语言代码 → 词典（按需加载）。 */
    private final Map<String, Map<String, String>> dictionaries = new HashMap<>();
    private final Context appContext;
    private String currentLang = DEFAULT_LANG;

    private I18nManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 应用级单例：词典在进程内共享，避免每个界面重复解析 assets。 */
    public static I18nManager get(Context context) {
        if (instance == null) {
            synchronized (I18nManager.class) {
                if (instance == null) {
                    instance = new I18nManager(context);
                }
            }
        }
        return instance;
    }

    /** 设置当前语言；未知语言回退默认（en）。仅加载当前语言与英文回退两份词典。 */
    public void setLanguage(String lang) {
        currentLang = LANG_FILES.containsKey(lang) ? lang : DEFAULT_LANG;
        ensureLoaded(currentLang);
        ensureLoaded(DEFAULT_LANG);
    }

    /** 按需加载词典；读取失败降级为空词典（tr() 会回退英文 / key 本身）。 */
    private void ensureLoaded(String lang) {
        if (dictionaries.containsKey(lang)) {
            return;
        }
        String assetPath = "i18n/" + LANG_FILES.get(lang);
        Map<String, String> dict = new HashMap<>();
        try (InputStream is = appContext.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            Map<String, String> parsed = new Gson().fromJson(reader, DICT_TYPE);
            if (parsed != null) {
                dict = parsed;
            }
        } catch (IOException e) {
            Log.w(TAG, "load i18n failed: " + assetPath, e);
        }
        dictionaries.put(lang, dict);
    }

    /** 当前语言代码。 */
    public String getLanguage() {
        return currentLang;
    }

    /**
     * 翻译取值：当前语言 → 英语 → key 本身（FR-50 回退规则）。
     */
    public String tr(String key) {
        Map<String, String> dict = dictionaries.get(currentLang);
        if (dict != null) {
            String v = dict.get(key);
            if (v != null) {
                return v;
            }
        }
        Map<String, String> en = dictionaries.get(DEFAULT_LANG);
        if (en != null) {
            String v = en.get(key);
            if (v != null) {
                return v;
            }
        }
        return key;
    }

    /**
     * 带占位符的翻译：先取译文，再把文本中的 {{占位符}} 替换为给定值（FR-50）。
     * 已用占位符如 offlineFiles / totalFiles / offlineKKFiles / totalKKFiles。
     */
    public String trf(String key, Map<String, String> placeholders) {
        String text = tr(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                text = text.replace("{{" + e.getKey() + "}}", e.getValue());
            }
        }
        return text;
    }
}
