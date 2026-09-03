package com.fongmi.android.tv.music.ui;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.databinding.ItemMusicBinding;
import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.RepeatMode;
import com.fongmi.android.tv.music.plugin.MusicRepository;
import com.fongmi.android.tv.music.service.MusicPlaybackService;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private String lastError;
    private List<LyricLine> lyricLines;
    private String lastLyricError;
    private Dialog lyricDialog;
    private ScrollView lyricScroll;
    private List<TextView> lyricTexts;
    private int currentLyricIndex = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long SEARCH_TIMEOUT_MS = 20_000L;
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

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
        // 状态区显示音源标识，便于确认装到的插件版本（对比 0.2.0）
        binding.tvState.setText("music " + MusicRepository.get().platform() + " v" + MusicRepository.get().version());
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
        // 歌词行点击：有词进全屏歌词；无词/失败也弹 Dialog 显示原因（Toast 在部分 ROM 上不可靠）
        binding.tvLyric.setOnClickListener(v -> {
            if (lyricLines != null && !lyricLines.isEmpty()) {
                showLyricDialog();
            } else if (lastLyricError != null) {
                showLyricMessage("歌词获取失败\n\n" + lastLyricError);
            } else {
                showLyricMessage("该歌曲暂无歌词\n\n（未收录歌词或 VIP 歌曲）");
            }
        });
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
        handler.removeCallbacksAndMessages(null);
        // 兜底：无论底层如何，20s 内必须结束搜索态，绝不无限转圈
        handler.postDelayed(() -> {
            if (binding.loading.getVisibility() != View.VISIBLE) return;
            binding.loading.setVisibility(View.GONE);
            Notify.show("搜索超时，请检查网络");
        }, SEARCH_TIMEOUT_MS);
        Log.d("MusicActivity", "search start: " + keyword);
        MusicRepository.get().search(keyword.trim()).whenComplete((list, error) -> runOnUiThread(() -> {
            handler.removeCallbacksAndMessages(null);
            binding.loading.setVisibility(View.GONE);
            results.clear();
            if (error != null) {
                Log.w("MusicActivity", "search error", error);
                Notify.show("搜索失败：" + friendly(error));
            } else if (list != null) {
                Log.d("MusicActivity", "search done: " + list.size() + " items");
                results.addAll(list);
                if (list.isEmpty()) Notify.show("未找到相关歌曲");
            }
            adapter.notifyDataSetChanged();
        }));
    }

    private static String friendly(Throwable t) {
        Throwable cause = t.getCause() == null ? t : t.getCause();
        String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.toString();
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    // ------------------------------------------------------------ 播放回调

    @Override
    public void onMusicChanged(MusicMedia media) {
        runOnUiThread(() -> {
            closeLyricDialog();
            updateNow(media, service == null ? RepeatMode.LIST : service.mode());
            loadCover(binding.ivNow, media == null ? null : media.cover);
            loadLyric(media);
        });
    }

    @Override
    public void onPlayingChanged(boolean playing) {
        this.playing = playing;
        runOnUiThread(() -> binding.btnPlay.setImageResource(playing ? R.drawable.ic_notify_pause : R.drawable.ic_notify_play));
    }

    @Override
    public void onStateChanged(int state) {
        // 错误一闪而过问题：onPlayerError 后状态切 IDLE，会覆盖错误文本。
        // 有未读错误时，IDLE 状态保留错误文本，等下一首歌开始播放才清空。
        runOnUiThread(() -> {
            if (state == Player.STATE_IDLE && lastError != null) {
                binding.tvState.setText(lastError);
            } else if (state == Player.STATE_READY) {
                lastError = null;
                binding.tvState.setText("ready");
            } else {
                binding.tvState.setText(stateName(state));
            }
        });
    }

    @Override
    public void onError(MusicMedia media, PlaybackException error) {
        String url = media == null || media.url == null ? "-" : media.url;
        // 表层 message 常是 "Source error"，真正原因在 cause 链里
        String why = describe(error);
        if (why.isEmpty()) why = error.getMessage() == null ? "未知" : error.getMessage();
        if (why.length() > 120) why = why.substring(0, 120) + "…";
        lastError = "err " + error.errorCode + ": " + why + "\n" + url;
        runOnUiThread(() -> binding.tvState.setText(lastError));
    }

    /** 取 cause 链顶层的具体异常原因（跳过笼统的 "Source error"）。 */
    private String describe(Throwable t) {
        for (int i = 0; t != null && i < 4; i++, t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isEmpty() && !"Source error".equals(m)) {
                return t.getClass().getSimpleName() + ": " + m;
            }
        }
        return "";
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "idle";
            case Player.STATE_BUFFERING: return "buffering";
            case Player.STATE_READY: return "ready";
            case Player.STATE_ENDED: return "ended";
            default: return "unknown(" + state + ")";
        }
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        runOnUiThread(() -> {
            if (!dragging) {
                int progress = durationMs <= 0 ? 0 : (int) Math.min(1000, positionMs * 1000 / durationMs);
                binding.seek.setProgress(progress);
            }
            updateLyricUi(positionMs);
        });
    }

    @Override
    public void onSourceFailed(MusicMedia media, String message) {
        // 换源失败：内核将自动跳过；把具体原因浮出，便于定位网络/协议问题
        String why = message == null || message.isEmpty() ? "未知错误" : message;
        runOnUiThread(() -> Notify.show("播放失败：" + media.title + "（" + why + "）"));
    }

    private void updateNow(MusicMedia media, RepeatMode mode) {
        binding.tvNow.setText(media.title + (media.artist.isEmpty() ? "" : " - " + media.artist));
        updateMode(mode);
    }

    private void updateMode(RepeatMode mode) {
        binding.tvMode.setText(mode == RepeatMode.ONE ? "单曲循环" : mode == RepeatMode.SHUFFLE ? "随机播放" : "列表循环");
    }

    // ------------------------------------------------------------ 封面

    private static final ColorDrawable PLACEHOLDER = new ColorDrawable(0xFF323232);

    private void loadCover(ImageView iv, String url) {
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(PLACEHOLDER);
            return;
        }
        Glide.with(this)
                .load(url)
                .placeholder(PLACEHOLDER)
                .error(PLACEHOLDER)
                .into(iv);
    }

    // ------------------------------------------------------------ 歌词

    private void loadLyric(MusicMedia media) {
        currentLyricIndex = -1;
        lyricLines = null;
        lastLyricError = null;
        binding.tvLyric.setText(media == null ? "暂无歌词" : "加载歌词…");
        if (media == null) return;
        MusicRepository.get().getLyric(media).whenComplete((lrc, error) -> runOnUiThread(() -> {
            if (error != null) {
                lastLyricError = friendly(error);
                Log.w("MusicActivity", "lyric error", error);
                // 失败原因直接上屏（截断），点击弹窗看完整
                String brief = lastLyricError.length() > 24 ? lastLyricError.substring(0, 24) + "…" : lastLyricError;
                binding.tvLyric.setText("歌词失败:" + brief);
                return;
            }
            List<LyricLine> lines = parseLrc(lrc);
            if (lines.isEmpty()) {
                binding.tvLyric.setText("该歌暂无歌词");
                return;
            }
            lyricLines = lines;
            binding.tvLyric.setText("点击显示歌词");
        }));
    }

    /** 解析标准 LRC：支持多个时间戳共享一行（取最后一个），时间单位 mm:ss(.xx)。 */
    private static List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        if (lrc == null) return lines;
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = LRC_TIME.matcher(line);
            long time = -1;
            int lastEnd = 0;
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) * 60_000L + Long.parseLong(m.group(2)) * 1000L;
                String frac = m.group(3);
                if (frac != null) {
                    t += frac.length() == 1 ? Long.parseLong(frac) * 100L
                            : frac.length() == 2 ? Long.parseLong(frac) * 10L
                            : Long.parseLong(frac);
                }
                time = Math.max(time, t);
                lastEnd = m.end();
            }
            if (time < 0) continue;
            String text = lastEnd >= line.length() ? "" : line.substring(lastEnd).trim();
            lines.add(new LyricLine(time, text));
        }
        lines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return lines;
    }

    /** 二分定位 positionMs 落在哪一句（上一句仍未结束则为前一句）。 */
    private static int lyricIndex(List<LyricLine> lines, long positionMs) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void updateLyricUi(long positionMs) {
        if (lyricLines == null || lyricLines.isEmpty()) return;
        int idx = lyricIndex(lyricLines, positionMs);
        if (idx == currentLyricIndex) return;
        currentLyricIndex = idx;
        binding.tvLyric.setText(idx < 0 ? "点击显示歌词" : lyricLines.get(idx).text);
        highlightLyricDialog(idx);
    }

    private void closeLyricDialog() {
        if (lyricDialog != null) lyricDialog.dismiss(); // dismiss 监听里置空字段
    }

    /** 全屏歌词 Dialog：按行渲染，当前句高亮 + 自动滚动到可视区；行可点，点击跳转对应时间。 */
    private void showLyricDialog() {
        if (lyricLines == null || lyricLines.isEmpty()) {
            Notify.show("暂无歌词");
            return;
        }
        if (lyricDialog != null) return;

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        // 顶部留白，方便第一句居中
        lines.addView(spacer(), spacerLp(96));
        lyricTexts = new ArrayList<>();
        for (LyricLine l : lyricLines) {
            TextView tv = new TextView(this);
            tv.setText(l.text.isEmpty() ? "…" : l.text);
            tv.setTextSize(18f);
            tv.setTextColor(0xFF999999);
            tv.setGravity(Gravity.CENTER);
            tv.setFocusable(true);
            tv.setClickable(true);
            tv.setPadding(0, 16, 0, 16);
            tv.setOnClickListener(v -> ifService(s -> s.seekTo(l.timeMs)));
            tv.setTag(l.timeMs);
            lines.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            lyricTexts.add(tv);
        }
        lines.addView(spacer(), spacerLp(96));
        lyricScroll = new ScrollView(this);
        lyricScroll.setFillViewport(true);
        lyricScroll.addView(lines, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 顶部标题行：歌名 + 关闭提示
        TextView title = new TextView(this);
        title.setText(binding.tvNow.getText());
        title.setTextSize(14f);
        title.setTextColor(0xFFCCCCCC);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 20, 0, 8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(lyricScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        lyricDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = lyricDialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xE6000000));
        lyricDialog.setContentView(root);
        lyricDialog.setOnDismissListener(d -> {
            lyricDialog = null;
            lyricScroll = null;
            lyricTexts = null;
        });
        lyricDialog.setOnShowListener(d -> {
            if (lyricScroll != null) lyricScroll.post(() -> highlightLyricDialog(currentLyricIndex));
        });
        lyricDialog.show();
    }

    private View spacer() {
        return new View(this);
    }

    /** 无词/失败时的全屏提示：不依赖 Toast，保证用户一定能看到原因。点击任意处关闭。 */
    private void showLyricMessage(String message) {
        if (lyricDialog != null) return;
        TextView body = new TextView(this);
        body.setText(message == null ? "" : message);
        body.setTextSize(16f);
        body.setTextColor(0xFFDDDDDD);
        body.setGravity(Gravity.CENTER);
        body.setPadding(48, 0, 48, 0);

        TextView tip = new TextView(this);
        tip.setText("点击任意处关闭");
        tip.setTextSize(12f);
        tip.setTextColor(0xFF666666);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 24, 0, 48);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setClickable(true);
        root.setFocusable(true);
        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        lyricDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = lyricDialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xE6000000));
        lyricDialog.setContentView(root);
        root.setOnClickListener(v -> lyricDialog.dismiss());
        lyricDialog.setOnDismissListener(d -> lyricDialog = null);
        lyricDialog.setCanceledOnTouchOutside(true);
        lyricDialog.show();
    }

    private LinearLayout.LayoutParams spacerLp(int h) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
    }

    private void highlightLyricDialog(int idx) {
        if (lyricTexts == null || lyricTexts.isEmpty()) return;
        for (int i = 0; i < lyricTexts.size(); i++) {
            TextView tv = lyricTexts.get(i);
            boolean cur = i == idx;
            tv.setTextColor(cur ? 0xFFFFFFFF : 0xFF999999);
            tv.getPaint().setFakeBoldText(cur);
        }
        if (lyricScroll != null && idx >= 0 && idx < lyricTexts.size()) {
            TextView tv = lyricTexts.get(idx);
            int y = tv.getTop() + tv.getHeight() / 2 - lyricScroll.getHeight() / 2;
            lyricScroll.smoothScrollTo(0, Math.max(0, y));
        }
    }

    private static final class LyricLine {
        final long timeMs;
        final String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
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
            String title = media.vip ? media.title + "  [VIP]" : media.title;
            holder.binding.tvTitle.setText(title);
            holder.binding.tvTitle.setAlpha(media.vip ? 0.55f : 1f);
            holder.binding.tvArtist.setText(media.artist.isEmpty() ? media.album : (media.artist + (media.album.isEmpty() ? "" : " · " + media.album)));
            holder.binding.tvDuration.setText(fmt(media.durationMs));
            boolean hasCover = media.cover != null && !media.cover.isEmpty();
            holder.binding.ivCover.setVisibility(hasCover ? View.VISIBLE : View.GONE);
            if (hasCover) {
                Glide.with(activity)
                        .load(media.cover)
                        .centerCrop()
                        .placeholder(PLACEHOLDER)
                        .error(PLACEHOLDER)
                        .into(holder.binding.ivCover);
            }
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