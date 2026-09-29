package com.nook.mobile.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nook.mobile.R;
import com.nook.mobile.data.I18nManager;
import com.nook.mobile.data.KkRepository;
import com.nook.mobile.data.SettingsRepository;
import com.nook.mobile.service.PlayerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * K.K. 歌单编辑（FR-16）：193 首复选，全选/全不选/仅广播/仅现场/保存；
 * 保存即时生效——K.K. 模式下通知 Service 按新列表重新随机。
 */
public final class KkListActivity extends AppCompatActivity {

    private SettingsRepository settings;
    private I18nManager i18n;
    private KkRepository kkRepo;
    private PlayerService service;

    private final List<String> songs = new ArrayList<>();
    private final Set<String> checked = new HashSet<>();
    private SongAdapter adapter;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kk_list);

        InsetsUtil.applySystemBars(findViewById(R.id.root));
        settings = new SettingsRepository(this);
        i18n = I18nManager.get(this);
        i18n.setLanguage(settings.getLang());
        try {
            kkRepo = new KkRepository(this);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load kk.json", e);
        }

        songs.addAll(kkRepo.allSongs());
        checked.addAll(settings.getKkEnabled(kkRepo.allSongs()));

        RecyclerView recycler = findViewById(R.id.kkRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SongAdapter();
        recycler.setAdapter(adapter);

        setupButtons();
        renderTexts();

        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        unbindService(connection);
        super.onDestroy();
    }

    private void setupButtons() {
        findViewById(R.id.btnCheckAll).setOnClickListener(v -> {
            checked.clear();
            checked.addAll(songs);
            adapter.notifyDataSetChanged();
        });
        findViewById(R.id.btnUncheckAll).setOnClickListener(v -> {
            checked.clear();
            adapter.notifyDataSetChanged();
        });
        // 仅广播 / 仅现场（FR-16 快捷筛选）
        findViewById(R.id.btnRadioOnly).setOnClickListener(v -> {
            checked.clear();
            checked.addAll(kkRepo.radioSongs());
            adapter.notifyDataSetChanged();
        });
        findViewById(R.id.btnLiveOnly).setOnClickListener(v -> {
            checked.clear();
            checked.addAll(kkRepo.liveSongs());
            adapter.notifyDataSetChanged();
        });
        findViewById(R.id.btnSaveKk).setOnClickListener(v -> save());
    }

    /** 保存：按曲库原顺序持久化启用列表，K.K. 模式下即时重选（FR-16）。 */
    private void save() {
        List<String> enabled = new ArrayList<>();
        for (String song : songs) {
            if (checked.contains(song)) {
                enabled.add(song);
            }
        }
        settings.setKkEnabled(enabled);
        if (service != null) {
            service.kkListSaved();
        }
        Button btnSave = findViewById(R.id.btnSaveKk);
        btnSave.setText(i18n.tr("saved!"));
        btnSave.postDelayed(() -> {
            if (!isFinishing()) {
                btnSave.setText(i18n.tr("save"));
            }
        }, 1000);
    }

    private void renderTexts() {
        ((TextView) findViewById(R.id.lblTitle)).setText(i18n.tr("k.k. playlist"));
        ((Button) findViewById(R.id.btnCheckAll)).setText(i18n.tr("check all"));
        ((Button) findViewById(R.id.btnUncheckAll)).setText(i18n.tr("uncheck all"));
        ((Button) findViewById(R.id.btnRadioOnly)).setText(i18n.tr("radio only"));
        ((Button) findViewById(R.id.btnLiveOnly)).setText(i18n.tr("live only"));
        ((Button) findViewById(R.id.btnSaveKk)).setText(i18n.tr("save"));
    }

    // ---- 列表适配器 ----

    private final class SongAdapter extends RecyclerView.Adapter<SongHolder> {
        @NonNull
        @Override
        public SongHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new SongHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_kk_song, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull SongHolder holder, int position) {
            holder.bind(songs.get(position));
        }

        @Override
        public int getItemCount() {
            return songs.size();
        }
    }

    private final class SongHolder extends RecyclerView.ViewHolder {
        private final CheckBox check;

        SongHolder(@NonNull android.view.View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.songCheck);
        }

        void bind(final String song) {
            check.setOnCheckedChangeListener(null);
            check.setText(song);
            check.setChecked(checked.contains(song));
            check.setOnCheckedChangeListener((v, isChecked) -> {
                if (isChecked) {
                    checked.add(song);
                } else {
                    checked.remove(song);
                }
            });
        }
    }
}
