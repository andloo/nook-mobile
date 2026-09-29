package com.nook.mobile.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * 编辑器试听合成音（FR-23）：AudioTrack 三角波，13 个音高 G4≈392Hz … E6≈1319Hz。
 * 对应桌面版 player.js 的 playBeep() / playBeeps() 与 tunesBeepMap[]。
 * <p>
 * 滑块值规则：1(zZz)静音；2(-)立即结束；3..15 → 音高索引 = 值-3；16(?) 随机 13 音高。
 * 单音 350ms、音量 0.5；整条试听时延音 '-' 每个叠加 350ms（§6.2）。
 */
public final class BeepSynth {

    /** 单音默认时长（ms）。 */
    private static final int NOTE_MS = 350;

    /** 固定音量 0.50（FR-23）。 */
    private static final float VOLUME = 0.5f;

    private static final int SAMPLE_RATE = 44100;

    /** beep 音高频率表（索引 0..12 对应 G4..E6，即 G0..E2 的编辑音）。 */
    private static final double[] FREQS = {
            392.00, 440.00, 493.88, 523.25, 587.33, 659.25, 698.46,
            783.99, 880.00, 987.77, 1046.50, 1174.66, 1318.51
    };

    /** 整条试听高亮回调（主线程触发，FR-22 高亮步进 350ms）。 */
    public interface HighlightCallback {
        /** index 为当前高亮音符位 0..15；结束时回调 -1 清除高亮。 */
        void onHighlight(int index);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private AudioTrack track;
    /** 当前轨道缓冲字节数：同长度音符（拖动试听固定 350ms）复用同一轨道，避免每个音符新建/释放。 */
    private int trackBytes;
    /** 复用的 PCM 缓冲（与轨道等长）。 */
    private short[] pcm;
    private Runnable beepsStep;

    /**
     * 播放单个滑块值对应的编辑音（350ms）。
     * 1 → 静音；2 → 立即结束；3..15 → 索引 = 值-3；16 → 随机音高。
     */
    public void playBeep(int slider) {
        if (slider == 1 || slider == 2) {
            // 休止/延音：不发新音
            stopTrack();
            return;
        }
        int pitchIndex = slider == 16 ? random.nextInt(FREQS.length) : slider - 3;
        if (pitchIndex < 0 || pitchIndex >= FREQS.length) {
            return;
        }
        playTone(FREQS[pitchIndex], NOTE_MS);
    }

    /**
     * 整条主题曲试听（FR-22 / FR-23）：每 350ms 步进一个音符位并回调高亮；
     * 音符后紧跟的连续 '-'(2) 使该音时长按个数叠加 350ms；结束回调 onHighlight(-1)。
     *
     * @param sliders   16 个滑块值（1..16）
     * @param highlight 高亮回调（可为 null）
     */
    public void playBeeps(final int[] sliders, final HighlightCallback highlight) {
        cancel();
        beepsStep = new Runnable() {
            private int i = 0;

            @Override
            public void run() {
                if (i >= sliders.length) {
                    beepsStep = null;
                    stopTrack();
                    if (highlight != null) {
                        highlight.onHighlight(-1);
                    }
                    return;
                }
                if (highlight != null) {
                    highlight.onHighlight(i);
                }
                int v = sliders[i];
                if (v != 2) {
                    // 计算其后连续延音个数，叠加时长
                    int sustain = 0;
                    for (int j = i + 1; j < sliders.length && sliders[j] == 2; j++) {
                        sustain++;
                    }
                    if (v == 1) {
                        stopTrack();
                    } else {
                        int pitchIndex = v == 16 ? random.nextInt(FREQS.length) : v - 3;
                        if (pitchIndex >= 0 && pitchIndex < FREQS.length) {
                            playTone(FREQS[pitchIndex], NOTE_MS * (1 + sustain));
                        }
                    }
                }
                i++;
                handler.postDelayed(this, NOTE_MS);
            }
        };
        handler.post(beepsStep);
    }

    /** 取消整条试听与当前发声。 */
    public void cancel() {
        if (beepsStep != null) {
            handler.removeCallbacks(beepsStep);
            beepsStep = null;
        }
        stopTrack();
    }

    /**
     * 生成三角波 PCM 并用复用的 MODE_STATIC 轨道播放（带短包络防爆音）。
     * 轨道按音长复用：拖动试听固定 350ms，同一轨道反复使用，避免每个音符都新建/释放 AudioTrack。
     */
    private void playTone(double freq, int durationMs) {
        int samples = SAMPLE_RATE * durationMs / 1000;
        if (!ensureTrack(samples * 2)) {
            return;
        }
        // MODE_STATIC 下重写数据前必须先停止当前播放
        stopTrack();
        int attack = SAMPLE_RATE * 5 / 1000;
        int release = SAMPLE_RATE * 60 / 1000;
        for (int i = 0; i < samples; i++) {
            double phase = (i * freq / SAMPLE_RATE) % 1.0;
            // 三角波：0..1 相位映射到 -1..1..-1
            double tri = phase < 0.5 ? (4.0 * phase - 1.0) : (3.0 - 4.0 * phase);
            double env = 1.0;
            if (i < attack) {
                env = i / (double) attack;
            } else if (i > samples - release) {
                env = (samples - i) / (double) release;
            }
            pcm[i] = (short) (tri * env * VOLUME * Short.MAX_VALUE);
        }
        track.write(pcm, 0, samples);
        track.play();
    }

    /** 按需创建/复用静态轨道与 PCM 缓冲；返回 false 表示轨道创建失败。 */
    private boolean ensureTrack(int bytes) {
        if (track != null && trackBytes == bytes) {
            return true;
        }
        releaseTrack();
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            return false;
        }
        track = t;
        trackBytes = bytes;
        pcm = new short[bytes / 2];
        return true;
    }

    /** 停止当前发声（保留轨道供复用）。 */
    private void stopTrack() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException e) {
                // 未初始化或已释放，忽略
            }
        }
    }

    /** 释放轨道与 PCM 缓冲（页面销毁时调用）。 */
    private void releaseTrack() {
        if (track != null) {
            stopTrack();
            track.release();
            track = null;
        }
        trackBytes = 0;
        pcm = null;
    }

    public void release() {
        cancel();
        releaseTrack();
    }
}
