package com.nook.mobile.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import com.nook.mobile.R;
import com.nook.mobile.data.I18nManager;
import com.nook.mobile.data.SettingsRepository;
import com.nook.mobile.domain.GameCatalog;
import com.nook.mobile.service.PlayerService;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面（对应桌面版 main.js 主页）：
 * 首次需点击“开始”才启动播放（§7.2）；游戏 Spinner 16 项（FR-10 顺序）；
 * 音乐/雨声音量（FR-31/32）；暂停（FR-34）；语言切换即时重渲染（FR-50）；
 * 错误 Snackbar 约 4 秒（§6.5）。
 */
public final class MainActivity extends AppCompatActivity {

    /** 错误提示时长约 4 秒（§6.5）。 */
    private static final int ERROR_DURATION_MS = 4000;

    /** 语言下拉顺序与显示名（FR-50 表）。 */
    private static final String[] LANG_CODES = {"en", "es", "de", "it", "fr", "cn"};
    private static final String[] LANG_NAMES = {
            "English (US)", "Spanish/Español (ES)", "German/Deutsch (DE)",
            "Italian/Italiano (IT)", "French/Français (FR)", "Chinese/中文 (CN)"
    };

    private SettingsRepository settings;
    private I18nManager i18n;
    private PlayerService service;

    private Button btnPause;
    private Spinner gameSpinner;
    private Spinner langSpinner;
    private SeekBar musicVol;
    private SeekBar rainVol;
    private TextView txtPlaying;

    /** Spinner 顺序 = 随机池 14 项 + K.K. + Random（FR-10 表顺序）。 */
    private final List<String> gameIds = new ArrayList<>();

    private boolean suppressGameEvent;
    private boolean suppressLangEvent;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            service.setUiCallback(uiCallback);
            // 打开应用即自动进入播放控制页并开始播放（§7.2 的手势门槛已按需求移除）
            if (!service.isStarted()) {
                ContextCompat.startForegroundService(MainActivity.this,
                        new Intent(MainActivity.this, PlayerService.class));
                service.beginPlayback();
            }
            syncFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final PlayerService.UiCallback uiCallback = new PlayerService.UiCallback() {
        @Override
        public void onGameAutoChanged(String game) {
            selectGame(game);
        }

        @Override
        public void onPlayingChanged(String friendlyName, String hour) {
            txtPlaying.setText(friendlyName != null
                    ? "playing " + friendlyName + " (" + hour + ")!"
                    : "playing nothing!");
        }

        @Override
        public void onErrorKey(String key, String detail) {
            showError(key, detail);
        }

        @Override
        public void onPauseChanged(boolean paused) {
            updatePauseButton(paused);
        }

        @Override
        public void onDownloadProgress(boolean kk, int done, int total) {
            // 下载进度在设置页展示
        }

        @Override
        public void onOfflineCountChanged() {
        }

        @Override
        public void onDownloadFailed() {
            showError("failedToDownload", null);
        }

        @Override
        public void onDownloadComplete() {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        InsetsUtil.applySystemBars(findViewById(R.id.root));
        settings = new SettingsRepository(this);
        i18n = I18nManager.get(this);
        i18n.setLanguage(settings.getLang());

        btnPause = findViewById(R.id.btnPause);
        gameSpinner = findViewById(R.id.gameSpinner);
        langSpinner = findViewById(R.id.langSpinner);
        musicVol = findViewById(R.id.musicVol);
        rainVol = findViewById(R.id.rainVol);
        txtPlaying = findViewById(R.id.txtPlaying);

        for (String g : GameCatalog.GAMES) {
            gameIds.add(g);
        }
        gameIds.add(GameCatalog.KK_GAME);
        gameIds.add(GameCatalog.RANDOM);

        setupGameSpinner();
        setupLangSpinner();
        setupControls();
        renderTexts();
        requestNotificationPermission();

        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页返回时重新接管回调并刷新状态
        if (service != null) {
            service.setUiCallback(uiCallback);
            syncFromService();
        }
        // 语言可能在其他入口变更，保持一致
        i18n.setLanguage(settings.getLang());
        renderTexts();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 拖动中被打断（如返回手势）时补一次落盘，确保音量持久化
        if (musicVol.getProgress() != settings.getSoundVol()) {
            persistMusicVolume(musicVol.getProgress());
        }
        if (rainVol.getProgress() != settings.getRainVol()) {
            persistRainVolume(rainVol.getProgress());
        }
    }

    /** 音乐音量落盘（松手/离开页面时调用；服务未绑定则直接写设置）。 */
    private void persistMusicVolume(int vol) {
        if (service != null) {
            service.changeMusicVolume(vol, true);
        } else {
            settings.setSoundVol(vol);
        }
    }

    /** 雨声音量落盘。 */
    private void persistRainVolume(int vol) {
        if (service != null) {
            service.changeRainVolume(vol, true);
        } else {
            settings.setRainVol(vol);
        }
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            // 只注销自己的回调：本页销毁时可能已由其它页面接管（例如设置页正在前台），
            // 不能把对方的回调一并清空
            service.clearUiCallback(uiCallback);
        }
        unbindService(connection);
        super.onDestroy();
    }

    // ---- 初始化 ----

    private void setupGameSpinner() {
        gameSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressGameEvent) {
                    suppressGameEvent = false;
                    return;
                }
                String game = gameIds.get(position);
                if (service != null) {
                    service.changeGame(game);
                } else {
                    settings.setGame(game);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupLangSpinner() {
        langSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, LANG_NAMES));
        suppressLangEvent = true;
        langSpinner.setSelection(indexOfLang(settings.getLang()));
        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressLangEvent) {
                    suppressLangEvent = false;
                    return;
                }
                String lang = LANG_CODES[position];
                settings.setLang(lang);
                i18n.setLanguage(lang);
                // 切换即时重渲染（FR-50）
                renderTexts();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupControls() {
        btnPause.setOnClickListener(v -> {
            if (service != null) {
                ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class));
                service.togglePause();
            }
        });

        musicVol.setProgress(settings.getSoundVol());
        musicVol.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && service != null) {
                    // 拖动中只实时生效，不写盘（避免拖动期间的高频磁盘写入）
                    service.changeMusicVolume(progress, false);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistMusicVolume(seekBar.getProgress());
            }
        });

        rainVol.setProgress(settings.getRainVol());
        rainVol.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && service != null) {
                    service.changeRainVolume(progress, false);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistRainVolume(seekBar.getProgress());
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnTuneEditor).setOnClickListener(
                v -> startActivity(new Intent(this, TuneEditorActivity.class)));
        findViewById(R.id.btnKkList).setOnClickListener(
                v -> startActivity(new Intent(this, KkListActivity.class)));
    }

    // ---- 渲染 ----

    /** 重渲染全部可翻译文案（FR-50 切换语言即时生效）。 */
    private void renderTexts() {
        ((TextView) findViewById(R.id.lblGame)).setText(i18n.tr("game"));
        ((TextView) findViewById(R.id.lblMusicVol)).setText(i18n.tr("Music Volume"));
        ((TextView) findViewById(R.id.lblRainVol)).setText(i18n.tr("Rain Volume"));
        ((TextView) findViewById(R.id.lblLanguage)).setText(i18n.tr("language"));
        ((Button) findViewById(R.id.btnTuneEditor)).setText(i18n.tr("customize town tune"));
        ((Button) findViewById(R.id.btnKkList)).setText(i18n.tr("customize k.k. playlist"));
        updatePauseButton(service != null ? service.isPaused() : settings.isPaused());

        // 游戏下拉显示名跟随语言重建（选中项保持）
        String selected = service != null ? service.getCurrentGame() : settings.getGame();
        List<String> names = new ArrayList<>();
        for (String id : gameIds) {
            names.add(i18n.tr(GameCatalog.displayNameKey(id)));
        }
        suppressGameEvent = true;
        gameSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        selectGame(selected);
    }

    /**
     * 与服务状态对齐。
     * <p>"playing …" 文案只在播放变化时由服务推送，界面重建（从桌面重新打开）或从其它页面
     * 回到主界面时不会自动补发，这里主动拉取一次，避免文案停留在空文本。
     */
    private void syncFromService() {
        if (service == null) {
            return;
        }
        updatePauseButton(service.isPaused());
        selectGame(service.getCurrentGame());
        uiCallback.onPlayingChanged(service.getPlayingFriendlyName(), service.getPlayingHourText());
    }

    private void selectGame(String game) {
        int idx = gameIds.indexOf(game);
        if (idx >= 0 && gameSpinner.getSelectedItemPosition() != idx) {
            suppressGameEvent = true;
            gameSpinner.setSelection(idx);
        }
    }

    private void updatePauseButton(boolean paused) {
        // 暂停态按钮切换为“播放”文案（对应桌面版 data-i18n-title-alt）
        btnPause.setText(i18n.tr(paused ? "Play" : "Pause"));
    }

    private void showError(String key, String detail) {
        String text = i18n.tr(key);
        if (detail != null && !detail.isEmpty()) {
            // 附加技术原因（弱网/超时/HTTP 状态等），便于实机排障
            text = text + " (" + detail + ")";
        }
        Snackbar.make(findViewById(R.id.root), text, ERROR_DURATION_MS).show();
    }

    private int indexOfLang(String lang) {
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(lang)) {
                return i;
            }
        }
        return 0;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            }).launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /** 精简 SeekBar 监听基类。 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
