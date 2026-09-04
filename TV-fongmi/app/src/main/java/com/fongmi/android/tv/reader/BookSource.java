package com.fongmi.android.tv.reader;

import org.json.JSONObject;

/**
 * 书源（legado BookSource 格式，轻量子集）。
 * <pre>
 * {
 *   "bookSourceUrl": "https://www.example.com",
 *   "bookSourceName": "示例源",
 *   "searchUrl": "https://www.example.com/search?q={{key}}",
 *   "ruleSearch": { "bookList": "css", "bookName": "css", "author": "css", "bookUrl": "css@attr:href", "coverUrl": "" },
 *   "ruleBookInfo": { "intro": "css", "name": "", "author": "", "coverUrl": "", "tocUrl": "" },
 *   "ruleToc": { "chapterList": "css", "chapterName": "css@text", "chapterUrl": "css@attr:href" },
 *   "ruleContent": { "content": "css" }
 * }
 * </pre>
 * 规则语法对齐 legado 常用子集：裸 CSS / {@code @css:sel} / {@code @js:expr} /
 * {@code @attr:name} / {@code @text} / {@code @json:path}，可用 {@code @} 串联（如 {@code @css:a@attr:href}）。
 */
public final class BookSource {

    public final String url;
    public final String name;
    public final String searchUrl;
    public final RuleSet ruleSearch;
    public final RuleSet ruleBookInfo;
    public final RuleSet ruleToc;
    public final RuleSet ruleContent;
    /** 启用开关（导入默认启用）。 */
    public boolean enabled = true;

    private BookSource(String url, String name, String searchUrl,
                       RuleSet ruleSearch, RuleSet ruleBookInfo, RuleSet ruleToc, RuleSet ruleContent) {
        this.url = url == null ? "" : url;
        this.name = name == null || name.isEmpty() ? this.url : name;
        this.searchUrl = searchUrl == null ? "" : searchUrl;
        this.ruleSearch = ruleSearch;
        this.ruleBookInfo = ruleBookInfo;
        this.ruleToc = ruleToc;
        this.ruleContent = ruleContent;
    }

    /** 从 legado 书源 JSON 解析；结构不合法返回 null。 */
    public static BookSource parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String url = o.optString("bookSourceUrl");
            if (url.isEmpty()) return null;
            BookSource s = new BookSource(
                    url,
                    o.optString("bookSourceName"),
                    o.optString("searchUrl"),
                    RuleSet.of(o.optJSONObject("ruleSearch")),
                    RuleSet.of(o.optJSONObject("ruleBookInfo")),
                    RuleSet.of(o.optJSONObject("ruleToc")),
                    RuleSet.of(o.optJSONObject("ruleContent"))
            );
            return (s.ruleSearch == null && s.ruleToc == null && s.ruleContent == null) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 搜索模板里替换关键字（{{key}}），URL 编码。 */
    public String fillSearchUrl(String keyword) {
        String u = searchUrl.isEmpty() ? (url + "/search?q={{key}}") : searchUrl;
        return u.replace("{{key}}", java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static final class RuleSet {
        public final String init;
        public final String bookList;
        public final String bookName;
        /** ruleBookInfo 的书籍名规则（与 search 的 bookName 分离）。 */
        public final String name;
        public final String author;
        public final String coverUrl;
        public final String intro;
        public final String bookUrl;
        public final String tocUrl;
        public final String chapterList;
        public final String chapterName;
        public final String chapterUrl;
        public final String content;

        RuleSet(String init, String bookList, String bookName, String name, String author, String coverUrl,
                String intro, String bookUrl, String tocUrl,
                String chapterList, String chapterName, String chapterUrl, String content) {
            this.init = init;
            this.bookList = bookList;
            this.bookName = bookName;
            this.name = name;
            this.author = author;
            this.coverUrl = coverUrl;
            this.intro = intro;
            this.bookUrl = bookUrl;
            this.tocUrl = tocUrl;
            this.chapterList = chapterList;
            this.chapterName = chapterName;
            this.chapterUrl = chapterUrl;
            this.content = content;
        }

        static RuleSet of(JSONObject o) {
            if (o == null) return null;
            return new RuleSet(
                    o.optString("init"),
                    o.optString("bookList"),
                    o.optString("bookName"),
                    o.optString("name"),
                    o.optString("author"),
                    o.optString("coverUrl"),
                    o.optString("intro"),
                    o.optString("bookUrl"),
                    o.optString("tocUrl"),
                    o.optString("chapterList"),
                    o.optString("chapterName"),
                    o.optString("chapterUrl"),
                    o.optString("content")
            );
        }
    }
}