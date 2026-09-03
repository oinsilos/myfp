package com.fongmi.android.tv.music.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.RepeatMode;
import com.github.catvod.net.OkHttp;

import java.util.List;

/**
 * 音乐播放内核：复用捆绑 Media3（fork 版）的 ExoPlayer，脱离 react-native-trackPlayer。
 * <p>
 * 职责：
 * - 队列驱动（MusicQueue 纯逻辑）→ Media3 单条 MediaItem 播放
 * - 失败换源：备选 URL（更多音质）→ 上层插件重新拉 URL → 仍失败自动跳过
 * - 播完自动驾驶：依 RepeatMode 决定 next / 循环 / 单曲
 * - 进度回调（500ms 节流）供 UI 刷新
 */
public final class MusicPlayer {

    private static final String TAG = "MusicPlayer";

    public interface Callback {

        /** 当前曲目变化（切换/新队列）。 */
        void onMusicChanged(MusicMedia media);

        /** 播放/暂停状态变化。 */
        void onPlayingChanged(boolean playing);

        /** 播放器状态机变化（STATE_IDLE/BUFFERING/READY/ENDED），诊断用。 */
        void onStateChanged(int state);

        /** 播放进度（位置/时长，毫秒）。 */
        void onProgress(long positionMs, long durationMs);

        /**
         * 请求上层（插件）重新拉取当前曲目 URL。
         * 上层取到后必须调用 {@link #updateUrl(MusicMedia)}，否则内核在重试预算耗尽后自动跳过。
         */
        void onNeedReloadUrl(MusicMedia media);

        /** 当前曲目尝试完所有来源仍失败（即将自动跳过）。 */
        void onSourceFailed(MusicMedia media, String message);

        /** 播完当前队列（LIST 模式到达末尾后不再回绕，或用户手动触发下一首而非末尾）。 */
        void onQueueEnded();
    }

    /** 单曲重试预算：超过则放弃当前曲，自动下一首。 */
    private static final int MAX_RETRY = 2;

    private final MusicQueue queue = new MusicQueue();
    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ProgressTicker ticker = new ProgressTicker();

    private ExoPlayer player;
    private MusicMedia current;
    private int failCount;

    public MusicPlayer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    // ------------------------------------------------------------ 播放控制

    /** 整表装载队列并立即播放（startIndex 为记住位置恢复点）。 */
    public void setQueue(List<MusicMedia> items, int startIndex) {
        ensurePlayer();
        queue.setQueue(items, startIndex);
        MusicMedia first = queue.current();
        if (first == null) {
            stopInternal();
            return;
        }
        playIndex(queue.position(), 0);
    }

    public void play() {
        if (player == null || current == null) return;
        player.setPlayWhenReady(true);
    }

    public void pause() {
        if (player != null) player.setPlayWhenReady(false);
    }

    public void toggle() {
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    public void seekTo(long positionMs) {
        if (player != null) player.seekTo(Math.max(0, positionMs));
    }

    public void next() {
        MusicMedia next = queue.next();
        if (next == null) {
            callback.onQueueEnded();
            return;
        }
        startPlaying(next, 0);
    }

    public void prev() {
        MusicMedia prev = queue.prev();
        if (prev == null) {
            callback.onQueueEnded();
            return;
        }
        startPlaying(prev, 0);
    }

    /** 跳转到队列指定位置播放。 */
    public void playAt(int index) {
        MusicMedia target = queue.play(index);
        if (target != null) startPlaying(target, 0);
    }

    /** 插队到当前之后并立即播放（队列顺序同步前移）。 */
    public void playNext(MusicMedia media) {
        queue.addNext(media);
        next();
    }

    /** 追加到队列末尾。 */
    public int append(MusicMedia media) {
        return queue.append(media);
    }

    /** 移除队列项；若移除的是当前曲则顺延播放。 */
    public void removeAt(int index) {
        boolean isCurrent = index == queue.position();
        MusicMedia removed = queue.remove(index);
        if (removed == null) return;
        if (!isCurrent) return;
        MusicMedia fallback = queue.current();
        if (fallback == null) {
            stopInternal();
        } else {
            startPlaying(fallback, 0);
        }
    }

    public RepeatMode mode() {
        return queue.mode();
    }

    public void setRepeatMode(RepeatMode mode) {
        queue.setMode(mode);
        if (player != null) player.setRepeatMode(mode == RepeatMode.ONE ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public MusicMedia current() {
        return current;
    }

    public MusicQueue queue() {
        return queue;
    }

    public long position() {
        return player == null ? 0 : player.getCurrentPosition();
    }

    public boolean isPlaying() {
        return player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED && player.getPlaybackState() != Player.STATE_IDLE;
    }

    /**
     * 上层（插件）换源成功后回填 URL 并重试。
     * 也会用于回调 {@link Callback#onNeedReloadUrl} 之后的补 URL。
     * 注意：不重置失败预算——换源本身也是一次尝试，避免坏 URL 时无限换源死循环。
     */
    public void updateUrl(MusicMedia media, String url) {
        if (media == null || current == null || media != current) return;
        if (url == null || url.isEmpty()) return;
        current.url = url;
        rebindSource();
    }

    /** 释放资源。 */
    public void release() {
        stopInternal();
        if (player != null) {
            player.release();
            player = null;
        }
        handler.removeCallbacks(ticker);
        queue.clear();
        current = null;
    }

    /** 播放器状态机映射（诊断/日志用）。 */
    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    // ------------------------------------------------------------ 内部驱动

    private void ensurePlayer() {
        if (player != null) return;
        // 网易云等源返回的 mp3 直链常为 http，而请求是 https；
        // OkHttp 默认跟随跨协议重定向，直接复用主播放器同款数据源，避免 Media3 默认拒绝 https→http
        HttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(OkHttp.player());
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(httpFactory);
        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(sourceFactory)
                .build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG, "state: " + stateName(state) + " media=" + (current == null ? "null" : current.title));
                callback.onStateChanged(state);
                if (state == Player.STATE_ENDED) onEnded();
                else if (state == Player.STATE_READY) queueDuration();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                callback.onPlayingChanged(isPlaying);
                updateTicker(isPlaying);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "playback error: media=" + (current == null ? "null" : current.title)
                        + " url=" + (current == null ? "null" : current.url)
                        + " code=" + error.errorCode + " msg=" + error.getMessage(), error);
                handleErrors(error.getMessage());
            }
        });
        syncRepeatMode();
    }

    private void syncRepeatMode() {
        if (player != null) player.setRepeatMode(queue.mode() == RepeatMode.ONE ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    private void playIndex(int index, int retry) {
        MusicMedia media = queue.get(index);
        if (media == null) return;
        startPlaying(media, retry);
    }

    /** 开始播放一首曲目（重置失败预算与进度）。 */
    private void startPlaying(MusicMedia media, int retry) {
        ensurePlayer();
        failCount = retry;
        current = media;
        player.setMediaItem(buildItem(media));
        player.prepare();
        player.setPlayWhenReady(true);
        callback.onMusicChanged(media);
    }

    /** 当前曲目播放结束：依模式收尾。 */
    private void onEnded() {
        if (current == null) return;
        RepeatMode mode = queue.mode();
        if (mode == RepeatMode.ONE) {
            player.setPlayWhenReady(true);
            return;
        }
        player.stop();
        MusicMedia next = queue.next();
        if (next == null) {
            callback.onQueueEnded();
            return;
        }
        startPlaying(next, 0);
    }

    private void queueDuration() {
        // 时长由 ProgressTicker 统一上报；此处仅为 READY 状态钩子占位
    }

    /** 失败换源：备选 URL → 上层重拉 → 自动跳过。 */
    private void handleErrors(String message) {
        if (current == null) return;
        failCount++;
        if (failCount >= MAX_RETRY) {
            callback.onSourceFailed(current, message == null ? "Unknown error" : message);
            next();
            return;
        }
        // 1) 备选 URL
        String alt = current.popAlternative();
        if (alt != null && !alt.isEmpty()) {
            current.url = alt;
            rebindSource();
            return;
        }
        // 2) 上层插件重拉 URL
        callback.onNeedReloadUrl(current);
    }

    /** 以当前曲目现有 url 重新绑定数据源并播放。 */
    private void rebindSource() {
        if (current == null || current.url == null || current.url.isEmpty()) return;
        ensurePlayer();
        player.setMediaItem(buildItem(current));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private MediaItem buildItem(MusicMedia media) {
        String url = media.url;
        if (url == null || url.isEmpty()) {
            url = media.moreUrls.isEmpty() ? "" : media.moreUrls.remove(0);
            media.url = url;
        }
        return new MediaItem.Builder().setMediaId(media.id).setUri(url).build();
    }

    private void stopInternal() {
        current = null;
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.stop();
        }
    }

    private void updateTicker(boolean running) {
        handler.removeCallbacks(ticker);
        if (!running) return;
        ticker.run();
    }

    /** 进度上报（仅播放中每 500ms 一次）。 */
    private final class ProgressTicker implements Runnable {
        @Override
        public void run() {
            if (player == null || current == null) return;
            if (player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING) {
                long raw = player.getDuration();
                long duration = (raw > 0) ? raw : (current.durationMs > 0 ? current.durationMs : 0);
                callback.onProgress(player.getCurrentPosition(), duration);
            }
            handler.postDelayed(this, 500);
        }
    }
}