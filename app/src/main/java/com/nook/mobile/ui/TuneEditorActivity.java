package com.nook.mobile.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.nook.mobile.R;
import com.nook.mobile.audio.BeepSynth;
import com.nook.mobile.data.I18nManager;
import com.nook.mobile.data.SettingsRepository;
import com.nook.mobile.domain.TownTune;

import java.io.IOException;

/**
 * 城镇主题曲编辑器（FR-22）：16 竖直滑块（1..16），改动即试听 beep（FR-23）并按值着色（16 档色）；
 * 播放按钮整条试听并以 350ms 步进高亮；保存后约 1s 显示“已保存”。
 */
public final class TuneEditorActivity extends AppCompatActivity {

    private static final int NOTE_COUNT = 16;

    /** 16 档滑块着色（对齐桌面版 main.css c=1..16）。 */
    private static final int[] COLOR_RES = {
            R.color.tune_c1, R.color.tune_c2, R.color.tune_c3, R.color.tune_c4,
            R.color.tune_c5, R.color.tune_c6, R.color.tune_c7, R.color.tune_c8,
            R.color.tune_c9, R.color.tune_c10, R.color.tune_c11, R.color.tune_c12,
            R.color.tune_c13, R.color.tune_c14, R.color.tune_c15, R.color.tune_c16
    };

    private SettingsRepository settings;
    private I18nManager i18n;
    private BeepSynth beep;

    private final VerticalSeekBar[] sliders = new VerticalSeekBar[NOTE_COUNT];
    private final TextView[] noteLabels = new TextView[NOTE_COUNT];
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tune_editor);

        InsetsUtil.applySystemBars(findViewById(R.id.root));
        settings = new SettingsRepository(this);
        try {
            i18n = new I18nManager(this);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load i18n assets", e);
        }
        i18n.setLanguage(settings.getLang());
        beep = new BeepSynth();

        ((TextView) findViewById(R.id.lblTitle)).setText(i18n.tr("tune settings"));
        ((TextView) findViewById(R.id.lblTip)).setText(i18n.tr("customize"));

        buildSliders();

        Button btnPlay = findViewById(R.id.btnPlayTune);
        btnPlay.setText(i18n.tr("play"));
        btnPlay.setOnClickListener(v -> playWholeTune());

        Button btnSave = findViewById(R.id.btnSaveTune);
        btnSave.setText(i18n.tr("save"));
        btnSave.setOnClickListener(v -> saveTune(btnSave));
    }

    @Override
    protected void onDestroy() {
        beep.release();
        super.onDestroy();
    }

    /** 动态创建 16 个竖直滑块，排成“上 8 下 8”两行（竖屏适配，对应源码 flex-wrap 换行）。 */
    private void buildSliders() {
        LinearLayout topRow = findViewById(R.id.topRow);
        LinearLayout bottomRow = findViewById(R.id.bottomRow);
        String[] tune = settings.getTune();

        for (int i = 0; i < NOTE_COUNT; i++) {
            final int index = i;
            final LinearLayout row = (i < 8) ? topRow : bottomRow;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));

            TextView label = new TextView(this);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(11);
            noteLabels[i] = label;
            cell.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            VerticalSeekBar bar = new VerticalSeekBar(this);
            // 滑块值 1..16 → progress 0..15
            bar.setMax(NOTE_COUNT - 1);
            int slider = safeSliderFor(tune, index);
            bar.setProgress(slider - 1);
            sliders[i] = bar;
            cell.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            bar.setOnUserChangeListener((b, progress, fromUser) -> {
                // 改动即试听 + 重新着色（FR-22）
                int value = progress + 1;
                applyCellStyle(index, value, false);
                beep.playBeep(value);
            });

            row.addView(cell);
            applyCellStyle(index, slider, false);
        }
    }

    private int safeSliderFor(String[] tune, int index) {
        if (tune != null && index < tune.length) {
            try {
                return TownTune.sliderForSymbol(tune[index]);
            } catch (IllegalArgumentException e) {
                // 存储中出现未知符号时回退休止符
            }
        }
        return 1;
    }

    /** 按滑块值着色并更新符号标签；highlight 时加粗放大模拟 blink。 */
    private void applyCellStyle(int index, int value, boolean highlight) {
        int color = ContextCompat.getColor(this, COLOR_RES[value - 1]);
        VerticalSeekBar bar = sliders[index];
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setThumbTintList(ColorStateList.valueOf(color));
        TextView label = noteLabels[index];
        label.setText(TownTune.symbolForSlider(value));
        label.setTextColor(color);
        label.setTextSize(highlight ? 16 : 12);
        label.setTypeface(null, highlight ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** 整条试听：BeepSynth 内部按 350ms 步进回调高亮（FR-22/23）。 */
    private void playWholeTune() {
        final int[] values = new int[NOTE_COUNT];
        for (int i = 0; i < NOTE_COUNT; i++) {
            values[i] = sliders[i].getProgress() + 1;
        }
        beep.playBeeps(values, index -> {
            for (int i = 0; i < NOTE_COUNT; i++) {
                applyCellStyle(i, sliders[i].getProgress() + 1, i == index);
            }
        });
    }

    /** 保存：滑块值转符号数组持久化；按钮短暂显示“已保存”（约 1s，FR-22）。 */
    private void saveTune(final Button btnSave) {
        String[] tune = new String[NOTE_COUNT];
        for (int i = 0; i < NOTE_COUNT; i++) {
            tune[i] = TownTune.symbolForSlider(sliders[i].getProgress() + 1);
        }
        settings.setTune(tune);
        btnSave.setText(i18n.tr("saved!"));
        handler.postDelayed(() -> {
            if (!isFinishing()) {
                btnSave.setText(i18n.tr("save"));
            }
        }, 1000);
    }
}
