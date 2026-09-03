package com.fongmi.rhino.plugin;

import com.fongmi.rhino.method.Global;
import com.fongmi.rhino.utils.Async;
import com.fongmi.rhino.utils.JSUtil;
import com.fongmi.rhino.utils.Transpile;
import com.github.catvod.utils.Asset;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MusicFree 插件沙箱（Rhino 侧实现，契约对齐 RN {@code evalSandbox.ts} 的 {@code IPluginSandbox}）。
 * <p>
 * 生命周期：ready()（解释模式 + ES6 + 宿主绑定）→ load(code)（转译 + 8 参 CJS 包装）→ call(method, args)
 * （Async 桥回 Promise）→ destroy()。所有 Rhino 操作收敛在单一 executor 线程，与 Spider 桥同款模型。
 */
public final class PluginSandbox {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PluginHost.Variables variables;
    private final String appVersion;

    private volatile Context cx;
    private volatile VarScope scope;
    private volatile Global global;
    private volatile PluginHost host;
    private volatile Scriptable instance;
    private volatile boolean ready;
    private volatile boolean destroyed;

    private PluginSandbox(PluginHost.Variables variables, String appVersion) {
        this.variables = variables;
        this.appVersion = appVersion;
    }

    public static PluginSandbox create() {
        return new PluginSandbox(null, null);
    }

    public static PluginSandbox create(PluginHost.Variables variables, String appVersion) {
        return new PluginSandbox(variables, appVersion);
    }

    /** 初始化引擎：进入上下文、绑定宿主。幂等。 */
    public void ready() {
        if (ready) return;
        try {
            executor.submit(() -> {
                initCtx();
                host = new PluginHost(cx, scope, variables, appVersion);
                host.bind();
                return null;
            }).get();
            ready = true;
        } catch (Exception e) {
            throw new RuntimeException("rhino plugin runtime init failed", e);
        }
    }

    /** 退出上下文并释放资源。 */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try {
            executor.submit(() -> {
                if (global != null) global.destroy();
                Context.exit();
                return null;
            }).get();
        } catch (Throwable ignored) {
        }
        executor.shutdownNow();
    }

    /** 同步加载插件源码，返回插件定义（IPluginDefine 的 Rhino Scriptable 镜像，含 platform 等方法）。 */
    public Scriptable load(final String code) {
        ready();
        try {
            return executor.submit(() -> {
                String content = Transpile.toCommonJs(code);
                // 与 RN evalSandbox 相同的 8 参注入顺序，保证插件可移植
                String wrapped = "(function(){\n'use strict';\nvar __module = { exports: {} };\n"
                        + "(function(require, __musicfree_require, module, exports, console, env, URL, process) {\n"
                        + content
                        + "\n})(__mf_req, __mf_req, __module, __module.exports, console, __mf_env, __mf_url, __mf_proc);\n"
                        + "return __module.exports;\n})();";
                Object obj = cx.evaluateString(scope, wrapped, "plugin.js", 1, null);
                this.instance = obj instanceof Scriptable ? (Scriptable) obj : (Scriptable) cx.newObject(scope);
                // babel 转译产物兼容：exports.default
                Object def = ScriptableObject.getProperty(this.instance, "default");
                if (def instanceof Scriptable) this.instance = (Scriptable) def;
                Object name = ScriptableObject.getProperty(this.instance, "platform");
                if (host != null) host.setPluginName(name instanceof CharSequence ? name.toString() : "");
                return this.instance;
            }).get();
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException("plugin load failed", cause);
        }
    }

    /** 调用插件方法（方法可返回 Promise），异步桥回 Java CompletableFuture。 */
    public CompletableFuture<Object> call(final String method, final Object... args) {
        final CompletableFuture<Object> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                Async.run(cx, scope, instance, method, args).whenComplete((result, error) -> {
                    if (error != null) future.completeExceptionally(error);
                    else future.complete(result);
                });
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** 同步调用（阻塞至结果返回）。 */
    public Object callSync(String method, Object... args) throws Exception {
        return call(method, args).get();
    }

    /** 当前插件实例（未加载时为 null）。 */
    public Scriptable instance() {
        return instance;
    }

    /** 插件声明的方法名集合（与 RN supportedMethods 等价的本地查询）。 */
    public boolean hasMethod(String method) {
        if (instance == null || method == null) return false;
        Object fn = ScriptableObject.getProperty(instance, method);
        return fn instanceof Function;
    }

    private void initCtx() {
        cx = Context.enter();
        cx.setOptimizationLevel(-1); // 解释模式：体积与兼容优先
        cx.setLanguageVersion(Context.VERSION_ES6);
        scope = (VarScope) cx.initStandardObjects();
        if (!ScriptableObject.hasProperty(scope, "globalThis")) ScriptableObject.putProperty(scope, "globalThis", scope);
        // Global 桥：console/setTimeout/req/_http/加解密/__tick（Promise 微任务泵送）等
        global = Global.create(cx, scope, executor);
        cx.evaluateString(scope, Asset.read("js/lib/promise.js"), "promise.js", 1, null);
        cx.evaluateString(scope, Asset.read("js/lib/http.js"), "http.js", 1, null);
    }
}