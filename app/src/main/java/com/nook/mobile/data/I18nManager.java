package com.nook.mobile.data;

import android.content.Context;

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
 * 对应桌面版 main.js 的 translations / i18n() / changeLang()。
 */
public final class I18nManager {

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

    /** 语言代码 → 词典。 */
    private final Map<String, Map<String, String>> dictionaries = new HashMap<>();
    private String currentLang = DEFAULT_LANG;

    /**
     * @param context 用于访问 assets
     * @throws IOException 读取或解析任一语言文件失败
     */
    public I18nManager(Context context) throws IOException {
        Gson gson = new Gson();
        Type mapType = new TypeToken<Map<String, String>>() {
        }.getType();
        for (Map.Entry<String, String> entry : LANG_FILES.entrySet()) {
            String assetPath = "i18n/" + entry.getValue();
            try (InputStream is = context.getAssets().open(assetPath);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                Map<String, String> dict = gson.fromJson(reader, mapType);
                dictionaries.put(entry.getKey(), dict != null ? dict : new HashMap<String, String>());
            }
        }
    }

    /** 设置当前语言；未知语言回退默认（en）。 */
    public void setLanguage(String lang) {
        currentLang = LANG_FILES.containsKey(lang) ? lang : DEFAULT_LANG;
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
