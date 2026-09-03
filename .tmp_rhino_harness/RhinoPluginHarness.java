import com.fongmi.rhino.utils.JSUtil;
import com.fongmi.rhino.utils.Transpile;

import org.htmlunit.corejs.javascript.BaseFunction;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Rhino 实跑闭环验证：插件（netease.js）经 Transpile 转译后在解释模式加载，
 * 用 mock req 驱动 search/getMediaSource，验证 require('axios') 链与 async 方法全通。
 */
public class RhinoPluginHarness {

    private static final String ASSETS = "/workspace/TV-fongmi/rhino/src/main/assets/js/";
    private static final String PLUGIN = "/workspace/TV-fongmi/app/src/main/assets/music/netease.js";

    public static void main(String[] args) throws Exception {
        Context cx = Context.enter();
        String opt = System.getProperty("opt-level", "-1");
        cx.setOptimizationLevel(Integer.parseInt(opt));
        cx.setLanguageVersion(Context.VERSION_ES6);
        VarScope scope = (VarScope) cx.initStandardObjects();
        if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);

        // mock __tick：同步泵送 Promise 微任务（真实环境由 Global 桥走线程池）
        final Scriptable thisObj = cx.newObject(scope);
        JSUtil.bind(cx, scope, "__tick", fnArgs -> {
            if (fnArgs.length > 0 && fnArgs[0] instanceof Function) ((Function) fnArgs[0]).call(cx, scope, thisObj, new Object[0]);
            return null;
        });
        // 真实网络 req（走系统代理；与 Rhino 宿主 Global.req 语义一致：code/content + headers）
        JSUtil.bind(cx, scope, "req", fnArgs -> {
            String url = fnArgs.length > 0 && fnArgs[0] != null ? Context.toString(fnArgs[0]) : "";
            String ua = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Referer", "https://music.163.com/");
            int code = conn.getResponseCode();
            String content;
            if (code >= 400) content = "{}";
            else content = readAll(conn.getInputStream());
            Scriptable r = cx.newObject(scope);
            ScriptableObject.putProperty(r, "code", String.valueOf(code));
            ScriptableObject.putProperty(r, "content", content);
            return r;
        });
        // promise.js（含 __async generator 驱动）
        evalAsset(cx, scope, "lib/promise.js");
        // axios 兼容层（基于 mock req）：mf/axios.js 自行写入 g.__mf_lib_axios，shim 可直接查表
        evalLib(cx, scope, "mf/axios.js", "axios");

        // JS 层 require shim：查 script scope 槽（__mf_lib_<name>），未命中走 __mf_host_load 副作用加载。
        // 注意：不允许插件引用 Java 函数的返回值（解释模式下 var 绑定此链路会丢失，详见调试记录）。
        cx.evaluateString(scope,
                "var __mf_require_js = function (name) { var g = globalThis;"
                        + " if (g['__mf_lib_' + name] !== undefined) return g['__mf_lib_' + name];"
                        + " __mf_host_load(name); return g['__mf_lib_' + name]; };",
                "mf-require.js", 1, null);
        JSUtil.bind(cx, scope, "__mf_host_load", fnArgs -> {
            String name = fnArgs.length > 0 && fnArgs[0] != null ? Context.toString(fnArgs[0]) : "";
            evalLib(cx, scope, "mf/axios.js", name);
            return null;
        });

        // wrapper 参数占位（console/env/URL/process）：插件未使用但需存在
        Scriptable consoleObj = cx.newObject(scope);
        Scriptable envObj = cx.newObject(scope);
        Scriptable procObj = cx.newObject(scope);
        Scriptable urlObj = cx.newObject(scope);
        ScriptableObject.putProperty(scope, "__mf_console", consoleObj);
        ScriptableObject.putProperty(scope, "__mf_env", envObj);
        ScriptableObject.putProperty(scope, "__mf_proc", procObj);
        ScriptableObject.putProperty(scope, "__mf_url", urlObj);

        // 插件：CommonJS wrapper（与 PluginSandbox.load 相同）
        String code = new String(Files.readAllBytes(Paths.get(PLUGIN)), StandardCharsets.UTF_8);
        String transpiled = Transpile.toCommonJs(code);
        Files.write(Paths.get("/tmp/plugin.transpiled.js"), transpiled.getBytes(StandardCharsets.UTF_8));
        String wrapped = "(function(){\n'use strict';\nvar __module = { exports: {} };\n"
                + "(function(require, __musicfree_require, module, exports, console, env, URL, process) {\n"
                + transpiled
                + "\n})(__mf_require_js, __mf_require_js, __module, __module.exports, __mf_console, __mf_env, __mf_url, __mf_proc);\n"
                + "return __module.exports;\n})();";
        Object loaded = cx.evaluateString(scope, wrapped, "plugin.js", 1, null);
        Scriptable instance = (Scriptable) loaded;
        System.out.println("[Rhino] plugin platform = " + ScriptableObject.getProperty(instance, "platform"));

        // 调用 search：async 方法返回 Promise，经 then 收集结果
        // 手动泵送 __tick 驱动微任务完成（真实环境由 Global 桥走线程池）
        ScriptableObject.putProperty(scope, "__plugin", instance);
        cx.evaluateString(scope, "var __result = null; var __err = null;", "init.js", 1, null);
        cx.evaluateString(scope,
                "__plugin.search('\\u6674\\u5929', 1, 1).then(function(r){ __result = r; }, function(e){ __err = ((e && e.message) ? e.message : String(e)) + '\\n' + (e && e.stack || ''); });",
                "invoke.js", 1, null);
        for (int i = 0; i < 50; i++) {
            cx.evaluateString(scope, "if (typeof __tick === 'function') __tick();", "tick.js", 1, null);
        }
        System.out.println("[Rhino] search done，err = " + cx.evaluateString(scope, "String(__err)", "errdiag", 1, null));

        // 结果在 Promise resolve 后写入 __result；同步轮询器直接调 __tick
        String json = (String) cx.evaluateString(scope, "JSON.stringify(__result)", "collect", 1, null);
        System.out.println("[Rhino] search json(截断) = " + (json == null ? "null" : (json.length() > 220 ? json.substring(0, 220) : json)));
        if (json == null || !json.contains("\"isEnd\":true") || !json.contains("晴天")) {
            System.out.println("[FAIL] search 结果不符合预期");
            System.exit(1);
        }

        // 真实源验证：取搜索结果第一首，经 getMediaSource 拿播放 URL
        cx.evaluateString(scope,
                "var __url = null; var __url_err = null;"
                        + "var __first = __result.data[0];"
                        + "__plugin.getMediaSource(__first, '').then(function(r){ __url = r; }, function(e){ __url_err = ((e && e.message) ? e.message : String(e)); });",
                "invoke_url.js", 1, null);
        for (int i = 0; i < 30; i++) {
            cx.evaluateString(scope, "if (typeof __tick === 'function') __tick();", "tick2.js", 1, null);
        }
        String url = (String) cx.evaluateString(scope, "JSON.stringify(__url)", "collect_url", 1, null);
        System.out.println("[Rhino] getMediaSource url = " + url);
        if (url == null || !url.contains("http")) {
            String urlErr = (String) cx.evaluateString(scope, "String(__url_err)", "url_err", 1, null);
            System.out.println("[FAIL] getMediaSource 未返回 URL: " + urlErr);
            System.exit(1);
        }
        System.out.println("ALL PASS");
        Context.exit();
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