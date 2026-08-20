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

import androidx.core.app.NotificationCompat;

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
import java.util.concurrent.Executors;

/**
 * 前台播放服务（对应桌面版隐藏窗口 player.js，FR-01/03/14/17/21/30-35/73）。
 * 5s Handler 轮询 timeCheck() + AlarmManager 整点兜底；
 * 音频取源（SoundCache.getUrlSync）一律在后台线程执行。
 */
public final class PlayerService extends Service {

    public static final String ACTION_TIME_CHECK = "com.nook.mobile.action.TIME_CHECK";

    private static final String TAG = "PlayerService";
    private static final String CHANNEL_ID = "nook_playback";
    private static final int NOTIFICATION_ID = 1;

    /** 整点检测循环 5000ms（FR-01 / §6.2）。 */
    private static final long TICK_MS = 5000;

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
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    private SettingsRepository settings;
    private KkRepository kkRepo;
    private SoundCache soundCache;
    private BatchDownloader downloader;
    private AudioEngine engine;
    private ChimePlayer chime;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService 后必须尽快 startForeground
        startForeground(NOTIFICATION_ID, buildNotification(lastFriendly, lastHourText));
        if (intent != null && ACTION_TIME_CHECK.equals(intent.getAction()) && started) {
            timeCheck();
            scheduleHourAlarm();
        }
        return START_STICKY;
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
        chime.release();
        engine.release();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---- 对 UI 的入口 ----

    public void setUiCallback(UiCallback callback) {
        this.ui = callback;
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
    }

    /** 手动切换游戏模式（FR-03）：持久化并以当前小时立即重播。 */
    public void changeGame(String game) {
        settings.setGame(game);
        currentGame = game;
        if (started && !paused) {
            replayCurrentHour();
        }
    }

    /** 音乐音量 0-100（FR-31），同步报时音量（报时每次发声时读取）。 */
    public void changeMusicVolume(int vol) {
        settings.setSoundVol(vol);
        engine.setMusicVolume(vol / 100f);
    }

    /** 雨声音量 0-100（FR-32）。 */
    public void changeRainVolume(int vol) {
        settings.setRainVol(vol);
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
            timeCheck();
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

    private void startTick() {
        stopTick();
        tick = new Runnable() {
            @Override
            public void run() {
                if (!paused) {
                    timeCheck();
                }
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.postDelayed(tick, TICK_MS);
    }

    private void stopTick() {
        if (tick != null) {
            handler.removeCallbacks(tick);
            tick = null;
        }
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
            List<String> enabled = settings.getKkEnabled(kkRepo.allSongs());
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
        Calendar next = Calendar.getInstance();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 1);
        next.set(Calendar.MILLISECOND, 0);
        next.add(Calendar.HOUR_OF_DAY, 1);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), hourAlarmIntent());
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

    /** 媒体通知文案对齐桌面版托盘：playing {友好名} ({hour})! / playing nothing!（FR-70 替代）。 */
    private Notification buildNotification(String friendlyName, String hourText) {
        String text = friendlyName != null
                ? "playing " + friendlyName + " (" + hourText + ")!"
                : "playing nothing!";
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon())
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    /** 通知展开态大图标：直接使用参考图（独立资源，避免 adaptive 蒙版影响）。 */
    private Bitmap largeIcon() {
        Drawable d = getDrawable(R.drawable.ic_notification_large);
        if (d == null) {
            return null;
        }
        int size = (int) (getResources().getDisplayMetrics().density * 64);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(canvas);
        return bmp;
    }

    private void updateNotification(String friendlyName, String hourText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(friendlyName, hourText));
        }
    }
}
