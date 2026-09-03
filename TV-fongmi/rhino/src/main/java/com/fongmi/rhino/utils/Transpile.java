package com.fongmi.rhino.utils;

/**
 * 轻量 ES module → CommonJS 转译 + async/await → generator 转译。
 * 仅覆盖影视源生态的受限语法子集（import/export、async function/箭头、await），
 * 不做完整语法分析。单遍字符扫描，无正则回溯，无运行期依赖（性能/体积优先）。
 */
public final class Transpile {

    private Transpile() {
    }

    public static String toCommonJs(String source) {
        return asyncToGenerator(rewriteModules(source));
    }

    // ---------------------------------------------------------------- import/export

    private static String rewriteModules(String src) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\'' || c == '"') {
                int end = scanString(src, i, c);
                out.append(src, i, end);
                i = end;
            } else if (c == '`') {
                int end = scanString(src, i, '`');
                out.append(src, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                if (end < 0) end = n;
                out.append(src, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(src, i, end);
                i = end;
            } else if (Character.isWhitespace(c)) {
                out.append(c);
                i++;
            } else {
                int kw = matchKeyword(src, i);
                if (kw == KW_IMPORT) {
                    Object[] r = rewriteImport(src, i);
                    if ((Integer) r[0] >= 0) {
                        out.append(r[1]);
                        i = (Integer) r[0];
                        continue;
                    }
                } else if (kw == KW_EXPORT) {
                    Object[] r = rewriteExport(src, i);
                    if ((Integer) r[0] >= 0) {
                        out.append(r[1]);
                        i = (Integer) r[0];
                        continue;
                    }
                }
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static final int KW_NONE = 0;
    private static final int KW_IMPORT = 1;
    private static final int KW_EXPORT = 2;

    private static int matchKeyword(String src, int i) {
        if (startsWithWord(src, i, "import")) return KW_IMPORT;
        if (startsWithWord(src, i, "export")) return KW_EXPORT;
        return KW_NONE;
    }

    private static boolean startsWithWord(String src, int i, String word) {
        int end = i + word.length();
        if (end > src.length() || !src.regionMatches(i, word, 0, word.length())) return false;
        return end >= src.length() || !isIdentChar(src.charAt(end));
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int scanString(String src, int i, char quote) {
        int n = src.length();
        for (int j = i + 1; j < n; j++) {
            char c = src.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j + 1;
            }
        }
        return n;
    }

    private static int skipWs(String src, int p) {
        while (p < src.length() && Character.isWhitespace(src.charAt(p))) p++;
        return p;
    }

    private static int skipSc(String src, int p) {
        return p < src.length() && src.charAt(p) == ';' ? p + 1 : p;
    }

    /** 读取到行尾或可结束符（; 或平衡闭合）为止。 */
    private static int statementEnd(String src, int p) {
        int n = src.length();
        int depth = 0;
        for (int j = p; j < n; j++) {
            char c = src.charAt(j);
            if (c == '\'' || c == '"') {
                j = scanString(src, j, c) - 1;
                continue;
            }
            if (c == '`') {
                j = scanString(src, j, '`') - 1;
                continue;
            }
            if (c == '/' && j + 1 < n && src.charAt(j + 1) == '/') {
                int e = src.indexOf('\n', j);
                j = e < 0 ? n : e - 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) return j + 1;
            } else if (c == ';' && depth == 0) return j + 1;
        }
        return n;
    }

    /** 找到 open 对应括号的平衡闭合位置（含闭合符之后？返回闭合符下标）。未找到返回 -1。 */
    private static int findClose(String src, int p, char open) {
        char close = open == '{' ? '}' : open == '[' ? ']' : ')';
        int depth = 0;
        int n = src.length();
        for (int j = p; j < n; j++) {
            char c = src.charAt(j);
            if (c == '\'' || c == '"') {
                j = scanString(src, j, c) - 1;
                continue;
            }
            if (c == '`') {
                j = scanString(src, j, '`') - 1;
                continue;
            }
            if (c == '/' && j + 1 < n && src.charAt(j + 1) == '/') {
                int e = src.indexOf('\n', j);
                j = e < 0 ? n : e - 1;
                continue;
            }
            if (c == '/' && j + 1 < n && src.charAt(j + 1) == '*') {
                int e = src.indexOf("*/", j + 2);
                j = e < 0 ? n : e + 1;
                continue;
            }
            if (c == open) depth++;
            else if (c == close && --depth <= 0) return j;
        }
        return -1;
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String[] readWord(String src, int p) {
        int j = p;
        while (j < src.length() && isIdentChar(src.charAt(j))) j++;
        return new String[]{src.substring(p, j), String.valueOf(j)};
    }

    private static String[] readSpec(String src, int p) {
        if (p >= src.length()) return new String[]{"", String.valueOf(p)};
        char q = src.charAt(p);
        if (q == '\'' || q == '"') {
            int end = scanString(src, p, q);
            return new String[]{src.substring(p + 1, end - 1), String.valueOf(end)};
        }
        int j = p;
        while (j < src.length() && !Character.isWhitespace(src.charAt(j)) && src.charAt(j) != ';' && src.charAt(j) != ',') j++;
        return new String[]{src.substring(p, j), String.valueOf(j)};
    }

    /** 从 import 关键字开始改写，返回 {nextIndex, code}，失败返回 {-1,null}。 */
    private static Object[] rewriteImport(String src, int i) {
        int n = src.length();
        int p = skipWs(src, i + 6);
        StringBuilder out = new StringBuilder();
        String spec = null;
        if (p < n && src.charAt(p) == '{') {
            int close = findClose(src, p, '{');
            if (close < 0) return new Object[]{-1, null};
            String binds = src.substring(p + 1, close);
            StringBuilder destr = new StringBuilder();
            for (String part : destrParts(binds)) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                int as = indexOfWord(t, "as");
                String from = as > 0 ? t.substring(0, as).trim() : t;
                String to = as > 0 ? t.substring(as + 2).trim() : t;
                if (destr.length() > 0) destr.append(',');
                destr.append(from).append(": ").append(to);
            }
            int after = skipWs(src, close + 1);
            if (after < n && src.charAt(after) == ',') { // import X, { a } from ...
                after = skipWs(src, after + 1);
                if (after < n && src.charAt(after) == '*') {
                    String[] as = readWord(src, skipWs(src, after + 1));
                    if ("as".equals(as[0])) { /* 跳过 * as 形式 */ }
                } else while (after < n && src.charAt(after) != ';' && src.charAt(after) != '}') after = skipWs(src, after + 1);
                if (after < n && src.charAt(after) == '}') after = skipWs(src, after + 1);
                p = after;
            } else {
                p = after;
            }
            if (p < n && src.regionMatches(p, "from", 0, 4)) {
                String[] s = readSpec(src, skipWs(src, p + 4));
                spec = s[0];
                p = Integer.parseInt(s[1]);
            }
            if (spec != null) {
                out.append("const ").append(destr.length() > 0 ? "{" + destr + "}" : "{}")
                        .append(" = __require(").append(quote(spec)).append(");\n");
            }
        } else if (p < n && src.charAt(p) == '*') {
            String[] as = readWord(src, skipWs(src, p + 1));
            if ("as".equals(as[0])) {
                String[] alias = readWord(src, skipWs(src, Integer.parseInt(as[1])));
                int after = skipWs(src, Integer.parseInt(alias[1]));
                if (after < n && src.regionMatches(after, "from", 0, 4)) {
                    String[] s = readSpec(src, skipWs(src, after + 4));
                    out.append("const ").append(alias[0]).append(" = __require(").append(quote(s[0])).append(");\n");
                    p = Integer.parseInt(s[1]);
                }
            }
        } else if (p < n && isIdentStart(src.charAt(p))) {
            String[] w = readWord(src, p);
            int after = skipWs(src, Integer.parseInt(w[1]));
            if (after < n && src.charAt(after) == ',') { // import X, { a } from
                after = skipWs(src, after + 1);
                if (after < n && src.charAt(after) == '*') {
                    // import X, * as Y from
                } else {
                    int close = findClose(src, after, '{');
                    if (close >= 0) after = skipWs(src, close + 1);
                }
                p = after;
            } else {
                p = after;
            }
            if (p < n && src.regionMatches(p, "from", 0, 4)) {
                String[] s = readSpec(src, skipWs(src, p + 4));
                out.append("const ").append(w[0]).append(" = __require(").append(quote(s[0])).append(");\n");
                p = Integer.parseInt(s[1]);
            }
        } else if (p < n && (src.charAt(p) == '\'' || src.charAt(p) == '"')) {
            String[] s = readSpec(src, p);
            out.append("__require(").append(quote(s[0])).append(");\n");
            p = Integer.parseInt(s[1]);
        }
        return new Object[]{statementEnd(src, p), out.toString()};
    }

    /** 从 export 关键字开始改写，返回 {nextIndex, code}，失败返回 {-1,null}。 */
    private static Object[] rewriteExport(String src, int i) {
        int n = src.length();
        int declStart = skipWs(src, i + 6);
        int p = declStart;
        StringBuilder out = new StringBuilder();
        if (src.regionMatches(p, "default", 0, 7) && (p + 7 >= n || !isIdentChar(src.charAt(p + 7)))) {
            p = skipWs(src, p + 7);
            int end = p < n && src.charAt(p) == '{' ? findClose(src, p, '{') + 1 : statementEnd(src, p);
            if (end == 0) end = statementEnd(src, p);
            out.append("__module.exports.default = ").append(src, p, end).append(";\n");
            return new Object[]{skipSc(src, end), out.toString()};
        }
        boolean isAsync = src.regionMatches(p, "async", 0, 5) && isWordEnd(src, p + 5);
        if (isAsync) p = skipWs(src, p + 5);
        if (src.regionMatches(p, "function", 0, 8) && isWordEnd(src, p + 8)) {
            int namePos = skipWs(src, p + 8);
            int body = findFnBodyEnd(src, namePos);
            String fn = src.substring(declStart, body);
            String[] w = readWord(src, namePos);
            out.append(fn).append("\n__module.exports.").append(w[0]).append(" = ").append(w[0]).append(";\n");
            return new Object[]{skipSc(src, body), out.toString()};
        }
        if (src.regionMatches(p, "class", 0, 5) && isWordEnd(src, p + 5)) {
            int namePos = skipWs(src, p + 5);
            int body = findFnBodyEnd(src, namePos);
            String[] w = readWord(src, namePos);
            out.append(src, declStart, body).append("\n__module.exports.").append(w[0]).append(" = ").append(w[0]).append(";\n");
            return new Object[]{skipSc(src, body), out.toString()};
        }
        int kwLen = 0;
        if (src.regionMatches(p, "const", 0, 5) && isWordEnd(src, p + 5)) kwLen = 5;
        else if (src.regionMatches(p, "let", 0, 3) && isWordEnd(src, p + 3)) kwLen = 3;
        else if (src.regionMatches(p, "var", 0, 3) && isWordEnd(src, p + 3)) kwLen = 3;
        if (kwLen > 0) {
            int end = statementEnd(src, p);
            String body = src.substring(p, end);
            out.append(body);
            String decl = body.substring(kwLen).trim();
            for (String name : declaredNames(decl)) {
                if (!name.isEmpty()) out.append("\n__module.exports.").append(name).append(" = ").append(name).append(";");
            }
            out.append("\n");
            return new Object[]{skipSc(src, end), out.toString()};
        }
        if (p < n && src.charAt(p) == '{') {
            int close = findClose(src, p, '{');
            if (close < 0) return new Object[]{-1, null};
            String binds = src.substring(p + 1, close);
            int after = skipWs(src, close + 1);
            if (after < n && src.regionMatches(after, "from", 0, 4)) {
                String[] s = readSpec(src, skipWs(src, after + 4));
                String temp = "__reexp_" + Integer.toHexString((s[0] + binds).hashCode() & 0xffff);
                for (String part : destrParts(binds)) {
                    String t = part.trim();
                    if (t.isEmpty()) continue;
                    int as = indexOfWord(t, "as");
                    String from = as > 0 ? t.substring(0, as).trim() : t;
                    String to = as > 0 ? t.substring(as + 2).trim() : t;
                    out.append("const ").append(temp).append(" = __require(").append(quote(s[0])).append(");\n")
                            .append("__module.exports.").append(to).append(" = ").append(temp).append('.').append(from).append(";\n");
                }
                return new Object[]{skipSc(src, Integer.parseInt(s[1])), out.toString()};
            }
            for (String part : destrParts(binds)) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                int as = indexOfWord(t, "as");
                String from = as > 0 ? t.substring(0, as).trim() : t;
                String to = as > 0 ? t.substring(as + 2).trim() : t;
                out.append("__module.exports.").append(to).append(" = ").append(from).append(";\n");
            }
            return new Object[]{skipSc(src, close + 1), out.toString()};
        }
        return new Object[]{-1, null};
    }

    private static boolean isWordEnd(String src, int p) {
        return p >= src.length() || !isIdentChar(src.charAt(p));
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static int indexOfWord(String t, String w) {
        int k = 0;
        while ((k = t.indexOf(w, k)) >= 0) {
            boolean l = k == 0 || !isIdentChar(t.charAt(k - 1));
            boolean r = k + w.length() >= t.length() || !isIdentChar(t.charAt(k + w.length()));
            if (l && r) return k;
            k += w.length();
        }
        return -1;
    }

    /** 找函数/类声明体的结束位置（含闭合的 '}'）。 */
    private static int findFnBodyEnd(String src, int p) {
        int n = src.length();
        for (int j = skipWs(src, p); j < n; j++) {
            char c = src.charAt(j);
            if (c == '{') return findClose(src, j, '{') + 1;
            if (c == ';') return j + 1;
        }
        return n;
    }

    /** 从 "const a = 1, b = 2;" 中提取顶层声明的名字列表。 */
    private static String[] declaredNames(String decl) {
        java.util.List<String> names = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int j = 0; j < decl.length(); j++) {
            char c = decl.charAt(j);
            if (c == '\'' || c == '"') {
                j = scanString(decl, j, c) - 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if ((c == ',' || j == decl.length() - 1) && depth == 0) {
                String seg = decl.substring(start, j == decl.length() - 1 ? j + 1 : j).trim();
                int eq = seg.indexOf('=');
                String id = (eq > 0 ? seg.substring(0, eq) : seg).trim();
                if (isIdentStart(id.length() > 0 ? id.charAt(0) : ' ')) names.add(id);
                start = j + 1;
            }
        }
        return names.toArray(new String[0]);
    }

    /** 顶层逗号分隔片段。 */
    private static String[] destrParts(String binds) {
        if (binds.isEmpty()) return new String[0];
        java.util.List<String> list = new java.util.ArrayList<>();
        int depth = 0;
        int seg = 0;
        for (int j = 0; j < binds.length(); j++) {
            char c = binds.charAt(j);
            if (c == '\'' || c == '"') {
                j = scanString(binds, j, c) - 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                list.add(binds.substring(seg, j));
                seg = j + 1;
            }
        }
        list.add(binds.substring(seg));
        return list.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- async → generator

    private static String asyncToGenerator(String src) {
        StringBuilder out = new StringBuilder(src.length() + 128);
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\'' || c == '"') {
                int end = scanString(src, i, c);
                out.append(src, i, end);
                i = end;
            } else if (c == '`') {
                int end = scanString(src, i, '`');
                out.append(src, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                if (end < 0) end = n;
                out.append(src, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(src, i, end);
                i = end;
            } else if (tryAsync(src, i)) {
                i = rewriteAsync(src, i, out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean tryAsync(String src, int i) {
        return startsWithWord(src, i, "async");
    }

    /** 改写 async 函数（声明/匿名/箭头），返回下一个扫描位置。 */
    private static int rewriteAsync(String src, int i, StringBuilder out) {
        int n = src.length();
        int p = skipWs(src, i + 5);
        boolean isFn = src.regionMatches(p, "function", 0, 8) && isWordEnd(src, p + 8);
        if (isFn) p = skipWs(src, p + 8);
        boolean paren = p < n && src.charAt(p) == '(';
        if (!isFn && !paren) { // 非 async 函数用法（如 async 变量名），原样拷贝
            out.append("async");
            return i + 5;
        }
        String params = "()";
        String name = "";
        if (isFn) {
            // 具名函数：函数名在参数列表之前
            if (p < n && isIdentStart(src.charAt(p))) {
                String[] w = readWord(src, p);
                name = w[0];
                p = skipWs(src, Integer.parseInt(w[1]));
            }
            if (p < n && src.charAt(p) == '(') {
                int close = findClose(src, p, '(');
                if (close < 0) {
                    out.append("async");
                    return i + 5;
                }
                params = src.substring(p, close + 1);
                p = skipWs(src, close + 1);
            }
            char g = p < n ? src.charAt(p) : 0;
            if (g != '{') {
                out.append("async");
                return i + 5;
            }
            int close = findClose(src, p, '{');
            if (close < 0) {
                out.append("async");
                return i + 5;
            }
            out.append("function ").append(name).append(params).append(" { return __async(function* ")
                    .append(name).append(params).append(" {").append(toGeneratorBody(src.substring(p + 1, close))).append("}); }");
            return close + 1;
        }
        // 箭头函数或无括号单参数箭头
        if (paren) {
            int close = findClose(src, p, '(');
            if (close < 0) {
                out.append("async");
                return i + 5;
            }
            params = src.substring(p, close + 1);
            p = skipWs(src, close + 1);
        } else {
            String[] w = readWord(src, p);
            if (w[0].isEmpty()) {
                out.append("async");
                return i + 5;
            }
            params = "(" + w[0] + ")";
            p = skipWs(src, Integer.parseInt(w[1]));
        }
        if (p < n && src.charAt(p) == '=' && p + 1 < n && src.charAt(p + 1) == '>') { // 箭头
            p = skipWs(src, p + 2);
            char g = p < n ? src.charAt(p) : 0;
            if (g == '{') {
                int close = findClose(src, p, '{');
                if (close < 0) {
                    out.append("async");
                    return i + 5;
                }
                out.append(params).append(" => __async(function* ").append(params).append(" {")
                        .append(toGeneratorBody(src.substring(p + 1, close))).append("});");
                return close + 1;
            }
            int end = arrowExprEnd(src, p);
            out.append(params).append(" => __async(function* ").append(params).append(" { return ")
                    .append(toGeneratorBody(src.substring(p, end))).append("; });");
            return end;
        }
        out.append("async");
        return i + 5;
    }

    /** 箭头表达式体结束位置。 */
    private static int arrowExprEnd(String src, int p) {
        int n = src.length();
        for (int j = p; j < n; j++) {
            char c = src.charAt(j);
            if (c == '\'' || c == '"') {
                j = scanString(src, j, c) - 1;
                continue;
            }
            if (c == '\n' || c == ',' || c == ';' || c == '}') return j;
        }
        return n;
    }

    /** 函数体内 await → yield；嵌套 async 段递归改写。 */
    private static String toGeneratorBody(String body) {
        if (body.indexOf("await") < 0 && body.indexOf("async") < 0) return body;
        StringBuilder out = new StringBuilder(body.length() + 32);
        int n = body.length();
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '\'' || c == '"') {
                int end = scanString(body, i, c);
                out.append(body, i, end);
                i = end;
            } else if (c == '`') {
                int end = scanString(body, i, '`');
                out.append(body, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && body.charAt(i + 1) == '/') {
                int end = body.indexOf('\n', i);
                if (end < 0) end = n;
                out.append(body, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && body.charAt(i + 1) == '*') {
                int end = body.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(body, i, end);
                i = end;
            } else if (tryAsync(body, i)) {
                StringBuilder tmp = new StringBuilder();
                i = rewriteAsync(body, i, tmp);
                out.append(tmp);
            } else if (isAwait(body, i)) {
                out.append("yield");
                i += 5;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isAwait(String src, int i) {
        if (!src.regionMatches(i, "await", 0, 5)) return false;
        int end = i + 5;
        if (end < src.length() && isIdentChar(src.charAt(end))) return false;
        if (i > 0) {
            char p = src.charAt(i - 1);
            if (isIdentChar(p) || p == '.') return false;
        }
        return true;
    }
}