package com.fongmi.android.tv.music.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.databinding.ItemMusicBinding;
import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.RepeatMode;
import com.fongmi.android.tv.music.plugin.MusicRepository;
import com.fongmi.android.tv.music.service.MusicPlaybackService;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小原生音乐界面：搜索 → 结果列表 → 点击播放。
 * 播放交给 {link MusicPlaybackService}（后台前台服务），本页订阅其回调刷新控制条。
 */
public final class MusicActivity extends AppCompatActivity implements MusicPlaybackService.Listener {

    private ActivityMusicBinding binding;
    private final List<MusicMedia> results = new ArrayList<>();
    private Adapter adapter;
    private MusicPlaybackService service;
    private boolean bound;
    private boolean dragging;
    private boolean playing;

    /** 从主界面（HomeActivity）进入音乐模块。 */
    public static void start(Context context) {
        context.startActivity(new Intent(context, MusicActivity.class));
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            service = ((MusicPlaybackService.MusicBinder) iBinder).service().bindListener(MusicActivity.this);
            bound = true;
            if (service.current() != null) updateNow(service.current(), service.mode());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMusicBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getSupportActionBarHere();
        MusicRepository.get().init(this);
        setupList();
        setupSearch();
        setupControls();
        startAndBindService();
        search("周杰伦");
    }

    private void getSupportActionBarHere() {
        // 无 ActionBar：标题直接用输入框展示，保持极简
    }

    private void startAndBindService() {
        startService(new Intent(this, MusicPlaybackService.class));
        bindService(new Intent(this, MusicPlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }

    // ------------------------------------------------------------ 初始化

    private void setupList() {
        adapter = new Adapter(this);
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);
    }

    private void setupSearch() {
        binding.btnSearch.setOnClickListener(v -> search(binding.input.getText().toString()));
        binding.input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                search(binding.input.getText().toString());
                return true;
            }
            return false;
        });
    }

    private void setupControls() {
        binding.btnPrev.setOnClickListener(v -> ifService(s -> s.prev()));
        binding.btnNext.setOnClickListener(v -> ifService(s -> s.next()));
        binding.btnPlay.setOnClickListener(v -> ifService(MusicPlaybackService::toggle));
        binding.tvMode.setOnClickListener(v -> {
            ifService(s -> {
                RepeatMode mode = s.mode();
                RepeatMode next = mode == RepeatMode.LIST ? RepeatMode.ONE : mode == RepeatMode.ONE ? RepeatMode.SHUFFLE : RepeatMode.LIST;
                s.setRepeatMode(next);
                updateMode(next);
            });
        });
        binding.seek.setMax(1000);
        binding.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) dragging = true;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ifService(s -> s.seekTo(seekBar.getProgress() * 10L));
                dragging = false;
            }
        });
    }

    private interface Action {
        void run(MusicPlaybackService service);
    }

    private void ifService(Action action) {
        if (service != null) action.run(service);
    }

    // ------------------------------------------------------------ 搜索

    private void search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        binding.loading.setVisibility(View.VISIBLE);
        MusicRepository.get().search(keyword.trim()).whenComplete((list, error) -> runOnUiThread(() -> {
            binding.loading.setVisibility(View.GONE);
            results.clear();
            if (error == null && list != null) results.addAll(list);
            adapter.notifyDataSetChanged();
        }));
    }

    // ------------------------------------------------------------ 播放回调

    @Override
    public void onMusicChanged(MusicMedia media) {
        runOnUiThread(() -> updateNow(media, service == null ? RepeatMode.LIST : service.mode()));
    }

    @Override
    public void onPlayingChanged(boolean playing) {
        this.playing = playing;
        runOnUiThread(() -> binding.btnPlay.setImageResource(playing ? R.drawable.ic_notify_pause : R.drawable.ic_notify_play));
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        runOnUiThread(() -> {
            if (!dragging) {
                int progress = durationMs <= 0 ? 0 : (int) Math.min(1000, positionMs * 1000 / durationMs);
                binding.seek.setProgress(progress);
            }
        });
    }

    @Override
    public void onSourceFailed(MusicMedia media, String message) {
        // 换源失败：内核将自动跳过；此处可刷新列表提示
    }

    private void updateNow(MusicMedia media, RepeatMode mode) {
        binding.tvNow.setText(media.title + (media.artist.isEmpty() ? "" : " - " + media.artist));
        updateMode(mode);
    }

    private void updateMode(RepeatMode mode) {
        binding.tvMode.setText(mode == RepeatMode.ONE ? "单曲循环" : mode == RepeatMode.SHUFFLE ? "随机播放" : "列表循环");
    }

    // ------------------------------------------------------------ 列表适配器

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final MusicActivity activity;

        private Adapter(MusicActivity activity) {
            this.activity = activity;
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        @Override
        public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            ItemMusicBinding itemBinding = ItemMusicBinding.inflate(getLayoutInflater(), (android.view.ViewGroup) parent, false);
            return new Holder(itemBinding);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            MusicMedia media = results.get(position);
            holder.binding.tvTitle.setText(media.title);
            holder.binding.tvArtist.setText(media.artist.isEmpty() ? media.album : (media.artist + (media.album.isEmpty() ? "" : " · " + media.album)));
            holder.binding.tvDuration.setText(fmt(media.durationMs));
            holder.binding.getRoot().setOnClickListener(v -> {
                if (service == null) return;
                service.play(new ArrayList<>(results), position);
            });
        }

        private final class Holder extends RecyclerView.ViewHolder {
            private final ItemMusicBinding binding;

            private Holder(ItemMusicBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "--:--";
        long total = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            if (service != null) service.unbindListener();
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}