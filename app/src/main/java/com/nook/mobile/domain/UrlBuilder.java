package com.nook.mobile.domain;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * 音频 URL 拼接与本地缓存文件名推导（FR-02 / FR-40 / §4.1）。
 * 对应桌面版 player.js 的 baseUrl 拼接与 toNewUrl()。
 */
public final class UrlBuilder {

    /** CDN 根地址（AWS CloudFront）。 */
    public static final String BASE_URL = "https://d17orwheorv96d.cloudfront.net";

    private UrlBuilder() {
    }

    /**
     * 一般游戏整点 / Pocket Camp 时段 URL：{CDN}/{gameId}/{hour}.ogg。
     * hour 既可为 24 段小时值，也可为 Pocket Camp 时段键。
     */
    public static String buildHourlyUrl(String gameId, String hour) {
        return BASE_URL + "/" + gameId + "/" + hour + ".ogg";
    }

    /**
     * K.K. 歌曲 URL：{CDN}/kk-slider-desktop/{歌名}.ogg。
     * 歌名作为 URL 路径段需编码（空格转 %20），本地命名仍用原始歌名（见 toLocalName）。
     */
    public static String buildKkUrl(String songName) {
        return BASE_URL + "/kk-slider-desktop/" + encodePathSegment(songName) + ".ogg";
    }

    /**
     * 雨声 URL：普通 rain / 游戏内 game-rain / 无雷 no-thunder-rain（FR-30）。
     * gameRain 与 peacefulRain 互斥，gameRain 优先。
     */
    public static String buildRainUrl(boolean gameRain, boolean peacefulRain) {
        String seg = gameRain ? "game-rain" : (peacefulRain ? "no-thunder-rain" : "rain");
        return BASE_URL + "/rain/" + seg + ".ogg";
    }

    /**
     * 由完整 URL 推导本地缓存文件名（FR-40 / §4.1）：
     * 取 URL 倒数两段，用连字符拼接、去掉 .ogg 再加 .ogg。
     * 段落使用未编码的原始名（对 %20 等做解码）。
     * 例：.../new-leaf/3pm.ogg → new-leaf-3pm.ogg；
     * .../kk-slider-desktop/Agent%20K.K..ogg → kk-slider-desktop-Agent K.K..ogg。
     */
    public static String toLocalName(String url) {
        String[] parts = url.split("/");
        String last = decodePathSegment(parts[parts.length - 1]);
        String secondLast = decodePathSegment(parts[parts.length - 2]);
        String combined = secondLast + "-" + last;
        if (combined.endsWith(".ogg")) {
            combined = combined.substring(0, combined.length() - 4);
        }
        return combined + ".ogg";
    }

    /**
     * 对 URL 路径段做百分号编码：空格 → %20，其余保留 URLEncoder 默认的非保留字符。
     */
    private static String encodePathSegment(String segment) {
        try {
            // URLEncoder 面向 application/x-www-form-urlencoded：空格会编码为 "+"，此处改回 %20。
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 在任何 JVM 上都存在，理论上不会触发。
            return segment;
        }
    }

    /**
     * 对 URL 路径段做百分号解码，还原原始名（用于本地命名）。
     */
    private static String decodePathSegment(String segment) {
        try {
            return URLDecoder.decode(segment, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return segment;
        }
    }
}
