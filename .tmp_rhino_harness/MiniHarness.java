import com.fongmi.rhino.utils.Transpile;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JVM harness：复刻 PluginHost.evalLib 的加载路径（Rhino 解释模式 + Transpile + CommonJS wrapper），
 * 验证 vendored cheerio 0.22.0 (ES5 bundle) 可加载并跑常用 API。
 */
public class MiniHarness {

    private static final String ASSETS = "/workspace/TV-fongmi/rhino/src/main/assets/js/";

    public static void main(String[] args) throws Exception {
        Context cx = Context.enter();
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(Context.VERSION_ES6);
        VarScope scope = (VarScope) cx.initStandardObjects();
        if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);

        // 与 PluginSandbox.initCtx 一致：先加载 promise.js（提供 __async/微任务泵送）
        evalAsset(cx, scope, "lib/promise.js");

        // 与 PluginHost.evalLib 一致：CommonJS wrapper + Transpile
        Scriptable cheerio = evalLib(cx, scope, "lib/cheerio.min.js", "cheerio");
        System.out.println("[Rhino] cheerio load OK, typeof=" + (cheerio == null ? "null" : cheerio.getClass().getSimpleName()));
        Object version = ScriptableObject.getProperty(cheerio, "version");
        System.out.println("[Rhino] version = " + version);

        // 常用 API 冒烟（与 MusicFree 插件典型用法一致）
        String html = "<ul id=\"list\"><li class=\"item\" data-k=\"v1\">晴天</li><li class=\"item\">七里香<b>intro</b></li></ul>";
        String test = "{ var $ = __mf_lib_cheerio.load(" + jsQuote(html) + ");"
                + " var out = [];"
                + " out.push($('#list').length);"
                + " out.push($('.item').length);"
                + " out.push($('.item[data-k]').attr('data-k'));"
                + " out.push($('.item').first().text());"
                + " out.push($('.item').eq(1).find('b').text());"
                + " out.push($('.item').eq(1).text());"
                + " out.push($('li:nth-child(2)').html());"
                + " var items = []; $('.item').each(function(i, el){ items.push($(el).text()); });"
                + " out.push(items.length); out.push(items[0]);"
                + " out.push($('.item').toArray().length);"
                + " out.join('|'); }";
        // 把 cheerio 挂到 scope（模拟 __mf_lib_cheerio）
        ScriptableObject.putProperty(scope, "__mf_lib_cheerio", cheerio);
        Object result = cx.evaluateString(scope, test, "smoke.js", 1, null);
        System.out.println("[Rhino] smoke result = " + result);
        String expected = "1|2|v1|晴天|intro|七里香intro|&#x4E03;&#x91CC;&#x9999;<b>intro</b>|2|晴天|2";
        if (!expected.equals(String.valueOf(result))) {
            System.out.println("[FAIL] expected: " + expected);
            System.exit(1);
        }
        System.out.println("ALL PASS");
        Context.exit();
    }

    private static Scriptable evalLib(Context cx, VarScope scope, String path, String globalName) {
        String code = readAsset(path);
        if (code == null) return cx.newObject(scope);
        String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n"
                + Transpile.toCommonJs(code) + "\nreturn __module.exports;\n})();";
        Object result;
        try {
            result = cx.evaluateString(scope, wrapped, path, 1, null);
        } catch (Throwable e) {
            System.out.println("[mf-host] 库 " + path + " 加载失败: " + e.getMessage());
            return cx.newObject(scope);
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
        return cx.newObject(scope);
    }

    private static boolean isEmpty(Scriptable obj) {
        for (Object id : obj.getIds()) {
            if (id instanceof String) return false;
        }
        return true;
    }

    private static void evalAsset(Context cx, VarScope scope, String path) {
        String code = readAsset(path);
        if (code == null) throw new IllegalStateException("asset missing: " + path);
        cx.evaluateString(scope, Transpile.toCommonJs(code), path, 1, null);
    }

    private static String readAsset(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(ASSETS + path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}