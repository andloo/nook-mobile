package com.nook.mobile.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
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

    /** 报时淡出步进间隔 5ms（§6.2）。 */
    private static final long FADE_STEP_MS = 5;

    private final ExoPlayer player;
    private final Uri chimeUri;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Runnable tuneStep;
    private Runnable fadeStep;

    public ChimePlayer(Context context) {
        this.player = new ExoPlayer.Builder(context).build();
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

    /** 淡出（每步 -0.01 / 5ms）到 0 后停止。 */
    private void fadeAndStop(final Runnable onDone) {
        fadeStep = new Runnable() {
            @Override
            public void run() {
                float next = Math.max(0f, player.getVolume() - 0.01f);
                player.setVolume(next);
                if (next <= 0f) {
                    fadeStep = null;
                    player.stop();
                    if (onDone != null) {
                        onDone.run();
                    }
                } else {
                    handler.postDelayed(this, FADE_STEP_MS);
                }
            }
        };
        handler.post(fadeStep);
    }

    /** 立即取消当前演奏与淡出。 */
    public void cancel() {
        if (tuneStep != null) {
            handler.removeCallbacks(tuneStep);
            tuneStep = null;
        }
        if (fadeStep != null) {
            handler.removeCallbacks(fadeStep);
            fadeStep = null;
        }
        player.stop();
    }

    public void release() {
        cancel();
        player.release();
    }
}
