import com.fongmi.rhino.utils.Transpile;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** JVM harness：验证全部宿主库（cheerio/big-integer/he/dayjs/qs）在 Rhino 解释模式下可加载并正确运行。 */
public class LibHarness {

    private static final String ASSETS = "/workspace/TV-fongmi/rhino/src/main/assets/js/";

    public static void main(String[] args) throws Exception {
        Context cx = Context.enter();
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(Context.VERSION_ES6);
        VarScope scope = (VarScope) cx.initStandardObjects();
        if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);

        // 与 PluginSandbox.initCtx 一致：promise.js（__async / Promise 泵送）
        evalAsset(cx, scope, "lib/promise.js");

        // 各库按 require 表的 evalLib 路径加载
        Scriptable cheerio = evalLib(cx, scope, "lib/cheerio.min.js", "cheerio");
        Scriptable bigInt = evalLib(cx, scope, "lib/big-integer.js", "bigInt");
        Scriptable he = evalLib(cx, scope, "lib/he.js", "he");
        Scriptable dayjs = evalLib(cx, scope, "lib/dayjs.min.js", "dayjs");
        Scriptable qs = evalLib(cx, scope, "lib/qs.js", "qs");

        ScriptableObject.putProperty(scope, "__mf_cheerio", cheerio);
        ScriptableObject.putProperty(scope, "__mf_bigInt", bigInt);
        ScriptableObject.putProperty(scope, "__mf_he", he);
        ScriptableObject.putProperty(scope, "__mf_dayjs", dayjs);
        ScriptableObject.putProperty(scope, "__mf_qs", qs);

        String test =
                "(function(){ var out = [];"

                // cheerio
                + " var $ = __mf_cheerio.load('<ul><li class=\"i\">甲</li><li class=\"i\">乙</li></ul>');"
                + " out.push($('.i').length); out.push($('.i').first().text());"

                // big-integer
                + " var bi = __mf_bigInt('123456789012345678901234567890').multiply(100).toString();"
                + " out.push(bi);"
                + " out.push(__mf_bigInt('999').add('1').toString());"

                // he
                + " out.push(__mf_he.decode('&amp;&lt;&#x4E03;&#37415;'));"
                + " out.push(__mf_he.encode('<>'));"

                // dayjs
                + " out.push(__mf_dayjs('2024-01-02 03:04:05').format('YYYY-MM-DD HH:mm:ss'));"
                + " out.push(__mf_dayjs('2024-01-02').add(1, 'month').format('YYYY-MM-DD'));"
                + " out.push(__mf_dayjs('2024-01-02').unix() ? 'unix-ok' : 'unix-fail');"

                // qs
                + " var s1 = __mf_qs.stringify({ a: 1, b: 'x y', c: [1, 2] }); out.push(s1);"
                + " var s2 = __mf_qs.stringify({ a: [1, 2] }, { arrayFormat: 'brackets' }); out.push(s2);"
                + " var p1 = JSON.stringify(__mf_qs.parse('a=1&b=x+y&c[]=1&c[]=2')); out.push(p1);"
                + " var p2 = JSON.stringify(__mf_qs.parse('a[x]=1&a[y][0]=k')); out.push(p2);"

                + " return out.join('\\n====\\n'); })()";

        Object result;
        try {
            result = cx.evaluateString(scope, test, "lib-smoke.js", 1, null);
        } catch (Throwable e) {
            System.out.println("[FAIL] eval error: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println(String.valueOf(result));
        Context.exit();
    }

    private static Scriptable evalLib(Context cx, VarScope scope, String path, String globalName) {
        String code = readAsset(path);
        String wrapped = "(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n"
                + Transpile.toCommonJs(code) + "\nreturn __module.exports;\n})();";
        Object result;
        try {
            result = cx.evaluateString(scope, wrapped, path, 1, null);
        } catch (Throwable e) {
            System.out.println("[FAIL] 库 " + path + " 加载失败: " + e.getMessage());
            System.exit(1);
            return cx.newObject(scope);
        }
        System.out.println("[OK] load " + path + " -> " + (result == null ? "null" : result.getClass().getSimpleName()));
        if (result instanceof Scriptable) {
            Scriptable obj = (Scriptable) result;
            for (Object id : obj.getIds()) {
                if (id instanceof String) return obj;
            }
        }
        return cx.newObject(scope);
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
}