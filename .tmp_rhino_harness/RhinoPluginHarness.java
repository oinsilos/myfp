import com.fongmi.rhino.utils.Async;
import com.fongmi.rhino.utils.JSUtil;
import com.fongmi.rhino.utils.Transpile;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.Undefined;
import org.htmlunit.corejs.javascript.VarScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rhino 插件闭环验证（对齐 PluginSandbox 真实线程模型）：
 * - 单一单线程 executor：一切 Rhino 操作（init/load/call）都在其上，Context.enter 只在该线程发生一次；
 * - __tick 与 Global.tick 一致：把微任务 flush 异步提交回同一 executor（串行排队，不跨线程）；
 * - 调用链与 MusicSource.callJson 一致：Async.run 桥回 CompletableFuture；
 * - req 走真实网络（Java HttpURLConnection，语义对齐 Global.req 的 code/content）。
 * 验证 search → getMediaSource 真实音乐源闭环。
 */
public class RhinoPluginHarness {

    private static final String ASSETS = "/workspace/TV-fongmi/rhino/src/main/assets/js/";
    private static final String PLUGIN = "/workspace/TV-fongmi/app/src/main/assets/music/netease.js";

    public static void main(String[] args) throws Exception {
        final ExecutorService sandbox = Executors.newSingleThreadExecutor();
        final AtomicReference<Object> searchResult = new AtomicReference<>();
        final AtomicReference<String> searchError = new AtomicReference<>();
        final AtomicReference<Object> mediaResult = new AtomicReference<>();
        final AtomicReference<String> mediaError = new AtomicReference<>();

        sandbox.submit(() -> {
            Context cx = Context.enter();
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            VarScope scope = (VarScope) cx.initStandardObjects();
            if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);
            final Scriptable thisObj = cx.newObject(scope);

            // __tick：与 Global.tick 一致 —— 异步提交回同一单线程 executor（真实插件微任务泵送）
            JSUtil.bind(cx, scope, "__tick", fnArgs -> {
                if (fnArgs.length > 0 && fnArgs[0] instanceof Function) {
                    Function fn = (Function) fnArgs[0];
                    sandbox.submit(() -> fn.call(cx, scope, thisObj, new Object[0]));
                }
                return null;
            });
            // req：真实网络 GET（语义对齐 Global.req：code/content）
            JSUtil.bind(cx, scope, "req", fnArgs -> {
                String url = str(fnArgs, 0);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
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
            });

            // promise.js（__async/微任务泵送）
            evalAsset(cx, scope, "lib/promise.js");
            // axios 兼容层（自行写 __mf_lib_axios）
            evalLib(cx, scope, "mf/axios.js", "axios");
            // JS require shim（与 PluginHost 注入一致）
            cx.evaluateString(scope,
                    "var __mf_require_js = function (name) { var g = globalThis;"
                            + " if (g['__mf_lib_' + name] !== undefined) return g['__mf_lib_' + name];"
                            + " __mf_host_load(name); return g['__mf_lib_' + name]; };",
                    "mf-require.js", 1, null);
            JSUtil.bind(cx, scope, "__mf_host_load", fnArgs -> {
                evalLib(cx, scope, "mf/axios.js", str(fnArgs, 0));
                return null;
            });
            Scriptable consoleObj = cx.newObject(scope);
            Scriptable envObj = cx.newObject(scope);
            Scriptable procObj = cx.newObject(scope);
            Scriptable urlObj = cx.newObject(scope);
            ScriptableObject.putProperty(scope, "__mf_console", consoleObj);
            ScriptableObject.putProperty(scope, "__mf_env", envObj);
            ScriptableObject.putProperty(scope, "__mf_proc", procObj);
            ScriptableObject.putProperty(scope, "__mf_url", urlObj);

            // 插件：CommonJS wrapper（与 PluginSandbox.load 一致，require 走 JS shim）
            String code;
            try {
                code = new String(Files.readAllBytes(Paths.get(PLUGIN)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                searchError.set("read plugin fail: " + e);
                return null;
            }
            String wrapped = "(function(){\n'use strict';\nvar __module = { exports: {} };\n"
                    + "(function(require, __musicfree_require, module, exports, console, env, URL, process) {\n"
                    + Transpile.toCommonJs(code)
                    + "\n})(__mf_require_js, __mf_require_js, __module, __module.exports, __mf_console, __mf_env, __mf_url, __mf_proc);\n"
                    + "return __module.exports;\n})();";
            Object loaded = cx.evaluateString(scope, wrapped, "plugin.js", 1, null);
            Scriptable instance = (Scriptable) loaded;
            System.out.println("[Rhino] plugin platform = " + ScriptableObject.getProperty(instance, "platform"));

            // search：走真实桥 Async.run（与 MusicSource.callJson 一致）
            Async.run(cx, scope, instance, "search", "晴天", 1, 1).whenComplete((result, error) -> {
                if (error != null) searchError.set(describe(error));
                else {
                    try {
                        ScriptableObject.putProperty(scope, "__str__", result);
                        String json = Context.toString(cx.evaluateString(scope, "JSON.stringify(__str__)", "stringify", 1, null));
                        searchResult.set(json);
                        // 取第一首调 getMediaSource（真实桥）
                        String item = extractFirstItem(json);
                        Async.run(cx, scope, instance, "getMediaSource",
                                cx.evaluateString(scope, "(" + item + ")", "item", 1, null), "").whenComplete((r2, e2) -> {
                            if (e2 != null) mediaError.set(describe(e2));
                            else {
                                ScriptableObject.putProperty(scope, "__str2__", r2);
                                mediaResult.set(Context.toString(cx.evaluateString(scope, "JSON.stringify(__str2__)", "stringify2", 1, null)));
                            }
                        });
                    } catch (Throwable e) {
                        searchError.set(describe(e));
                    }
                }
            });
            return null;
        });

        // 主线程等待（真实网络 + 异步泵送，给足时间）
        CompletableFuture<Void> wait = new CompletableFuture<>();
        for (int i = 0; i < 240 && !wait.isDone(); i++) {
            if (searchError.get() != null) break;
            if (searchResult.get() == null && mediaResult.get() == null && mediaError.get() == null) {
                Thread.sleep(250);
            } else {
                // 等 getMediaSource 也结束
                if (mediaResult.get() != null || mediaError.get() != null) break;
                Thread.sleep(250);
            }
        }
        if (searchError.get() != null) {
            System.out.println("[FAIL] search error: " + searchError.get());
            System.exit(1);
        }
        String json = (String) searchResult.get();
        System.out.println("[Rhino] search json(截断) = " + (json == null ? "null" : (json.length() > 220 ? json.substring(0, 220) : json)));
        if (json == null || !json.contains("\"isEnd\":true") || !json.contains("晴天")) {
            System.out.println("[FAIL] search 结果不符合预期");
            System.exit(1);
        }
        System.out.println("[Rhino] getMediaSource url = " + mediaResult.get());
        if (mediaError.get() != null) {
            System.out.println("[FAIL] getMediaSource error: " + mediaError.get());
            System.exit(1);
        }
        if (mediaResult.get() == null || !String.valueOf(mediaResult.get()).contains("http")) {
            System.out.println("[FAIL] getMediaSource 未返回 URL");
            System.exit(1);
        }
        System.out.println("ALL PASS");
        sandbox.shutdownNow();
    }

    private static String extractFirstItem(String searchJson) {
        int idx = searchJson.indexOf("\"data\":[");
        if (idx < 0) return "{}";
        int start = searchJson.indexOf('{', idx + 8);
        if (start < 0) return "{}";
        int depth = 0;
        int end = -1;
        for (int i = start; i < searchJson.length(); i++) {
            char c = searchJson.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end < 0) return "{}";
        String first = searchJson.substring(start, end);
        StringBuilder sb = new StringBuilder("{");
        String[] keys = {"id", "title", "artist", "album", "cover", "url", "songId"};
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

    private static String describe(Throwable t) {
        Throwable cause = t.getCause() == null ? t : t.getCause();
        return String.valueOf(cause.getMessage() == null ? cause : cause.getMessage());
    }

    private static String str(Object[] args, int i) {
        if (args == null || i >= args.length || args[i] == null || args[i] instanceof Undefined) return "";
        return Context.toString(args[i]);
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void evalAsset(Context cx, VarScope scope, String path) {
        String c = read(ASSETS + path);
        cx.evaluateString(scope, Transpile.toCommonJs(c), path, 1, null);
    }

    private static void evalLib(Context cx, VarScope scope, String path, String globalName) {
        String c = read(ASSETS + path);
        String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n"
                + Transpile.toCommonJs(c) + "\nreturn __module.exports;\n})();";
        try {
            cx.evaluateString(scope, wrapped, path, 1, null);
        } catch (Throwable e) {
            System.out.println("lib load fail " + path + ": " + e.getMessage());
        }
        Object lib = ScriptableObject.getProperty(scope, "__mf_lib_" + globalName);
        if (lib instanceof Scriptable) System.out.println("[OK] lib loaded: " + path);
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}