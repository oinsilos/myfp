package com.fongmi.android.tv.music.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.plugin.MusicRepository;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.Notify;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 音乐下载器（单曲离线）：复用通用 {@link Download}（OkHttp + 进度 + 取消）。
 * <p>
 * 流程：media → 按 source 路由取真实播放 URL（getMediaSource）→ 下载到
 * {@code Android/data/com.fongmi.android.tv/files/music/}，文件名清洗为
 * {@code 平台 - 歌名 - 歌手.mp3}。同一首歌（platform+id）串行去重，重复点击提示。
 * <p>
 * 生命周期与 Repository 解耦：download 幂等（进行中/已完成则直接返回状态），
 * UI 经 {@link Listener} 回调刷新（主线程）。
 */
public final class MusicDownloader {

    private static final String TAG = "MusicDownloader";
    private static final String DIR = "music";

    private static volatile MusicDownloader instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 下载中（platform+id 键），去重。 */
    private final Set<String> running = new HashSet<>();
    /** 下载中队列（按加入顺序，供下载管理页展示进度/取消）。 */
    private final List<MusicMedia> queue = new ArrayList<>();
    /** key → 当前进度（0~100）。 */
    private final Map<String, Integer> progress = new HashMap<>();
    /** key → 下载句柄（取消用）。 */
    private final Map<String, Download> tasks = new HashMap<>();
    /** 完成/失败标记，避免重复下载同一首。 */
    private final Set<String> doneKeys = new HashSet<>();

    private Context context;
    private Listener listener;

    public interface Listener {
        default void onStateChanged() {
        }

        default void onProgress(String key, int percent) {
        }
    }

    public static MusicDownloader get() {
        if (instance == null) {
            synchronized (MusicDownloader.class) {
                if (instance == null) instance = new MusicDownloader();
            }
        }
        return instance;
    }

    /** 绑定 Context（init 幂等，默认存外部私有目录无需权限）。 */
    public synchronized void init(Context context) {
        if (this.context != null) return;
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 是否正在下载（UI 图标转圈）。 */
    public boolean isRunning(MusicMedia media) {
        synchronized (running) {
            return running.contains(key(media));
        }
    }

    /** 是否已完成（含失败，UI 灰显/去重）。 */
    public boolean isDone(MusicMedia media) {
        synchronized (doneKeys) {
            return doneKeys.contains(key(media));
        }
    }

    private static String key(MusicMedia m) {
        return (m == null || m.id == null || m.id.isEmpty()) ? "" : (m.source + ":" + m.id);
    }

    /**
     * 下载单曲：先路由插件取真实 URL（异步），URL 就绪后开始下载。
     * VIP/无版权歌取 URL 会失败 → 回调失败并清除进行中标记。
     */
    public void download(MusicMedia media) {
        Context ctx = context;
        if (media == null || ctx == null) return;
        String k = key(media);
        if ("local".equals(media.source)) {
            Notify.show("本地文件无需下载");
            return;
        }
        if (k.isEmpty() || media.vip) {
            Notify.show("无法下载：无有效地址或 VIP 歌曲");
            return;
        }
        synchronized (running) {
            if (running.contains(k)) {
                Notify.show("正在下载中…");
                return;
            }
            running.add(k);
        }
        synchronized (queue) {
            queue.add(media);
        }
        synchronized (progress) {
            progress.put(k, 0);
        }
        notifyState();
        MusicRepository.get().getMediaUrl(media, "").whenComplete((url, error) -> {
            if (error != null || url == null || url.isEmpty()) {
                finishFail(k, "取播放地址失败（VIP/版权或接口异常）");
                return;
            }
            File file = target(media);
            if (file.exists()) {
                finishDone(k, file);
                return;
            }
            Download d = Download.create(url, file);
            synchronized (tasks) {
                tasks.put(k, d);
            }
            d.start(new Download.Callback() {
                @Override
                public void progress(int progress) {
                    final int p = progress;
                    synchronized (MusicDownloader.this.progress) {
                        MusicDownloader.this.progress.put(k, p);
                    }
                    onUi(() -> {
                        Listener l = listener;
                        if (l != null) l.onProgress(k, p);
                    });
                }

                @Override
                public void error(String msg) {
                    finishFail(k, "下载失败：" + msg);
                }

                @Override
                public void success(File file) {
                    finishDone(k, file);
                }
            });
        });
    }

    private void finishDone(String k, File file) {
        synchronized (doneKeys) {
            doneKeys.add(k);
        }
        removeFromQueue(k);
        Notify.show("已下载：" + file.getName());
        notifyState();
    }

    private void finishFail(String k, String msg) {
        // 失败不记 doneKeys：允许用户稍后重试同一首
        removeFromQueue(k);
        Notify.show(msg);
        notifyState();
    }

    /** 从下载中队列/句柄/进度清理（完成、失败、取消共用）。 */
    private void removeFromQueue(String k) {
        synchronized (queue) {
            queue.removeIf(m -> key(m).equals(k));
        }
        synchronized (tasks) {
            tasks.remove(k);
        }
        synchronized (progress) {
            progress.remove(k);
        }
        synchronized (running) {
            running.remove(k);
        }
    }

    private void notifyState() {
        onUi(() -> {
            Listener l = listener;
            if (l != null) l.onStateChanged();
        });
    }

    private void onUi(Runnable r) {
        handler.post(r);
    }

    /** 下载目标文件：music/<platform> - <title> - <artist>.mp3（清洗非法字符）。 */
    private File target(MusicMedia m) {
        File dir = new File(context.getExternalFilesDir(null), DIR);
        if (!dir.exists()) dir.mkdirs();
        String name = sanitize(m.title + " - " + m.artist);
        return new File(dir, name + ".mp3");
    }

    /** 文件名清洗：不允许 / \ : * ? " < > | 及控制字符，超长截断。 */
    static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "untitled";
        StringBuilder sb = new StringBuilder(s.trim().length());
        for (int i = 0; i < s.length() && i < 80; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "/\\:*?\"<>|".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        return sb.toString().trim().isEmpty() ? "untitled" : sb.toString().trim();
    }

    /** 下载目录（供 UI 查看/打开）。 */
    public File musicDir() {
        return context == null ? null : new File(context.getExternalFilesDir(null), DIR);
    }

    // ------------------------------------------------------------ 下载管理

    /** 进行中的下载（按加入顺序）。 */
    public List<MusicMedia> runningList() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    /** 某曲当前下载进度（0~100）。 */
    public int progressOf(MusicMedia m) {
        synchronized (progress) {
            Integer p = progress.get(key(m));
            return p == null ? 0 : p;
        }
    }

    /** 取消下载（进行中从队列移除并中断）。 */
    public void cancel(MusicMedia m) {
        String k = key(m);
        Download d;
        synchronized (tasks) {
            d = tasks.remove(k);
        }
        if (d != null) d.cancel();
        removeFromQueue(k);
        notifyState();
    }

    /** 已完成的 mp3 文件（按修改时间倒序）。 */
    public List<File> completedFiles() {
        File dir = musicDir();
        if (dir == null || !dir.exists()) return new ArrayList<>();
        File[] fs = dir.listFiles((d, name) -> name.endsWith(".mp3"));
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified).reversed());
        return new ArrayList<>(Arrays.asList(fs));
    }

    /** 删除一个已下载文件。 */
    public void deleteDownloaded(File f) {
        if (f == null) return;
        boolean ok = f.exists() && f.delete();
        if (!ok) Notify.show("删除失败");
        else notifyState();
    }
}