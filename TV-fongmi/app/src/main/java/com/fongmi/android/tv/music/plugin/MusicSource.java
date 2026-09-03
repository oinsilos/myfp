package com.fongmi.android.tv.music.plugin;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.rhino.plugin.PluginSandbox;

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
    private boolean loaded;

    /** 加载插件源码（唯一入口，失败抛 Runtime）。 */
    public synchronized void load(String pluginJs) {
        if (loaded) return;
        sandbox.load(pluginJs);
        loaded = true;
    }

    public boolean loaded() {
        return loaded;
    }

    /** 插件名（load 后可用）。 */
    public String platform() {
        return loaded ? sandbox.platformName() : "";
    }

    /**
     * 搜索。返回可播放列表；插件未实现 search 或结果为空时返回空列表。
     */
    public CompletableFuture<List<MusicMedia>> search(String keyword, int page, int type) {
        String args = quote(keyword == null ? "" : keyword) + "," + page + "," + type;
        return sandbox.callJson("search", args).thenApply(result -> {
            String json = sandbox.stringify(result);
            return parseSearch(json);
        });
    }

    /** 拉取播放 URL。返回可直接播放的 url；失败时异常交由上层换源策略处理。 */
    public CompletableFuture<String> getMediaUrl(MusicMedia media, String quality) {
        String item = buildItemJson(media);
        return sandbox.callJson("getMediaSource", item + "," + quote(quality == null ? "" : quality))
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
                list.add(media);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 组装传回插件的 musicItem JSON（保留插件的 songId 等原始字段）。 */
    private static String buildItemJson(MusicMedia media) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(quote(media.id)).append(',');
        sb.append("\"title\":").append(quote(media.title)).append(',');
        sb.append("\"artist\":").append(quote(media.artist)).append(',');
        sb.append("\"album\":").append(quote(media.album)).append(',');
        sb.append("\"cover\":").append(quote(media.cover == null ? "" : media.cover)).append(',');
        sb.append("\"url\":").append(quote(media.url == null ? "" : media.url));
        if (media.extra != null && !media.extra.isEmpty() && !"{}".equals(media.extra)) {
            sb.append(',').append(media.extra.substring(1)); // 去掉 extra 的开头 {，合并字段
        }
        sb.append('}');
        return sb.toString();
    }

    private static String opt(JSONObject obj, String key) {
        String v = obj.optString(key);
        return v == null ? "" : v;
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}