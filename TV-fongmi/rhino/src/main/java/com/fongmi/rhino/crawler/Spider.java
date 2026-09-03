package com.fongmi.rhino.crawler;

import android.content.Context;

import com.fongmi.rhino.bean.Res;
import com.fongmi.rhino.method.Global;
import com.fongmi.rhino.utils.Async;
import com.fongmi.rhino.utils.JSUtil;
import com.fongmi.rhino.utils.Module;
import com.fongmi.rhino.utils.Require;
import com.fongmi.rhino.utils.Transpile;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;

import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dalvik.system.DexClassLoader;

/** 基于 Rhino（htmlunit-core-js fork，与 legado 同源引擎）的 Spider 桥。 */
public class Spider extends com.github.catvod.crawler.Spider {

    private final ExecutorService executor;
    private final DexClassLoader dex;
    private final String api;

    private volatile org.htmlunit.corejs.javascript.Context cx;
    private volatile VarScope scope;
    private volatile Scriptable jsObject;
    private Global global;
    private volatile boolean cat;

    public Spider(String api, DexClassLoader dex) {
        this.executor = Executors.newSingleThreadExecutor();
        this.api = api;
        this.dex = dex;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    private Object call(String func, Object... args) throws Exception {
        return submit(() -> Async.run(cx, scope, jsObject, func, args)).get().get();
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        initializeJS();
        call("init", submit(() -> getExt(extend)).get());
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return (String) call("home", filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return (String) call("homeVod");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        Scriptable obj = submit(() -> JSUtil.toObject(cx, scope, extend)).get();
        return (String) call("category", tid, pg, filter, obj);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return (String) call("detail", ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return (String) call("search", key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return (String) call("search", key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Scriptable array = submit(() -> JSUtil.toArray(cx, scope, vipFlags)).get();
        return (String) call("play", flag, id, array);
    }

    @Override
    public String liveContent(String url) throws Exception {
        return (String) call("live", url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return (Boolean) call("sniffer");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return (Boolean) call("isVideo", url);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        return "catvod".equals(params.get("from")) ? proxy2(params) : proxy1(params);
    }

    @Override
    public String action(String action) throws Exception {
        return (String) call("action", action);
    }

    @Override
    public void destroy() {
        try {
            call("destroy");
        } catch (Throwable e) {
            e.printStackTrace();
        }
        try {
            releaseJS();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
        }
    }

    private void releaseJS() throws Exception {
        submit(() -> {
            if (global != null) global.destroy();
            if (cx != null) org.htmlunit.corejs.javascript.Context.exit();
            return null;
        }).get();
    }

    private void initializeJS() throws Exception {
        submit(() -> {
            createCtx();
            createObj();
            return null;
        }).get();
    }

    private void createCtx() {
        cx = org.htmlunit.corejs.javascript.Context.enter();
        cx.setOptimizationLevel(-1); // 解释模式：体积与兼容优先，避免生成类/字节码
        cx.setLanguageVersion(org.htmlunit.corejs.javascript.Context.VERSION_ES6);
        scope = (VarScope) cx.initStandardObjects();
        if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);
        global = Global.create(cx, scope, executor);
        new Require(cx, scope).bind();
        cx.evaluateString(scope, Asset.read("js/lib/promise.js"), "promise.js", 1, null);
        cx.evaluateString(scope, Asset.read("js/lib/http.js"), "http.js", 1, null);
    }

    private void createObj() {
        String content = Module.get().fetch(api);
        cat = content.contains("__jsEvalReturn");
        String code = Transpile.toCommonJs(content);
        String wrapped = "(function(){\nvar __module = { exports: {} };\nglobalThis.__module = __module;\n" + code + "\n})();";
        cx.evaluateString(scope, wrapped, api, 1, null);
        cx.evaluateString(scope, Asset.read("js/lib/spider.js"), "spider.js", 1, null);
        Object js = ScriptableObject.getProperty(scope, "__JS_SPIDER__");
        jsObject = js instanceof Scriptable ? (Scriptable) js : cx.newObject(scope);
        ScriptableObject.deleteProperty(scope, "__module");
    }

    private Object getExt(String ext) {
        if (!cat) return Json.isObj(ext) ? parse(ext) : ext;
        Scriptable obj = cx.newObject(scope);
        ScriptableObject.putProperty(obj, "stype", 3);
        ScriptableObject.putProperty(obj, "skey", siteKey);
        if (!Json.isObj(ext)) ScriptableObject.putProperty(obj, "ext", ext);
        else ScriptableObject.putProperty(obj, "ext", parse(ext));
        return obj;
    }

    private Scriptable parse(String ext) {
        try {
            return (Scriptable) cx.evaluateString(scope, "(" + ext + ")", "ext", 1, null);
        } catch (Throwable e) {
            return cx.newObject(scope);
        }
    }

    private Object[] proxy1(Map<String, String> params) throws Exception {
        Scriptable obj = submit(() -> JSUtil.toObject(cx, scope, params)).get();
        Object proxy = call("proxy", obj);
        Scriptable array = proxy instanceof Scriptable ? (Scriptable) proxy : null;
        String json = array == null ? "[]" : submit(() -> JSUtil.stringify(cx, scope, array)).get();
        JSONArray jsonArray = new JSONArray(json);
        Map<String, String> headers = jsonArray.length() > 3 ? Json.toMap(jsonArray.optString(3)) : null;
        boolean base64 = jsonArray.length() > 4 && jsonArray.optInt(4) == 1;
        Object[] result = new Object[4];
        result[0] = jsonArray.optInt(0);
        result[1] = jsonArray.optString(1);
        result[2] = getStream(jsonArray.opt(2), base64);
        result[3] = headers;
        return result;
    }

    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        Scriptable array = submit(() -> JSUtil.toArray(cx, scope, Arrays.asList(url.split("/")))).get();
        Object object = submit(() -> parse(header)).get();
        String proxy = (String) call("proxy", array, object);
        Res res = Res.objectFrom(proxy);
        Object[] result = new Object[3];
        result[0] = res.getCode();
        result[1] = res.getContentType();
        result[2] = res.getStream();
        return result;
    }

    private ByteArrayInputStream getStream(Object o, boolean base64) {
        if (o instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) o);
        } else {
            String content = String.valueOf(o);
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
        }
    }
}