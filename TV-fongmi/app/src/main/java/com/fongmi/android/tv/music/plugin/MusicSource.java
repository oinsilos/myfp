package com.fongmi.android.tv.music.plugin;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.MusicSheet;
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
 * Rhino 音乐插件桥：封装 MusicFree 插件契约（search / getMediaSource / getLyric /
 * getTopLists / getRecommendSheetTags / getMusicSheetInfo / importMusicSheet / getArtistWorks...），
 * 把插件返回的数据映射为播放内核的 {@link MusicMedia} 与歌单模型 {@link MusicSheet}。
 * <p>
 * 插件契约对齐 RN 端（MusicFree）：search(keyword, page, type) → { isEnd, data:[MusicItem] }；
 * getMediaSource(musicItem, quality) → { url }；getTopListDetail(item, page) → { isEnd, musicList } 等。
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

    /** 可安全销毁沙箱（release 时调用）。 */
    public void destroy() {
        try {
            sandbox.destroy();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 搜索。返回可播放列表；插件未实现 search 或结果为空时返回空列表。
     * 结果逐条回填 source=插件 platform，供多源路由。
     * type 对齐 MusicFree 契约（字符串 "music"/"album"/"sheet"），旧数字 type 不再适用。
     */
    public CompletableFuture<List<MusicMedia>> search(String keyword, int page, String type) {
        String t = (type == null || type.isEmpty()) ? "music" : type;
        String args = new JSONArray().put(keyword == null ? "" : keyword).put(page).put(t).toString();
        return sandbox.callJson("search", args).thenApply(result -> {
            List<MusicMedia> list = parseSearch(sandbox.stringify(result));
            tagSource(list);
            return list;
        });
    }

    /** 拉取播放 URL。返回可直接播放的 url；失败时异常交由上层换源策略处理。
     *  播放器侧未指定音质时默认 standard（插件 quality 契约：low/standard/high/super）。 */
    public CompletableFuture<String> getMediaUrl(MusicMedia media, String quality) {
        String q = (quality == null || quality.isEmpty()) ? "standard" : quality;
        String args = new JSONArray().put(itemObject(media)).put(q).toString();
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
                    // 插件返回对象（如 {rawLrc: "..."} / {lrc: "..."}）：JSON 序列化后提取
                    if (!(result instanceof Scriptable)) return null;
                    String json = sandbox.stringify(result);
                    if (json == null || json.isEmpty() || "null".equals(json)) return null;
                    try {
                        JSONObject obj = new JSONObject(json);
                        // MusicFree 插件常见歌词返回形态：{rawLrc: "<LRC 文本>"}
                        if (obj.has("rawLrc")) {
                            String lrc = obj.optString("rawLrc");
                            return (lrc == null || lrc.isEmpty() || "null".equals(lrc)) ? null : lrc;
                        }
                        return new JSONObject("{\"v\":" + json + "}").getString("v");
                    } catch (JSONException e) {
                        return null;
                    }
                });
    }

    // ------------------------------------------------------------ 歌单 / 榜单 / 歌手

    /** 榜单分组列表：getTopLists() → [{ id?, name, data:[MusicSheet] }]。 */
    public CompletableFuture<List<SheetGroup>> topLists() {
        return sandbox.callJson("getTopLists", "[]").thenApply(result -> {
            try {
                JSONArray arr = new JSONArray(sandbox.stringify(result));
                List<SheetGroup> groups = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject g = arr.optJSONObject(i);
                    if (g == null) continue;
                    String gid = g.optString("id");
                    String gname = g.optString("name");
                    if (gname.isEmpty()) gname = g.optString("title");
                    SheetGroup group = new SheetGroup(gid, gname, parseSheets(g.optJSONArray("data")));
                    groups.add(group);
                }
                return groups;
            } catch (JSONException e) {
                return new ArrayList<>();
            }
        });
    }

    /** 推荐歌单分类标签：getRecommendSheetTags() → 组名集合。 */
    public CompletableFuture<List<String>> recommendTags() {
        return sandbox.callJson("getRecommendSheetTags", "[]").thenApply(result -> {
            List<String> tags = new ArrayList<>();
            try {
                JSONObject root = new JSONObject(sandbox.stringify(result));
                JSONArray data = root.optJSONArray("data");
                if (data == null) return tags;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name");
                    if (name.isEmpty()) name = o.optString("title");
                    if (!name.isEmpty() && !tags.contains(name)) tags.add(name);
                }
            } catch (JSONException ignored) {
            }
            return tags;
        });
    }

    /** 某分类下的推荐歌单：getRecommendSheetsByTag({id,name:tag}, page) → {isEnd, data:[MusicSheet]}。 */
    public CompletableFuture<List<MusicSheet>> sheetsByTag(String tag, int page) {
        // 手拼 JSON（JSONObject().put 链会抛 checked JSONException，这里避开）
        String args = "[{\"id\":\"" + jsonEsc(tag) + "\",\"name\":\"" + jsonEsc(tag) + "\"}," + page + "]";
        return sandbox.callJson("getRecommendSheetsByTag", args).thenApply(result -> {
            List<MusicSheet> sheets = new ArrayList<>();
            try {
                JSONObject root = new JSONObject(sandbox.stringify(result));
                sheets.addAll(parseSheets(root.optJSONArray("data")));
            } catch (JSONException ignored) {
            }
            tagSourceSheet(sheets);
            return sheets;
        });
    }

    /** 歌单详情：getMusicSheetInfo(sheet, page) → {isEnd, musicList}。 */
    public CompletableFuture<List<MusicMedia>> sheetDetail(MusicSheet sheet, int page) {
        String args = new JSONArray().put(sheetObject(sheet)).put(page).toString();
        return sandbox.callJson("getMusicSheetInfo", args).thenApply(result -> {
            List<MusicMedia> list = parseMusicList(sandbox.stringify(result));
            tagSource(list);
            return list;
        });
    }

    /** 榜单详情：getTopListDetail(item, page) → {isEnd, musicList}。 */
    public CompletableFuture<List<MusicMedia>> topListDetail(MusicSheet item, int page) {
        String args = new JSONArray().put(sheetObject(item)).put(page).toString();
        return sandbox.callJson("getTopListDetail", args).thenApply(result -> {
            List<MusicMedia> list = parseMusicList(sandbox.stringify(result));
            tagSource(list);
            return list;
        });
    }

    /** 歌手作品：getArtistWorks(artist, page, "music") → {isEnd, data:[MusicItem]}。 */
    public CompletableFuture<List<MusicMedia>> artistSongs(MusicSheet artist, int page) {
        String args = new JSONArray().put(sheetObject(artist)).put(page).put("music").toString();
        return sandbox.callJson("getArtistWorks", args).thenApply(result -> {
            List<MusicMedia> list = parseWorks(sandbox.stringify(result));
            tagSource(list);
            return list;
        });
    }

    /** 歌单导入：importMusicSheet(urlLike) → MusicItem[]。 */
    public CompletableFuture<List<MusicMedia>> importSheet(String urlLike) {
        String args = new JSONArray().put(urlLike == null ? "" : urlLike).toString();
        return sandbox.callJson("importMusicSheet", args).thenApply(result -> {
            List<MusicMedia> list = parseImport(sandbox.stringify(result));
            tagSource(list);
            return list;
        });
    }

    /** 插件是否实现某方法（UI 据此决定入口是否展示）。 */
    public boolean hasMethod(String method) {
        return sandbox.hasMethod(method);
    }

    // ------------------------------------------------------------ 解析与拼装

    /** 搜索结果：{isEnd, data:[MusicItem]}。 */
    private static List<MusicMedia> parseSearch(String json) {
        List<MusicMedia> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return list;
            for (int i = 0; i < data.length(); i++) list.add(parseMusicItem(data.optJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 歌单详情/榜单详情：{isEnd, musicList:[...]}。 */
    private static List<MusicMedia> parseMusicList(String json) {
        List<MusicMedia> list = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).optJSONArray("musicList");
            if (data == null) return list;
            for (int i = 0; i < data.length(); i++) list.add(parseMusicItem(data.optJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 歌手作品：{isEnd, data:[...]}。 */
    private static List<MusicMedia> parseWorks(String json) {
        List<MusicMedia> list = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            if (data == null) return list;
            for (int i = 0; i < data.length(); i++) list.add(parseMusicItem(data.optJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 导入歌单：直接返回 MusicItem[]。 */
    private static List<MusicMedia> parseImport(String json) {
        List<MusicMedia> list = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(json);
            for (int i = 0; i < data.length(); i++) list.add(parseMusicItem(data.optJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 通用 MusicItem 解析（search/歌单/榜单/歌手/导入共用）。 */
    private static MusicMedia parseMusicItem(JSONObject item) {
        if (item == null) return null;
        String songId = item.optString("songId");
        if (songId.isEmpty()) songId = item.optString("id");
        if (songId.isEmpty()) return null;
        long durationSec = item.optLong("duration", 0);
        // MusicFree 插件封面字段多为 artwork（酷我/酷狗系），兼容 cover
        String cover = opt(item, "cover");
        if (cover.isEmpty()) cover = opt(item, "artwork");
        MusicMedia media = new MusicMedia(
                songId,
                opt(item, "title"),
                opt(item, "artist"),
                opt(item, "album"),
                durationSec * 1000,
                cover,
                opt(item, "url"),
                null,
                null
        );
        // 透传插件原始字段（getMediaSource 时按原样回传）：songId 固定；artistId/albumId 供详情入口跳转
        StringBuilder extra = new StringBuilder("{\"songId\":\"").append(songId).append("\"");
        String artistId = item.optString("artistId");
        if (!artistId.isEmpty()) extra.append(",\"artistId\":\"").append(artistId).append("\"");
        String albumId = item.optString("albumId");
        if (!albumId.isEmpty()) extra.append(",\"albumId\":\"").append(albumId).append("\"");
        extra.append("}");
        media.extra = extra.toString();
        media.vip = item.optBoolean("vip", false);
        return media;
    }

    /** 歌单列表解析（榜单组 data / 推荐歌单 data 共用）。 */
    private static List<MusicSheet> parseSheets(JSONArray data) {
        List<MusicSheet> sheets = new ArrayList<>();
        if (data == null) return sheets;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id");
            if (id.isEmpty()) continue;
            String cover = opt(it, "coverImgUrl");
            if (cover.isEmpty()) cover = opt(it, "cover");
            if (cover.isEmpty()) cover = opt(it, "artwork");
            sheets.add(new MusicSheet(
                    id,
                    opt(it, "title").isEmpty() ? opt(it, "name") : opt(it, "title"),
                    cover,
                    opt(it, "artist"),
                    opt(it, "description"),
                    it.optLong("worksNum", -1),
                    it.optLong("playCount", -1)
            ));
        }
        return sheets;
    }

    /** 给一条 MusicMedia 回填 source=插件 platform。 */
    private void tagSource(List<MusicMedia> list) {
        String p = platform;
        if (p.isEmpty()) return;
        for (MusicMedia m : list) m.source = p;
    }

    private void tagSourceSheet(List<MusicSheet> list) {
        String p = platform;
        if (p.isEmpty()) return;
        for (MusicSheet s : list) s.source = p;
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

    /** 组装歌单/榜单/歌手对象（原生 key 对齐 MusicFree IMusicSheetItemBase，另带 playlistId 供插件复用）。 */
    private static JSONObject sheetObject(MusicSheet sheet) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", sheet.id)
                    .put("title", sheet.title)
                    .put("name", sheet.title)
                    .put("playlistId", sheet.id)
                    .put("artist", sheet.artist)
                    .put("description", sheet.description == null ? "" : sheet.description)
                    .put("worksNum", sheet.worksNum)
                    .put("playCount", sheet.playCount);
            if (sheet.cover != null && !sheet.cover.isEmpty()) {
                obj.put("coverImgUrl", sheet.cover).put("cover", sheet.cover).put("artwork", sheet.cover);
            }
        } catch (JSONException ignored) {
        }
        return obj;
    }

    private static String opt(JSONObject obj, String key) {
        String v = obj.optString(key);
        return v == null ? "" : v;
    }

    /** JS 字符串转义（嵌入 JSON 双引号字面量）。 */
    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** 榜单分组（MusicFree IMusicSheetGroupItem 镜像）。 */
    public static final class SheetGroup {
        public final String id;
        public final String name;
        public final List<MusicSheet> items;

        SheetGroup(String id, String name, List<MusicSheet> items) {
            this.id = id;
            this.name = name.isEmpty() ? "榜单" : name;
            this.items = items;
        }
    }
}