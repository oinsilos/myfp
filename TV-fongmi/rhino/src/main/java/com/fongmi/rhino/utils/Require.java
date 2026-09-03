package com.fongmi.rhino.utils;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 犀牛环境下的轻量模块装载器（CommonJS 风格）。
 * 将影视源 import 的库名解析到内置 js/lib 资源或远程脚本，并缓存 exports。
 */
public final class Require {

    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    static {
        ALIASES.put("http", "lib/http");
        ALIASES.put("crypto-js", "lib/crypto-js");
        ALIASES.put("cheerio", "lib/cheerio.min");
        ALIASES.put("gbk", "lib/gbk");
        ALIASES.put("similarity", "lib/similarity");
        ALIASES.put("cat", "lib/cat");
        ALIASES.put("dayjs", "lib/cat");
    }

    private final Map<String, Scriptable> cache = new ConcurrentHashMap<>();
    private final Context cx;
    private final Scriptable scope;

    public Require(Context cx, Scriptable scope) {
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
        if (s.startsWith("assets://js/lib/")) return s.substring("assets://js/lib/".length(), s.length() - (s.endsWith(".js") ? 3 : 0));
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
            String wrapped = "(function(){\nvar __module = { exports: {} };\n" + Transpile.toCommonJs(code) + "\nreturn __module.exports;\n})();";
            Object result = cx.evaluateString(scope, wrapped, name, 1, null);
            return result instanceof Scriptable ? (Scriptable) result : empty;
        } catch (Throwable e) {
            return empty;
        }
    }

    /** 将 __require 绑定到全局。 */
    public void bind() {
        if (!(scope instanceof ScriptableObject)) return;
        ScriptableObject target = (ScriptableObject) scope;
        target.put("__require", target, new org.htmlunit.corejs.javascript.BaseFunction(scope, "__require", 1) {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 && args[0] != null ? Context.toString(args[0]) : "";
                return Require.this.load(name);
            }
        });
    }
}