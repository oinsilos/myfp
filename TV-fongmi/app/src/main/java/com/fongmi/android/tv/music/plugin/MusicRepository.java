package com.fongmi.android.tv.music.plugin;

import android.content.Context;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.github.catvod.utils.Asset;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 音乐数据仓库：懒加载 Rhino 内置插件（assets/music/netease.js）并暴露搜索入口。
 * 播放走 {@link com.fongmi.android.tv.music.service.MusicPlaybackService}。
 */
public final class MusicRepository {

    private static volatile MusicRepository instance;

    private final MusicSource source = new MusicSource();
    private volatile boolean initialised;

    private MusicRepository() {
    }

    public static MusicRepository get() {
        if (instance == null) {
            synchronized (MusicRepository.class) {
                if (instance == null) instance = new MusicRepository();
            }
        }
        return instance;
    }

    /** 绑定 Context 后首次调用触发插件加载（幂等）。 */
    public synchronized void init(Context context) {
        if (initialised) return;
        String code = Asset.read("music/netease.js");
        if (code == null || code.isEmpty()) throw new IllegalStateException("内置音乐插件缺失 assets/music/netease.js");
        source.load(code);
        initialised = true;
    }

    public boolean ready() {
        return initialised;
    }

    /** 搜索（第 1 页，类型=歌曲）。 */
    public CompletableFuture<List<MusicMedia>> search(String keyword) {
        return source.search(keyword == null ? "" : keyword, 1, 1);
    }

    /** 拉取播放 URL（换源用）。 */
    public CompletableFuture<String> getMediaUrl(MusicMedia media, String quality) {
        return source.getMediaUrl(media, quality);
    }
}