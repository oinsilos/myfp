package com.fongmi.android.tv.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
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
    private static final String KEY_SETTINGS = "reader_settings";

    private static volatile ReaderStore instance;

    private SharedPreferences prefs;
    private File cacheRoot;

    // ------------------------------------------------------------ 阅读设置（内存缓存，save 时落盘）

    /** 正文字号（sp）。 */
    public int fontSize = 17;
    /** 行高倍数。 */
    public float lineHeight = 1.9f;
    /** 主题：dark 深色 / sepia 暖黄 / night 夜间。 */
    public String theme = "dark";
    /** 本地 TXT 切章正则（txtTocRule，须带一个捕获整个标题的 match；默认匹配「第X章/卷/回/节」）。 */
    public String txtTocRegex = "第[0-9零一二三四五六七八九十百千万]+[章节卷回部篇集]";
    /** TTS 朗读语速（0.5~2.0）。 */
    public float ttsSpeed = 1.0f;
    /** TTS 引擎："local" 本机 TextToSpeech / "online" 在线 HTTP 朗读。 */
    public String ttsEngine = "online";
    /** 在线朗读接口模板（{text} 会被 URL 编码替换；{title} 为章节名）。 */
    public String ttsOnlineUrl = "https://dict.youdao.com/dictvoice?audio={text}&type=2";

    /** 阅读进度：章节号 + 章内首可见段落号 + 段落比例 + 最近阅读时间。 */
    public static final class Progress {
        public final int chapter;
        public final float percent;
        /** 章内首可见段落号；-1 表示旧数据（无段落位置，只能从头续）。 */
        public final int para;
        /** 最近一次保存进度的时间戳（书架按最近阅读排序用）。 */
        public final long lastRead;

        Progress(int chapter, float percent, int para, long lastRead) {
            this.chapter = chapter;
            this.percent = percent;
            this.para = para;
            this.lastRead = lastRead;
        }
    }

    /** 书签（某本书内一个位置）：章号 + 章内段落号。 */
    public static final class Bookmark {
        public final int chapter;
        public final String chapterName;
        public final float percent;
        public final long time;
        /** 章内段落号；-1 为旧数据。 */
        public final int para;

        Bookmark(int chapter, String chapterName, float percent, long time, int para) {
            this.chapter = chapter;
            this.chapterName = chapterName == null ? "" : chapterName;
            this.percent = percent;
            this.time = time;
            this.para = para;
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
        cacheRoot = new File(context.getFilesDir(), "reader_cache");
        loadSettings();
    }

    // ------------------------------------------------------------ 阅读设置

    private void loadSettings() {
        if (prefs == null) return;
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_SETTINGS, "{}"));
            int fs = o.optInt("fontSize", 17);
            if (fs >= 12 && fs <= 30) fontSize = fs;
            float lh = (float) o.optDouble("lineHeight", 1.9);
            if (lh >= 1.2f && lh <= 3.0f && !Float.isNaN(lh)) lineHeight = lh;
            String t = o.optString("theme", "dark");
            if ("dark".equals(t) || "sepia".equals(t) || "night".equals(t)) theme = t;
            String tr = o.optString("txtTocRegex", "");
            if (!tr.trim().isEmpty()) txtTocRegex = tr.trim();
            float ts = (float) o.optDouble("ttsSpeed", 1.0);
            if (ts >= 0.5f && ts <= 2.0f && !Float.isNaN(ts)) ttsSpeed = ts;
            String te = o.optString("ttsEngine", "");
            if ("local".equals(te) || "online".equals(te)) ttsEngine = te;
            String tu = o.optString("ttsOnlineUrl", "");
            if (!tu.trim().isEmpty()) ttsOnlineUrl = tu.trim();
        } catch (Exception e) {
            Log.w(TAG, "load settings failed", e);
        }
    }

    public void saveSettings() {
        if (prefs == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("fontSize", fontSize);
            o.put("lineHeight", lineHeight);
            o.put("theme", theme);
            o.put("txtTocRegex", txtTocRegex);
            o.put("ttsSpeed", ttsSpeed);
            o.put("ttsEngine", ttsEngine);
            o.put("ttsOnlineUrl", ttsOnlineUrl);
            prefs.edit().putString(KEY_SETTINGS, o.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save settings failed", e);
        }
    }

    // ------------------------------------------------------------ 章节缓存（私有目录 reader_cache/<md5(bookUrl)>/<index>.html）

    private File bookCacheDir(String bookUrl) {
        if (cacheRoot == null || bookUrl == null || bookUrl.isEmpty()) return null;
        File dir = new File(cacheRoot, md5(bookUrl));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 某章缓存文件（index 从 0 起）。 */
    public File cacheFile(String bookUrl, int index) {
        File dir = bookCacheDir(bookUrl);
        return dir == null ? null : new File(dir, index + ".html");
    }

    /** 章节名索引文件（随缓存目录一起存放，清缓存时一并清除）。 */
    private File namesFile(String bookUrl) {
        File dir = bookCacheDir(bookUrl);
        return dir == null ? null : new File(dir, "_names.json");
    }

    // ------------------------------------------------------------ 阅读统计 / 记录

    private static final String KEY_STATS = "reader_stats";

    /** 阅读统计快照：今日阅读时长(毫秒) + 最近阅读记录列表。 */
    public static final class ReadingStats {
        public long todayMs;
        public final List<Record> records = new ArrayList<>();
    }

    /** 一条阅读记录：书名 + 最后阅读时间 + 进度。 */
    public static final class Record {
        public final String url;
        public final String name;
        public final long time;
        public final String chapterName;
        public final float percent;

        Record(String url, String name, long time, String chapterName, float percent) {
            this.url = url == null ? "" : url;
            this.name = name == null ? "" : name;
            this.time = time;
            this.chapterName = chapterName == null ? "" : chapterName;
            this.percent = percent;
        }
    }

    /** 累计今日阅读时长（开机/跨天后自动归零），页面切换调用。 */
    public void tickRead(long ms, String bookUrl, String bookName) {
        if (prefs == null || ms <= 0) return;
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_STATS, "{}"));
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            if (!today.equals(o.optString("day"))) {
                o.put("day", today);
                o.put("todayMs", 0L);
            }
            o.put("todayMs", o.optLong("todayMs") + ms);
            prefs.edit().putString(KEY_STATS, o.toString()).apply();
            recordRead(bookUrl, bookName, "", 0f);
        } catch (Exception e) {
            Log.w(TAG, "tick read failed", e);
        }
    }

    /** 记录一次阅读进度（某本书记录章节+时间，供阅读记录页；同书去重置顶）。 */
    public void recordRead(String bookUrl, String bookName, String chapterName, float percent) {
        if (prefs == null || bookUrl == null || bookUrl.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_STATS, "{}"));
            JSONArray records = o.optJSONArray("records");
            if (records == null) records = new JSONArray();
            JSONObject n = new JSONObject();
            n.put("url", bookUrl);
            n.put("name", bookName == null ? "" : bookName);
            n.put("time", System.currentTimeMillis());
            n.put("chapterName", chapterName == null ? "" : chapterName);
            n.put("percent", percent);
            // 去重置顶：移除同 url 旧项
            JSONArray clean = new JSONArray();
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null) continue;
                if (bookUrl.equals(r.optString("url"))) continue;
                clean.put(r);
            }
            JSONArray finalArr = new JSONArray();
            finalArr.put(n);
            for (int i = 0; i < clean.length(); i++) finalArr.put(clean.get(i));
            while (finalArr.length() > 50) finalArr.remove(finalArr.length() - 1);
            o.put("records", finalArr);
            prefs.edit().putString(KEY_STATS, o.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "record read failed", e);
        }
    }

    /** 阅读统计快照（今日时长 + 记录列表）。 */
    public ReadingStats readingStats() {
        ReadingStats out = new ReadingStats();
        if (prefs == null) return out;
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_STATS, "{}"));
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            if (today.equals(o.optString("day"))) out.todayMs = o.optLong("todayMs");
            JSONArray records = o.optJSONArray("records");
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject r = records.optJSONObject(i);
                    if (r == null) continue;
                    out.records.add(new Record(r.optString("url"), r.optString("name"), r.optLong("time"),
                            r.optString("chapterName"), (float) r.optDouble("percent", 0.0)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "read stats failed", e);
        }
        return out;
    }

    /** 保存整本书章节名（导入本地书时一次性写入，书架重开后可显示真实章名）。 */
    public void saveChapterNames(String bookUrl, List<String> names) {
        File f = namesFile(bookUrl);
        if (f == null) return;
        try {
            JSONArray a = new JSONArray();
            for (String n : names) a.put(n == null ? "" : n);
            Files.write(f.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "save chapter names failed", e);
        }
    }

    /** 某章章节名；无记录返回空串。 */
    public String chapterName(String bookUrl, int index) {
        File f = namesFile(bookUrl);
        if (f == null || !f.exists()) return "";
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return index >= 0 && index < a.length() ? a.optString(index) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 读缓存正文；无缓存返回 null。 */
    public String cachedChapter(String bookUrl, int index) {
        File f = cacheFile(bookUrl, index);
        if (f == null || !f.exists()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入章节缓存。 */
    public boolean cacheChapter(String bookUrl, int index, String html) {
        File f = cacheFile(bookUrl, index);
        if (f == null || html == null) return false;
        try {
            Files.write(f.toPath(), html.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 已缓存章节数（0~total）。 */
    public int cachedCount(String bookUrl) {
        File dir = bookCacheDir(bookUrl);
        if (dir == null) return 0;
        File[] fs = dir.listFiles((d, name) -> name.endsWith(".html"));
        return fs == null ? 0 : fs.length;
    }

    /** 缓存条目（缓存管理页用）：一本有缓存的书 + 已缓存章节数。 */
    public static final class CachedBook {
        public final Book book;
        public final int count;

        CachedBook(Book book, int count) {
            this.book = book;
            this.count = count;
        }
    }

    /** 对照书架列出“已有章节缓存”的书（按书架顺序）。 */
    public List<CachedBook> cachedBooks() {
        List<CachedBook> out = new ArrayList<>();
        if (cacheRoot == null) return out;
        for (Book b : shelf()) {
            File d = new File(cacheRoot, md5(b.url));
            if (!d.exists()) continue;
            File[] fs = d.listFiles((x, name) -> name.endsWith(".html"));
            int n = fs == null ? 0 : fs.length;
            if (n > 0) out.add(new CachedBook(b, n));
        }
        return out;
    }

    /** 清除全部书籍缓存，返回清掉的文件数。 */
    public int clearAllCache() {
        if (cacheRoot == null || !cacheRoot.exists()) return 0;
        File[] dirs = cacheRoot.listFiles(File::isDirectory);
        int n = 0;
        if (dirs != null) {
            for (File d : dirs) {
                File[] fs = d.listFiles();
                if (fs == null) continue;
                n += fs.length;
                for (File f : fs) f.delete();
            }
        }
        return n;
    }

    /** 清空某书缓存。 */
    public void clearCache(String bookUrl) {
        File dir = bookCacheDir(bookUrl);
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
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
                b.group = o.optString("group");
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
                o.put("group", b.group == null ? "" : b.group);
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
            int para = o.optInt("para", -1);
            long lastRead = o.optLong("time", 0L);
            return new Progress(chapter, percent, para, lastRead);
        } catch (Exception e) {
            return null;
        }
    }

    /** 记录进度：章号 + 章内首可见段落号（percent 由此推导，供列表/详情页展示）。 */
    public void saveProgressPara(String bookUrl, int chapter, int para, int totalPara) {
        if (prefs == null || bookUrl == null || bookUrl.isEmpty() || chapter < 0 || para < 0) return;
        try {
            JSONObject map = new JSONObject(prefs.getString(KEY_PROGRESS, "{}"));
            JSONObject o = new JSONObject();
            o.put("chapter", chapter);
            o.put("para", para);
            o.put("percent", totalPara > 0 ? Math.max(0f, Math.min(1f, (float) para / totalPara)) : 0f);
            o.put("time", System.currentTimeMillis());
            map.put(bookUrl, o);
            prefs.edit().putString(KEY_PROGRESS, map.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "write progress failed", e);
        }
    }

    // ------------------------------------------------------------ 书架分组

    /** 全部书架分组名（含"全部"之外的实体分组），按首次出现顺序。 */
    public List<String> groupNames() {
        List<String> out = new ArrayList<>();
        for (Book b : shelf()) {
            String g = b.group == null || b.group.isEmpty() ? "默认" : b.group;
            if (!out.contains(g)) out.add(g);
        }
        return out;
    }

    /** 把书移入分组（覆盖应改动的书；group 空表示默认分组）。 */
    public void moveToGroup(String bookUrl, String group) {
        String g = group == null || group.trim().isEmpty() ? "默认" : group.trim();
        List<Book> list = shelf();
        boolean changed = false;
        for (Book b : list) {
            if (b.url.equals(bookUrl) && !g.equals(b.group)) {
                b.group = g;
                changed = true;
            }
        }
        if (changed) saveShelf(list);
    }

    /** 删除分组：组内书全部回到默认分组。 */
    public void removeGroup(String group) {
        if (group == null || group.isEmpty()) return;
        List<Book> list = shelf();
        boolean changed = false;
        for (Book b : list) {
            if (group.equals(b.group)) {
                b.group = "默认";
                changed = true;
            }
        }
        if (changed) saveShelf(list);
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
                out.add(new Bookmark(o.optInt("chapter", 0), o.optString("name"), (float) o.optDouble("percent", 0.0), o.optLong("time", 0), o.optInt("para", -1)));
            }
        } catch (Exception e) {
            Log.w(TAG, "read bookmarks failed", e);
        }
        return out;
    }

    /** 添加书签（同一本书同一章去重置顶）：章号 + 章内段落号。 */
    public void addBookmarkPara(String bookUrl, int chapter, String chapterName, int para, int totalPara) {
        if (prefs == null || bookUrl == null || bookUrl.isEmpty() || chapter < 0) return;
        float percent = totalPara > 0 ? Math.max(0f, Math.min(1f, (float) para / totalPara)) : 0f;
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
                    o.put("percent", percent);
                    o.put("para", para);
                    o.put("time", System.currentTimeMillis());
                    replaced = true;
                }
                if (o != null) updated.put(o);
            }
            if (!replaced) {
                JSONObject n = new JSONObject();
                n.put("chapter", chapter);
                n.put("name", chapterName == null ? "" : chapterName);
                n.put("percent", percent);
                n.put("para", para);
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

    // ------------------------------------------------------------ 备份 / 恢复

    /** 导出阅读库（书架/进度/书签/正文规则/阅读设置）为单一 JSON。 */
    public String exportJson() {
        if (prefs == null) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("shelf", new JSONArray(prefs.getString(KEY_SHELF, "[]")));
            o.put("progress", new JSONObject(prefs.getString(KEY_PROGRESS, "{}")));
            o.put("bookmarks", new JSONObject(prefs.getString(KEY_BOOKMARKS, "{}")));
            o.put("reader_settings", new JSONObject(prefs.getString(KEY_SETTINGS, "{}")));
            o.put("reader_stats", new JSONObject(prefs.getString(KEY_STATS, "{}")));
            return o.toString(2);
        } catch (Exception e) {
            Log.w(TAG, "export failed", e);
            return "{}";
        }
    }

    /** 恢复阅读库：覆盖书架/进度/书签/规则/设置，并重载内存。 */
    public boolean importJson(String json) {
        if (prefs == null || json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = prefs.edit();
            JSONArray shelf = o.optJSONArray("shelf");
            if (shelf != null) e.putString(KEY_SHELF, shelf.toString());
            JSONObject progress = o.optJSONObject("progress");
            if (progress != null) e.putString(KEY_PROGRESS, progress.toString());
            JSONObject bookmarks = o.optJSONObject("bookmarks");
            if (bookmarks != null) e.putString(KEY_BOOKMARKS, bookmarks.toString());
            JSONObject settings = o.optJSONObject("reader_settings");
            if (settings != null) e.putString(KEY_SETTINGS, settings.toString());
            JSONObject stats = o.optJSONObject("reader_stats");
            if (stats != null) e.putString(KEY_STATS, stats.toString());
            e.apply();
            loadSettings();
            return true;
        } catch (Exception ex) {
            Log.w(TAG, "import failed", ex);
            return false;
        }
    }
}