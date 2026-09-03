import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

/**
 * 定位 Rhino 解释模式 generator 闭包捕获的边界：
 * depth = 引用变量的父函数嵌套深度；strict = 是否严格模式。
 */
public class GenDepth {

    public static void main(String[] args) throws Exception {
        Context cx = Context.enter();
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(Context.VERSION_ES6);
        VarScope scope = (VarScope) cx.initStandardObjects();
        if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);

        final Scriptable thisObj = cx.newObject(scope);
        // mock __tick 同步泵送
        scope.put("__tick", scope, null);
        org.htmlunit.corejs.javascript.BaseFunction tick = new org.htmlunit.corejs.javascript.BaseFunction() {
            @Override
            public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] fnArgs) {
                if (fnArgs.length > 0 && fnArgs[0] instanceof Function) ((Function) fnArgs[0]).call(cx, scope, thisObj, new Object[0]);
                return null;
            }
        };
        ScriptableObject.putProperty(scope, "__tick", tick);
        ScriptableObject.putProperty(scope, "__topSecret", 5);

        // 用正式 promise.js（含 __async/微任务泵送），与 harness 一致
        String promiseJs = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("/workspace/TV-fongmi/rhino/src/main/assets/js/lib/promise.js")),
                java.nio.charset.StandardCharsets.UTF_8);
        cx.evaluateString(scope, promiseJs, "promise.js", 1, null);

        // Java 宿主 require（BaseFunction 调用）
        final Scriptable thisObj2 = cx.newObject(scope);
        org.htmlunit.corejs.javascript.BaseFunction jreq = new org.htmlunit.corejs.javascript.BaseFunction() {
            @Override
            public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] fnArgs) {
                return 100;
            }
        };
        ScriptableObject.putProperty(scope, "__javaRequire", jreq);

        String[] cases = {
                // 深度 1（generator 定义处父函数直接持有变量）+ 非 strict
                "case-d1-ns",
                "(function(){ var secret = 'v1'; var out = null;"
                        + "__async(function* () { var a = yield Promise.resolve(1); return secret + a; })"
                        + ".then(function(r){ out = r; }); globalThis.__res_d1_ns = out; })();",
                // 深度 1 + strict
                "case-d1-st",
                "(function(){ 'use strict'; var secret = 'v2'; var out = null;"
                        + "__async(function* () { var a = yield Promise.resolve(1); return secret + a; })"
                        + ".then(function(r){ out = r; }); globalThis.__res_d1_st = out; })();",
                // 深度 2（经对象方法层）+ non strict
                "case-d2-ns",
                "(function(){ var out = null; var obj = { m: function(kw) {"
                        + "var secret = 'v3'; return __async(function* () { var a = yield Promise.resolve(1); return secret + kw; }); } };"
                        + "obj.m(2).then(function(r){ out = r; }); globalThis.__res_d2_ns = out; })();",
                // 深度 2 + strict
                "case-d2-st",
                "(function(){ 'use strict'; var out = null; var obj = { m: function(kw) {"
                        + "var secret = 'v4'; return __async(function* () { var a = yield Promise.resolve(1); return secret + kw; }); } };"
                        + "obj.m(3).then(function(r){ out = r; }); globalThis.__res_d2_st = out; })();",
                // 深度 3（IIFE -> inner -> obj method -> generator）+ strict（对齐插件 wrapper）
                "case-d3-st",
                "(function(){ 'use strict'; (function(require2){ var secret = 'v5'; var out = null; var obj = { m: function(kw) {"
                        + "return __async(function* () { var a = yield Promise.resolve(1); return secret + kw; }); } };"
                        + "obj.m(4).then(function(r){ out = r; }); globalThis.__res_d3_st = out; })(function(){}); })();",
                // 全局引用（对照，应 always OK）
                "case-g",
                "var out2 = null; __async(function* () { var a = yield Promise.resolve(1); return globalThis.__topSecret + a; })"
                        + ".then(function(r){ out2 = r; }); globalThis.__res_g = out2;",
                // 深度 3 + 外层变量为普通函数
                "case-fn",
                "(function(){ 'use strict'; (function(require2){"
                        + "var fn = function(a){ return a * 10; }; var out = null;"
                        + "var obj = { m: function(kw) { return __async(function* () { var a = yield Promise.resolve(1); return fn(a) + kw; }); } };"
                        + "obj.m(4).then(function(r){ out = r; }); globalThis.__res_fn = out; })(function(){}); })();",
                // 深度 3 + 外层变量来自 require 返回值（对象）
                "case-lib",
                "(function(){ 'use strict'; (function(require2){"
                        + "var lib = require2('dummy'); var out = null;"
                        + "var obj = { m: function(kw) { return __async(function* () { var a = yield Promise.resolve(1); return lib.tag + a + kw; }); } };"
                        + "obj.m(5).then(function(r){ out = r; }); globalThis.__res_lib = out; })(function(){ return { tag: 100 }; }); })();",
                // 深度 3 + require 是 Java BaseFunction（对齐真实插件 wrapper）
                "case-jreq",
                "(function(){ 'use strict'; (function(require, __musicfree_require, module, exports, console, env, URL, process){"
                        + "var axios = require('axios'); var out = null;"
                        + "var obj = { m: function(kw) { return __async(function* () { var a = yield Promise.resolve(1); return typeof axios + ';' + kw; }); } };"
                        + "obj.m(5).then(function(r){ out = r; }); globalThis.__res_jreq = out; })(globalThis.__javaRequire, globalThis.__javaRequire, {exports:{}}, null, null, null, null, null); })();",
        };

        String[] keys = {"d1_ns", "d1_st", "d2_ns", "d2_st", "d3_st", "g", "fn", "lib", "jreq"};
        for (int i = 0; i < cases.length; i += 2) {
            String name = cases[i];
            String code = cases[i + 1];
            String key = keys[i / 2];
            try {
                cx.evaluateString(scope, code, name + ".js", 1, null);
                Object r = cx.evaluateString(scope, "String(globalThis.__res_" + key + ")", "read-" + name, 1, null);
                System.out.println(name + " => " + r);
            } catch (Throwable e) {
                System.out.println(name + " => THROW " + String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
        Context.exit();
    }
}