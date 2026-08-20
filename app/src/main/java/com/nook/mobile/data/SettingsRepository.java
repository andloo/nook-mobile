package com.nook.mobile.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.nook.mobile.domain.GameCatalog;
import com.nook.mobile.domain.TownTune;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户设置持久化（SharedPreferences 封装，key 与 §5 表一致）。
 * 对应桌面版 electron-json-storage 的每 key 一条记录；tune / kkEnabled 用 Gson 序列化为字符串。
 * 同时承载文件元数据 meta-{文件名} 与离线计数（§4.2）。
 */
public final class SettingsRepository {

    private static final String PREFS_NAME = "nook_settings";

    // 设置项 key（§5）
    public static final String KEY_SOUND_VOL = "soundVol";
    public static final String KEY_RAIN_VOL = "rainVol";
    public static final String KEY_GAME = "game";
    public static final String KEY_GRANDFATHER = "grandFather";
    public static final String KEY_GAME_RAIN = "gameRain";
    public static final String KEY_PEACEFUL_RAIN = "peacefulRain";
    public static final String KEY_TUNE_ENABLED = "tuneEnabled";
    public static final String KEY_TUNE = "tune";
    public static final String KEY_KK_ENABLED = "kkEnabled";
    public static final String KEY_KK_SATURDAY = "kkSaturday";
    public static final String KEY_PREFER_NO_DOWNLOAD = "preferNoDownload";
    public static final String KEY_PAUSED = "paused";
    public static final String KEY_LANG = "lang";
    public static final String KEY_LATEST_VERSION = "latestVersion";

    private static final String META_PREFIX = "meta-";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public SettingsRepository(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- 音量（0-100） ----

    public int getSoundVol() {
        return prefs.getInt(KEY_SOUND_VOL, 50);
    }

    public void setSoundVol(int vol) {
        prefs.edit().putInt(KEY_SOUND_VOL, vol).apply();
    }

    public int getRainVol() {
        return prefs.getInt(KEY_RAIN_VOL, 50);
    }

    public void setRainVol(int vol) {
        prefs.edit().putInt(KEY_RAIN_VOL, vol).apply();
    }

    // ---- 游戏 ----

    public String getGame() {
        return prefs.getString(KEY_GAME, GameCatalog.DEFAULT_GAME);
    }

    public void setGame(String game) {
        prefs.edit().putString(KEY_GAME, game).apply();
    }

    // ---- 布尔开关 ----

    public boolean isGrandFather() {
        return prefs.getBoolean(KEY_GRANDFATHER, false);
    }

    public void setGrandFather(boolean enabled) {
        prefs.edit().putBoolean(KEY_GRANDFATHER, enabled).apply();
    }

    public boolean isGameRain() {
        return prefs.getBoolean(KEY_GAME_RAIN, false);
    }

    public void setGameRain(boolean enabled) {
        prefs.edit().putBoolean(KEY_GAME_RAIN, enabled).apply();
    }

    public boolean isPeacefulRain() {
        return prefs.getBoolean(KEY_PEACEFUL_RAIN, false);
    }

    public void setPeacefulRain(boolean enabled) {
        prefs.edit().putBoolean(KEY_PEACEFUL_RAIN, enabled).apply();
    }

    public boolean isTuneEnabled() {
        return prefs.getBoolean(KEY_TUNE_ENABLED, true);
    }

    public void setTuneEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TUNE_ENABLED, enabled).apply();
    }

    public boolean isKkSaturday() {
        return prefs.getBoolean(KEY_KK_SATURDAY, false);
    }

    public void setKkSaturday(boolean enabled) {
        prefs.edit().putBoolean(KEY_KK_SATURDAY, enabled).apply();
    }

    public boolean isPreferNoDownload() {
        return prefs.getBoolean(KEY_PREFER_NO_DOWNLOAD, false);
    }

    public void setPreferNoDownload(boolean enabled) {
        prefs.edit().putBoolean(KEY_PREFER_NO_DOWNLOAD, enabled).apply();
    }

    public boolean isPaused() {
        return prefs.getBoolean(KEY_PAUSED, false);
    }

    public void setPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply();
    }

    // ---- 语言 / 版本 ----

    public String getLang() {
        return prefs.getString(KEY_LANG, "cn");
    }

    public void setLang(String lang) {
        prefs.edit().putString(KEY_LANG, lang).apply();
    }

    public String getLatestVersion() {
        return prefs.getString(KEY_LATEST_VERSION, null);
    }

    public void setLatestVersion(String version) {
        prefs.edit().putString(KEY_LATEST_VERSION, version).apply();
    }

    // ---- 主题曲（Gson 序列化 String[]） ----

    /** 城镇主题曲；未设置时返回默认旋律副本（FR-20）。 */
    public String[] getTune() {
        String json = prefs.getString(KEY_TUNE, null);
        if (json == null) {
            return TownTune.defaultTune();
        }
        String[] tune = gson.fromJson(json, String[].class);
        return tune != null ? tune : TownTune.defaultTune();
    }

    public void setTune(String[] tune) {
        prefs.edit().putString(KEY_TUNE, gson.toJson(tune)).apply();
    }

    // ---- K.K. 启用列表（Gson 序列化 List<String>） ----

    /**
     * K.K. 启用列表；未设置时返回给定的全曲库兜底（默认全 193 首，§5）。
     *
     * @param fallbackAll 未设置时使用的默认列表（通常为全曲库）
     */
    public List<String> getKkEnabled(List<String> fallbackAll) {
        String json = prefs.getString(KEY_KK_ENABLED, null);
        if (json == null) {
            return fallbackAll != null ? new ArrayList<>(fallbackAll) : new ArrayList<String>();
        }
        Type listType = new TypeToken<List<String>>() {
        }.getType();
        List<String> songs = gson.fromJson(json, listType);
        return songs != null ? songs : (fallbackAll != null ? new ArrayList<>(fallbackAll) : new ArrayList<String>());
    }

    public void setKkEnabled(List<String> songs) {
        prefs.edit().putString(KEY_KK_ENABLED, gson.toJson(songs)).apply();
    }

    // ---- 文件元数据 meta-{文件名} → lastModified（§4.2 / FR-41） ----

    /** 读取某本地文件的 Last-Modified 记录；不存在返回 null。 */
    public String getMeta(String localName) {
        return prefs.getString(metaKey(localName), null);
    }

    /** 写入某本地文件的 Last-Modified 记录。 */
    public void setMeta(String localName, String lastModified) {
        prefs.edit().putString(metaKey(localName), lastModified).apply();
    }

    /** 删除某本地文件的元数据（FR-43 损坏清理）。 */
    public void removeMeta(String localName) {
        prefs.edit().remove(metaKey(localName)).apply();
    }

    private static String metaKey(String localName) {
        String name = localName;
        if (name.endsWith(".ogg")) {
            name = name.substring(0, name.length() - 4);
        }
        return META_PREFIX + name;
    }

    // ---- 离线计数（§4.2） ----

    /** 已缓存整点文件数 = 含 meta- 且不含 meta-kk-slider、不含 meta-rain 的键数。 */
    public int countOfflineHourly() {
        int count = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.contains(META_PREFIX) && !key.contains("meta-kk-slider") && !key.contains("meta-rain")) {
                count++;
            }
        }
        return count;
    }

    /** 已缓存 K.K. 文件数 = 含 meta-kk-slider 的键数。 */
    public int countOfflineKk() {
        int count = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.contains("meta-kk-slider")) {
                count++;
            }
        }
        return count;
    }

    /** 清空所有设置与元数据（FR-45）。用同步 commit，确保退出/重启前真正落盘。 */
    public void clearAll() {
        prefs.edit().clear().commit();
    }

    /** 直接访问底层存储（供高级用途，如遍历 meta 键）。 */
    public Map<String, ?> getAll() {
        return prefs.getAll();
    }
}
