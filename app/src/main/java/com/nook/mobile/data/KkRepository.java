package com.nook.mobile.data;

import android.content.Context;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * K.K. 曲库仓库（FR-14 / FR-16 / §4.3）。
 * 从 assets/kk.json 加载 193 首歌名（前 98 首现场版，后 95 首广播版）。
 */
public final class KkRepository {

    private static final String ASSET_PATH = "kk.json";

    private final List<String> songs;
    private final Random random = new Random();

    /**
     * @param context 用于访问 assets
     * @throws IOException 读取或解析 kk.json 失败
     */
    public KkRepository(Context context) throws IOException {
        this.songs = loadSongs(context);
    }

    private static List<String> loadSongs(Context context) throws IOException {
        try (InputStream is = context.getAssets().open(ASSET_PATH);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String[] arr = new Gson().fromJson(reader, String[].class);
            List<String> list = new ArrayList<>();
            if (arr != null) {
                for (String s : arr) {
                    list.add(s);
                }
            }
            return list;
        }
    }

    /** 全部歌曲（193 首），返回只读副本。 */
    public List<String> allSongs() {
        return new ArrayList<>(songs);
    }

    /** 是否为广播版（歌名包含子串 "Radio"）。 */
    public static boolean isRadio(String name) {
        return name != null && name.contains("Radio");
    }

    /** 现场版列表（不含 "Radio"）。 */
    public List<String> liveSongs() {
        List<String> res = new ArrayList<>();
        for (String s : songs) {
            if (!isRadio(s)) {
                res.add(s);
            }
        }
        return res;
    }

    /** 广播版列表（含 "Radio"）。 */
    public List<String> radioSongs() {
        List<String> res = new ArrayList<>();
        for (String s : songs) {
            if (isRadio(s)) {
                res.add(s);
            }
        }
        return res;
    }

    /**
     * 从启用列表中等概率随机取一首（FR-14）。
     * 空列表兜底：回退到全曲库随机（§7.4）；全曲库也为空则返回 null。
     */
    public String randomFrom(List<String> enabled) {
        List<String> pool = (enabled == null || enabled.isEmpty()) ? songs : enabled;
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
