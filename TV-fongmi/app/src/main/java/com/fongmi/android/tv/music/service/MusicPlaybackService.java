package com.fongmi.android.tv.music.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.media3.common.PlaybackException;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.core.LrcParser;
import com.fongmi.android.tv.music.core.MusicLibrary;
import com.fongmi.android.tv.music.core.MusicPlayer;
import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.RepeatMode;
import com.fongmi.android.tv.music.plugin.MusicRepository;

import java.util.List;

/**
 * 音乐后台播放服务：持有 {@link MusicPlayer}，播放时提升为前台服务并常驻通知栏，
 * 通知提供 上一首 / 播放暂停 / 下一首 控制（本地广播驱动）。
 */
public final class MusicPlaybackService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.fongmi.music.action.PLAY_PAUSE";
    public static final String ACTION_PREV = "com.fongmi.music.action.PREV";
    public static final String ACTION_NEXT = "com.fongmi.music.action.NEXT";
    public static final String ACTION_STOP = "com.fongmi.music.action.STOP";

    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFY_ID = 0x51c2;

    /** 回调给绑定方（Activity）刷新 UI。 */
    public interface Listener {
        void onMusicChanged(MusicMedia media);

        void onPlayingChanged(boolean playing);

        void onStateChanged(int state);

        void onError(MusicMedia media, PlaybackException error);

        void onProgress(long positionMs, long durationMs);

        void onSourceFailed(MusicMedia media, String message);

        /** 睡眠定时变化（untilMs 为触发时刻 epoch 毫秒；<0 表示已关闭）。 */
        void onSleepTimerChanged(long untilMs);

        /** 睡眠定时触发（服务已暂停播放）。 */
        void onSleepTriggered();

        /** 倍速变化（换绑/服务内部同步用）。 */
        void onSpeedChanged(float speed);
    }

    public final class MusicBinder extends Binder {
        public MusicPlaybackService service() {
            return MusicPlaybackService.this;
        }
    }

    private final MusicBinder binder = new MusicBinder();
    private MusicPlayer player;
    private Listener listener;
    /** 倍速（播放器懒创建前暂存，首次播放时由 play 同步）。 */
    private volatile float speed = 1.0f;
    /** 实体媒体键（遥控器/耳机/车机）路由入口：MediaSession + Manifest MediaButtonReceiver。 */
    private MediaSessionCompat mediaSession;

    /** 睡眠定时：到点暂停播放（不断服务，通知保持可再次进入）。 */
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private volatile long sleepUntilMs = -1;
    private final Runnable sleepTick = new Runnable() {
        @Override
        public void run() {
            if (player != null) player.pause();
            sleepUntilMs = -1;
            postNotification();
            if (listener != null) listener.onSleepTriggered();
        }
    };

    /** 通知栏歌词缓存：换歌时异步拉 LRC 解析，进度回调切句节流更新通知。 */
    private volatile List<LrcParser.Line> lyricLines = null;
    private volatile String lyricHint = "";
    private long lastLyricPosMs = -1;

    private final BroadcastReceiver actions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction() == null ? "" : intent.getAction();
            switch (action) {
                case ACTION_PLAY_PAUSE:
                    if (player != null) player.toggle();
                    break;
                case ACTION_PREV:
                    if (player != null) player.prev();
                    break;
                case ACTION_NEXT:
                    if (player != null) player.next();
                    break;
                case ACTION_STOP:
                    stopSelf();
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 从通知直接进入时可能没有 Activity 触发，这里先确保插件仓库就绪（幂等）
        MusicRepository.get().init(getApplicationContext());
        player = new MusicPlayer(this, new PlayerBridge());
        // 实体媒体键/遥控器：媒体会话接收系统 MediaButton 路由
        mediaSession = new MediaSessionCompat(this, "MusicPlayback");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (player != null) player.play();
            }

            @Override
            public void onPause() {
                if (player != null) player.pause();
            }

            @Override
            public void onSkipToNext() {
                if (player != null) player.next();
            }

            @Override
            public void onSkipToPrevious() {
                if (player != null) player.prev();
            }

            @Override
            public void onStop() {
                if (player != null) player.pause();
            }
        });
        mediaSession.setActive(true);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY_PAUSE);
        filter.addAction(ACTION_PREV);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_STOP);
        registerReceiver(actions, filter, RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 媒体按钮（MediaButtonReceiver 唤醒）转发到会话回调
        if (mediaSession != null) MediaButtonReceiver.handleIntent(mediaSession, intent);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        sleepHandler.removeCallbacks(sleepTick);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        unregisterReceiver(actions);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ 对外控制

    /** 装载队列并播放（列表首曲顺延播出的场景，index 为起始曲）。 */
    public void play(List<MusicMedia> items, int index) {
        if (player == null) return;
        player.setSpeed(speed);
        player.setQueue(items, index);
        enterForeground();
    }

    public void toggle() {
        if (player != null) player.toggle();
    }

    public void play() {
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void next() {
        if (player != null) player.next();
    }

    public void prev() {
        if (player != null) player.prev();
    }

    public void seekTo(long positionMs) {
        if (player != null) player.seekTo(positionMs);
    }

    public void setRepeatMode(RepeatMode mode) {
        if (player != null) player.setRepeatMode(mode);
    }

    public RepeatMode mode() {
        return player == null ? RepeatMode.LIST : player.mode();
    }

    /** 设置倍速（透传播放内核；播放器懒创建前先存值，首次播放时同步）。 */
    public void setSpeed(float speed) {
        this.speed = speed;
        if (player != null) player.setSpeed(speed);
        if (listener != null) listener.onSpeedChanged(speed());
    }

    /** 当前倍速。 */
    public float speed() {
        return player == null ? speed : player.speed();
    }

    /** 设置睡眠定时（毫秒）；0/负数取消。 */
    public void setSleepTimer(long ms) {
        sleepHandler.removeCallbacks(sleepTick);
        sleepUntilMs = ms > 0 ? System.currentTimeMillis() + ms : -1;
        if (ms > 0) sleepHandler.postDelayed(sleepTick, ms);
        if (listener != null) listener.onSleepTimerChanged(sleepUntilMs);
    }

    /** 睡眠定时触发时刻（epoch 毫秒），未设置为 -1。 */
    public long sleepUntilMillis() {
        return sleepUntilMs;
    }

    /** 插件换源失败后由上层重新拉取 URL 并回填（走内核 onNeedReloadUrl 契约）。 */
    public void reloadSource(MusicMedia media, String url) {
        if (player != null) player.updateUrl(media, url);
    }

    /** 当前播放内容（未播放为 null）。 */
    public MusicMedia current() {
        return player == null ? null : player.current();
    }

    /** 供 Activity 注册回调（绑定并用）。 */
    public MusicPlaybackService bindListener(Listener listener) {
        this.listener = listener;
        return this;
    }

    public void unbindListener() {
        this.listener = null;
    }

    // ------------------------------------------------------------ 前台与通知

    private void enterForeground() {
        Notification notification = buildNotification(player.isPlaying(), player.current());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
        getNotificationManager().notify(NOTIFY_ID, notification);
    }

    private Notification buildNotification(boolean playing, MusicMedia media) {
        ensureChannel();
        String title = media == null ? "未在播放" : media.title;
        String sub = media == null ? "" : (media.artist + (media.album != null && !media.album.isEmpty() ? " · " + media.album : ""));
        // 通知栏歌词：有当前句则显示歌词，否则歌手/专辑
        String text = (lyricHint != null && !lyricHint.isEmpty()) ? lyricHint : sub;
        Intent open = new Intent(this, com.fongmi.android.tv.music.ui.MusicActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(icon(R.drawable.ic_notify_prev, "上一首", ACTION_PREV))
                .addAction(icon(playing ? R.drawable.ic_notify_pause : R.drawable.ic_notify_play, playing ? "暂停" : "播放", ACTION_PLAY_PAUSE))
                .addAction(icon(R.drawable.ic_notify_next, "下一首", ACTION_NEXT));
        return builder.build();
    }

    private NotificationCompat.Action icon(int res, String label, String action) {
        return new NotificationCompat.Action.Builder(res, label, pending(action)).build();
    }

    private PendingIntent pending(String action) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureChannel() {
        NotificationManager manager = getNotificationManager();
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    // ------------------------------------------------------------ 通知栏歌词

    /** 换歌后异步拉 LRC 解析（失败/无词则通知显示歌手专辑）。 */
    private void loadLyricForNotification(MusicMedia media) {
        lyricLines = null;
        lyricHint = "";
        lastLyricPosMs = -1;
        if (media == null) return;
        MusicRepository.get().getLyric(media).whenComplete((lrc, error) -> {
            List<LrcParser.Line> lines = (lrc == null) ? null : LrcParser.parse(lrc);
            if (lines == null || lines.isEmpty()) return;
            if (player == null || player.current() != media) return; // 已切歌，丢弃
            lyricLines = lines;
            lyricHint = lines.get(0).text; // 初始显示第一句或空白
            postNotification();
        });
    }

    /** 进度回调切句：歌词行变化才刷新通知（防每 500ms 都 notify）。 */
    private void updateLyricNotification(long positionMs) {
        List<LrcParser.Line> lines = lyricLines;
        if (lines == null || lines.isEmpty()) return;
        int idx = LrcParser.indexOf(lines, positionMs);
        if (idx < 0) return;
        String text = lines.get(idx).text;
        if (text.isEmpty()) return;
        // 位置未跨句（500ms 粒度内）且 hint 未变则跳过，避免无谓通知刷新
        if (Math.abs(positionMs - lastLyricPosMs) < 700 && text.equals(lyricHint)) return;
        lastLyricPosMs = positionMs;
        lyricHint = text;
        postNotification();
    }

    private void postNotification() {
        if (player != null) getNotificationManager().notify(NOTIFY_ID, buildNotification(player.isPlaying(), player.current()));
    }

    // ------------------------------------------------------------ 播放内核回调

    private final class PlayerBridge implements MusicPlayer.Callback {

        @Override
        public void onMusicChanged(MusicMedia media) {
            // 最近播放记录（换歌即记，与服务无关性 UI 均可）
            if (media != null) MusicLibrary.get().record(media);
            loadLyricForNotification(media);
            postNotification();
            if (listener != null) listener.onMusicChanged(media);
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            postNotification();
            if (listener != null) listener.onPlayingChanged(playing);
        }

        @Override
        public void onStateChanged(int state) {
            if (listener != null) listener.onStateChanged(state);
        }

        @Override
        public void onError(MusicMedia media, PlaybackException error) {
            if (listener != null) listener.onError(media, error);
        }

        @Override
        public void onProgress(long positionMs, long durationMs) {
            updateLyricNotification(positionMs);
            if (listener != null) listener.onProgress(positionMs, durationMs);
        }

        @Override
        public void onNeedReloadUrl(MusicMedia media) {
            // 播放失败且无备用 URL 时：交给插件重新拉取（getMediaSource），取到后回填内核
            MusicRepository.get().getMediaUrl(media, "").whenComplete((url, error) -> {
                if (error == null && url != null && !url.isEmpty()) {
                    new Handler(Looper.getMainLooper()).post(() -> reloadSource(media, url));
                }
                // 失败或空 URL：内核在重试预算耗尽后自动跳到下一首
            });
        }

        @Override
        public void onSourceFailed(MusicMedia media, String message) {
            if (listener != null) listener.onSourceFailed(media, message);
        }

        @Override
        public void onQueueEnded() {
            // 列表播完：退回后台但不停止服务，通知保持供再次进入
        }
    }
}