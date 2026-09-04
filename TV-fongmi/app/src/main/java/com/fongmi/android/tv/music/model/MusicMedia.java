package com.fongmi.android.tv.music.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音乐媒体项：插件返回的歌曲元数据 + 播放内核所需的 URL/换源信息。
 * 一条媒体可携带多个备选 URL（moreUrls），内核在播放失败/音质切换时按序尝试。
 */
public final class MusicMedia {

    /** 歌曲唯一 id（插件侧，用于重新拉取 URL / 歌词 / 封面）。 */
    public final String id;
    public final String title;
    public final String artist;
    public final String album;
    /** 时长（毫秒），未知为 -1。 */
    public final long durationMs;
    /** 封面图 URL（可空）。 */
    public final String cover;
    /** 当前播放 URL（首次播放前可能为空，由内核回调上层补取）。 */
    public String url;
    /** 备选 URL：音质切换 / 换源按序尝试（可空）。 */
    public final List<String> moreUrls;
    /** 自定义请求头（可空）。 */
    public final Map<String, String> headers;
    /** 插件原始字段透传（JSON 片段，如 {"songId":"123"}，getMediaSource 时原样带回插件）。 */
    public String extra;
    /** 是否需付费/VIP（网易云 fee>0），播放时大概率无源，UI 灰显标注。 */
    public boolean vip;
    /** 来源插件 platform 名（多源路由：换源/歌词回该插件处理；空=用当前源）。 */
    public String source = "";

    public MusicMedia(String id, String title, String artist, String album, long durationMs, String cover, String url, List<String> moreUrls, Map<String, String> headers) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.durationMs = durationMs;
        this.cover = cover;
        this.url = url;
        this.moreUrls = moreUrls == null ? new ArrayList<>() : moreUrls;
        this.headers = headers == null ? new HashMap<>() : headers;
    }

    public boolean isEmpty() {
        return (url == null || url.isEmpty()) && moreUrls.isEmpty();
    }

    /** 依序取下一个可用 URL（当前 url 之后的下一个备选），无则 null。 */
    public String popAlternative() {
        return moreUrls.isEmpty() ? null : moreUrls.remove(0);
    }

    @Override
    public String toString() {
        return "MusicMedia{title='" + title + "', artist='" + artist + "', url='" + url + "', moreUrls=" + moreUrls + "}";
    }
}