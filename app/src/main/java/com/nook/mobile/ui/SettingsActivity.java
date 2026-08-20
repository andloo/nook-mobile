package com.nook.mobile.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.nook.mobile.R;
import com.nook.mobile.data.BatchDownloader;
import com.nook.mobile.data.I18nManager;
import com.nook.mobile.data.SettingsRepository;
import com.nook.mobile.service.PlayerService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页（对应桌面版 main.js settings 区）：
 * 摆钟（FR-33）、游戏雨/无雷雨互斥（FR-30）、主题曲开关（FR-21）、周六 K.K.（FR-17）、
 * 禁止下载（FR-44）、批量下载（FR-42）、清除数据（FR-45）、更新日志（FR-60）。
 */
public final class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private SettingsRepository settings;
    private I18nManager i18n;
    private PlayerService service;

    private MaterialSwitch swGrandFather;
    private MaterialSwitch swGameRain;
    private MaterialSwitch swPeacefulRain;
    private MaterialSwitch swTuneEnabled;
    private MaterialSwitch swKkSaturday;
    private MaterialSwitch swNoDownload;
    private Button btnDownloadHourly;
    private Button btnDownloadKk;
    private ProgressBar hourlyProgress;
    private ProgressBar kkProgress;

    private boolean suppressSwitchEvents;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            service.setUiCallback(uiCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final PlayerService.UiCallback uiCallback = new PlayerService.UiCallback() {
        @Override
        public void onGameAutoChanged(String game) {
        }

        @Override
        public void onPlayingChanged(String friendlyName, String hour) {
        }

        @Override
        public void onErrorKey(String key, String detail) {
            showError(key, detail);
        }

        @Override
        public void onPauseChanged(boolean paused) {
        }

        @Override
        public void onDownloadProgress(boolean kk, int done, int total) {
            ProgressBar bar = kk ? kkProgress : hourlyProgress;
            bar.setProgress((int) (done * 100L / total));
        }

        @Override
        public void onOfflineCountChanged() {
            renderCounts();
        }

        @Override
        public void onDownloadFailed() {
            // 失败熔断：进度直接置 100（§6.5）
            hourlyProgress.setProgress(100);
            kkProgress.setProgress(100);
            showError("failedToDownload", null);
        }

        @Override
        public void onDownloadComplete() {
            btnDownloadHourly.setEnabled(true);
            btnDownloadKk.setEnabled(true);
            btnDownloadHourly.setText(i18n.tr("download all hourly music"));
            btnDownloadKk.setText(i18n.tr("download all k.k. music"));
            renderCounts();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        InsetsUtil.applySystemBars(findViewById(R.id.root));
        settings = new SettingsRepository(this);
        try {
            i18n = new I18nManager(this);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load i18n assets", e);
        }
        i18n.setLanguage(settings.getLang());

        swGrandFather = findViewById(R.id.swGrandFather);
        swGameRain = findViewById(R.id.swGameRain);
        swPeacefulRain = findViewById(R.id.swPeacefulRain);
        swTuneEnabled = findViewById(R.id.swTuneEnabled);
        swKkSaturday = findViewById(R.id.swKkSaturday);
        swNoDownload = findViewById(R.id.swNoDownload);
        btnDownloadHourly = findViewById(R.id.btnDownloadHourly);
        btnDownloadKk = findViewById(R.id.btnDownloadKk);
        hourlyProgress = findViewById(R.id.hourlyProgress);
        kkProgress = findViewById(R.id.kkProgress);

        setupSwitches();
        setupButtons();
        renderTexts();
        renderCounts();

        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.setUiCallback(null);
        }
        unbindService(connection);
        super.onDestroy();
    }

    // ---- 开关 ----

    private void setupSwitches() {
        suppressSwitchEvents = true;
        swGrandFather.setChecked(settings.isGrandFather());
        swGameRain.setChecked(settings.isGameRain());
        swPeacefulRain.setChecked(settings.isPeacefulRain());
        swTuneEnabled.setChecked(settings.isTuneEnabled());
        swKkSaturday.setChecked(settings.isKkSaturday());
        swNoDownload.setChecked(settings.isPreferNoDownload());
        suppressSwitchEvents = false;

        swGrandFather.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchEvents) {
                return;
            }
            if (service != null) {
                service.changeGrandFather(checked);
            } else {
                settings.setGrandFather(checked);
            }
        });

        // 游戏雨 / 无雷雨互斥（FR-30）
        swGameRain.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchEvents) {
                return;
            }
            if (checked) {
                suppressSwitchEvents = true;
                swPeacefulRain.setChecked(false);
                suppressSwitchEvents = false;
            }
            if (service != null) {
                service.changeGameRain(checked);
            } else {
                settings.setGameRain(checked);
                settings.setPeacefulRain(false);
            }
        });

        swPeacefulRain.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchEvents) {
                return;
            }
            if (checked) {
                suppressSwitchEvents = true;
                swGameRain.setChecked(false);
                suppressSwitchEvents = false;
            }
            if (service != null) {
                service.changePeacefulRain(checked);
            } else {
                settings.setPeacefulRain(checked);
                settings.setGameRain(false);
            }
        });

        swTuneEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchEvents) {
                return;
            }
            if (service != null) {
                service.changeTuneEnabled(checked);
            } else {
                settings.setTuneEnabled(checked);
            }
        });

        swKkSaturday.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchEvents) {
                return;
            }
            if (service != null) {
                service.changeKkSaturday(checked);
            } else {
                settings.setKkSaturday(checked);
            }
        });

        swNoDownload.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchEvents) {
                return;
            }
            if (service != null) {
                service.changePreferNoDownload(checked);
            } else {
                settings.setPreferNoDownload(checked);
            }
        });
    }

    // ---- 按钮 ----

    private void setupButtons() {
        btnDownloadHourly.setOnClickListener(v -> {
            if (service == null) {
                return;
            }
            btnDownloadHourly.setEnabled(false);
            btnDownloadHourly.setText(i18n.tr("downloading..."));
            hourlyProgress.setProgress(0);
            service.startDownloadHourly();
        });

        btnDownloadKk.setOnClickListener(v -> {
            if (service == null) {
                return;
            }
            btnDownloadKk.setEnabled(false);
            btnDownloadKk.setText(i18n.tr("downloading..."));
            kkProgress.setProgress(0);
            service.startDownloadKk();
        });

        findViewById(R.id.btnChangelog).setOnClickListener(v -> showChangelog());

        // 清除数据需二次确认（FR-45）
        findViewById(R.id.btnClearData).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(i18n.tr("Are you sure?"))
                .setMessage(i18n.tr("Click \"OK\" to proceed and delete all local music files and user settings."))
                .setPositiveButton(android.R.string.ok, (d, w) -> clearAllData())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    /** 清空设置 + 删除 sound 目录 + 返回主界面（FR-45）。 */
    private void clearAllData() {
        stopService(new Intent(this, PlayerService.class));
        // 同步 commit 清空设置与下载元数据（同样落盘，避免进程退出时丢失）
        settings.clearAll();
        // 真正删除全部离线音频文件；重试一次覆盖 Windows/占用型延迟
        boolean deleted = deleteRecursively(new File(getFilesDir(), "sound")) == null;
        Log.i(TAG, "clear data: prefs cleared=" + true + ", sound deleted=" + deleted);
        // 正常流转到主界面；不再强制杀进程，确保上面的写盘与删除全部完成
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
    }

    /** 递归删除目录；返回 null 表示已全部删除，否则返回残留的首个未删项。 */
    private static File deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    File leftover = deleteRecursively(child);
                    if (leftover != null) {
                        return leftover;
                    }
                }
            }
        }
        if (!file.exists()) {
            return null;
        }
        return file.delete() ? null : file;
    }

    /** 更新日志（FR-60）：渲染 assets/release-log.json（版本号 + 条目列表）。 */
    private void showChangelog() {
        StringBuilder sb = new StringBuilder();
        // 中文语言（cn）加载翻译后的中文更新日志，其余语言使用英文原版
        String assetName = "cn".equals(i18n.getLanguage()) ? "release-log_cn.json" : "release-log.json";
        try (InputStream is = getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            Type type = new TypeToken<Map<String, List<String>>>() {
            }.getType();
            Map<String, List<String>> log = new Gson().fromJson(reader, type);
            if (log != null) {
                for (Map.Entry<String, List<String>> entry : log.entrySet()) {
                    sb.append("v").append(entry.getKey()).append("\n");
                    for (String item : entry.getValue()) {
                        sb.append("  • ").append(item).append("\n");
                    }
                    sb.append("\n");
                }
            }
        } catch (IOException e) {
            sb.append("failed to load release log");
        }
        new AlertDialog.Builder(this)
                .setTitle(i18n.tr("changelog"))
                .setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---- 渲染 ----

    private void renderTexts() {
        ((TextView) findViewById(R.id.lblTitle)).setText(i18n.tr("player settings"));
        ((TextView) findViewById(R.id.lblOffline)).setText(i18n.tr("offline"));
        swGrandFather.setText(i18n.tr("Grandfather clock mode"));
        swGameRain.setText(i18n.tr("Use game rain sound"));
        swPeacefulRain.setText(i18n.tr("Use no-thunder rain sound"));
        swTuneEnabled.setText(i18n.tr("Enable town tune"));
        swKkSaturday.setText(i18n.tr("Play K.K. music on Saturday nights"));
        swNoDownload.setText(i18n.tr("Don't download music"));
        btnDownloadHourly.setText(i18n.tr("download all hourly music"));
        btnDownloadKk.setText(i18n.tr("download all k.k. music"));
        ((Button) findViewById(R.id.btnChangelog)).setText(i18n.tr("changelog"));
        ((Button) findViewById(R.id.btnClearData)).setText(i18n.tr("clear local files and settings"));
    }

    /** 离线计数文案（trf 占位符，分母统一 316 / 193，FR-42 决策）。 */
    private void renderCounts() {
        Map<String, String> hourly = new HashMap<>();
        hourly.put("offlineFiles", String.valueOf(settings.countOfflineHourly()));
        hourly.put("totalFiles", String.valueOf(BatchDownloader.TOTAL_HOURLY));
        ((TextView) findViewById(R.id.txtHourlyCount)).setText(
                i18n.trf("{{offlineFiles}}/{{totalFiles}} offline hourly music files downloaded", hourly));

        Map<String, String> kk = new HashMap<>();
        kk.put("offlineKKFiles", String.valueOf(settings.countOfflineKk()));
        kk.put("totalKKFiles", String.valueOf(BatchDownloader.TOTAL_KK));
        ((TextView) findViewById(R.id.txtKkCount)).setText(
                i18n.trf("{{offlineKKFiles}}/{{totalKKFiles}} offline k.k. music files downloaded", kk));
    }

    private void showError(String key, String detail) {
        String text = i18n.tr(key);
        if (detail != null && !detail.isEmpty()) {
            // 附加技术原因（弱网/超时/HTTP 状态等），便于实机排障
            text = text + " (" + detail + ")";
        }
        Snackbar.make(findViewById(android.R.id.content), text, 4000).show();
    }
}
