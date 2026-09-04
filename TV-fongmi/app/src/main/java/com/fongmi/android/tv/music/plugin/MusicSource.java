package com.fongmi.android.tv.music.plugin;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.rhino.plugin.PluginSandbox;

import org.htmlunit.corejs.javascript.Scriptable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Rhino 音乐插件桥：封装 MusicFree 插件契约（search / getMediaSource），
 * 把插件返回的数据映射为播放内核的 {@link MusicMedia}。
 * <p>
 * 插件契约对齐 RN 端：search(keyword, page, type) → { isEnd, data:[MusicItem] }；
 * getMediaSource(musicItem, quality) → { url }。
 */
public final class MusicSource {

    private final PluginSandbox sandbox = PluginSandbox.create();
    private volatile boolean loaded;
    /** load 时缓存的插件名/版本（io 线程取一次，之后纯字段读取，避免主线程轮询沙箱线程）。 */
    private volatile String platform = "";
    private volatile String version = "";

    /** 加载插件源码（唯一入口，失败抛 Runtime）。 */
    public synchronized void load(String pluginJs) {
        if (loaded) return;
        sandbox.load(pluginJs);
        platform = sandbox.platformName();
        version = sandbox.versionName();
        loaded = true;
    }

    public boolean loaded() {
        return loaded;
    }

    /** 插件名（load 后可用；缓存读取，不触碰沙箱线程）。 */
    public String platform() {
        return platform;
    }

    /** 插件版本（load 后可用；缓存读取，不触碰沙箱线程）。 */
    public String version() {
        return version;
    }

    /**
     * 搜索。返回可播放列表；插件未实现 search 或结果为空时返回空列表。
     * 结果逐条回填 source=插件 platform，供多源路由。
     */
    public CompletableFuture<List<MusicMedia>> search(String keyword, int page, int type) {
        String args = new JSONArray().put(keyword == null ? "" : keyword).put(page).put(type).toString();
        return sandbox.callJson("search", args).thenApply(result -> {
            String json = sandbox.stringify(result);
            List<MusicMedia> list = parseSearch(json);
            String p = platform();
            if (!p.isEmpty()) for (MusicMedia m : list) m.source = p;
            return list;
        });
    }

    /** 拉取播放 URL。返回可直接播放的 url；失败时异常交由上层换源策略处理。 */
    public CompletableFuture<String> getMediaUrl(MusicMedia media, String quality) {
        String args = new JSONArray().put(itemObject(media)).put(quality == null ? "" : quality).toString();
        return sandbox.callJson("getMediaSource", args)
                .thenApply(result -> {
                    String json = sandbox.stringify(result);
                    try {
                        JSONObject obj = new JSONObject(json);
                        String url = obj.optString("url");
                        if (url == null || url.isEmpty()) throw new CompletionException(new Exception("getMediaSource: empty url"));
                        return url;
                    } catch (JSONException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    /** 拉取歌词。返回 LRC 文本；插件无词返回 null，接口异常抛错（UI 可见原因）。 */
    public CompletableFuture<String> getLyric(MusicMedia media) {
        String args = new JSONArray().put(itemObject(media)).toString();
        return sandbox.callJson("getLyric", args)
                .thenApply(result -> {
                    if (result == null) return null;
                    // 插件返回的是原始 LRC 文本（Java String / NativeString）：直接使用。
                    // 切勿走 JSON.stringify 再包 {"v":...} 二次解析——原始文本未加引号，构造出的 JSON 必然非法，
                    // 会被 JSONException 吞成 null，导致「该歌曲暂无歌词」假象（根因）。
                    if (result instanceof CharSequence) {
                        String s = result.toString();
                        return (s.isEmpty() || "null".equals(s)) ? null : s;
                    }
                    // 插件返回对象（如 {lrc: "..."}）：JSON 序列化后提取
                    if (!(result instanceof Scriptable)) return null;
                    String json = sandbox.stringify(result);
                    if (json == null || json.isEmpty() || "null".equals(json)) return null;
                    try {
                        return new JSONObject("{\"v\":" + json + "}").getString("v");
                    } catch (JSONException e) {
                        return null;
                    }
                });
    }

    /** 组装传回插件的 musicItem JSON 对象（保留插件的 songId 等原始字段）。 */
    private static JSONObject itemObject(MusicMedia media) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", media.id)
                    .put("title", media.title)
                    .put("artist", media.artist)
                    .put("album", media.album)
                    .put("cover", media.cover == null ? "" : media.cover)
                    .put("url", media.url == null ? "" : media.url);
            if (media.extra != null && !media.extra.isEmpty() && !"{}".equals(media.extra)) {
                obj.put("songId", media.id); // extra 固定为 {songId:...}，直接补字段
            }
        } catch (JSONException ignored) {
        }
        return obj;
    }

    // ------------------------------------------------------------ 解析与拼装

    private static List<MusicMedia> parseSearch(String json) {
        List<MusicMedia> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return list;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String songId = item.optString("songId");
                if (songId.isEmpty() && item.optString("id").isEmpty()) continue;
                if (songId.isEmpty()) songId = item.optString("id");
                long durationSec = item.optLong("duration", 0);
                MusicMedia media = new MusicMedia(
                        songId,
                        opt(item, "title"),
                        opt(item, "artist"),
                        opt(item, "album"),
                        durationSec * 1000,
                        opt(item, "cover"),
                        opt(item, "url"),
                        null,
                        null
                );
                // 透传插件原始字段（getMediaSource 时按原样回传）
                media.extra = "{\"songId\":\"" + songId + "\"}";
                media.vip = item.optBoolean("vip", false);
                list.add(media);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    private static String opt(JSONObject obj, String key) {
        String v = obj.optString(key);
        return v == null ? "" : v;
    }
}