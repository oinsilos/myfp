package com.fongmi.android.tv.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读本地库：书架 + 阅读进度（断点续读）+ 书签，轻量 SharedPreferences(JSON) 持久化。
 * <p>
 * 存储结构（文件 {@code reader_library}）：
 * - {@code shelf}：书架书籍 [{url,name,author,source,...}]
 * - {@code progress}：{bookUrl: {chapter, percent}}，章节号 + 章内阅读进度(0~1)
 * - {@code bookmarks}：{bookUrl: [{chapter,name,percent,time}]}
 * <p>
 * key 一律使用书籍详情页 url（Book.url），同一书源内唯一。
 */
public final class ReaderStore {

    private static final String TAG = "ReaderStore";
    private static final String KEY_SHELF = "shelf";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_BOOKMARKS = "bookmarks";

    private static volatile ReaderStore instance;

    private SharedPreferences prefs;

    /** 阅读进度（章节号 + 章内 0~1 进度）。 */
    public static final class Progress {
        public final int chapter;
        public final float percent;

        Progress(int chapter, float percent) {
            this.chapter = chapter;
            this.percent = percent;
        }
    }

    /** 书签（某本书内一个位置）。 */
    public static final class Bookmark {
        public final int chapter;
        public final String chapterName;
        public final float percent;
        public final long time;

        Bookmark(int chapter, String chapterName, float percent, long time) {
            this.chapter = chapter;
            this.chapterName = chapterName == null ? "" : chapterName;
            this.percent = percent;
            this.time = time;
        }
    }

    public static ReaderStore get() {
        if (instance == null) {
            synchronized (ReaderStore.class) {
                if (instance == null) instance = new ReaderStore();
            }
        }
        return instance;
    }

    public synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences("reader_library", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 书架

    /** 书架书籍（轻量 Book：url/name/author/source/cover）。 */
    public List<Book> shelf() {
        List<Book> out = new ArrayList<>();
        String raw = prefs == null ? "" : prefs.getString(KEY_SHELF, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Book b = new Book();
                b.url = o.optString("url");
                if (b.url.isEmpty()) continue;
                b.name = o.optString("name");
                b.author = o.optString("author");
                b.cover = o.optString("cover");
                b.source = o.optString("source");
                out.add(b);
            }
        } catch (Exception e) {
            Log.w(TAG, "read shelf failed", e);
        }
        return out;
    }

    /** 是否在书架上（按 url）。 */
    public boolean inShelf(String url) {
        for (Book b : shelf()) if (b.url.equals(url)) return true;
        return false;
    }

    /** 加入书架（已存在则忽略）。 */
    public void addToShelf(Book book) {
        if (book == null || book.url == null || book.url.isEmpty()) return;
        List<Book> list = shelf();
        for (Book b : list) if (b.url.equals(book.url)) return;
        Book light = new Book(book.url, book.name, book.author, book.cover);
        light.source = book.source;
        list.add(0, light);
        saveShelf(list);
    }

    /** 移出书架。 */
    public void removeFromShelf(String url) {
        List<Book> list = shelf();
        list.removeIf(b -> b.url.equals(url));
        saveShelf(list);
    }

    private void saveShelf(List<Book> list) {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Book b : list) {
                JSONObject o = new JSONObject();
                o.put("url", b.url);
                o.put("name", b.name);
                o.put("author", b.author);
                o.put("cover", b.cover == null ? "" : b.cover);
                o.put("source", b.source == null ? "" : b.source);
                arr.put(o);
            }
            prefs.edit().putString(KEY_SHELF, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "write shelf failed", e);
        }
    }

    // ------------------------------------------------------------ 阅读进度

    /** 读取某书进度；无记录返回 null。 */
    public Progress progress(String bookUrl) {
        if (prefs == null || bookUrl == null) return null;
        try {
            JSONObject map = new JSONObject(prefs.getString(KEY_PROGRESS, "{}"));
            JSONObject o = map.optJSONObject(bookUrl);
            if (o == null) return null;
            int chapter = o.optInt("chapter", -1);
            if (chapter < 0) return null;
            float percent = (float) o.optDouble("percent", 0.0);
            return new Progress(chapter, percent);
        } catch (Exception e) {
            return null;
        }
    }

    /** 记录进度（percent 0~1，越界自动夹取）。 */
    public void saveProgress(String bookUrl, int chapter, float percent) {
        if (prefs == null || bookUrl == null || bookUrl.isEmpty() || chapter < 0) return;
        try {
            JSONObject map = new JSONObject(prefs.getString(KEY_PROGRESS, "{}"));
            JSONObject o = new JSONObject();
            o.put("chapter", chapter);
            o.put("percent", Math.max(0f, Math.min(1f, percent)));
            map.put(bookUrl, o);
            prefs.edit().putString(KEY_PROGRESS, map.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "write progress failed", e);
        }
    }

    // ------------------------------------------------------------ 书签

    /** 某书的全部书签（按添加时间正序）。 */
    public List<Bookmark> bookmarks(String bookUrl) {
        List<Bookmark> out = new ArrayList<>();
        if (prefs == null || bookUrl == null) return out;
        try {
            JSONObject map = new JSONObject(prefs.getString(KEY_BOOKMARKS, "{}"));
            JSONArray arr = map.optJSONArray(bookUrl);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Bookmark(o.optInt("chapter", 0), o.optString("name"), (float) o.optDouble("percent", 0.0), o.optLong("time", 0)));
            }
        } catch (Exception e) {
            Log.w(TAG, "read bookmarks failed", e);
        }
        return out;
    }

    /** 添加书签（同一本书同一章节去重置顶）。 */
    public void addBookmark(String bookUrl, int chapter, String chapterName, float percent) {
        if (prefs == null || bookUrl == null || bookUrl.isEmpty() || chapter < 0) return;
        try {
            JSONObject map = new JSONObject(prefs.getString(KEY_BOOKMARKS, "{}"));
            JSONArray arr = map.optJSONArray(bookUrl);
            if (arr == null) arr = new JSONArray();
            // 同章节已存在则替换（保留最新位置）
            JSONArray updated = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optInt("chapter") == chapter) {
                    o.put("name", chapterName == null ? "" : chapterName);
                    o.put("percent", Math.max(0f, Math.min(1f, percent)));
                    o.put("time", System.currentTimeMillis());
                    replaced = true;
                }
                if (o != null) updated.put(o);
            }
            if (!replaced) {
                JSONObject n = new JSONObject();
                n.put("chapter", chapter);
                n.put("name", chapterName == null ? "" : chapterName);
                n.put("percent", Math.max(0f, Math.min(1f, percent)));
                n.put("time", System.currentTimeMillis());
                updated.put(n);
            }
            map.put(bookUrl, updated);
            prefs.edit().putString(KEY_BOOKMARKS, map.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "add bookmark failed", e);
        }
    }

    /** 删除某书的一个书签（按章节号）。 */
    public void removeBookmark(String bookUrl, int chapter) {
        if (prefs == null || bookUrl == null) return;
        try {
            JSONObject map = new JSONObject(prefs.getString(KEY_BOOKMARKS, "{}"));
            JSONArray arr = map.optJSONArray(bookUrl);
            if (arr == null) return;
            JSONArray updated = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optInt("chapter") != chapter) updated.put(o);
            }
            map.put(bookUrl, updated);
            prefs.edit().putString(KEY_BOOKMARKS, map.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "remove bookmark failed", e);
        }
    }
}