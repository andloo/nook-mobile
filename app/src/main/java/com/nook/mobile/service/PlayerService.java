package com.nook.mobile.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.nook.mobile.R;
import com.nook.mobile.alarm.HourChangeReceiver;
import com.nook.mobile.audio.AudioEngine;
import com.nook.mobile.audio.ChimePlayer;
import com.nook.mobile.data.BatchDownloader;
import com.nook.mobile.data.KkRepository;
import com.nook.mobile.data.SettingsRepository;
import com.nook.mobile.data.SoundCache;
import com.nook.mobile.domain.GameCatalog;
import com.nook.mobile.domain.HourResolver;
import com.nook.mobile.domain.UrlBuilder;
import com.nook.mobile.ui.MainActivity;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 前台播放服务（对应桌面版隐藏窗口 player.js，FR-01/03/14/17/21/30-35/73）。
 * 整点对齐 Handler 定时器 + AlarmManager 整点兜底（原固定 5s 轮询改为每整点唤醒一次，省电）；
 * 音频取源（SoundCache.getUrlSync）一律在后台线程执行。
 */
public final class PlayerService extends Service {

    public static final String ACTION_TIME_CHECK = "com.nook.mobile.action.TIME_CHECK";

    /** 通知栏/锁屏「播放暂停」按钮动作（与主界面按钮同一行为）。 */
    public static final String ACTION_TOGGLE_PAUSE = "com.nook.mobile.action.TOGGLE_PAUSE";

    private static final String TAG = "PlayerService";
    private static final String CHANNEL_ID = "nook_playback";
    private static final int NOTIFICATION_ID = 1;

    /** UI 回调（全部已切主线程）。 */
    public interface UiCallback {
        /** 周六 K.K. 自动切换 / 周日恢复时通知下拉框更新（FR-17）。 */
        void onGameAutoChanged(String game);

        /** 正在播放变化（friendlyName/hour 为 null 表示未播放）。 */
        void onPlayingChanged(String friendlyName, String hour);

        /** 错误提示（i18n key：failedToLoadSound / failedToLoadRainSound / failedToDownload）+ 技术原因（可为 null）。 */
        void onErrorKey(String key, String detail);

        /** 暂停态变化（含音频焦点丢失自动暂停，FR-35 替代）。 */
        void onPauseChanged(boolean paused);

        /** 批量下载进度（FR-42）。 */
        void onDownloadProgress(boolean kk, int done, int total);

        /** 单文件下载完成 / 坏文件移除后离线计数变化（FR-42/43）。 */
        void onOfflineCountChanged();

        /** 批量下载熔断失败（FR-42）。 */
        void onDownloadFailed();

        /** 批量下载全部结束（FR-42）。 */
        void onDownloadComplete();
    }

    /** LocalBinder：UI 通过 bindService 直接拿到服务实例。 */
    public final class LocalBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 单线程取源执行器：空闲 30s 后回收工作线程，避免常驻线程（省内存）。 */
    private final ExecutorService ioExecutor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Random random = new Random();

    private SettingsRepository settings;
    private KkRepository kkRepo;
    private SoundCache soundCache;
    private BatchDownloader downloader;
    private AudioEngine engine;
    private ChimePlayer chime;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSessionCompat mediaSession;

    private UiCallback ui;

    /** 运行时当前游戏（周六 K.K. 自动切换只改这里，不覆盖持久化偏好）。 */
    private String currentGame;
    private String hour;
    private boolean paused;
    private boolean started;
    private boolean switching;

    /** 播放请求令牌：快速切换时丢弃过期的取源结果。 */
    private int playToken;

    /** 最近一次通知内容（避免 onStartCommand 重建通知时丢失正在播放文案）。 */
    private String lastFriendly;
    private String lastHourText;

    private Runnable tick;

    /** 通知/锁屏大图标位图缓存（服务内复用，避免每次刷新通知都新建位图）。 */
    private Bitmap largeIcon;

    /** K.K. 启用列表缓存：仅在歌单保存后失效（避免每次整点选曲都 Gson 解析 + 拷贝）。 */
    private List<String> kkEnabledSongs;

    // ---- 生命周期 ----

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SettingsRepository(this);
        try {
            kkRepo = new KkRepository(this);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load kk.json", e);
        }
        soundCache = new SoundCache(this, settings);
        downloader = new BatchDownloader(soundCache, settings, kkRepo);
        chime = new ChimePlayer(this);
        engine = new AudioEngine(this, new AudioEngine.Listener() {
            @Override
            public void onBgmEnded() {
                // K.K. 连播（FR-14）：清空小时记录后立即重新选曲
                hour = null;
                timeCheck();
            }

            @Override
            public void onBgmError(String source) {
                handleCorrupt(source, false);
            }

            @Override
            public void onRainError(String source) {
                handleCorrupt(source, true);
            }
        });
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        currentGame = settings.getGame();
        paused = settings.isPaused();
        engine.setMusicVolume(settings.getSoundVol() / 100f);
        engine.setRainVolume(settings.getRainVol() / 100f);
        createChannel();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService 后必须尽快 startForeground
        startForeground(NOTIFICATION_ID, buildNotification(lastFriendly, lastHourText));
        syncMediaSession(lastFriendly, lastHourText);
        final String action = intent != null ? intent.getAction() : null;
        final boolean isTimeCheck = ACTION_TIME_CHECK.equals(action);
        if (ACTION_TOGGLE_PAUSE.equals(action)) {
            // 通知栏/锁屏的播放暂停按钮：与主界面按钮同一入口（含恢复播放流程）
            togglePause();
        } else if (isTimeCheck && started) {
            timeCheck();
            if (!paused) {
                // 暂停中不续订整点闹钟（恢复播放时会重新安排）
                scheduleHourAlarm();
            }
        } else if (isTimeCheck) {
            // 进程被系统回收后残留的整点触发：此时没有播放任务，退出前台并停止服务，
            // 避免留下「常驻通知 + 空转进程」的僵尸前台服务
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopTick();
        cancelHourAlarm();
        abandonFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        chime.release();
        engine.release();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---- 对 UI 的入口 ----

    /** 注册 UI 回调（单槽位，后注册者生效）。 */
    public void setUiCallback(UiCallback callback) {
        this.ui = callback;
    }

    /**
     * 注销 UI 回调——<b>仅当当前注册者正好是 callback 时才清除</b>。
     * <p>
     * 这里不能像原来那样直接 {@code setUiCallback(null)}。从设置页返回主界面时，
     * Android 的回调顺序是：
     * <pre>
     *   设置页 onPause → 主界面 onResume（重新注册）→ 设置页 onStop → 设置页 onDestroy（注销）
     * </pre>
     * 即「先恢复的页面注册」早于「后销毁的页面注销」。无条件置 null 会把主界面刚注册的回调顶掉，
     * 主界面此后收不到 onPauseChanged / onPlayingChanged 等事件，表现为
     * <b>「播放/暂停按钮点一下不再变化」</b>——而音乐其实照常切换，只是按钮文案与实际状态脱节。
     * <p>
     * 按实例比对后，无论上述顺序如何颠倒都是安全的：只有真正持有该槽位的页面才能清空它。
     */
    public void clearUiCallback(UiCallback callback) {
        if (this.ui == callback) {
            this.ui = null;
        }
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getCurrentGame() {
        return currentGame;
    }

    /** 当前播放的友好名（null=未在播放）；供界面重建/回到前台时恢复"playing …"文案。 */
    public String getPlayingFriendlyName() {
        return lastFriendly;
    }

    /** 当前播放的小时/曲名，配合 {@link #getPlayingFriendlyName()} 使用。 */
    public String getPlayingHourText() {
        return lastHourText;
    }

    /**
     * 用户首次点击“开始”后启动播放（§7.2）：
     * 播雨声底噪 → 启动 5s 轮询与整点闹钟 → 立即 timeCheck 播当前小时。
     */
    public void beginPlayback() {
        if (started) {
            return;
        }
        started = true;
        paused = false;
        settings.setPaused(false);
        requestFocus();
        playRain();
        startTick();
        scheduleHourAlarm();
        timeCheck();
        notifyPause();
        // 立即把通知/锁屏按钮切到「暂停」态（曲目加载完成后再更新为具体曲名）
        updateNotification(null, null);
    }

    /** 手动切换游戏模式（FR-03）：持久化并以当前小时立即重播。 */
    public void changeGame(String game) {
        settings.setGame(game);
        currentGame = game;
        if (started && !paused) {
            replayCurrentHour();
        }
    }

    /**
     * 音乐音量 0-100（FR-31），同步报时音量（报时每次发声时读取）。
     *
     * @param persist 是否写入设置：拖动过程中传 false（仅实时生效），松手/离开页面时传 true
     */
    public void changeMusicVolume(int vol, boolean persist) {
        if (persist) {
            settings.setSoundVol(vol);
        }
        engine.setMusicVolume(vol / 100f);
    }

    /**
     * 雨声音量 0-100（FR-32）。
     *
     * @param persist 是否写入设置：拖动过程中传 false（仅实时生效），松手/离开页面时传 true
     */
    public void changeRainVolume(int vol, boolean persist) {
        if (persist) {
            settings.setRainVol(vol);
        }
        engine.setRainVolume(vol / 100f);
    }

    /** 摆钟模式（FR-33）：切换时以当前小时重播一次。 */
    public void changeGrandFather(boolean enabled) {
        settings.setGrandFather(enabled);
        engine.setRainLooping(!enabled);
        if (started && !paused) {
            replayCurrentHour();
        }
    }

    /** 游戏内雨声（FR-30）：与无雷雨互斥，切换后重播雨声。 */
    public void changeGameRain(boolean enabled) {
        settings.setGameRain(enabled);
        settings.setPeacefulRain(false);
        restartRain();
    }

    /** 无雷雨声（FR-30）：与游戏雨互斥，切换后重播雨声。 */
    public void changePeacefulRain(boolean enabled) {
        settings.setPeacefulRain(enabled);
        settings.setGameRain(false);
        restartRain();
    }

    public void changeTuneEnabled(boolean enabled) {
        settings.setTuneEnabled(enabled);
    }

    /** 周六 K.K.（FR-17）：切换时以当前小时重播（对齐桌面版行为）。 */
    public void changeKkSaturday(boolean enabled) {
        settings.setKkSaturday(enabled);
        if (started && !paused) {
            replayCurrentHour();
        }
    }

    public void changePreferNoDownload(boolean enabled) {
        settings.setPreferNoDownload(enabled);
    }

    /** K.K. 列表保存后即时生效（FR-16）：K.K. 模式下立即按新列表重新随机。 */
    public void kkListSaved() {
        kkEnabledSongs = null;
        if (GameCatalog.KK_GAME.equals(currentGame) && started && !paused) {
            replayCurrentHour();
        }
    }

    /** 暂停/继续（FR-34）。 */
    public void togglePause() {
        setPaused(!paused);
    }

    public void setPaused(boolean pause) {
        if (pause == paused) {
            return;
        }
        paused = pause;
        settings.setPaused(pause);
        if (pause) {
            // 暂停即无播放任务：停止整点定时器与闹钟，避免空转唤醒（省电）
            stopTick();
            cancelHourAlarm();
            chime.cancel();
            engine.stopAll(null);
            lastFriendly = null;
            lastHourText = null;
            updateNotification(null, null);
            if (ui != null) {
                ui.onPlayingChanged(null, null);
            }
        } else {
            if (!started) {
                beginPlayback();
                return;
            }
            requestFocus();
            hour = null;
            playRain();
            startTick();
            scheduleHourAlarm();
            timeCheck();
            // 立即把通知/锁屏按钮切到「暂停」态（曲目加载完成后再更新为具体曲名）
            updateNotification(null, null);
        }
        notifyPause();
    }

    /** 批量下载整点音乐（FR-42），回调切主线程转发 UI。 */
    public void startDownloadHourly() {
        downloader.downloadHourly(newDownloadListener(false));
    }

    /** 批量下载 K.K. 音乐（FR-42）。 */
    public void startDownloadKk() {
        downloader.downloadKk(newDownloadListener(true));
    }

    public void cancelDownload() {
        downloader.cancel();
    }

    // ---- 整点检测 ----

    /**
     * 整点对齐定时器（FR-01）：一次性调度到「下一个整点 +1s」，触发后重新对齐下个整点。
     * 与精确闹钟互为兜底（精确闹钟不可用时保证整点切歌），timeCheck() 幂等，
     * 两者同时触发不会重复切歌；唤醒次数由固定 5s 轮询（720 次/小时）降为 1 次/小时。
     */
    private void startTick() {
        stopTick();
        tick = new Runnable() {
            @Override
            public void run() {
                if (paused) {
                    tick = null;
                    return;
                }
                timeCheck();
                handler.postDelayed(this, delayToNextHourMs());
            }
        };
        handler.postDelayed(tick, delayToNextHourMs());
    }

    private void stopTick() {
        if (tick != null) {
            handler.removeCallbacks(tick);
            tick = null;
        }
    }

    /** 距「下一个整点 +1s」的毫秒数（与精确闹钟同一时刻点）。 */
    private static long delayToNextHourMs() {
        return Math.max(1L, nextHourBoundaryMs() - System.currentTimeMillis());
    }

    /** 「下一个整点 +1s」的绝对时间戳（定时器与精确闹钟共用）。 */
    private static long nextHourBoundaryMs() {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 1);
        next.set(Calendar.MILLISECOND, 0);
        next.add(Calendar.HOUR_OF_DAY, 1);
        return next.getTimeInMillis();
    }

    /**
     * 整点检测（FR-01）：小时未变或暂停→跳过；变化→淡出停 bgm→（非首次且启用）演奏主题曲→
     * 周六 K.K. 判定（FR-17）→计算 URL→后台取源→播放。
     */
    private void timeCheck() {
        if (!started || paused || switching) {
            return;
        }
        final String newHour = HourResolver.getHour(currentGame, Calendar.getInstance());
        if (newHour.equals(hour)) {
            return;
        }
        switching = true;
        final boolean first = (hour == null);
        engine.stopBgm(() -> {
            if (!first && settings.isTuneEnabled()) {
                chime.playTune(settings.getTune(), settings.getSoundVol() / 100f,
                        () -> afterChime(newHour));
            } else {
                afterChime(newHour);
            }
        });
    }

    private void afterChime(String newHour) {
        hour = newHour;

        // 周六 K.K. 自动切换与周日恢复（FR-17）
        if (settings.isKkSaturday()) {
            int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            boolean nightHour = "8pm".equals(newHour) || "9pm".equals(newHour)
                    || "10pm".equals(newHour) || "11pm".equals(newHour);
            if (day == Calendar.SATURDAY && nightHour) {
                currentGame = GameCatalog.KK_GAME;
                if (ui != null) {
                    ui.onGameAutoChanged(currentGame);
                }
            } else if (day == Calendar.SUNDAY && "12am".equals(newHour)) {
                currentGame = settings.getGame();
                if (currentGame == null) {
                    currentGame = GameCatalog.DEFAULT_GAME;
                }
                if (ui != null) {
                    ui.onGameAutoChanged(currentGame);
                }
            }
        }

        switching = false;
        playForHour();
    }

    /** 手动触发的“以当前小时立即重播”（FR-03/33 等）。 */
    private void replayCurrentHour() {
        hour = HourResolver.getHour(currentGame, Calendar.getInstance());
        switching = false;
        engine.stopBgm(this::playForHour);
    }

    /** 计算 URL 并在后台取源后播放当前小时音乐。 */
    private void playForHour() {
        // random 每次从 14 个游戏随机（FR-11）
        final String gameUrl = GameCatalog.RANDOM.equals(currentGame)
                ? GameCatalog.GAMES[random.nextInt(GameCatalog.GAMES.length)]
                : currentGame;
        final boolean isKk = GameCatalog.KK_GAME.equals(gameUrl);

        final String musicKey;
        final String url;
        if (isKk) {
            List<String> enabled = kkEnabled();
            String song = kkRepo.randomFrom(enabled);
            if (song == null) {
                postError("failedToLoadSound", null);
                return;
            }
            musicKey = song;
            url = UrlBuilder.buildKkUrl(song);
        } else if ("pocket-camp".equals(gameUrl)) {
            musicKey = HourResolver.hourToPocketCamp(hour);
            url = UrlBuilder.buildHourlyUrl(gameUrl, musicKey);
        } else {
            // population-growing-rainy 特例已在 getHour 内固定为 12am（FR-13）
            musicKey = hour;
            url = UrlBuilder.buildHourlyUrl(gameUrl, musicKey);
        }

        final boolean noDownload = settings.isPreferNoDownload();
        final boolean loop = !settings.isGrandFather() && !isKk;
        final int token = ++playToken;
        ioExecutor.execute(() -> {
            // 网络/磁盘取源必须在后台线程（SoundCache 约定）
            final SoundCache.FetchResult result = soundCache.getUrlDetailed(url, noDownload);
            handler.post(() -> {
                if (token != playToken || paused || !started) {
                    return;
                }
                if (result.path == null) {
                    Log.w(TAG, "no source for " + url + ": " + result.detail);
                    postError("failedToLoadSound", result.detail);
                    return;
                }
                engine.playBgm(result.path, loop, isKk);
                String friendly = GameCatalog.toFriendlyName(gameUrl);
                lastFriendly = friendly;
                lastHourText = musicKey;
                updateNotification(friendly, musicKey);
                if (ui != null) {
                    ui.onPlayingChanged(friendly, musicKey);
                }
            });
        });
    }

    // ---- 雨声 ----

    /** K.K. 启用列表（带缓存，kkListSaved() 时失效）。 */
    private List<String> kkEnabled() {
        if (kkEnabledSongs == null) {
            kkEnabledSongs = settings.getKkEnabled(kkRepo.allSongs());
        }
        return kkEnabledSongs;
    }

    /** 启动/重启雨声底噪（FR-30），循环由摆钟模式决定。 */
    private void playRain() {
        final String url = UrlBuilder.buildRainUrl(settings.isGameRain(), settings.isPeacefulRain());
        final boolean noDownload = settings.isPreferNoDownload();
        final boolean loop = !settings.isGrandFather();
        ioExecutor.execute(() -> {
            final SoundCache.FetchResult result = soundCache.getUrlDetailed(url, noDownload);
            handler.post(() -> {
                if (paused || !started) {
                    return;
                }
                if (result.path == null) {
                    Log.w(TAG, "no rain source for " + url + ": " + result.detail);
                    postError("failedToLoadRainSound", result.detail);
                    return;
                }
                engine.playRain(result.path, loop);
            });
        });
    }

    private void restartRain() {
        if (started && !paused) {
            engine.stopRain(this::playRain);
        }
    }

    // ---- 坏文件清理（FR-43） ----

    private void handleCorrupt(String source, boolean isRain) {
        if (source != null && source.startsWith("/") && !settings.isPreferNoDownload()) {
            final String localName = new File(source).getName();
            ioExecutor.execute(() -> {
                soundCache.deleteCorrupt(localName);
                handler.post(() -> {
                    if (ui != null) {
                        ui.onOfflineCountChanged();
                    }
                });
            });
        }
        postError(isRain ? "failedToLoadRainSound" : "failedToLoadSound", source);
    }

    private void postError(String key, String detail) {
        Log.w(TAG, "play error key=" + key + (detail != null ? " detail=" + detail : ""));
        if (ui != null) {
            ui.onErrorKey(key, detail);
        }
    }

    private void notifyPause() {
        if (ui != null) {
            ui.onPauseChanged(paused);
        }
    }

    // ---- 下载回调转发 ----

    private BatchDownloader.Listener newDownloadListener(final boolean kk) {
        return new BatchDownloader.Listener() {
            @Override
            public void onProgress(final int done, final int total) {
                handler.post(() -> {
                    if (ui != null) {
                        ui.onDownloadProgress(kk, done, total);
                    }
                });
            }

            @Override
            public void onFileDone(boolean isKk) {
                handler.post(() -> {
                    if (ui != null) {
                        ui.onOfflineCountChanged();
                    }
                });
            }

            @Override
            public void onFailed() {
                handler.post(() -> {
                    if (ui != null) {
                        ui.onDownloadFailed();
                    }
                });
            }

            @Override
            public void onComplete() {
                handler.post(() -> {
                    if (ui != null) {
                        ui.onDownloadComplete();
                    }
                });
            }
        };
    }

    // ---- 整点闹钟兜底 ----

    /** 下一个整点 +1s 触发 exact alarm；无精确闹钟权限时降级纯轮询。 */
    private void scheduleHourAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            return;
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextHourBoundaryMs(), hourAlarmIntent());
    }

    private void cancelHourAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(hourAlarmIntent());
        }
    }

    private PendingIntent hourAlarmIntent() {
        Intent intent = new Intent(this, HourChangeReceiver.class);
        intent.setAction(HourChangeReceiver.ACTION_HOUR_ALARM);
        return PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- 音频焦点（FR-35 替代：LOSS 自动暂停） ----

    private void requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS && !paused) {
                            setPaused(true);
                        }
                    })
                    .build();
        }
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    // ---- 通知 ----

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * 创建媒体会话：向锁屏与系统媒体控制区提供「播放/暂停」控件与当前曲目信息。
     * 播放状态由本服务的 paused 状态驱动（暂停=停止全部音频），不绑定某个播放器实例。
     */
    private void createMediaSession() {
        mediaSession = new MediaSessionCompat(this, "nook_playback");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                // 与主界面按钮同一入口；转 post 派发，避免在会话回调里重入会话方法
                handler.post(() -> setPaused(false));
            }

            @Override
            public void onPause() {
                handler.post(() -> setPaused(true));
            }
        });
        Intent open = new Intent(this, MainActivity.class);
        mediaSession.setSessionActivity(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mediaSession.setActive(true);
    }

    /** 同步媒体会话的播放状态与元数据（锁屏/系统媒体控制区展示用）。 */
    private void syncMediaSession(String friendlyName, String hourText) {
        if (mediaSession == null) {
            return;
        }
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playingText(friendlyName, hourText))
                .build());
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(started && !paused
                                ? PlaybackStateCompat.STATE_PLAYING
                                : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    /** 通知与锁屏共用的播放文案（对齐桌面版托盘）。 */
    private static String playingText(String friendlyName, String hourText) {
        return friendlyName != null
                ? "playing " + friendlyName + " (" + hourText + ")!"
                : "playing nothing!";
    }

    /** 精简媒体通知：大图标 + 播放文案 + 播放暂停按钮（不设进度条/额外按钮）。 */
    private Notification buildNotification(String friendlyName, String hourText) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 播放暂停按钮：点击后由 PlayerService 执行 togglePause()（与主界面按钮同一行为）
        Intent toggle = new Intent(this, PlayerService.class).setAction(ACTION_TOGGLE_PAUSE);
        PendingIntent toggleIntent = PendingIntent.getForegroundService(this, 1, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean showPlay = paused; // 暂停中显示「播放」，播放中显示「暂停」（与主界面按钮文案一致）
        NotificationCompat.Action toggleAction = new NotificationCompat.Action.Builder(
                showPlay ? R.drawable.ic_play : R.drawable.ic_pause,
                showPlay ? "Play" : "Pause", toggleIntent).build();

        // MediaStyle + 会话令牌：使锁屏媒体区 / 系统媒体控制区能显示播放暂停控件
        MediaStyle style = new MediaStyle().setShowActionsInCompactView(0);
        if (mediaSession != null) {
            style.setMediaSession(mediaSession.getSessionToken());
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon())
                .setContentTitle(playingText(friendlyName, hourText))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(toggleAction)
                .setStyle(style)
                .build();
    }

    /** 通知/锁屏大图标：直接使用参考图（独立资源，避免 adaptive 蒙版影响），服务内只渲染一次。 */
    private Bitmap largeIcon() {
        if (largeIcon == null) {
            Drawable d = getDrawable(R.drawable.ic_notification_large);
            if (d == null) {
                return null;
            }
            int size = (int) (getResources().getDisplayMetrics().density * 64);
            largeIcon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(largeIcon);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
        }
        return largeIcon;
    }

    private void updateNotification(String friendlyName, String hourText) {
        syncMediaSession(friendlyName, hourText);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(friendlyName, hourText));
        }
    }
}
