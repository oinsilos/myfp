package com.fongmi.rhino.utils;

import org.htmlunit.corejs.javascript.BaseFunction;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rhino 环境下的轻量模块装载器（CommonJS 风格）。
 * 将影视源 import 的库名解析到内置 js/lib 资源或远程脚本，并缓存 exports。
 */
public final class Require {

    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    static {
        ALIASES.put("http", "http");
        ALIASES.put("crypto-js", "crypto-js");
        ALIASES.put("cheerio", "cheerio.min");
        ALIASES.put("gbk", "gbk");
        ALIASES.put("similarity", "similarity");
        ALIASES.put("cat", "cat");
        ALIASES.put("dayjs", "cat");
    }

    private final Map<String, Scriptable> cache = new ConcurrentHashMap<>();
    private final Context cx;
    private final VarScope scope;

    public Require(Context cx, VarScope scope) {
        this.cx = cx;
        this.scope = scope;
    }

    public Scriptable load(String raw) {
        if (raw == null) raw = "";
        String name = normalize(raw);
        Scriptable cached = cache.get(name);
        if (cached != null) return cached;
        String code = fetch(name);
        Scriptable exports = execute(name, code);
        cache.put(name, exports);
        return exports;
    }

    private String normalize(String raw) {
        String s = raw.trim().replace('\\', '/');
        if (s.startsWith("assets://js/lib/")) {
            String r = s.substring("assets://js/lib/".length());
            return r.endsWith(".js") ? r.substring(0, r.length() - 3) : r;
        }
        if (s.startsWith("assets://")) return s;
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        while (s.startsWith("./") || s.startsWith("../") || s.startsWith("lib/")) {
            if (s.startsWith("lib/")) s = s.substring("lib/".length());
            else s = s.substring(s.indexOf('/') + 1);
        }
        if (s.endsWith(".js")) s = s.substring(0, s.length() - 3);
        String alias = ALIASES.get(s);
        return alias != null ? alias : s;
    }

    private String fetch(String name) {
        if (name.startsWith("http://") || name.startsWith("https://")) return Module.get().fetch(name);
        if (name.startsWith("assets://")) return Module.get().fetch(name.substring("assets://".length()));
        return Module.get().fetch("lib/" + name + ".js");
    }

    private Scriptable execute(String name, String code) {
        Scriptable empty = cx.newObject(scope);
        if (code == null) return empty;
        try {
            String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n" + Transpile.toCommonJs(code) + "\nreturn __module.exports;\n})();";
            Object result = cx.evaluateString(scope, wrapped, name, 1, null);
            return result instanceof Scriptable ? (Scriptable) result : empty;
        } catch (Throwable e) {
            return empty;
        }
    }

    /** 将 __require 绑定到全局。 */
    public void bind() {
        ScriptableObject.putProperty(scope, "__require", new BaseFunction() {
            @Override
            public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 && args[0] != null ? Context.toString(args[0]) : "";
                return Require.this.load(name);
            }
        });
    }
}