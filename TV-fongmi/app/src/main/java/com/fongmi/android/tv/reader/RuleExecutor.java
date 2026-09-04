package com.fongmi.android.tv.reader;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.NativeJSON;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.json.JSONArray;
import org.json.JSONObject;

/** 书源规则求值（legado 常用语法子集）。 */
public final class RuleExecutor {

    private RuleExecutor() {
    }

    /** 列表选择：bookList / chapterList 用的 CSS（首段），返回元素集。 */
    public static Elements select(String rule, Element ctx) {
        String sel = firstSelector(rule);
        if (sel == null || sel.isEmpty()) return new Elements();
        try {
            return ctx.select(sel);
        } catch (Exception e) {
            return new Elements();
        }
    }

    /** 字段求值：完整指令链 → 文本/属性；规则为空返回空串。 */
    public static String compute(String rule, Element ctx, String baseUrl, String bookJson) {
        if (rule == null || rule.isEmpty()) return "";
        String r = rule.trim();
        if (r.startsWith("@js:")) return jsEval(r.substring(4), bookJson);
        if (r.startsWith("@json:")) return jsonPath(r.substring(6), bookJson);
        return nodeCompute(r, ctx, baseUrl);
    }

    /** 节点指令链求值：@css:sel / 裸 css / @css:a@attr:href / @css:a@text / @this@attr:href 等。 */
    private static String nodeCompute(String rule, Element ctx, String baseUrl) {
        try {
            // 拆指令（@css: 含冒号，直接按 @ 切开保留段内内容）
            String[] steps = rule.split("@");
            Elements nodes = new Elements();
            String sel = steps[0].trim();
            if (sel.equals("this")) {
                nodes.add(ctx);
            } else {
                if (sel.startsWith("css:")) sel = sel.substring(4).trim();
                if (sel.isEmpty()) return "";
                Elements s = ctx.select(sel);
                if (s.isEmpty()) return "";
                nodes = s;
            }
            // 后续步骤（dim 每步对节点集统一处理，取首个做属性/文本）
            for (int i = 1; i < steps.length; i++) {
                String raw = steps[i].trim();
                if (raw.isEmpty()) continue;
                if (raw.equals("this")) continue;
                if (raw.startsWith("css:")) {
                    String s = raw.substring(4).trim();
                    Elements next = new Elements();
                    for (Element e : nodes) next.addAll(e.select(s));
                    nodes = next.isEmpty() ? nodes : next;
                } else if (raw.startsWith("attr:")) {
                    String name = raw.substring(5).trim();
                    String attrName = name;
                    boolean abs = attrName.startsWith("abs:");
                    if (abs) attrName = attrName.substring(4);
                    String v = nodes.isEmpty() ? "" : (abs ? nodes.first().absUrl(attrName) : nodes.first().attr(attrName));
                    if (v.isEmpty() && !nodes.isEmpty()) v = nodes.first().attr(attrName);
                    return absolutize(v, baseUrl);
                } else if (raw.equals("text")) {
                    return nodes.isEmpty() ? "" : nodes.first().text();
                } else if (raw.equals("ownText")) {
                    return nodes.isEmpty() ? "" : nodes.first().ownText();
                } else if (raw.equals("html")) {
                    return nodes.isEmpty() ? "" : nodes.first().html();
                }
            }
            // 无终止指令 → 返回元素自身（用于绝对化章节链接场景，取 href 属性兜底）
            if (nodes.size() == 1 && !bookLike(nodes.first())) return nodes.first().ownText();
            return firstText(nodes);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean bookLike(Element e) {
        return e.tagName().equals("img") || e.tagName().equals("a");
    }

    private static String firstText(Elements nodes) {
        for (Element e : nodes) {
            String t = e.ownText();
            if (!t.isEmpty()) return t;
        }
        return nodes.isEmpty() ? "" : nodes.first().text();
    }

    /** 取 CSS 片段（规则第一段，可能带 @ 链）。 */
    private static String firstSelector(String rule) {
        if (rule == null || rule.isEmpty()) return "";
        String r = rule.trim();
        if (r.startsWith("@css:")) r = r.substring(5).trim();
        int at = r.indexOf('@');
        String sel = at >= 0 ? r.substring(0, at) : r;
        return sel.trim();
    }

    /** 相对链接绝对化；已是完整 URL 原样返回。 */
    private static String absolutize(String href, String baseUrl) {
        if (href == null || href.isEmpty()) return href == null ? "" : href;
        String h = href.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) return h;
        if (baseUrl == null || baseUrl.isEmpty()) return h;
        if (h.startsWith("//")) return "https:" + h;
        return org.jsoup.internal.StringUtil.resolve(baseUrl, h);
    }

    // ------------------------------------------------------------ @js: / @json:

    /** 简易 @js: 表达式求值（Rhino；提供 book 变量与 JSON 全局）。 */
    static String jsEval(String code, String bookJson) {
        if (code == null || code.isEmpty()) return "";
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            if (bookJson != null && !bookJson.isEmpty()) {
                try {
                    Scriptable book = NativeJSON.parse(cx, scope, bookJson, null, 1);
                    ScriptableObject.putProperty(scope, "book", book);
                } catch (Exception ignored) {
                }
            }
            Object r = cx.evaluateString(scope, code, "rule@js", 1, null);
            if (r == null) return "";
            return String.valueOf(r);
        } catch (Throwable e) {
            return "";
        } finally {
            Context.exit();
        }
    }

    /** @json: 简易 JSON 路径（$.a.b[1].c）；未命中返回空串。 */
    static String jsonPath(String path, String json) {
        Object v = jsonNode(path, json);
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return v.toString();
    }

    /** JSON 路径取节点（供列表分支复用，返回 Object）。 */
    static Object jsonNode(String path, String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            Object cur;
            String t = json.trim();
            if (t.startsWith("[")) cur = new JSONArray(t);
            else if (t.startsWith("{")) cur = new JSONObject(t);
            else return null;
            String p = (path == null ? "" : path).trim();
            if (p.startsWith("$")) p = p.substring(1);
            p = p.replace("[", ".").replace("]", "");
            String[] keys = p.split("\\.");
            for (String k : keys) {
                if (k.isEmpty()) continue;
                if (cur instanceof JSONObject) {
                    JSONObject o = (JSONObject) cur;
                    if (!o.has(k)) return null;
                    cur = o.get(k);
                } else if (cur instanceof JSONArray) {
                    JSONArray a = (JSONArray) cur;
                    int idx;
                    try {
                        idx = Integer.parseInt(k);
                    } catch (Exception e) {
                        return null;
                    }
                    if (idx < 0 || idx >= a.length()) return null;
                    cur = a.get(idx);
                } else {
                    return null;
                }
            }
            return cur;
        } catch (Exception e) {
            return null;
        }
    }

    /** 列表分支：@json: 路径解析为 JSONArray（无路径时整个响应视为数组）。 */
    static JSONArray jsonArray(String rule, String json) {
        if (rule == null || rule.isEmpty()) return new JSONArray();
        try {
            String r = rule.trim();
            Object v = null;
            if (r.startsWith("@json:")) {
                v = jsonNode(r.substring(6), json);
            } else {
                // 缺省：整体若为数组直接用
                String t = json == null ? "" : json.trim();
                if (t.startsWith("[")) v = new JSONArray(t);
            }
            if (v instanceof JSONArray) return (JSONArray) v;
            if (v instanceof JSONObject) {
                JSONArray a = new JSONArray();
                a.put(v);
                return a;
            }
            return new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** JSON 模式字段求值（item 为 JSONObject 时：@json: 路径 / @js: 表达式）。 */
    static String jsonField(String rule, String json) {
        if (rule == null || rule.isEmpty()) return "";
        String r = rule.trim();
        if (r.startsWith("@json:")) return jsonPath(r.substring(6), json);
        if (r.startsWith("@js:")) return jsEval(r.substring(4), json);
        return "";
    }

    /** Document 转 Element 便捷方法。 */
    public static Element document(org.jsoup.nodes.Document doc) {
        return doc;
    }

    public static String text(Element e) {
        return e == null ? "" : e.text();
    }
}