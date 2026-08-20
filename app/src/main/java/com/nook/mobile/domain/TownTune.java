package com.nook.mobile.domain;

/**
 * 城镇主题曲数据结构与滑块↔符号互转（FR-20）。
 * 对应桌面版 main.js 的 tunes[]（16 元素）、player.js 的 tunes[]（13 音高）与默认 tune。
 */
public final class TownTune {

    /** 编辑器 16 个可选符号，滑块值 1..16 对应索引 0..15。 */
    public static final String[] SYMBOLS = {
            "zZz", "-", "G0", "A1", "B1", "C1", "D1", "E1",
            "F1", "G1", "A2", "B2", "C2", "D2", "E2", "?"
    };

    /** 13 个可发声音高（"?" 的随机来源，也是报时精灵音高全集）。 */
    public static final String[] PITCHES = {
            "G0", "A1", "B1", "C1", "D1", "E1", "F1",
            "G1", "A2", "B2", "C2", "D2", "E2"
    };

    /** 默认主题曲（未设置时，长度 16）。 */
    private static final String[] DEFAULT_TUNE = {
            "G1", "E2", "-", "G1", "F1", "D2", "-", "B2",
            "C2", "zZz", "?", "zZz", "C1", "-", "zZz", "zZz"
    };

    private TownTune() {
    }

    /** 返回默认主题曲的副本（避免外部修改共享数组）。 */
    public static String[] defaultTune() {
        return DEFAULT_TUNE.clone();
    }

    /**
     * 滑块值（1..16）转符号。
     *
     * @throws IllegalArgumentException 当 slider 不在 1..16 范围内
     */
    public static String symbolForSlider(int slider) {
        if (slider < 1 || slider > SYMBOLS.length) {
            throw new IllegalArgumentException("slider out of range 1.." + SYMBOLS.length + ": " + slider);
        }
        return SYMBOLS[slider - 1];
    }

    /**
     * 符号转滑块值（1..16）。
     *
     * @throws IllegalArgumentException 当 symbol 不是合法符号
     */
    public static int sliderForSymbol(String symbol) {
        for (int i = 0; i < SYMBOLS.length; i++) {
            if (SYMBOLS[i].equals(symbol)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("unknown tune symbol: " + symbol);
    }
}
