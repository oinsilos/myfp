package com.fongmi.android.tv.music.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.fongmi.android.tv.music.model.MusicMedia;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 音乐本地库（收藏 + 最近播放）：轻量 SharedPreferences 持久化（JSON），不引入 Room。
 * <p>
 * 存储结构：
 * - {@code music_library} → 收藏列表：[{id,title,artist,album,durationMs,cover,source,extra}]
 * - {@code music_history} → 最近播放（最多 HISTORY_MAX 条，新播插队头、去重同 id）
 * <p>
 * 语义：收藏按加入顺序；最近播放按时间倒序。点击收藏/历史项可直接播放（source 路由回原插件）。
 */
public final class MusicLibrary {

    private static final String TAG = "MusicLibrary";
    private static final String KEY_FAVORITES = "music_library";
    private static final String KEY_HISTORY = "music_history";
    private static final String KEY_PLAYLISTS = "music_playlists";
    private static final int HISTORY_MAX = 100;

    private static volatile MusicLibrary instance;

    private SharedPreferences prefs;

    public static MusicLibrary get() {
        if (instance == null) {
            synchronized (MusicLibrary.class) {
                if (instance == null) instance = new MusicLibrary();
            }
        }
        return instance;
    }

    /** 绑定 Context（init 幂等）。 */
    public synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences("music_local", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 收藏

    /** 是否已收藏（按 id）。 */
    public boolean isFavorite(MusicMedia m) {
        return indexOf(favorites(), m) >= 0;
    }

    public List<MusicMedia> favorites() {
        return read(KEY_FAVORITES);
    }

    /** 切换收藏状态，返回切换后的状态（true=已收藏）。 */
    public boolean toggleFavorite(MusicMedia m) {
        if (m == null) return false;
        List<MusicMedia> list = favorites();
        int idx = indexOf(list, m);
        if (idx >= 0) {
            list.remove(idx);
            write(KEY_FAVORITES, list);
            return false;
        }
        list.add(0, copy(m));
        write(KEY_FAVORITES, list);
        return true;
    }

    // ------------------------------------------------------------ 最近播放

    public List<MusicMedia> history() {
        return read(KEY_HISTORY);
    }

    /** 播放一首歌时记录：头插、同 id 去重（旧位置移除）、限长。 */
    public void record(MusicMedia m) {
        if (m == null || m.id == null || m.id.isEmpty()) return;
        List<MusicMedia> list = history();
        int idx = indexOf(list, m);
        if (idx >= 0) list.remove(idx);
        list.add(0, copy(m));
        while (list.size() > HISTORY_MAX) list.remove(list.size() - 1);
        write(KEY_HISTORY, list);
    }

    // ------------------------------------------------------------ 自建歌单

    /** 自建歌单（可读写）。 */
    public static final class Playlist {
        public final String name;
        public final List<MusicMedia> items = new ArrayList<>();

        Playlist(String name) {
            this.name = name;
        }
    }

    /** 全部自建歌单。 */
    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        String raw = prefs == null ? "" : prefs.getString(KEY_PLAYLISTS, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name");
                if (name.isEmpty()) continue;
                Playlist p = new Playlist(name);
                JSONArray ia = o.optJSONArray("items");
                if (ia != null) {
                    for (int j = 0; j < ia.length(); j++) {
                        MusicMedia m = parse(ia.optJSONObject(j));
                        if (m != null) p.items.add(m);
                    }
                }
                out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "read playlists failed", e);
        }
        return out;
    }

    /** 新建歌单（重名失败）。 */
    public boolean createPlaylist(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        List<Playlist> ps = playlists();
        for (Playlist p : ps) if (p.name.equals(name.trim())) return false;
        ps.add(new Playlist(name.trim()));
        savePlaylists(ps);
        return true;
    }

    /** 重命名歌单（新名重名/为空失败）。 */
    public boolean renamePlaylist(String oldName, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        List<Playlist> ps = playlists();
        for (Playlist p : ps) if (!p.name.equals(oldName) && p.name.equals(newName.trim())) return false;
        for (int i = 0; i < ps.size(); i++) {
            Playlist p = ps.get(i);
            if (p.name.equals(oldName)) {
                Playlist n = new Playlist(newName.trim());
                n.items.addAll(p.items);
                ps.set(i, n);
                savePlaylists(ps);
                return true;
            }
        }
        return false;
    }

    /** 删除歌单。 */
    public void deletePlaylist(String name) {
        List<Playlist> ps = playlists();
        ps.removeIf(p -> p.name.equals(name));
        savePlaylists(ps);
    }

    /** 往歌单加歌（同 id 去重，头插）。 */
    public boolean addToPlaylist(String name, MusicMedia m) {
        if (m == null) return false;
        List<Playlist> ps = playlists();
        for (Playlist p : ps) {
            if (p.name.equals(name)) {
                if (indexOf(p.items, m) < 0) p.items.add(0, copy(m));
                savePlaylists(ps);
                return true;
            }
        }
        return false;
    }

    /** 从歌单移除指定下标的歌曲。 */
    public void removeFromPlaylist(String name, int index) {
        List<Playlist> ps = playlists();
        for (Playlist p : ps) {
            if (p.name.equals(name)) {
                if (index >= 0 && index < p.items.size()) {
                    p.items.remove(index);
                    savePlaylists(ps);
                }
                return;
            }
        }
    }

    private void savePlaylists(List<Playlist> ps) {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Playlist p : ps) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                JSONArray ia = new JSONArray();
                for (MusicMedia m : p.items) ia.put(toJson(m));
                o.put("items", ia);
                arr.put(o);
            }
            prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "write playlists failed", e);
        }
    }

    // ------------------------------------------------------------ 统一备份（收藏/历史/歌单整个音乐本地库）

    /** 导出整个音乐本地库（收藏/最近/歌单），供统一备份使用。 */
    public synchronized String exportJson() {
        if (prefs == null) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("favorites", prefs.getString(KEY_FAVORITES, "[]"));
            o.put("history", prefs.getString(KEY_HISTORY, "[]"));
            o.put("playlists", prefs.getString(KEY_PLAYLISTS, "[]"));
            return o.toString(2);
        } catch (Exception e) {
            Log.w(TAG, "export failed", e);
            return "{}";
        }
    }

    /** 恢复整个音乐本地库（覆盖式）。 */
    public synchronized boolean importJson(String json) {
        if (prefs == null || json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            String fav = o.optString("favorites", "");
            String his = o.optString("history", "");
            String pls = o.optString("playlists", "");
            // 容错解析校验：不是合法数组就当作空恢复
            new JSONArray(fav);
            new JSONArray(his);
            new JSONArray(pls);
            prefs.edit()
                    .putString(KEY_FAVORITES, fav)
                    .putString(KEY_HISTORY, his)
                    .putString(KEY_PLAYLISTS, pls)
                    .apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "import failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------ 内部

    private int indexOf(List<MusicMedia> list, MusicMedia m) {
        if (m == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            MusicMedia e = list.get(i);
            if (e != null && e.id != null && e.id.equals(m.id)) return i;
        }
        return -1;
    }

    private MusicMedia copy(MusicMedia m) {
        return new MusicMedia(m.id, m.title, m.artist, m.album, m.durationMs, m.cover, m.url, null, m.headers);
    }

    private List<MusicMedia> read(String key) {
        List<MusicMedia> list = new ArrayList<>();
        String raw = prefs == null ? "" : prefs.getString(key, "");
        if (raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                MusicMedia m = parse(arr.optJSONObject(i));
                if (m != null) list.add(m);
            }
        } catch (Exception e) {
            Log.w(TAG, "read " + key + " failed", e);
        }
        return list;
    }

    private void write(String key, List<MusicMedia> list) {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (MusicMedia m : list) arr.put(toJson(m));
            prefs.edit().putString(key, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "write " + key + " failed", e);
        }
    }

    private JSONObject toJson(MusicMedia m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", m.id);
        o.put("title", m.title);
        o.put("artist", m.artist);
        o.put("album", m.album);
        o.put("durationMs", m.durationMs);
        o.put("cover", m.cover == null ? "" : m.cover);
        o.put("url", m.url == null ? "" : m.url);
        o.put("source", m.source == null ? "" : m.source);
        o.put("extra", m.extra == null ? "" : m.extra);
        return o;
    }

    private MusicMedia parse(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.optString("id");
            if (id.isEmpty()) return null;
            MusicMedia m = new MusicMedia(
                    id,
                    o.optString("title"),
                    o.optString("artist"),
                    o.optString("album"),
                    o.optLong("durationMs"),
                    o.optString("cover"),
                    o.optString("url"),
                    null,
                    null
            );
            m.source = o.optString("source");
            m.extra = o.optString("extra");
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}