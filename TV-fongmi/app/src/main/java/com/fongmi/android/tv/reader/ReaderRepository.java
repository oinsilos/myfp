package com.fongmi.android.tv.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 书源阅读仓库（轻量 legado 书源）：
 * <ul>
 *   <li>书源管理：内置 + 粘贴 JSON/URL 导入，SharedPreferences 持久化</li>
 *   <li>搜索：全部启用书源并行抓取，HTML(CSS 规则) / JSON(@json:) 两种响应模式</li>
 *   <li>详情 / 目录 / 正文：按 ruleBookInfo / ruleToc / ruleContent 规则求值</li>
 * </ul>
 */
public final class ReaderRepository {

    private static final String TAG = "ReaderRepository";
    private static final String PREFS = "reader_sources";
    private static final String KEY_SOURCES = "sources";
    private static final String BUILTIN = "reader/builtin_sources.json";
    /** 单源搜索超时：源站点无响应/卡死时按空结果结束，避免 allOf 无限等待（书源加载转圈的根因）。 */
    private static final long SOURCE_TIMEOUT_MS = 12_000L;
    /** 整次搜索整体超时：兜底之上的兜底，保证 UI 永远能拿到结论。 */
    private static final long SEARCH_TIMEOUT_MS = 25_000L;
    /** 详情/目录单页超时：防止卡死任务长期占用 io 线程池（池占满后新搜索排队=转圈）。 */
    private static final long PAGE_TIMEOUT_MS = 20_000L;
    /** 正文单页超时：公版书全文页（如 Gutenberg txt，可达 1~2MB）拉取更慢，单独放宽。 */
    private static final long CHAPTER_TIMEOUT_MS = 30_000L;
    /** 超时护栏专用守护线程池（仅负责 complete，不执行请求，避免占用业务 io 线程）。 */
    private static final ExecutorService timeoutPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "reader-timeout");
        t.setDaemon(true);
        return t;
    });

    private static volatile ReaderRepository instance;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private volatile Context context;
    private volatile boolean initialised;
    private volatile List<BookSource> sources = Collections.emptyList();

    public static ReaderRepository get() {
        if (instance == null) {
            synchronized (ReaderRepository.class) {
                if (instance == null) instance = new ReaderRepository();
            }
        }
        return instance;
    }

    /** 绑定 Context + 加载书源（幂等）。 */
    public synchronized void init(Context context) {
        if (initialised) return;
        initialised = true;
        this.context = context.getApplicationContext();
        reload();
    }

    /** 全部书源（含禁用的）。 */
    public List<BookSource> sources() {
        return sources;
    }

    /** 启用的书源。 */
    public List<BookSource> enabledSources() {
        List<BookSource> out = new ArrayList<>();
        for (BookSource s : sources) if (s.enabled) out.add(s);
        return out;
    }

    /** 书源列表重载（从持久化）。 */
    public synchronized void reload() {
        List<BookSource> list = new ArrayList<>();
        try {
            String raw = prefs().getString(KEY_SOURCES, "");
            if (raw.isEmpty()) raw = builtinJson();
            JSONArray arr = new JSONArray(raw.length() == 0 ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.getString(i);
                BookSource s = BookSource.parse(item);
                if (s != null) {
                    try {
                        s.enabled = new JSONObject(item).optBoolean("enabled", true);
                    } catch (Exception ignored) {
                    }
                    list.add(s);
                }
            }
        } catch (Exception e) {
            try {
                String raw = builtinJson();
                JSONArray arr = new JSONArray(raw.length() == 0 ? "[]" : raw);
                for (int i = 0; i < arr.length(); i++) {
                    BookSource s = BookSource.parse(arr.getString(i));
                    if (s != null) list.add(s);
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            BookSource demo = demoSource();
            if (demo != null) list.add(demo);
        }
        sources = Collections.unmodifiableList(list);
    }

    private String builtinJson() {
        try {
            String s = Asset.read(BUILTIN);
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    /** 内置兜底源（assets 缺失时保证可搜索/测试闭环），与 builtin_sources.json 保持一致。 */
    private BookSource demoSource() {
        return BookSource.parse("{\n"
                + "  \"bookSourceUrl\": \"https://www.gutenberg.org\",\n"
                + "  \"bookSourceName\": \"Project Gutenberg(内置)\",\n"
                + "  \"searchUrl\": \"https://www.gutenberg.org/ebooks/search/?query={{key}}\",\n"
                + "  \"ruleSearch\": {\"bookList\": \"li.booklink\", \"bookName\": \"span.title@text\", \"author\": \"span.subtitle@text\", \"bookUrl\": \"a.link@attr:href\", \"coverUrl\": \"img.cover-thumb@attr:src\"},\n"
                + "  \"ruleBookInfo\": {\"intro\": \"@css:meta[name=\\\"description\\\"]@attr:content\"},\n"
                + "  \"ruleToc\": {\"chapterList\": \"a[href$='.txt.utf-8']\", \"chapterName\": \"@js:'全文'\", \"chapterUrl\": \"@this@attr:href\"},\n"
                + "  \"ruleContent\": {\"content\": \"@plain\"}\n"
                + "}");
    }

    /** 导入书源：接收 JSON 文本 / 数组文本 / 指向 JS 或 JSON 的 URL。返回成功数量。 */
    public CompletableFuture<Integer> importSource(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return CompletableFuture.completedFuture(0);
        if (t.startsWith("http")) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String code = OkHttp.string(t);
                    if (code == null || code.isEmpty()) return 0;
                    return importText(code);
                } catch (Exception e) {
                    return 0;
                }
            }, io);
        }
        return CompletableFuture.completedFuture(importText(t));
    }

    private synchronized int importText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return 0;
        int added = 0;
        List<String> rawJson = new ArrayList<>(savedRaw());
        try {
            if (t.startsWith("[")) {
                JSONArray arr = new JSONArray(t);
                for (int i = 0; i < arr.length(); i++) {
                    String item = arr.optString(i);
                    if (BookSource.parse(item) != null && !containsUrl(rawJson, item)) {
                        rawJson.add(item);
                        added++;
                    }
                }
            } else if (BookSource.parse(t) != null && !containsUrl(rawJson, t)) {
                rawJson.add(t);
                added++;
            }
        } catch (Exception e) {
            return 0;
        }
        if (added > 0) save(rawJson);
        reload();
        return added;
    }

    private boolean containsUrl(List<String> list, String item) {
        String u = null;
        try {
            u = new JSONObject(item).optString("bookSourceUrl");
        } catch (Exception ignored) {
        }
        if (u == null || u.isEmpty()) return true;
        for (String s : list) {
            if (s.contains("\"bookSourceUrl\":\"" + u + "\"")) return true;
        }
        return false;
    }

    /** 删除书源。 */
    public synchronized void removeSource(String url) {
        List<String> raw = new ArrayList<>();
        for (String s : savedRaw()) {
            try {
                if (!url.equals(new JSONObject(s).optString("bookSourceUrl"))) raw.add(s);
            } catch (Exception ignored) {
            }
        }
        save(raw);
        reload();
    }

    /** 更新书源（编辑保存）：按 URL 覆盖原条目并保留启用开关；新 URL 视为导入。 */
    public synchronized boolean updateSource(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        BookSource parsed = BookSource.parse(json);
        if (parsed == null) return false;
        List<String> raw = new ArrayList<>(savedRaw());
        boolean hit = false;
        for (int i = 0; i < raw.size(); i++) {
            try {
                JSONObject o = new JSONObject(raw.get(i));
                if (parsed.url.equals(o.optString("bookSourceUrl"))) {
                    boolean enabled = o.optBoolean("enabled", true);
                    JSONObject edited = new JSONObject(json);
                    edited.put("enabled", enabled);
                    raw.set(i, edited.toString());
                    hit = true;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (hit) {
            save(raw);
            reload();
            return true;
        }
        int n = importText(json);
        return n > 0;
    }

    /** 连通性测试：请求一次搜索，只要拿到非空响应即视为可用（不同源对关键词敏感度不同，规则解析到列表仅作加分项，不作为失败依据）。 */
    public CompletableFuture<Boolean> testSource(String sourceUrl) {
        BookSource s = sourceOf(sourceUrl);
        if (s == null) return CompletableFuture.completedFuture(false);
        return testParsed(s);
    }

    /** 连通性测试（草稿 JSON，未保存）：编辑书源弹窗里用当前改动做测试。 */
    public CompletableFuture<Boolean> testSourceJson(String json) {
        BookSource s = BookSource.parse(json);
        if (s == null) return CompletableFuture.completedFuture(false);
        return testParsed(s);
    }

    private CompletableFuture<Boolean> testParsed(BookSource s) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String raw = OkHttp.string(s.fillSearchUrl("测试"));
                if (raw == null || raw.trim().isEmpty()) return false;
                // 规则能解析出列表视为强通过；解析失败不代表源不可用（词条无结果即属此类）
                String rule = s.ruleSearch == null ? "" : s.ruleSearch.bookList;
                if (rule.isEmpty()) return true;
                try {
                    JSONArray list = RuleExecutor.jsonArray(rule, raw);
                    if (list != null) return list.length() > 0 || raw.trim().length() > 200;
                } catch (Exception ignored) {
                }
                return raw.trim().length() > 100;
            } catch (Exception e) {
                return false;
            }
        }, io);
    }

    public synchronized void toggleSource(String url) {
        for (BookSource s : sources) {
            if (s.url.equals(url)) s.enabled = !s.enabled;
        }
        saveCurrent();
    }

    private List<String> savedRaw() {
        List<String> out = new ArrayList<>();
        String raw = prefs().getString(KEY_SOURCES, "");
        if (raw.isEmpty()) raw = builtinJson();
        try {
            JSONArray arr = new JSONArray(raw.length() == 0 ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private synchronized void save(List<String> list) {
        prefs().edit().putString(KEY_SOURCES, new JSONArray(list).toString()).apply();
    }

    private synchronized void saveCurrent() {
        List<String> raw = new ArrayList<>();
        for (BookSource s : sources) raw.add(toJson(s));
        save(raw);
    }

    /** 导出全部书源原始 JSON（含 enabled），供备份用。 */
    public String exportSources() {
        return prefs().getString(KEY_SOURCES, "");
    }

    /** 恢复书源（覆盖式）：接受书源数组 JSON，逐项校验后整体写入。 */
    public synchronized boolean importSources(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            try {
                arr = new JSONArray("[" + json + "]");
            } catch (Exception ex) {
                return false;
            }
        }
        if (arr.length() == 0) return false;
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                if (BookSource.parse(arr.getString(i)) == null) return false;
                raw.add(arr.getString(i));
            } catch (Exception e) {
                return false;
            }
        }
        save(raw);
        reload();
        return true;
    }

    /** 完整序列化（含规则与 enabled），供 toggle 后持久化不丢规则。 */
    private String toJson(BookSource s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"bookSourceUrl\":\"").append(esc(s.url)).append("\"");
        sb.append(",\"bookSourceName\":\"").append(esc(s.name)).append("\"");
        sb.append(",\"searchUrl\":\"").append(esc(s.searchUrl)).append("\"");
        sb.append(",\"bookSourceType\":0");
        sb.append(",\"enabled\":").append(s.enabled);
        appendSet(sb, "ruleSearch", s.ruleSearch, "bookName", "author", "bookUrl", "coverUrl", "intro", "bookList");
        appendSet(sb, "ruleBookInfo", s.ruleBookInfo, "name", "author", "intro", "coverUrl", "tocUrl");
        appendSet(sb, "ruleToc", s.ruleToc, "chapterList", "chapterName", "chapterUrl");
        appendSet(sb, "ruleContent", s.ruleContent, "content", "init");
        sb.append("}");
        return sb.toString();
    }

    private void appendSet(StringBuilder sb, String key, BookSource.RuleSet r, String... fields) {
        if (r == null) {
            sb.append(",\"").append(key).append("\":{}");
            return;
        }
        sb.append(",\"").append(key).append("\":{");
        boolean first = true;
        for (String f : fields) {
            String v = null;
            switch (f) {
                case "bookList": v = r.bookList; break;
                case "bookName": v = r.bookName; break;
                case "author": v = r.author; break;
                case "coverUrl": v = r.coverUrl; break;
                case "intro": v = r.intro; break;
                case "bookUrl": v = r.bookUrl; break;
                case "tocUrl": v = r.tocUrl; break;
                case "chapterList": v = r.chapterList; break;
                case "chapterName": v = r.chapterName; break;
                case "chapterUrl": v = r.chapterUrl; break;
                case "content": v = r.content; break;
                case "init": v = r.init; break;
                default: v = null;
            }
            if (v == null || v.isEmpty()) continue;
            if (!first) sb.append(",");
            sb.append("\"").append(f).append("\":\"").append(esc(v)).append("\"");
            first = false;
        }
        sb.append("}");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------ 网络链路

    /** 搜索（全部启用源并行，汇总结果）。任一源卡死/超时按空结果处理，整次搜索有总时限。 */
    public CompletableFuture<List<Book>> search(String keyword) {
        List<BookSource> list = enabledSources();
        if (list.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyList());
        List<CompletableFuture<List<Book>>> futures = new ArrayList<>();
        for (BookSource s : list) {
            CompletableFuture<List<Book>> f = CompletableFuture.supplyAsync(() -> searchOne(s, keyword), io);
            // 单源 12s 兜底：到点强制按空结果结束，allOf 不再被单个卡死源拖住
            withTimeout(f, SOURCE_TIMEOUT_MS, Collections.emptyList());
            futures.add(f);
        }
        CompletableFuture<List<Book>> combined = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Book> out = new ArrayList<>();
                    for (CompletableFuture<List<Book>> f : futures) {
                        try {
                            out.addAll(f.get());
                        } catch (Exception ignored) {
                        }
                    }
                    return out;
                });
        // 整次搜索总时限：即便上面出现意外，UI 也能在 25s 内拿到结论
        withTimeout(combined, SEARCH_TIMEOUT_MS, Collections.emptyList());
        return combined;
    }

    /** 到点强制完成（底层任务继续执行，OkHttp 30s 超时兜底释放线程），保证调用方永远不被卡死。 */
    private static <T> void withTimeout(CompletableFuture<T> future, long ms, T fallback) {
        timeoutPool.execute(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                return;
            }
            future.complete(fallback);
        });
    }

    private List<Book> searchOne(BookSource s, String keyword) {
        List<Book> out = new ArrayList<>();
        if (s.ruleSearch == null) return out;
        try {
            String raw = OkHttp.string(s.fillSearchUrl(keyword));
            if (raw == null || raw.isEmpty()) return out;
            String t = raw.trim();
            if (t.startsWith("{") || t.startsWith("[")) {
                out.addAll(searchJson(s, raw));
            } else {
                out.addAll(searchHtml(s, raw));
            }
        } catch (Exception e) {
            throw new CompletionException(e);
        }
        return out;
    }

    private List<Book> searchHtml(BookSource s, String raw) {
        List<Book> out = new ArrayList<>();
        Document doc = Jsoup.parse(raw, s.url);
        Elements items = RuleExecutor.select(s.ruleSearch.bookList, doc);
        for (Element item : items) {
            String name = RuleExecutor.compute(s.ruleSearch.bookName, item, s.url, null);
            String bookUrl = RuleExecutor.compute(s.ruleSearch.bookUrl, item, s.url, null);
            String author = RuleExecutor.compute(s.ruleSearch.author, item, s.url, null);
            String cover = RuleExecutor.compute(s.ruleSearch.coverUrl, item, s.url, null);
            if (name.isEmpty() || bookUrl.isEmpty()) continue;
            Book b = new Book(bookUrl, name, author, cover);
            b.intro = RuleExecutor.compute(s.ruleSearch.intro, item, s.url, null);
            b.source = s.url;
            out.add(b);
        }
        return out;
    }

    private List<Book> searchJson(BookSource s, String raw) {
        List<Book> out = new ArrayList<>();
        JSONArray list = RuleExecutor.jsonArray(s.ruleSearch.bookList, raw);
        for (int i = 0; i < list.length(); i++) {
            String item = list.optJSONObject(i) == null ? list.optString(i) : list.optJSONObject(i).toString();
            String name = RuleExecutor.jsonField(s.ruleSearch.bookName, item);
            String bookUrl = RuleExecutor.jsonField(s.ruleSearch.bookUrl, item);
            if (name.isEmpty() || bookUrl.isEmpty()) continue;
            Book b = new Book(bookUrl, name,
                    RuleExecutor.jsonField(s.ruleSearch.author, item),
                    RuleExecutor.jsonField(s.ruleSearch.coverUrl, item));
            b.source = s.url;
            out.add(b);
        }
        return out;
    }

    /** 详情（简介/封面等）：攻书详情页按 ruleBookInfo 求值。超时/失败原样返回。 */
    public CompletableFuture<Book> detail(Book book) {
        if (book.url == null || book.url.isEmpty()) return CompletableFuture.completedFuture(book);
        CompletableFuture<Book> f = CompletableFuture.supplyAsync(() -> {
            BookSource s = sourceOf(book.source);
            if (s == null || s.ruleBookInfo == null) return book;
            try {
                String raw = OkHttp.string(book.url);
                if (raw == null || raw.isEmpty()) return book;
                Document doc = Jsoup.parse(raw, book.url);
                BookSource.RuleSet r = s.ruleBookInfo;
                String intro = RuleExecutor.compute(r.intro, doc, book.url, null);
                if (!intro.isEmpty() && intro.length() > 20) book.intro = intro;
                String cover = RuleExecutor.compute(r.coverUrl, doc, book.url, null);
                if (!cover.isEmpty()) book.cover = cover;
                String name = RuleExecutor.compute(r.name, doc, book.url, null);
                if (!name.isEmpty()) book.name = name;
                String author = RuleExecutor.compute(r.author, doc, book.url, null);
                if (!author.isEmpty()) book.author = author;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
            return book;
        }, io);
        withTimeout(f, PAGE_TIMEOUT_MS, book);
        return f;
    }

    /** 目录：ruleToc 章节列表（URL 与名称）。超时以单章兜底返回。 */
    public CompletableFuture<Book> toc(Book book) {
        if (book.url == null || book.url.isEmpty()) return CompletableFuture.completedFuture(book);
        CompletableFuture<Book> f = CompletableFuture.supplyAsync(() -> {
            BookSource s = sourceOf(book.source);
            if (s == null || s.ruleToc == null) return book;
            try {
                String raw = OkHttp.string(book.url);
                if (raw == null || raw.isEmpty()) return book;
                Document doc = Jsoup.parse(raw, book.url);
                Elements items = RuleExecutor.select(s.ruleToc.chapterList, doc);
                book.chapters.clear();
                for (Element item : items) {
                    String name = RuleExecutor.compute(s.ruleToc.chapterName, item, book.url, null);
                    String url = RuleExecutor.compute(s.ruleToc.chapterUrl, item, book.url, null);
                    if (url.isEmpty()) continue;
                    if (name.isEmpty()) name = "第" + (book.chapters.size() + 1) + "章";
                    book.chapters.add(new Book.Chapter(name, url));
                }
                // 无章目录规则/无章节（单章书，如维基文库长文页）：整书为一章
                if (book.chapters.isEmpty()) {
                    book.chapters.add(new Book.Chapter(book.name.isEmpty() ? "全文" : book.name, book.url));
                }
            } catch (Exception e) {
                // 目录解析失败：兜底单章（至少能进阅读页）
                if (book.chapters == null || book.chapters.isEmpty()) {
                    book.chapters.add(new Book.Chapter(book.name.isEmpty() ? "全文" : book.name, book.url));
                } else {
                    throw new CompletionException(e);
                }
            }
            return book;
        }, io);
        withTimeout(f, PAGE_TIMEOUT_MS, book);
        return f;
    }

    /** 正文：ruleContent.content 求值（HTML 片段，WebView 渲染），失败/空抛错供 UI 提示。超时按空返回。 */
    public CompletableFuture<String> chapter(String chapterUrl, String sourceUrl) {
        if (chapterUrl == null || chapterUrl.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            BookSource s = sourceOf(sourceUrl);
            try {
                String raw = OkHttp.string(chapterUrl);
                if (raw == null || raw.isEmpty()) return null;
                String contentRule = (s != null && s.ruleContent != null) ? s.ruleContent.content : "";
                // @plain：整页按纯文本处理（如 Gutenberg txt），保留换行、剥离项目头尾声明
                if ("@plain".equals(contentRule != null ? contentRule.trim() : "")) {
                    String body = stripBoilerplate(raw);
                    if (body.isEmpty()) return null;
                    return TextUtils.htmlEncode(body);
                }
                Document doc = Jsoup.parse(raw, chapterUrl);
                String content = "";
                if (!TextUtils.isEmpty(contentRule)) {
                    Elements nodes = RuleExecutor.select(contentRule, doc);
                    if (!nodes.isEmpty()) {
                        Element first = nodes.first();
                        // 规则带显式 @text / @html 等指令链时走完整求值；纯 CSS 取内层 HTML（保留段落/图片）
                        if (contentRule.contains("@")) {
                            content = RuleExecutor.compute(contentRule, first, chapterUrl, null);
                        } else {
                            content = first.html();
                        }
                    }
                } else {
                    content = doc.body() == null ? raw : doc.body().html();
                }
                // 清理脚本/样式注入（防页面脚本干扰阅读）
                content = content.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                        .replaceAll("(?is)<style[^>]*>.*?</style>", "");
                return content.isEmpty() ? null : content;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, io);
        withTimeout(f, CHAPTER_TIMEOUT_MS, null);
        return f;
    }

    /** 剥离如 Gutenberg txt 这类公版文本页头尾的官方声明（START/END OF THE PROJECT GUTENBERG EBOOK）。 */
    private static String stripBoilerplate(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String startCue = "*** START OF THE PROJECT GUTENBERG EBOOK";
        String endCue = "*** END OF THE PROJECT GUTENBERG EBOOK";
        int s = raw.indexOf(startCue);
        int e = raw.indexOf(endCue);
        if (s < 0 && e < 0) return raw;
        int from = s < 0 ? 0 : s + startCue.length();
        int to = e < 0 ? raw.length() : e;
        String body = raw.substring(from, to);
        body = body.replaceAll("(?m)^\\*{3}\\s*(START|END) OF THE PROJECT GUTENBERG EBOOK.*$", "");
        return body.trim();
    }

    private BookSource sourceOf(String url) {
        if (url == null || url.isEmpty()) return null;
        for (BookSource s : sources) if (s.url.equals(url)) return s;
        return null;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}