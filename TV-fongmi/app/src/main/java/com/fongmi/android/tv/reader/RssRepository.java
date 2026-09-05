package com.fongmi.android.tv.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RSS 阅读器数据层：订阅源管理 + 拉取解析 + 本地缓存兜底。
 * <ul>
 *   <li>源：SharedPreferences JSON 持久化（名称 + URL + 开关），内置一个国内稳定示例源</li>
 *   <li>解析：系统 XmlPullParser（不引入第三方依赖），item 的 title/link/pubDate/description</li>
 *   <li>缓存：filesDir/rss_cache/&lt;md5(url)&gt;.json，断网时用缓存条目兜底</li>
 *   <li>正文：优先条目 description（已含全文/摘要则直接用）；否则后台拉链接剥正文</li>
 * </ul>
 */
public final class RssRepository {

    private static final String TAG = "RssRepository";
    private static final String PREFS = "reader_rss";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_READ = "read";
    private static final int BODY_MAX = 16_000;

    private static volatile RssRepository instance;

    private SharedPreferences prefs;
    private File cacheRoot;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rss-io");
        t.setDaemon(true);
        return t;
    });

    /** 订阅源。 */
    public static final class RssSource {
        public final String name;
        public final String url;
        public final boolean enabled;

        RssSource(String name, String url, boolean enabled) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.enabled = enabled;
        }
    }

    /** 文章条目。 */
    public static final class RssArticle {
        public final String title;
        public final String link;
        public final String pubDate;
        public final String desc;

        public RssArticle(String title, String link, String pubDate, String desc) {
            this.title = title == null ? "" : title;
            this.link = link == null ? "" : link;
            this.pubDate = pubDate == null ? "" : pubDate;
            this.desc = desc == null ? "" : desc;
        }
    }

    /** 收藏条目：文章 + 来源信息 + 收藏时间。 */
    public static final class RssFav {
        public final String title;
        public final String link;
        public final String pubDate;
        public final String desc;
        public final String source;
        public final String sourceUrl;
        public final long favTime;

        RssFav(String title, String link, String pubDate, String desc, String source, String sourceUrl, long favTime) {
            this.title = title == null ? "" : title;
            this.link = link == null ? "" : link;
            this.pubDate = pubDate == null ? "" : pubDate;
            this.desc = desc == null ? "" : desc;
            this.source = source == null ? "" : source;
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
            this.favTime = favTime;
        }
    }

    private RssRepository() {
    }

    public static RssRepository get() {
        if (instance == null) {
            synchronized (RssRepository.class) {
                if (instance == null) instance = new RssRepository();
            }
        }
        return instance;
    }

    public synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cacheRoot = new File(context.getFilesDir(), "rss_cache");
        if (!prefs.contains(KEY_SOURCES)) {
            // 内置示例源：用户可删/自建
            saveSources(new ArrayList<RssSource>() {{
                add(new RssSource("IT之家", "http://www.ithome.com/rss/", true));
            }});
        }
    }

    // ------------------------------------------------------------ 源管理

    public List<RssSource> sources() {
        if (prefs == null) return new ArrayList<>();
        return parseSources(prefs.getString(KEY_SOURCES, "[]"));
    }

    public void addSource(String name, String url) {
        List<RssSource> list = sources();
        String n = name == null ? "" : name.trim();
        String u = url == null ? "" : url.trim();
        if (n.isEmpty() || u.isEmpty()) return;
        for (RssSource s : list) if (s.url.equals(u)) return;
        list.add(new RssSource(n, u, true));
        saveSources(list);
    }

    public void toggleSource(String url) {
        List<RssSource> list = sources();
        if (list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            RssSource s = list.get(i);
            if (s.url.equals(url)) {
                list.set(i, new RssSource(s.name, s.url, !s.enabled));
            }
        }
        saveSources(list);
    }

    public void removeSource(String url) {
        List<RssSource> list = sources();
        list.removeIf(s -> s.url.equals(url));
        saveSources(list);
        File f = cacheFile(url);
        if (f != null && f.exists()) f.delete();
    }

    private List<RssSource> parseSources(String raw) {
        List<RssSource> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new RssSource(o.optString("name"), o.optString("url"), o.optBoolean("enabled", true)));
            }
        } catch (Exception e) {
            Log.w(TAG, "parse sources failed", e);
        }
        return out;
    }

    private void saveSources(List<RssSource> list) {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (RssSource s : list) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("enabled", s.enabled);
                arr.put(o);
            }
            prefs.edit().putString(KEY_SOURCES, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save sources failed", e);
        }
    }

    /** 导出全部订阅源 JSON（含 enabled），供备份用。 */
    public String exportSources() {
        return prefs == null ? "[]" : prefs.getString(KEY_SOURCES, "[]");
    }

    /** 恢复订阅源（覆盖式）：接受订阅源数组 JSON。 */
    public synchronized boolean importSources(String json) {
        if (prefs == null || json == null || json.trim().isEmpty()) return false;
        try {
            saveSources(parseSources(json));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 收藏 / 已读（跨源汇总，持久化）

    /** 收藏列表（按收藏时间倒序）。 */
    public synchronized List<RssFav> favorites() {
        List<RssFav> out = new ArrayList<>();
        if (prefs == null) return out;
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_FAVORITES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new RssFav(o.optString("t"), o.optString("l"), o.optString("pd"),
                        o.optString("d"), o.optString("s"), o.optString("su"), o.optLong("ft", 0)));
            }
        } catch (Exception e) {
            Log.w(TAG, "parse favorites failed", e);
        }
        List<RssFav> rev = new ArrayList<>();
        for (int i = out.size() - 1; i >= 0; i--) rev.add(out.get(i)); // 存储倒序，读取还原
        return rev;
    }

    /** 本文是否已收藏。 */
    public synchronized boolean isFavorite(String link) {
        if (prefs == null || link == null || link.isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_FAVORITES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && link.equals(o.optString("l"))) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "isFavorite failed", e);
        }
        return false;
    }

    /** 收藏/取消收藏；返回操作后是否已收藏。 */
    public synchronized boolean toggleFavorite(RssArticle article, String sourceUrl, String sourceName) {
        if (prefs == null || article == null || article.link.isEmpty()) return false;
        if (isFavorite(article.link)) {
            removeFavorite(article.link);
            return false;
        }
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_FAVORITES, "[]"));
            JSONObject o = new JSONObject();
            o.put("t", article.title);
            o.put("l", article.link);
            o.put("pd", article.pubDate);
            o.put("d", article.desc);
            o.put("s", sourceName == null ? "" : sourceName);
            o.put("su", sourceUrl == null ? "" : sourceUrl);
            o.put("ft", System.currentTimeMillis());
            arr.put(o);
            prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save favorite failed", e);
            return false;
        }
    }

    public synchronized boolean removeFavorite(String link) {
        if (prefs == null || link == null || link.isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_FAVORITES, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !link.equals(o.optString("l"))) next.put(o);
            }
            prefs.edit().putString(KEY_FAVORITES, next.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized int clearFavorites() {
        int n = favorites().size();
        if (prefs != null) prefs.edit().remove(KEY_FAVORITES).apply();
        return n;
    }

    /** 是否已读。 */
    public synchronized boolean isRead(String link) {
        if (prefs == null || link == null || link.isEmpty()) return false;
        String k = link.trim();
        return prefs.getString(KEY_READ, "").contains("\u0001" + k + "\u0001");
    }

    /** 标记已读（重复标记幂等）。 */
    public synchronized void markRead(String link) {
        if (prefs == null || link == null) return;
        String k = link.trim();
        if (k.isEmpty() || isRead(k)) return;
        String v = prefs.getString(KEY_READ, "");
        prefs.edit().putString(KEY_READ, v + "\u0001" + k + "\u0001").apply();
    }

    /** 批量同步已读状态（登出/批量打开后清空标记用）。 */
    public synchronized void clearRead() {
        if (prefs != null) prefs.edit().remove(KEY_READ).apply();
    }

    /** 导出收藏（供备份）。 */
    public String exportFavorites() {
        return prefs == null ? "[]" : prefs.getString(KEY_FAVORITES, "[]");
    }

    /** 恢复收藏（覆盖式）。 */
    public synchronized boolean importFavorites(String json) {
        if (prefs == null || json == null || json.trim().isEmpty()) return false;
        try {
            new JSONArray(json);
            prefs.edit().putString(KEY_FAVORITES, json).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 拉取 / 解析 / 缓存

    /** 拉取某源条目；网络失败时用本地缓存兜底。 */
    public CompletableFuture<List<RssArticle>> refresh(String url) {
        return CompletableFuture.supplyAsync(() -> {
            List<RssArticle> cached = readCache(url);
            try {
                String xml = OkHttp.string(url);
                if (xml != null && !xml.isEmpty()) {
                    List<RssArticle> list = parse(xml);
                    if (!list.isEmpty()) {
                        writeCache(url, list);
                        return list;
                    }
                }
                Log.w(TAG, "rss empty result " + url);
            } catch (Throwable e) {
                Log.w(TAG, "rss fetch failed " + url, e);
            }
            return cached == null ? new ArrayList<>() : cached;
        }, io);
    }

    /** 正文：description 已含内容则直接用；否则后台拉链接剥正文文本。 */
    public CompletableFuture<String> body(RssArticle article) {
        if (article.desc != null && !article.desc.trim().isEmpty()) {
            return CompletableFuture.completedFuture(article.desc);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (article.link == null || article.link.isEmpty()) return "";
            try {
                String raw = OkHttp.string(article.link);
                if (raw == null || raw.isEmpty()) return "";
                String text = org.jsoup.Jsoup.parse(raw).body().text();
                if (text == null) return "";
                String trimmed = text.trim();
                return trimmed.length() > BODY_MAX ? trimmed.substring(0, BODY_MAX) : trimmed;
            } catch (Throwable e) {
                Log.w(TAG, "rss body failed " + article.link, e);
                return "";
            }
        }, io);
    }

    /** 解析 RSS2.0/Atom 常见结构：取 item 下的 title/link/pubDate/description。 */
    private List<RssArticle> parse(String xml) throws Exception {
        List<RssArticle> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(xml));
        boolean inItem = false;
        String curTag = "";
        String title = "", link = "", pub = "", desc = "";
        int evt;
        while ((evt = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (evt == XmlPullParser.START_TAG) {
                curTag = p.getName() == null ? "" : p.getName();
                if ("item".equals(curTag)) {
                    inItem = true;
                    title = link = pub = desc = "";
                } else if ("entry".equals(curTag)) {
                    inItem = true;
                    title = link = pub = desc = "";
                }
            } else if (evt == XmlPullParser.TEXT && inItem && !curTag.isEmpty()) {
                String text = p.getText();
                if (text == null) continue;
                String v = text.trim();
                if (v.isEmpty()) continue;
                if ("title".equals(curTag)) title += v;
                else if ("link".equals(curTag)) link += v;
                else if ("pubDate".equals(curTag) || "published".equals(curTag) || "updated".equals(curTag)) pub += v;
                else if ("description".equals(curTag) || "summary".equals(curTag) || "content".equals(curTag)) desc += v;
            } else if (evt == XmlPullParser.END_TAG) {
                String n = p.getName() == null ? "" : p.getName();
                if ("item".equals(n) || "entry".equals(n)) {
                    inItem = false;
                    if (!title.isEmpty() || !link.isEmpty()) {
                        out.add(new RssArticle(title, link.trim(), pub, desc));
                    }
                }
                curTag = "";
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 缓存

    private File cacheFile(String url) {
        if (cacheRoot == null || url == null || url.isEmpty()) return null;
        if (!cacheRoot.exists()) cacheRoot.mkdirs();
        return new File(cacheRoot, md5(url) + ".json");
    }

    private void writeCache(String url, List<RssArticle> list) {
        File f = cacheFile(url);
        if (f == null) return;
        try {
            JSONArray items = new JSONArray();
            for (RssArticle a : list) {
                JSONObject o = new JSONObject();
                o.put("t", a.title);
                o.put("l", a.link);
                o.put("pd", a.pubDate);
                o.put("d", a.desc);
                items.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("items", items);
            Files.write(f.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write rss cache failed", e);
        }
    }

    private List<RssArticle> readCache(String url) {
        try {
            File f = cacheFile(url);
            if (f == null || !f.exists()) return null;
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(raw);
            JSONArray items = root.optJSONArray("items");
            if (items == null) return null;
            List<RssArticle> out = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                out.add(new RssArticle(o.optString("t"), o.optString("l"), o.optString("pd"), o.optString("d")));
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 已缓存的条目（书架打开 RSS 书时离线重建目录与正文）。 */
    public List<RssArticle> cachedArticles(String url) {
        List<RssArticle> cached = readCache(url);
        return cached == null ? new ArrayList<>() : cached;
    }

    // ------------------------------------------------------------ OPML

    /** 导出全部订阅源为 OPML 文本。 */
    public String exportOpml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<opml version=\"2.0\">\n<head><title>TV-fongmi 阅读订阅</title></head>\n<body>\n")
                .append("<outline text=\"订阅源\" title=\"订阅源\">\n");
        for (RssSource s : sources()) {
            sb.append("<outline type=\"rss\" text=\"").append(esc(s.name))
                    .append("\" title=\"").append(esc(s.name))
                    .append("\" xmlUrl=\"").append(esc(s.url)).append("\"/>\n");
        }
        sb.append("</outline>\n</body>\n</opml>");
        return sb.toString();
    }

    /** 导入 OPML：解析所有含 xmlUrl 的 outline，批量添加源；返回新增数量。 */
    public int importOpml(String content) {
        if (content == null || content.isEmpty()) return 0;
        int added = 0;
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new StringReader(content));
            int evt;
            while ((evt = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (evt == XmlPullParser.START_TAG && "outline".equals(p.getName())) {
                    String xmlUrl = p.getAttributeValue(null, "xmlUrl");
                    if (xmlUrl != null && !xmlUrl.trim().isEmpty()) {
                        String name = p.getAttributeValue(null, "title");
                        if (name == null || name.isEmpty()) name = p.getAttributeValue(null, "text");
                        if (name == null || name.isEmpty()) name = "订阅源";
                        addSource(name, xmlUrl.trim());
                        added++;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "import opml failed", e);
            return 0;
        }
        return added;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
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
}