package com.nook.mobile.audio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.net.Uri;
import android.view.animation.LinearInterpolator;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;

/**
 * 双 ExoPlayer 播放引擎（bgm + rain），对应桌面版 player.js 的
 * playSound()/playRain()/fadeSound()/stopAudio()。
 * <p>
 * 循环规则（FR-14 / FR-30 / FR-33）：bgm 在 !grandFather 且非 K.K. 时 REPEAT_MODE_ONE；
 * rain 在 !grandFather 时 REPEAT_MODE_ONE；否则 OFF。
 * 淡入淡出：ValueAnimator 跟随 vsync 步进 setVolume（等效原 0.01/2ms 的渐变时长），淡出到 0 后 stop。
 * 所有方法须在主线程调用。
 */
public final class AudioEngine {

    /** 淡入淡出步进间隔（ms）与步长（§6.2）：用于把原步进折算为等价的 ValueAnimator 时长。 */
    private static final long FADE_STEP_MS = 2;
    private static final float FADE_STEP = 0.01f;

    /** 纯音频紧凑缓冲：默认 min/max 为 50s，内存占用偏大；15s/30s 足以覆盖弱网抖动。 */
    private static final int MIN_BUFFER_MS = 15_000;
    private static final int MAX_BUFFER_MS = 30_000;
    private static final int BUFFER_FOR_PLAYBACK_MS = 2_500;
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000;

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
    private final Listener listener;

    private float musicVol = 0.5f;
    private float rainVol = 0.5f;

    private ValueAnimator bgmFade;
    private ValueAnimator rainFade;

    private boolean kkMode;
    private String bgmSource;
    private String rainSource;

    public AudioEngine(Context context, Listener listener) {
        this.listener = listener;
        this.bgm = buildPlayer(context);
        this.rain = buildPlayer(context);

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

    /** 创建播放器并应用纯音频紧凑缓冲（降低 ExoPlayer 默认 50s 预缓冲的内存占用）。 */
    private static ExoPlayer buildPlayer(Context context) {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build();
        return new ExoPlayer.Builder(context).setLoadControl(loadControl).build();
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
        // 在线流媒体在熄屏时需保持 CPU 唤醒，避免缓冲被 Doze 打断；本地文件不持锁（省电）
        bgm.setWakeMode(source.startsWith("/") ? C.WAKE_MODE_NONE : C.WAKE_MODE_LOCAL);
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
        // 在线雨声同理：熄屏时保持 CPU 唤醒，本地文件不持锁
        rain.setWakeMode(source.startsWith("/") ? C.WAKE_MODE_NONE : C.WAKE_MODE_LOCAL);
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

    /** 取消进行中的淡变（先清引用再 cancel，避免 onAnimationEnd 误触发原回调）。 */
    private void cancelFade(boolean isBgm) {
        ValueAnimator fade = isBgm ? bgmFade : rainFade;
        if (fade != null) {
            if (isBgm) {
                bgmFade = null;
            } else {
                rainFade = null;
            }
            fade.cancel();
        }
    }

    /**
     * 把音量渐变到 target，到达后回调 onDone。
     * <p>用 ValueAnimator 跟随 vsync 更新（约 60 次/秒），替代原 0.01/2ms 的 Handler 忙循环
     * （约 500 次/秒主线程消息）；时长按原步进折算，渐变速度保持一致。
     */
    private void fadeTo(final boolean isBgm, final float target, final Runnable onDone) {
        cancelFade(isBgm);
        final ExoPlayer player = isBgm ? bgm : rain;
        final float start = player.getVolume();
        if (Math.abs(target - start) < FADE_STEP) {
            player.setVolume(target);
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(start, target);
        anim.setDuration((long) Math.ceil(Math.abs(target - start) / FADE_STEP) * FADE_STEP_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> player.setVolume((Float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if ((isBgm ? bgmFade : rainFade) != animation) {
                    return; // 已被 cancelFade 取消/取代
                }
                if (isBgm) {
                    bgmFade = null;
                } else {
                    rainFade = null;
                }
                player.setVolume(target);
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        if (isBgm) {
            bgmFade = anim;
        } else {
            rainFade = anim;
        }
        anim.start();
    }
}
