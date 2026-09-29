package com.nook.mobile.audio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.LinearInterpolator;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import com.nook.mobile.R;
import com.nook.mobile.domain.TownTune;

import java.util.Random;

/**
 * 报时钟声播放（FR-21 / FR-24）。
 * chime.ogg 为 13 段音频精灵（G0[0-4s] … E2[48-52s]，每段 4 秒），
 * 用 MediaItem.ClippingConfiguration(startMs, endMs) 播指定片段。
 * 对应桌面版 player.js 的 playChime() / fadeAndStopChime()。
 * 所有方法须在主线程调用。
 */
public final class ChimePlayer {

    /** 音符间隔 900ms（FR-21）。 */
    private static final long NOTE_INTERVAL_MS = 900;

    /** 每段精灵 4 秒（§4.5）。 */
    private static final long SPRITE_LEN_MS = 4000;

    /** 报时淡出步进间隔 5ms（§6.2）：用于把原步进折算为等价的 ValueAnimator 时长。 */
    private static final long FADE_STEP_MS = 5;
    private static final float FADE_STEP = 0.01f;

    private final ExoPlayer player;
    private final Uri chimeUri;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Runnable tuneStep;
    private ValueAnimator fadeAnim;

    public ChimePlayer(Context context) {
        // chime.ogg 为本地资源，无需大缓冲（默认 50s，内存占用偏大）
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 4_000, 500, 1_000)
                .build();
        this.player = new ExoPlayer.Builder(context).setLoadControl(loadControl).build();
        this.chimeUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.chime);
    }

    /** 音高符号 → 精灵索引（0..12）；非音高返回 -1。 */
    private static int pitchIndex(String symbol) {
        for (int i = 0; i < TownTune.PITCHES.length; i++) {
            if (TownTune.PITCHES[i].equals(symbol)) {
                return i;
            }
        }
        return -1;
    }

    /** 播放单个音高精灵片段（每次发声前把音量同步为 vol，FR-21）。 */
    private void playPitch(int index, float vol) {
        long startMs = index * SPRITE_LEN_MS;
        MediaItem item = new MediaItem.Builder()
                .setUri(chimeUri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(startMs + SPRITE_LEN_MS)
                        .build())
                .build();
        player.setVolume(vol);
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    /**
     * 演奏整条主题曲（FR-21）：每 900ms 一个音符；zZz 与 - 静音；? 随机 13 音高；
     * 第 16 个音符后按 5ms 步长淡出到 0 并停止，随后回调 onDone。
     *
     * @param tune16 16 音符数组
     * @param vol    与音乐音量同步的报时音量（0-1）
     * @param onDone 演奏 + 淡出结束回调（可为 null）
     */
    public void playTune(final String[] tune16, final float vol, final Runnable onDone) {
        cancel();
        tuneStep = new Runnable() {
            private int i = 0;

            @Override
            public void run() {
                if (i < tune16.length) {
                    String note = tune16[i];
                    if ("?".equals(note)) {
                        playPitch(random.nextInt(TownTune.PITCHES.length), vol);
                    } else {
                        int idx = pitchIndex(note);
                        if (idx >= 0) {
                            playPitch(idx, vol);
                        }
                        // zZz / - → 静音，不发声
                    }
                    i++;
                    handler.postDelayed(this, NOTE_INTERVAL_MS);
                } else {
                    tuneStep = null;
                    fadeAndStop(onDone);
                }
            }
        };
        handler.post(tuneStep);
    }

    /** 淡出（等效原每步 -0.01 / 5ms）到 0 后停止；用 ValueAnimator 跟随 vsync，替代 5ms 忙循环。 */
    private void fadeAndStop(final Runnable onDone) {
        cancelFade();
        final float start = player.getVolume();
        if (start < FADE_STEP) {
            player.setVolume(0f);
            player.stop();
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(start, 0f);
        anim.setDuration((long) Math.ceil(start / FADE_STEP) * FADE_STEP_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> player.setVolume((Float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (fadeAnim != animation) {
                    return; // 已被 cancel() 取消/取代
                }
                fadeAnim = null;
                player.setVolume(0f);
                player.stop();
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        fadeAnim = anim;
        anim.start();
    }

    /** 取消进行中的淡出（先清引用再 cancel，避免 onAnimationEnd 误触发原回调）。 */
    private void cancelFade() {
        if (fadeAnim != null) {
            ValueAnimator anim = fadeAnim;
            fadeAnim = null;
            anim.cancel();
        }
    }

    /** 立即取消当前演奏与淡出。 */
    public void cancel() {
        if (tuneStep != null) {
            handler.removeCallbacks(tuneStep);
            tuneStep = null;
        }
        cancelFade();
        player.stop();
    }

    public void release() {
        cancel();
        player.release();
    }
}
