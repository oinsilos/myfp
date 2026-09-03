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

import com.fongmi.android.tv.R;
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

        void onProgress(long positionMs, long durationMs);

        void onSourceFailed(MusicMedia media, String message);
    }

    public final class MusicBinder extends Binder {
        public MusicPlaybackService service() {
            return MusicPlaybackService.this;
        }
    }

    private final MusicBinder binder = new MusicBinder();
    private MusicPlayer player;
    private Listener listener;

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
        player = new MusicPlayer(this, new PlayerBridge());
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY_PAUSE);
        filter.addAction(ACTION_PREV);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_STOP);
        registerReceiver(actions, filter, RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
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
        player.setQueue(items, index);
        enterForeground();
    }

    public void toggle() {
        if (player != null) player.toggle();
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
        String text = media == null ? "" : (media.artist + (media.album != null && !media.album.isEmpty() ? " · " + media.album : ""));
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

    private void postNotification() {
        if (player != null) getNotificationManager().notify(NOTIFY_ID, buildNotification(player.isPlaying(), player.current()));
    }

    // ------------------------------------------------------------ 播放内核回调

    private final class PlayerBridge implements MusicPlayer.Callback {

        @Override
        public void onMusicChanged(MusicMedia media) {
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
        public void onProgress(long positionMs, long durationMs) {
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