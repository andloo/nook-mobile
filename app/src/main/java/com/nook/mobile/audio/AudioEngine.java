package com.nook.mobile.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;

/**
 * 双 ExoPlayer 播放引擎（bgm + rain），对应桌面版 player.js 的
 * playSound()/playRain()/fadeSound()/stopAudio()。
 * <p>
 * 循环规则（FR-14 / FR-30 / FR-33）：bgm 在 !grandFather 且非 K.K. 时 REPEAT_MODE_ONE；
 * rain 在 !grandFather 时 REPEAT_MODE_ONE；否则 OFF。
 * 淡入淡出：Handler 步进 setVolume，每步 0.01、间隔 2ms（§6.2），淡出到 0 后 stop。
 * 所有方法须在主线程调用。
 */
public final class AudioEngine {

    /** 淡入淡出步进间隔（ms）与步长（§6.2）。 */
    private static final long FADE_STEP_MS = 2;
    private static final float FADE_STEP = 0.01f;

    /** 播放事件回调（主线程触发）。 */
    public interface Listener {
        /** K.K. 曲目自然播完（STATE_ENDED，FR-14 连播）。 */
        void onBgmEnded();

        /** bgm 解码/加载失败（供 FR-43 坏文件清理与报错），source 为当时的本地路径或 URL。 */
        void onBgmError(String source);

        /** 雨声解码/加载失败（FR-30 报错）。 */
        void onRainError(String source);
    }

    private final ExoPlayer bgm;
    private final ExoPlayer rain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private float musicVol = 0.5f;
    private float rainVol = 0.5f;

    private Runnable bgmFade;
    private Runnable rainFade;

    private boolean kkMode;
    private String bgmSource;
    private String rainSource;

    public AudioEngine(Context context, Listener listener) {
        this.listener = listener;
        this.bgm = new ExoPlayer.Builder(context).build();
        this.rain = new ExoPlayer.Builder(context).build();

        bgm.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && kkMode && AudioEngine.this.listener != null) {
                    AudioEngine.this.listener.onBgmEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (AudioEngine.this.listener != null) {
                    AudioEngine.this.listener.onBgmError(bgmSource);
                }
            }
        });
        rain.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (AudioEngine.this.listener != null) {
                    AudioEngine.this.listener.onRainError(rainSource);
                }
            }
        });
    }

    /** 本地绝对路径转 file Uri，其余按远程 URL 解析。 */
    private static Uri toUri(String source) {
        if (source.startsWith("/")) {
            return Uri.fromFile(new File(source));
        }
        return Uri.parse(source);
    }

    /**
     * 播放 bgm（本地路径或远程 URL），从 0 淡入到音乐音量。
     *
     * @param loop 是否单曲循环（!grandFather 且非 K.K.）
     * @param isKk 是否 K.K. 模式（播完触发 onBgmEnded 连播）
     */
    public void playBgm(String source, boolean loop, boolean isKk) {
        cancelFade(true);
        bgmSource = source;
        kkMode = isKk;
        bgm.setMediaItem(MediaItem.fromUri(toUri(source)));
        bgm.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        bgm.setVolume(0f);
        bgm.prepare();
        bgm.play();
        fadeTo(true, musicVol, null);
    }

    /** 播放雨声底噪（FR-30），从 0 淡入到雨声音量。 */
    public void playRain(String source, boolean loop) {
        cancelFade(false);
        rainSource = source;
        rain.setMediaItem(MediaItem.fromUri(toUri(source)));
        rain.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        rain.setVolume(0f);
        rain.prepare();
        rain.play();
        fadeTo(false, rainVol, null);
    }

    /** 淡出并停止 bgm，结束后回调 onDone（可为 null）。 */
    public void stopBgm(Runnable onDone) {
        if (!bgm.isPlaying() && bgm.getPlaybackState() == Player.STATE_IDLE) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        kkMode = false;
        fadeTo(true, 0f, () -> {
            bgm.stop();
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** 淡出并停止雨声，结束后回调 onDone（可为 null）。 */
    public void stopRain(Runnable onDone) {
        if (!rain.isPlaying() && rain.getPlaybackState() == Player.STATE_IDLE) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        fadeTo(false, 0f, () -> {
            rain.stop();
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** 淡出并停止全部音频（FR-34 暂停）。 */
    public void stopAll(Runnable onDone) {
        stopRain(null);
        stopBgm(onDone);
    }

    /** 音乐音量 0-1（FR-31），实时生效。 */
    public void setMusicVolume(float vol) {
        musicVol = clamp(vol);
        if (bgmFade == null) {
            bgm.setVolume(musicVol);
        }
    }

    /** 雨声音量 0-1（FR-32），实时生效。 */
    public void setRainVolume(float vol) {
        rainVol = clamp(vol);
        if (rainFade == null) {
            rain.setVolume(rainVol);
        }
    }

    /** 运行时切换雨声循环（摆钟模式切换用，FR-33）。 */
    public void setRainLooping(boolean loop) {
        rain.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void release() {
        cancelFade(true);
        cancelFade(false);
        bgm.release();
        rain.release();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void cancelFade(boolean isBgm) {
        Runnable fade = isBgm ? bgmFade : rainFade;
        if (fade != null) {
            handler.removeCallbacks(fade);
            if (isBgm) {
                bgmFade = null;
            } else {
                rainFade = null;
            }
        }
    }

    /** 以 0.01/2ms 步进把音量渐变到 target，到达后回调 onDone。 */
    private void fadeTo(final boolean isBgm, final float target, final Runnable onDone) {
        cancelFade(isBgm);
        final ExoPlayer player = isBgm ? bgm : rain;
        Runnable step = new Runnable() {
            @Override
            public void run() {
                float cur = player.getVolume();
                float next = cur < target
                        ? Math.min(target, cur + FADE_STEP)
                        : Math.max(target, cur - FADE_STEP);
                player.setVolume(next);
                if (next == target) {
                    if (isBgm) {
                        bgmFade = null;
                    } else {
                        rainFade = null;
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                } else {
                    handler.postDelayed(this, FADE_STEP_MS);
                }
            }
        };
        if (isBgm) {
            bgmFade = step;
        } else {
            rainFade = step;
        }
        handler.post(step);
    }
}
