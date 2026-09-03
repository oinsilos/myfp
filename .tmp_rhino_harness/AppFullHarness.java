import com.fongmi.rhino.utils.Async;
import com.fongmi.rhino.utils.JSUtil;
import com.fongmi.rhino.utils.Transpile;

import org.htmlunit.corejs.javascript.BaseFunction;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.Undefined;
import org.htmlunit.corejs.javascript.VarScope;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对齐 App 真实链路的 Rhino 音乐插件验证（PluginHost.bind + PluginSandbox wrapper + MusicSource 解析复制）：
 * - 单一单线程 executor，Context.enter 只在该线程发生一次；
 * - __tick 泵送回同一 executor；
 * - req 同步（失败返回 {code:"",content:""} 与 Connect.error 一致）；
 * - require 走 JS shim（__mf_require_js 查 __mf_lib_<name> 槽）。
 * 验证 search → getMediaSource → buildItemJson(getMediaUrl) 三段真实闭环。
 */
public class AppFullHarness {

    private static final String ASSETS = "/workspace/TV-fongmi/rhino/src/main/assets/js/";
    private static final String PLUGIN = "/workspace/TV-fongmi/app/src/main/assets/music/netease.js";

    public static void main(String[] args) throws Exception {
        final ExecutorService sandbox = Executors.newSingleThreadExecutor();
        final AtomicReference<String> out = new AtomicReference<>("");

        int exit = sandbox.submit(() -> run(sandbox, out)).get();
        if (exit == -1) exit = after();
        if (exit == -1) exit = mediaDone();
        sandbox.shutdownNow();
        System.out.println("----\n" + out.get());
        System.exit(exit);
    }

    private static volatile CompletableFuture<Object> searchF;
    private static volatile CompletableFuture<Object> mediaF;
    private static volatile Context cxS;
    private static volatile VarScope scopeS;
    private static volatile Scriptable instanceS;

    private static int run(ExecutorService sandbox, AtomicReference<String> out) {
        try {
            Context cx = Context.enter();
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            VarScope scope = (VarScope) cx.initStandardObjects();
            if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);
            final Scriptable thisObj = cx.newObject(scope);

            // ---- initCtx：Global.setProperty 关键子集（req/__tick/console 与 App 语义一致）----
            Scriptable console = cx.newObject(scope);
            for (String name : new String[]{"log", "info", "warn", "error", "debug"}) {
                ScriptableObject.putProperty(console, name, fn(cx, scope, args1 -> { System.out.println("[console] " + String.valueOf(args1[0])); return null; }));
            }
            ScriptableObject.putProperty(scope, "console", console);

            // __tick：与 Global.tick 一致，提交回同一 executor
            JSUtil.bind(cx, scope, "__tick", fnArgs -> {
                if (fnArgs.length > 0 && fnArgs[0] instanceof Function) {
                    Function f = (Function) fnArgs[0];
                    sandbox.submit(() -> f.call(cx, scope, thisObj, new Object[0]));
                }
                return null;
            });

            // _http：与 Java `_http` 语义一致——options.complete 存在时异步回调（提交回 executor），否则同步
            JSUtil.bind(cx, scope, "_http", fnArgs -> {
                try {
                    Scriptable opts = fnArgs.length > 1 && fnArgs[1] instanceof Scriptable ? (Scriptable) fnArgs[1] : null;
                    Object complete = opts == null ? null : ScriptableObject.getProperty(opts, "complete");
                    java.util.function.Supplier<Object> job = () -> {
                        String url = str(fnArgs, 0);
                        try {
                            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(10000);
                            conn.setReadTimeout(15000);
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                            conn.setRequestProperty("Referer", "https://music.163.com/");
                            int code = conn.getResponseCode();
                            String content = code >= 400 ? "{}" : readAll(conn.getInputStream());
                            Scriptable r = cx.newObject(scope);
                            ScriptableObject.putProperty(r, "code", String.valueOf(code));
                            ScriptableObject.putProperty(r, "content", content);
                            return r;
                        } catch (Throwable e) {
                            Scriptable r = cx.newObject(scope);
                            ScriptableObject.putProperty(r, "code", "");
                            ScriptableObject.putProperty(r, "content", "");
                            return r;
                        }
                    };
                    if (complete instanceof Function) {
                        sandbox.submit(() -> {
                            Object res = job.get();
                            ((Function) complete).call(cx, scope, thisObj, new Object[]{res});
                            return null;
                        });
                        return null;
                    }
                    return job.get();
                } catch (Throwable e) {
                    Scriptable r = cx.newObject(scope);
                    ScriptableObject.putProperty(r, "code", "");
                    ScriptableObject.putProperty(r, "content", "");
                    return r;
                }
            });

            // req：同步；网络失败返回 {code:"", content:""}（Connect.error 语义）
            JSUtil.bind(cx, scope, "req", fnArgs -> {
                String url = str(fnArgs, 0);
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                    conn.setRequestProperty("Referer", "https://music.163.com/");
                    int code = conn.getResponseCode();
                    String content = code >= 400 ? "{}" : readAll(conn.getInputStream());
                    Scriptable r = cx.newObject(scope);
                    ScriptableObject.putProperty(r, "code", String.valueOf(code));
                    ScriptableObject.putProperty(r, "content", content);
                    return r;
                } catch (Throwable e) {
                    Scriptable r = cx.newObject(scope);
                    ScriptableObject.putProperty(r, "code", "");
                    ScriptableObject.putProperty(r, "content", "");
                    return r;
                }
            });

            // promise.js / http.js / url.js
            evalAsset(cx, scope, "lib/promise.js");
            evalAsset(cx, scope, "lib/http.js");
            evalAsset(cx, scope, "mf/url.js");

            // ---- PluginHost.bind() 复制：JS require shim + env + url + process ----
            JSUtil.bind(cx, scope, "__mf_url_parse", args1 -> ScriptableObject.getProperty(scope, "__mf_url")); // 插件不使用，占位
            JSUtil.bind(cx, scope, "__mf_url_resolve", args1 -> str(args1, 1));
            JSUtil.bind(cx, scope, "__mf_host_load", args1 -> {
                require(cx, scope, str(args1, 0));
                return null;
            });
            cx.evaluateString(scope,
                    "var __mf_require_js = function (name) { var g = globalThis;"
                            + " if (g['__mf_lib_' + name] !== undefined) return g['__mf_lib_' + name];"
                            + " __mf_host_load(name); return g['__mf_lib_' + name]; };"
                            + "var __mf_req = __mf_require_js;",
                    "mf-require.js", 1, null);
            Scriptable env = cx.newObject(scope);
            ScriptableObject.putProperty(env, "appVersion", "0.0.1");
            ScriptableObject.putProperty(env, "os", "android");
            ScriptableObject.putProperty(env, "lang", "zh-CN");
            ScriptableObject.putProperty(env, "getUserVariables", fn(cx, scope, args1 -> cx.newObject(scope)));
            ScriptableObject.putProperty(scope, "__mf_env", env);
            ScriptableObject.putProperty(scope, "__mf_url", evalUrl(cx, scope));
            Scriptable proc = cx.newObject(scope);
            ScriptableObject.putProperty(proc, "platform", "android");
            ScriptableObject.putProperty(proc, "version", "0.0.1");
            ScriptableObject.putProperty(proc, "env", env);
            ScriptableObject.putProperty(scope, "__mf_proc", proc);
            cx.evaluateString(scope,
                    "Object.defineProperty(__mf_env, 'userVariables', { configurable: true, get: function () { var r = __mf_env.getUserVariables(); return (r && typeof r === 'object') ? r : {}; } });",
                    "env.js", 1, null);

            // ---- PluginSandbox.load()：转译 + 8 参 CJS wrapper ----
            String code = new String(Files.readAllBytes(Paths.get(PLUGIN)), StandardCharsets.UTF_8);
            String wrapped = "(function(){\n'use strict';\nvar __module = { exports: {} };\n"
                    + "(function(require, __musicfree_require, module, exports, console, env, URL, process) {\n"
                    + Transpile.toCommonJs(code)
                    + "\n})(__mf_require_js, __mf_require_js, __module, __module.exports, console, __mf_env, __mf_url, __mf_proc);\n"
                    + "return __module.exports;\n})();";
            Object loaded = cx.evaluateString(scope, wrapped, "plugin.js", 1, null);
            Scriptable instance = (Scriptable) loaded;
            System.out.println("plugin platform = " + ScriptableObject.getProperty(instance, "platform"));
            cxS = cx;
            scopeS = scope;
            instanceS = instance;
            ScriptableObject.putProperty(scope, "instance", instance);

            // ---- MusicSource.callJson("search", 'kw',1,1) 复制；不阻塞 executor（App 语义） ----
            String kw = "周杰伦";
            String argsJson = "\"" + kw.replace("\"", "\\\"") + "\",1,1";
            Scriptable arr = (Scriptable) cx.evaluateString(scope, "[" + argsJson + "]", "args", 1, null);
            Object[] argsArr = (Object[]) Context.jsToJava(arr, Object[].class);

            searchF = new CompletableFuture<>();
            Async.run(cx, scope, instance, "search", argsArr).whenComplete((result, error) -> {
                if (error != null) searchF.completeExceptionally(error);
                else searchF.complete(result);
            });
            return -1; // 回到 main 等待，executor 空闲以泵送微任务（与 App callJson 返回后一致）
        } catch (Throwable e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            System.out.println("[FAIL] " + (c.getMessage() == null ? c : c.getMessage()));
            return 1;
        }
    }

    private static int after() {
        Context cx = cxS;
        VarScope scope = scopeS;
        Scriptable instance = instanceS;
        try {
            Object searchRes = searchF.get(30, java.util.concurrent.TimeUnit.SECONDS);
            ScriptableObject.putProperty(scope, "__str__", searchRes);
            String json = Context.toString(cx.evaluateString(scope, "JSON.stringify(__str__)", "stringify", 1, null));
            System.out.println("search json head = " + (json.length() > 160 ? json.substring(0, 160) : json));
            if (json == null || !json.contains("\"isEnd\":true") || !json.contains("周杰伦")) {
                System.out.println("[FAIL] search 结果不符合预期");
                return 1;
            }

            // ---- MusicSource.getMediaUrl 复制：取第一首 → buildItemJson → getMediaSource ----
            String item = buildItemJson(findFirst(json));
            mediaF = new CompletableFuture<>();
            Async.run(cx, scope, instance, "getMediaSource",
                    cx.evaluateString(scope, "(" + item + ")", "item", 1, null), "").whenComplete((r, e) -> {
                if (e != null) mediaF.completeExceptionally(e);
                else mediaF.complete(r);
            });
            // 不阻塞 executor：返回，由 main 轮询
            return -1;
        } catch (Throwable e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            System.out.println("[FAIL] " + (c.getMessage() == null ? c : c.getMessage()));
            return 1;
        }
    }

    private static int mediaDone() {
        try {
            Object mediaRes = mediaF.get(30, java.util.concurrent.TimeUnit.SECONDS);
            ScriptableObject.putProperty(scopeS, "__str2__", mediaRes);
            String mediaJson = Context.toString(cxS.evaluateString(scopeS, "JSON.stringify(__str2__)", "stringify2", 1, null));
            System.out.println("getMediaSource = " + mediaJson);
            if (mediaJson == null || !mediaJson.contains("http")) {
                System.out.println("[FAIL] getMediaSource 未返回 URL");
                return 1;
            }
            System.out.println("[PASS] App 全链路（search → getMediaSource）验证通过");
            return 0;
        } catch (Throwable e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            System.out.println("[FAIL] " + (c.getMessage() == null ? c : c.getMessage()));
            return 1;
        }
    }

    /** MusicSource.parseSearch 找第一首的片段（简化：截取 data 数组第一个 {...} 并抽取字段）。 */
    private static String findFirst(String json) {
        int idx = json.indexOf("\"data\":[");
        if (idx < 0) return "{}";
        int start = json.indexOf('{', idx + 8);
        int depth = 0;
        int end = -1;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end < 0) return "{}";
        String first = json.substring(start, end);
        String[] keys = {"id", "title", "artist", "album", "cover", "url", "songId", "duration"};
        StringBuilder sb = new StringBuilder("{");
        for (String k : keys) {
            String v = jsonVal(first, k);
            if (v != null) {
                if (sb.length() > 1) sb.append(',');
                sb.append('"').append(k).append("\":\"").append(v).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonVal(String obj, String key) {
        String pat = "\"" + key + "\":\"";
        int i = obj.indexOf(pat);
        if (i < 0) return null;
        int s = i + pat.length();
        int e = obj.indexOf('"', s);
        if (e < 0) return null;
        return obj.substring(s, e);
    }

    /** MusicSource.buildItemJson 复制（id/title/artist/album/cover/url + extra.songId 合并）。 */
    private static String buildItemJson(String first) {
        StringBuilder sb = new StringBuilder("{");
        String[] keys = {"id", "title", "artist", "album", "cover", "url", "songId"};
        for (String k : keys) {
            String v = jsonVal(first, k);
            if (v != null) {
                if (sb.length() > 1) sb.append(',');
                sb.append('"').append(k).append("\":\"").append(v.replace("\"", "\\\"")).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** 求值 mf/url.js 并返回其 module.exports（PluginHost.evalLib 等价）。 */
    private static Scriptable evalUrl(Context cx, VarScope scope) {
        String c = read(ASSETS + "mf/url.js");
        if (c == null) return cx.newObject(scope);
        String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n"
                + Transpile.toCommonJs(c) + "\nreturn __module.exports;\n})();";
        try {
            Object r = cx.evaluateString(scope, wrapped, "mf/url.js", 1, null);
            if (r instanceof Scriptable) return (Scriptable) r;
        } catch (Throwable ignored) {
        }
        return cx.newObject(scope);
    }

    private static void require(Context cx, VarScope scope, String name) {
        if (ScriptableObject.getProperty(scope, "__mf_lib_" + name) instanceof Scriptable) return;
        String path = "axios".equals(name) ? "mf/axios.js" : null;
        if (path == null) {
            ScriptableObject.putProperty(scope, "__mf_lib_" + name, cx.newObject(scope));
            return;
        }
        String c = read(ASSETS + path);
        String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n"
                + Transpile.toCommonJs(c) + "\nreturn __module.exports;\n})();";
        try {
            cx.evaluateString(scope, wrapped, path, 1, null);
            Object lib = ScriptableObject.getProperty(scope, "__mf_lib_axios");
            if (!(lib instanceof Scriptable)) {
                // axios.js 自写槽，兜底从 scope 取
                ScriptableObject.putProperty(scope, "__mf_lib_axios", ScriptableObject.getProperty(scope, "__mf_lib_axios"));
            }
        } catch (Throwable e) {
            ScriptableObject.putProperty(scope, "__mf_lib_" + name, cx.newObject(scope));
        }
    }

    private static void evalAsset(Context cx, VarScope scope, String path) {
        String c = read(ASSETS + path);
        cx.evaluateString(scope, Transpile.toCommonJs(c), path, 1, null);
    }

    private static BaseFunction fn(Context cx, VarScope scope, JSUtil.JsFn f) {
        return new BaseFunction() {
            @Override
            public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
                try {
                    Object r = f.apply(args);
                    return r == null ? Context.getUndefinedValue() : r;
                } catch (Throwable e) {
                    return Context.getUndefinedValue();
                }
            }
        };
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String str(Object[] args, int i) {
        if (args == null || i >= args.length || args[i] == null || args[i] instanceof Undefined) return "";
        return Context.toString(args[i]);
    }
}