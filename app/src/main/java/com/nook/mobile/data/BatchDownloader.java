package com.nook.mobile.data;

import android.util.Log;

import com.nook.mobile.domain.GameCatalog;
import com.nook.mobile.domain.UrlBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 批量预下载整点音乐 / K.K. 音乐（FR-42）。
 * 对应桌面版 player.js 的 downloadHourly() / downloadKK()。
 * <p>
 * 整点音乐共 316 个文件（(14-1)×24 + 4，pocket-camp 为 4 时段），K.K. 共 193 首；
 * 每文件默认节流 1000ms，命中缓存（meta 已存在）跳过时 0ms；
 * 单个文件连续失败可自动重试（瞬时网络抖动），累计失败达阈值才熔断。
 */
public final class BatchDownloader {

    private static final String TAG = "BatchDownloader";

    /** 整点音乐文件总数（进度分母，统一为实际遍历数 316）。 */
    public static final int TOTAL_HOURLY = 316;

    /** K.K. 音乐文件总数（进度分母）。 */
    public static final int TOTAL_KK = 193;

    private static final int FAIL_THRESHOLD = 10;   // 累计失败达该值才熔断（降低误熔断）
    private static final long THROTTLE_MS = 1000;
    private static final int MAX_RETRIES = 2;       // 每个文件最多额外重试 2 次

    /** processFile 返回档位：资源缺失（如 CDN 上 403/404），跳过但不累计熔断失败。 */
    private static final long RESULT_MISSING = -2;
    /** processFile 返回档位：真实失败（网络/磁盘等），计入熔断失败数。 */
    private static final long RESULT_FAIL = -1;
    /** processFile 返回档位：本地缓存命中，跳过。 */
    private static final long RESULT_CACHED = 0;

    /** 最近一次下载失败明细（供 UI/日志诊断）。 */
    private final List<Failure> recentFailures = new ArrayList<>();

    /** 一次文件下载失败的记录。 */
    public static final class Failure {
        public final String url;
        public final String detail;

        Failure(String url, String detail) {
            this.url = url;
            this.detail = detail;
        }
    }

    private static final String[] HOURS = {
            "12am", "1am", "2am", "3am", "4am", "5am", "6am", "7am", "8am", "9am", "10am", "11am",
            "12pm", "1pm", "2pm", "3pm", "4pm", "5pm", "6pm", "7pm", "8pm", "9pm", "10pm", "11pm"
    };

    private static final String[] PC_HOURS = {"morning", "day", "evening", "night"};

    /** 批量下载进度与结果回调（回调在后台线程触发，UI 层需自行切主线程）。 */
    public interface Listener {
        /** 每处理完一个文件（含跳过）累加进度。 */
        void onProgress(int done, int total);

        /** 某文件被真正新下载完成时触发（用于离线计数增量）；isKk 区分 K.K./整点。 */
        void onFileDone(boolean isKk);

        /** 失败累计达阈值熔断。 */
        void onFailed();

        /** 全部结束（正常完成或熔断后）。 */
        void onComplete();
    }

    private final SoundCache soundCache;
    private final SettingsRepository settings;
    private final KkRepository kkRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean cancelled = false;

    public BatchDownloader(SoundCache soundCache, SettingsRepository settings, KkRepository kkRepo) {
        this.soundCache = soundCache;
        this.settings = settings;
        this.kkRepo = kkRepo;
    }

    /** 取消当前批量下载（在下一个文件处理前生效，最长等待一次节流间隔）。 */
    public void cancel() {
        cancelled = true;
    }

    /** 遍历 14 个游戏共 316 个文件下载整点音乐（FR-42）。 */
    public void downloadHourly(final Listener listener) {
        cancelled = false;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runHourly(listener);
            }
        });
    }

    /** 遍历全部 193 首 K.K. 歌曲下载（FR-42）。 */
    public void downloadKk(final Listener listener) {
        cancelled = false;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runKk(listener);
            }
        });
    }

    private void runHourly(Listener listener) {
        int done = 0;
        int errs = 0;
        long delay = THROTTLE_MS;
        for (String game : GameCatalog.GAMES) {
            boolean isPocketCamp = "pocket-camp".equals(game);
            int count = isPocketCamp ? PC_HOURS.length : HOURS.length;
            for (int i = 0; i < count; i++) {
                if (cancelled) {
                    safeComplete(listener);
                    return;
                }
                if (errs >= FAIL_THRESHOLD) {
                    logBreach("hourly", done, TOTAL_HOURLY);
                    if (listener != null) {
                        listener.onFailed();
                    }
                    safeComplete(listener);
                    return;
                }

                String hour = isPocketCamp ? PC_HOURS[i] : HOURS[i];
                String url = UrlBuilder.buildHourlyUrl(game, hour);
                // 每文件打印「进度序号 + 文件标识」，便于把 UI 上的 N/316 对到具体文件来定位失败
                Log.d(TAG, "[hourly #" + done + "/" + TOTAL_HOURLY + "] " + (isPocketCamp ? "pocket-camp" : game) + " '" + hour + "' " + url);
                delay = processFile(url, false, listener, delay);
                if (delay == RESULT_FAIL) {
                    // 真实失败：累计熔断失败数
                    errs++;
                    // 高亮失败的序号与文件，配合熔断日志定位持续失败的具体文件
                    Log.e(TAG, "[hourly FAIL #" + done + "/" + TOTAL_HOURLY + "] " + url);
                    delay = THROTTLE_MS;
                } else if (delay == RESULT_MISSING) {
                    // 资源缺失：跳过但不熔断，避免单个缺失文件中断整批有效下载
                    Log.w(TAG, "[hourly MISSING #" + done + "/" + TOTAL_HOURLY + "] " + url);
                    delay = THROTTLE_MS;
                }

                done++;
                if (listener != null) {
                    listener.onProgress(done, TOTAL_HOURLY);
                }
            }
        }
        safeComplete(listener);
    }

    private void runKk(Listener listener) {
        int done = 0;
        int errs = 0;
        long delay = THROTTLE_MS;
        List<String> songs = kkRepo.allSongs();
        for (String song : songs) {
            if (cancelled) {
                safeComplete(listener);
                return;
            }
            if (errs >= FAIL_THRESHOLD) {
                logBreach("kk", done, TOTAL_KK);
                if (listener != null) {
                    listener.onFailed();
                }
                safeComplete(listener);
                return;
            }

            String url = UrlBuilder.buildKkUrl(song);
            // 每文件打印「进度序号 + 歌曲」，便于把 UI 上的 N/193 对到具体歌曲来定位失败
            Log.d(TAG, "[kk #" + done + "/" + TOTAL_KK + "] '" + song + "' " + url);
            delay = processFile(url, true, listener, delay);
            if (delay == RESULT_FAIL) {
                errs++;
                // 高亮失败的序号与歌曲，配合熔断日志定位持续失败的具体文件
                Log.e(TAG, "[kk FAIL #" + done + "/" + TOTAL_KK + "] '" + song + "' " + url);
                delay = THROTTLE_MS;
            } else if (delay == RESULT_MISSING) {
                // 资源缺失：跳过但不熔断，避免单个缺失歌曲中断整批有效下载
                Log.w(TAG, "[kk MISSING #" + done + "/" + TOTAL_KK + "] '" + song + "' " + url);
                delay = THROTTLE_MS;
            }

            done++;
            if (listener != null) {
                listener.onProgress(done, TOTAL_KK);
            }
        }
        safeComplete(listener);
    }

    /**
     * 处理单个文件：先按上一步节流休眠；若本地 meta 已存在则跳过（返回 {@link #RESULT_CACHED}），
     * 否则下载，成功回调 onFileDone、返回 1000 节流；
     * 失败按原因分级：资源缺失（403/404）返回 {@link #RESULT_MISSING}（跳过不熔断），
     * 其余真实失败返回 {@link #RESULT_FAIL}（由调用方计熔断失败并复位节流）。
     */
    private long processFile(String url, boolean isKk, Listener listener, long delay) {
        sleep(delay);
        if (cancelled) {
            return RESULT_CACHED;
        }

        String localName = UrlBuilder.toLocalName(url);
        String meta = settings.getMeta(localName);
        if (meta != null) {
            // 已缓存，快速跳过
            return RESULT_CACHED;
        }

        // 下载（带重试）：成功返回可播放路径和 null；失败返回原因。
        java.util.Map.Entry<Boolean, String> r = fetchWithRetry(url);
        if (!r.getKey()) {
            // 记录失败（限制条数）
            recordFailure(url, r.getValue());
            // 资源缺失不熔断：单个缺失文件不应中断整批有效下载
            if (isMissingResource(r.getValue())) {
                return RESULT_MISSING;
            }
            return RESULT_FAIL;
        }
        if (listener != null) {
            listener.onFileDone(isKk);
        }
        return THROTTLE_MS;
    }

    /**
     * 下载单个文件并自动重试瞬时网络失败。
     *
     * @return Entry（key=成功与否，value=失败原因，成功时 value 为空）
     */
    private java.util.Map.Entry<Boolean, String> fetchWithRetry(String url) {
        String lastDetail = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) {
                return new java.util.AbstractMap.SimpleEntry<>(false, "cancelled");
            }
            SoundCache.FetchResult res = soundCache.getUrlDetailed(url, false);
            if (res.path != null) {
                return new java.util.AbstractMap.SimpleEntry<>(true, null);
            }
            lastDetail = res.detail != null ? res.detail : "unknown";
            // 磁盘空间不足等确定性错误不重试
            if (isDeterministicError(lastDetail)) {
                break;
            }
            Log.w(TAG, "download attempt " + (attempt + 1) + " failed for " + url + ": " + lastDetail);
            // 退避：2s / 6s
            sleep((attempt + 1) * 2000L);
        }
        Log.e(TAG, "download failed (after retries): " + url + " -> " + lastDetail);
        return new java.util.AbstractMap.SimpleEntry<>(false, lastDetail);
    }

    /** 确定性错误（重试无意义）判定。 */
    private static boolean isDeterministicError(String detail) {
        if (detail == null) {
            return false;
        }
        String d = detail.toLowerCase();
        return d.contains("no space")
                || d.contains("enospc")
                || d.contains("permission denied")
                || d.contains("403")
                || d.contains("404")
                || d.contains("extraction failed");
    }

    /**
     * 资源缺失类错误判定：CDN 返回 403/404（CloudFront 对不存在的对象返回 403）。
     * 此类失败重试无意义，且不应熔断中断整批有效下载，由调用方按缺失跳过。
     */
    private static boolean isMissingResource(String detail) {
        if (detail == null) {
            return false;
        }
        String d = detail.toLowerCase();
        return d.contains("403") || d.contains("404");
    }

    /** 记录一次失败（最近保留 50 条），供 UI 诊断展示。 */
    private void recordFailure(String url, String detail) {
        synchronized (recentFailures) {
            recentFailures.add(new Failure(url, detail));
            while (recentFailures.size() > 50) {
                recentFailures.remove(0);
            }
        }
    }

    /** 最近一次的失败明细（只读副本）。 */
    public List<Failure> getRecentFailures() {
        synchronized (recentFailures) {
            return new ArrayList<>(recentFailures);
        }
    }

    /** 熔断时打印最近失败明细，便于实机诊断（adb logcat -s BatchDownloader）。 */
    private void logBreach(String type, int done, int total) {
        synchronized (recentFailures) {
            Log.e(TAG, "download " + type + " breached threshold at #" + done + "/" + total
                    + "; failures=" + recentFailures.size());
            for (Failure f : recentFailures) {
                Log.e(TAG, "  fail: " + f.url + " -> " + f.detail);
            }
        }
    }

    private void safeComplete(Listener listener) {
        if (listener != null) {
            listener.onComplete();
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 视为取消
            cancelled = true;
            Thread.currentThread().interrupt();
        }
    }
}
