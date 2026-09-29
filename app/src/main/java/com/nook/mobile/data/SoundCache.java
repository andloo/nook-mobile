package com.nook.mobile.data;

import android.content.Context;
import android.util.Log;

import com.nook.mobile.domain.UrlBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 音频本地缓存与按需下载（FR-40 / FR-41 / FR-43）。
 * 对应桌面版 player.js 的 toNewUrl() / getUrl() / localSave()。
 * 网络操作须在后台线程执行；同步方法 {@link #getUrlSync} 不可在主线程调用。
 */
public final class SoundCache {

    private static final String TAG = "SoundCache";

    /** HTTP 超时（连接/读/写/整体调用），20 秒（移动网络慢时留足余量）。 */
    private static final long TIMEOUT_SECONDS = 20;

    /**
     * 本地缓存校验有效期 24 小时：TTL 内直接使用本地文件、不发任何网络请求（省电，FR-40）。
     * 上游音频极少变动，过期后首次播放再发一次 HEAD 比对 Last-Modified。
     */
    private static final long REVALIDATE_TTL_MS = 24 * 60 * 60 * 1000L;

    private static final String LAST_MODIFIED_HEADER = "Last-Modified";

    /** K.K. 曲目路径段（其文件为 theora+vorbis 混合 Ogg，需抽取音频流）。 */
    private static final String KK_PATH_SEGMENT = "/kk-slider-desktop/";

    /** 异步结果回调：返回可播放路径（本地 file 路径或在线 URL），失败返回 null。 */
    public interface Callback {
        void onResult(String pathOrUrlOrNull);
    }

    /** 取源结果：path 为可播放路径（null=失败）；detail 为失败原因（仅日志/UI 调试展示用）。 */
    public static final class FetchResult {
        public final String path;
        public final String detail;

        FetchResult(String path, String detail) {
            this.path = path;
            this.detail = detail;
        }
    }

    private final OkHttpClient client;
    private final File soundDir;
    private final SettingsRepository settings;
    /** 单线程执行器：空闲 30s 后回收工作线程，避免常驻线程（省内存）。 */
    private final ExecutorService executor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    public SoundCache(Context context, SettingsRepository settings) {
        this.settings = settings;
        this.soundDir = new File(context.getFilesDir(), "sound");
        this.client = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /** 缓存目录（{filesDir}/sound）。 */
    public File getSoundDir() {
        return soundDir;
    }

    /**
     * 异步取源，在后台线程执行 {@link #getUrlSync} 后回调（FR-40）。
     */
    public void getUrl(final String remoteUrl, final boolean preferNoDownload, final Callback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String result = getUrlSync(remoteUrl, preferNoDownload);
                if (callback != null) {
                    callback.onResult(result);
                }
            }
        });
    }

    /** 便捷方法：仅返回可播放路径，失败返回 null（批量下载等无需详情处使用）。 */
    public String getUrlSync(String remoteUrl, boolean preferNoDownload) {
        return getUrlDetailed(remoteUrl, preferNoDownload).path;
    }

    /**
     * 同步取源（须在后台线程）。取源优先级（FR-40 增强）：
     * <ol>
     *   <li>本地 meta 存在且距上次校验未超过 {@link #REVALIDATE_TTL_MS} → 直接返回本地文件（零网络请求）。</li>
     *   <li>preferNoDownload → 直接返回在线 URL（流式，不缓存）。</li>
     *   <li>HEAD 取 Last-Modified 与本地 meta 比对：命中缓存直接返回本地路径；否则 GET 下载。</li>
     *   <li>HEAD 失败或无 Last-Modified 头时不再直接判失败，降级走 GET（GET 成功仍可下载缓存，
     *       比桌面版对弱网更宽容）。</li>
     *   <li>下载先写 {name}.part 再改名，避免半截文件被当作可用缓存（FR-43 兼容）。</li>
     *   <li>下载失败但本地有完整缓存 → 回退本地（FR-41）；否则返回 null + detail。</li>
     * </ol>
     */
    public FetchResult getUrlDetailed(String remoteUrl, boolean preferNoDownload) {
        // K.K. 曲目为 theora+vorbis 混合 Ogg，无法直接流式播放（ExoPlayer 只认音频首流），
        // 因此 K.K. 一律下载后抽取音频流，忽略 preferNoDownload。
        final boolean isKk = isKkUrl(remoteUrl);
        if (preferNoDownload && !isKk) {
            Log.d(TAG, "stream (preferNoDownload): " + remoteUrl);
            return new FetchResult(remoteUrl, null);
        }

        String localName = UrlBuilder.toLocalName(remoteUrl);
        File localFile = new File(soundDir, localName);
        String stored = settings.getMeta(localName);

        Log.d(TAG, "fetch " + remoteUrl + " (local=" + localName + ", meta=" + stored + ")");

        // 0) TTL 内直接命中本地缓存：不发任何网络请求（省电，FR-40）
        if (stored != null && localFile.exists()
                && System.currentTimeMillis() - settings.getMetaCheckedAt(localName) < REVALIDATE_TTL_MS) {
            if (isKk && !OggRemuxer.ensurePlayable(localFile)) {
                localFile.delete();
                settings.removeMeta(localName);
                Log.w(TAG, "cached K.K. unplayable, re-download: " + localName);
            } else {
                Log.d(TAG, "cache hit (ttl): " + localFile.getAbsolutePath());
                return new FetchResult(localFile.getAbsolutePath(), null);
            }
        }

        // 1) HEAD：尝试取 Last-Modified；失败/缺头不致命，降级 GET（见下）。
        String serverLastModified = null;
        boolean headOk = false;
        try {
            Request headReq = new Request.Builder().url(remoteUrl).head().build();
            try (Response headResp = client.newCall(headReq).execute()) {
                headOk = headResp.isSuccessful();
                serverLastModified = headResp.header(LAST_MODIFIED_HEADER);
                if (!headOk) {
                    Log.w(TAG, "HEAD " + remoteUrl + " -> HTTP " + headResp.code());
                }
            }
            Log.d(TAG, "HEAD " + remoteUrl + " ok=" + headOk
                    + " lastModified=" + serverLastModified);
        } catch (IOException e) {
            Log.w(TAG, "HEAD failed for " + remoteUrl + ": " + e);
        }

        // 2) HEAD 成功且有 Last-Modified 且与本地一致 → 命中缓存
        if (headOk && serverLastModified != null && sameLastModified(stored, serverLastModified)) {
            if (localFile.exists()) {
                // K.K. 命中缓存也校验一次可播放性，修复历史遗留的混合文件
                if (isKk && !OggRemuxer.ensurePlayable(localFile)) {
                    localFile.delete();
                    settings.removeMeta(localName);
                    Log.w(TAG, "cached K.K. unplayable, re-download: " + localName);
                } else {
                    // 刷新校验时间戳：此后 TTL 内不再重复校验
                    settings.setMeta(localName, serverLastModified);
                    Log.d(TAG, "cache hit: " + localFile.getAbsolutePath());
                    return new FetchResult(localFile.getAbsolutePath(), null);
                }
            }
            Log.w(TAG, "meta present but file missing, re-download: " + localName);
        }

        // 3) GET 下载：先写 .part 再改名，成功才写 meta
        File part = new File(soundDir, localName + ".part");
        try {
            Request getReq = new Request.Builder().url(remoteUrl).build();
            Log.d(TAG, "GET start " + remoteUrl);
            long t0 = System.currentTimeMillis();
            try (Response getResp = client.newCall(getReq).execute()) {
                Log.d(TAG, "GET " + remoteUrl + " -> HTTP " + getResp.code() + " in " + (System.currentTimeMillis() - t0) + "ms");
                if (!getResp.isSuccessful()) {
                    Log.w(TAG, "GET " + remoteUrl + " -> HTTP " + getResp.code());
                    return fallbackLocal(localFile, "GET HTTP " + getResp.code());
                }
                ResponseBody body = getResp.body();
                if (body == null) {
                    return fallbackLocal(localFile, "GET empty body");
                }
                if (serverLastModified == null) {
                    serverLastModified = getResp.header(LAST_MODIFIED_HEADER);
                }
                if (!soundDir.exists()) {
                    soundDir.mkdirs();
                }
                try (InputStream in = body.byteStream();
                     FileOutputStream out = new FileOutputStream(part)) {
                    copy(in, out);
                }
                long size = part.length();
                Log.d(TAG, "GET " + remoteUrl + " downloaded bytes=" + size + " in " + (System.currentTimeMillis() - t0) + "ms");
                if (!part.renameTo(localFile)) {
                    part.delete();
                    return fallbackLocal(localFile, "rename failed");
                }
                // K.K. 混合 Ogg：下载后抽取 Vorbis 音频流，否则 ExoPlayer 无法播放
                if (isKk && !OggRemuxer.ensurePlayable(localFile)) {
                    localFile.delete();
                    Log.w(TAG, "K.K. audio extraction failed for " + remoteUrl);
                    return new FetchResult(null, "K.K. audio extraction failed");
                }
                // 无 Last-Modified 时存 "" 占位，保证离线计数/缓存判定可用
                settings.setMeta(localName, serverLastModified == null ? "" : serverLastModified);
                Log.d(TAG, "downloaded " + remoteUrl + " -> " + localFile.getAbsolutePath());
                return new FetchResult(localFile.getAbsolutePath(), null);
            }
        } catch (IOException e) {
            Log.w(TAG, "download failed for " + remoteUrl + ": " + e);
            if (part.exists()) {
                part.delete();
            }
            return fallbackLocal(localFile, "GET failed: " + summarize(e));
        }
    }

    /** 是否为 K.K. 曲目 URL。 */
    private static boolean isKkUrl(String remoteUrl) {
        return remoteUrl != null && remoteUrl.contains(KK_PATH_SEGMENT);
    }

    /**
     * 删除损坏的本地缓存文件及其元数据（FR-43）。
     */
    public void deleteCorrupt(String localName) {
        File f = new File(soundDir, localName);
        if (f.exists()) {
            // 忽略删除返回值：文件可能已被系统清理
            f.delete();
        }
        settings.removeMeta(localName);
    }

    private static FetchResult fallbackLocal(File localFile, String reason) {
        if (localFile.exists()) {
            Log.d(TAG, "fallback to cached local: " + localFile.getAbsolutePath());
            return new FetchResult(localFile.getAbsolutePath(), null);
        }
        return new FetchResult(null, reason);
    }

    /** 提取 IO 异常中最短可读的原因（不含长堆栈）。 */
    private static String summarize(IOException e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    /**
     * 按 HTTP Last-Modified 时间戳比对是否一致（解析失败时退化为字符串相等）。
     */
    private static boolean sameLastModified(String stored, String server) {
        if (stored == null) {
            return false;
        }
        Long a = parseHttpDate(stored);
        Long b = parseHttpDate(server);
        if (a != null && b != null) {
            return a.equals(b);
        }
        return stored.equals(server);
    }

    /** HTTP 日期解析器（SimpleDateFormat 非线程安全，按线程复用，避免每次调用新建）。 */
    private static final ThreadLocal<SimpleDateFormat> HTTP_DATE_FORMAT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt;
    });

    private static Long parseHttpDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            Date d = HTTP_DATE_FORMAT.get().parse(value);
            return d != null ? d.getTime() : null;
        } catch (ParseException e) {
            return null;
        }
    }
}
