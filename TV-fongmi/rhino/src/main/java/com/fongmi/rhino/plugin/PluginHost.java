package com.fongmi.rhino.plugin;

import com.fongmi.rhino.method.Console;
import com.fongmi.rhino.utils.JSUtil;
import com.fongmi.rhino.utils.Transpile;
import com.github.catvod.utils.Asset;

import org.htmlunit.corejs.javascript.BaseFunction;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.Undefined;
import org.htmlunit.corejs.javascript.VarScope;

import java.net.URI;
import java.util.Map;

/**
 * MusicFree 插件宿主（Rhino 侧实现）。
 * <p>
 * 契约对齐 RN {@code src/core/pluginManager/sandbox/types.ts} 的 IPluginHost：
 * 向插件注入 require / __musicfree_require / console / env / URL / process，
 * 使同一份插件定义可在 Hermes 与 Rhino 双环境运行。console 复用 Global 桥的全局 console。
 */
public final class PluginHost {

    /** 用户自定义变量来源：按插件名实时提供（对齐 RN env.userVariables 懒求值语义）。 */
    public interface Variables {
        Map<String, String> get(String name);
    }

    private final Context cx;
    private final VarScope scope;
    private final Variables variables;
    private final String appVersion;

    private volatile String pluginName = "";

    public PluginHost(Context cx, VarScope scope, Variables variables, String appVersion) {
        this.cx = cx;
        this.scope = scope;
        this.variables = variables == null ? name -> null : variables;
        this.appVersion = appVersion == null ? "" : appVersion;
    }

    /** 插件的 platform 名在 load 之后才可知，用于 env.getUserVariables 按名取变量。 */
    public void setPluginName(String name) {
        this.pluginName = name == null ? "" : name;
    }

    /**
     * 把 __mf_req(__mf_require_js) / __mf_env / __mf_url / __mf_proc 绑定到 scope，
     * 插件代码经这些占位被注入（与 RN 的 8 参注入顺序一致）。
     * <p>
     * 注意：require 必须走 JS shim（查 script scope 槽 __mf_lib_&lt;name&gt;）。
     * Rhino 解释模式下若把 Java 宿主函数的返回值直接赋给 var 再读取，变量绑定会丢失
     * （ReferenceError: "axios" is not defined），JS 查表路径则完全健康。
     */
    public void bind() {
        JSUtil.bind(cx, scope, "__mf_url_parse", args -> urlParse(str(args, 0)));
        JSUtil.bind(cx, scope, "__mf_url_resolve", args -> urlResolve(str(args, 0), str(args, 1)));
        // Java 副作用加载：仅负责把 <name> 对应的库加载并写入 script scope 槽
        // __mf_lib_<name>，不把返回值交由插件代码持有（见类注释，避免解释模式 var 绑定丢失）。
        JSUtil.bind(cx, scope, "__mf_host_load", args -> {
            require(str(args, 0));
            return null;
        });
        // JS require shim：优先查槽（幂等），未命中才交 __mf_host_load 加载
        cx.evaluateString(scope,
                "var __mf_require_js = function (name) { var g = globalThis;"
                        + " if (g['__mf_lib_' + name] !== undefined) return g['__mf_lib_' + name];"
                        + " __mf_host_load(name); return g['__mf_lib_' + name]; };"
                        + "var __mf_req = __mf_require_js;",
                "mf-require.js", 1, null);
        ScriptableObject.putProperty(scope, "__mf_env", env());
        ScriptableObject.putProperty(scope, "__mf_url", evalLib("mf/url.js", "URL"));
        ScriptableObject.putProperty(scope, "__mf_proc", process());
        // env.userVariables 懒求值 getter（与 RN IPluginHostEnv 一致）
        cx.evaluateString(scope,
                "Object.defineProperty(__mf_env, 'userVariables', { configurable: true, get: function () { var r = __mf_env.getUserVariables(); return (r && typeof r === 'object') ? r : {}; } });",
                "env.js", 1, null);
    }

    /** require 加载：解析宿主内置库，加载并把 exports 写入 script scope 槽 __mf_lib_<name>（幂等）。 */
    public void require(String name) {
        if (name == null) name = "";
        if (ScriptableObject.getProperty(scope, "__mf_lib_" + name) instanceof Scriptable) return;
        String key = name;
        Scriptable lib;
        switch (name) {
            case "axios":
                lib = evalLib("mf/axios.js", "axios");
                break;
            case "cheerio":
                lib = evalLib("lib/cheerio.min.js", "cheerio");
                break;
            case "crypto-js":
                lib = evalLib("lib/crypto-js.js", "CryptoJS");
                break;
            case "dayjs":
                lib = evalLib("lib/dayjs.min.js", "dayjs");
                break;
            case "big-integer":
                lib = evalLib("lib/big-integer.js", "bigInt");
                break;
            case "qs":
                lib = evalLib("lib/qs.js", "qs");
                break;
            case "he":
                lib = evalLib("lib/he.js", "he");
                break;
            default:
                lib = stub(name);
        }
        ScriptableObject.putProperty(scope, "__mf_lib_" + key, lib);
    }

    /** 求值内置库：CommonJS 包装（provide module/exports，让 UMD 走 CJS 分支），取 module.exports。 */
    private Scriptable evalLib(String path, String globalName) {
        String code = Asset.read("js/" + path);
        if (code == null) return empty();
        String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n"
                + Transpile.toCommonJs(code) + "\nreturn __module.exports;\n})();";
        Object result;
        try {
            result = cx.evaluateString(scope, wrapped, path, 1, null);
        } catch (Throwable e) {
            // 引擎不支持的语法（如 ES6 class）等：降级为空对象并告警，避免拖垮插件加载
            Console.log("[mf-host] 库 " + path + " 加载失败: " + e.getMessage());
            return empty();
        }
        if (result instanceof Scriptable) {
            Scriptable obj = (Scriptable) result;
            if (!isEmpty(obj)) return obj;
        }
        if (globalName != null) {
            Object lib = ScriptableObject.getProperty(scope, "__mf_lib_" + globalName);
            if (lib instanceof Scriptable) return (Scriptable) lib;
            Object g = ScriptableObject.getProperty(scope, globalName);
            if (g instanceof Scriptable) return (Scriptable) g;
        }
        return empty();
    }

    private Scriptable empty() {
        return cx.newObject(scope);
    }

    private boolean isEmpty(Scriptable obj) {
        for (Object id : obj.getIds()) {
            if (id instanceof String) return false;
        }
        return true;
    }

    /** 尚未移植的库：返回空对象并告警（后续按需逐个移植）。 */
    private Scriptable stub(String name) {
        Console.log("[mf-host] 库 " + name + " 尚未在 Rhino 宿主移植，返回空对象");
        return cx.newObject(scope);
    }

    private Scriptable env() {
        Scriptable env = cx.newObject(scope);
        ScriptableObject.putProperty(env, "appVersion", appVersion);
        ScriptableObject.putProperty(env, "os", "android");
        ScriptableObject.putProperty(env, "lang", "zh-CN");
        ScriptableObject.putProperty(env, "getUserVariables", new BaseFunction() {
            @Override
            public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
                Map<String, String> vars = PluginHost.this.variables.get(pluginName);
                return vars == null ? cx.newObject(scope) : JSUtil.toObject(cx, scope, vars);
            }
        });
        return env;
    }

    private Scriptable process() {
        Scriptable process = cx.newObject(scope);
        ScriptableObject.putProperty(process, "platform", "android");
        ScriptableObject.putProperty(process, "version", appVersion);
        ScriptableObject.putProperty(process, "env", ScriptableObject.getProperty(scope, "__mf_env"));
        return process;
    }

    private Scriptable urlParse(String url) {
        Scriptable obj = cx.newObject(scope);
        String href = url == null ? "" : url;
        String schema = "", host = "", hostname = "", port = "", path = "", query = "", fragment = "";
        try {
            URI uri = URI.create(href);
            href = uri.toString();
            if (uri.getScheme() != null) schema = uri.getScheme();
            if (uri.getHost() != null) {
                hostname = uri.getHost();
                int p = uri.getPort();
                host = hostname + (p != -1 ? ":" + p : "");
                if (p != -1) port = String.valueOf(p);
            } else if (uri.getRawAuthority() != null) {
                host = uri.getRawAuthority();
            }
            if (uri.getRawPath() != null) path = uri.getRawPath();
            if (uri.getRawQuery() != null) query = uri.getRawQuery();
            if (uri.getRawFragment() != null) fragment = uri.getRawFragment();
        } catch (Throwable ignored) {
        }
        ScriptableObject.putProperty(obj, "href", href);
        ScriptableObject.putProperty(obj, "schema", schema);
        ScriptableObject.putProperty(obj, "host", host);
        ScriptableObject.putProperty(obj, "hostname", hostname);
        ScriptableObject.putProperty(obj, "port", port);
        ScriptableObject.putProperty(obj, "path", path);
        ScriptableObject.putProperty(obj, "query", query);
        ScriptableObject.putProperty(obj, "fragment", fragment);
        return obj;
    }

    private String urlResolve(String base, String rel) {
        try {
            if (base == null || base.isEmpty()) return rel;
            return URI.create(base).resolve(rel == null ? "" : rel).toString();
        } catch (Throwable e) {
            return rel;
        }
    }

    private static String str(Object[] args, int i) {
        if (args == null || i >= args.length || args[i] == null || args[i] instanceof Undefined) return "";
        return Context.toString(args[i]);
    }
}