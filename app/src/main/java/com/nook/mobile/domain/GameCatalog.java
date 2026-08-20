package com.nook.mobile.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏模式清单与显示名映射（FR-10 / §4.1）。
 * 前 14 个 ID 构成随机池；kk-slider-desktop 与 random 不在随机池内。
 * 对应桌面版 player.js 的 games[] 与 convertGameToHuman()。
 */
public final class GameCatalog {

    /** 随机池的 14 个游戏 ID，顺序严格对应 FR-10 表与下拉框展示顺序。 */
    public static final String[] GAMES = {
            "population-growing",
            "population-growing-snowy",
            "population-growing-cherry",
            "population-growing-rainy",
            "wild-world",
            "wild-world-rainy",
            "wild-world-snowy",
            "new-leaf",
            "new-leaf-rainy",
            "new-leaf-snowy",
            "new-horizons",
            "new-horizons-rainy",
            "new-horizons-snowy",
            "pocket-camp"
    };

    /** K.K. 曲库模式 ID（不在随机池内）。 */
    public static final String KK_GAME = "kk-slider-desktop";

    /** 随机模式 ID（不在随机池内）。 */
    public static final String RANDOM = "random";

    /** 默认游戏模式（FR-10 / §5）。 */
    public static final String DEFAULT_GAME = "new-leaf";

    /** 游戏 ID → 界面显示名 i18n key（FR-10 表第三列，含 kk/random）。 */
    private static final Map<String, String> DISPLAY_NAME_KEYS = new LinkedHashMap<>();

    static {
        DISPLAY_NAME_KEYS.put("population-growing", "AC: Population Growing (GC)");
        DISPLAY_NAME_KEYS.put("population-growing-snowy", "AC: Population Growing (GC) [Snowy]");
        DISPLAY_NAME_KEYS.put("population-growing-cherry", "AC: Population Growing (GC) [Sakura]");
        DISPLAY_NAME_KEYS.put("population-growing-rainy", "AC: Population Growing (GC) [Rainy Day]");
        DISPLAY_NAME_KEYS.put("wild-world", "AC: City Folk (Wii)");
        DISPLAY_NAME_KEYS.put("wild-world-rainy", "AC: City Folk (Wii) [Rainy]");
        DISPLAY_NAME_KEYS.put("wild-world-snowy", "AC: City Folk (Wii) [Snowy]");
        DISPLAY_NAME_KEYS.put("new-leaf", "AC: New Leaf (3DS)");
        DISPLAY_NAME_KEYS.put("new-leaf-rainy", "AC: New Leaf (3DS) [Rainy]");
        DISPLAY_NAME_KEYS.put("new-leaf-snowy", "AC: New Leaf (3DS) [Snowy]");
        DISPLAY_NAME_KEYS.put("new-horizons", "AC: New Horizons (Switch)");
        DISPLAY_NAME_KEYS.put("new-horizons-rainy", "AC: New Horizons (Switch) [Rainy]");
        DISPLAY_NAME_KEYS.put("new-horizons-snowy", "AC: New Horizons (Switch) [Snowy]");
        DISPLAY_NAME_KEYS.put("pocket-camp", "AC: Pocket Camp (Mobile)");
        DISPLAY_NAME_KEYS.put(KK_GAME, "K.K. Slider");
        DISPLAY_NAME_KEYS.put(RANDOM, "Random");
    }

    private GameCatalog() {
    }

    /**
     * 取某游戏 ID 的界面显示名 i18n key；未知 ID 返回原 ID（供 i18n 兜底）。
     */
    public static String displayNameKey(String gameId) {
        String key = DISPLAY_NAME_KEYS.get(gameId);
        return key != null ? key : gameId;
    }

    /**
     * 托盘/展示用“友好名”转换（§4.1）：
     * 先把 kk-slider-desktop 替换为 kk-slider，再把连字符 ID 转为首字母大写的空格分词。
     * 例：new-leaf → "New Leaf"；kk-slider-desktop → "Kk Slider"。
     */
    public static String toFriendlyName(String gameId) {
        String g = gameId.replace("kk-slider-desktop", "kk-slider");
        String[] parts = g.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }
}
