package com.fongmi.rhino.method;

import android.net.Uri;

import com.fongmi.rhino.bean.Req;
import com.fongmi.rhino.utils.Connect;
import com.fongmi.rhino.utils.JSUtil;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.UriUtil;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;

import java.io.IOException;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/** 全局 JS API 桥：http/req/_http、setTimeout、加解密、简繁、代理、local、console、__tick。 */
public class Global {

    private final Map<Integer, Timeout> timers;
    private final ExecutorService executor;
    private final AtomicInteger timerId;
    private final Context cx;
    private final Scriptable scope;
    private final Timer timer;
    private final Local local;

    private volatile boolean destroyed;

    private Global(Context cx, Scriptable scope, ExecutorService executor) {
        this.executor = executor;
        this.timerId = new AtomicInteger();
        this.timers = new ConcurrentHashMap<>();
        this.timer = new Timer("rhino-timer", true);
        this.cx = cx;
        this.scope = scope;
        this.local = new Local();
        setProperty();
    }

    public static Global create(Context cx, Scriptable scope, ExecutorService executor) {
        return new Global(cx, scope, executor);
    }

    public void destroy() {
        destroyed = true;
        for (Timeout timeout : timers.values()) timeout.cancelAndRelease();
        timers.clear();
        timer.cancel();
    }

    private void setProperty() {
        ScriptableObject g = (ScriptableObject) scope;
        Scriptable console = cx.newObject(scope);
        ScriptableObject.putProperty(console, "log", fn(args -> consoleLog(args)));
        ScriptableObject.putProperty(console, "info", fn(args -> consoleLog(args)));
        ScriptableObject.putProperty(console, "warn", fn(args -> consoleLog(args)));
        ScriptableObject.putProperty(console, "error", fn(args -> consoleLog(args)));
        ScriptableObject.putProperty(console, "debug", fn(args -> consoleLog(args)));
        ScriptableObject.putProperty(g, "console", console);

        ScriptableObject l = cx.newObject(scope);
        JSUtil.bind(cx, scope, l, "get", args -> local.get(str(args, 0), str(args, 1)));
        JSUtil.bind(cx, scope, l, "set", args -> {
            local.set(str(args, 0), str(args, 1), str(args, 2));
            return null;
        });
        JSUtil.bind(cx, scope, l, "delete", args -> {
            local.delete(str(args, 0), str(args, 1));
            return null;
        });
        ScriptableObject.putProperty(g, "local", l);

        JSUtil.bind(cx, scope, g, "s2t", args -> Trans.s2t(false, str(args, 0)));
        JSUtil.bind(cx, scope, g, "t2s", args -> Trans.t2s(false, str(args, 0)));
        JSUtil.bind(cx, scope, g, "getPort", args -> Proxy.getPort());
        JSUtil.bind(cx, scope, g, "getProxy", args -> Proxy.getUrl(bool(args, 0)) + "?do=js");
        JSUtil.bind(cx, scope, g, "js2Proxy", args -> js2Proxy(args));
        JSUtil.bind(cx, scope, g, "setTimeout", args -> setTimeout(args));
        JSUtil.bind(cx, scope, g, "clearTimeout", args -> {
            cancel(intOf(args, 0));
            return null;
        });
        JSUtil.bind(cx, scope, g, "_http", args -> _http(args));
        JSUtil.bind(cx, scope, g, "req", args -> req(args));
        JSUtil.bind(cx, scope, g, "joinUrl", args -> UriUtil.resolve(str(args, 0), str(args, 1)));
        JSUtil.bind(cx, scope, g, "md5X", args -> Crypto.md5(str(args, 0)));
        JSUtil.bind(cx, scope, g, "aesX", args -> Crypto.aes(str(args, 0), bool(args, 1), str(args, 2), bool(args, 3), str(args, 4), str(args, 5), bool(args, 6)));
        JSUtil.bind(cx, scope, g, "desX", args -> Crypto.des(str(args, 0), bool(args, 1), str(args, 2), bool(args, 3), str(args, 4), str(args, 5), bool(args, 6)));
        JSUtil.bind(cx, scope, g, "rsaX", args -> Crypto.rsa(str(args, 0), bool(args, 1), bool(args, 2), str(args, 3), bool(args, 4), str(args, 5), bool(args, 6)));
        JSUtil.bind(cx, scope, g, "__tick", args -> tick(args));
    }

    // ------------------------------------------------------------ helpers

    private Object consoleLog(Object[] args) {
        if (args == null || args.length == 0) return null;
        Console.log(otherToString(args[0]));
        return null;
    }

    private static String otherToString(Object o) {
        return o == null ? "null" : String.valueOf(o);
    }

    private static String str(Object[] args, int i) {
        if (args == null || i >= args.length || args[i] == null || args[i] instanceof org.htmlunit.corejs.javascript.Undefined) return "";
        return Context.toString(args[i]);
    }

    private static boolean bool(Object[] args, int i) {
        if (args == null || i >= args.length || args[i] == null) return false;
        return Boolean.TRUE.equals(args[i]) || (args[i] instanceof Number && ((Number) args[i]).doubleValue() != 0);
    }

    private static int intOf(Object[] args, int i) {
        if (args == null || i >= args.length || args[i] == null) return 0;
        if (args[i] instanceof Number) return ((Number) args[i]).intValue();
        return 0;
    }

    private static Scriptable scriptableOf(Scriptable scope, Object[] args, int i) {
        if (args == null || i >= args.length || !(args[i] instanceof Scriptable)) return null;
        Scriptable s = (Scriptable) args[i];
        return s instanceof ScriptableObject ? (ScriptableObject) s : s;
    }

    private static org.htmlunit.corejs.javascript.BaseFunction fn(final JSUtil.JsFn fn) {
        return new org.htmlunit.corejs.javascript.BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    return fn.apply(args);
                } catch (Throwable e) {
                    return Context.getUndefinedValue();
                }
            }
        };
    }

    private Object js2Proxy(Object[] args) {
        Boolean dynamic = args.length > 0 && args[0] instanceof Boolean ? (Boolean) args[0] : null;
        Integer siteType = intOf(args, 1);
        String siteKey = str(args, 2);
        String url = str(args, 3);
        Scriptable headers = scriptableOf(scope, args, 4);
        return getProxy(dynamic != null && !dynamic) + String.format("&from=catvod&siteType=%s&siteKey=%s&header=%s&url=%s", siteType, siteKey, Uri.encode(JSUtil.stringify(cx, scope, headers == null ? scope : headers)), Uri.encode(url));
    }

    private Object setTimeout(Object[] args) {
        Function func = args.length > 0 && args[0] instanceof Function ? (Function) args[0] : null;
        if (func == null || destroyed) return 0;
        Timeout timeout = new Timeout(timerId.incrementAndGet(), func);
        timers.put(timeout.id, timeout);
        try {
            timer.schedule(timeout, Math.max(0, intOf(args, 1)));
            return timeout.id;
        } catch (Throwable e) {
            cancel(timeout.id);
            return 0;
        }
    }

    private void cancel(Integer id) {
        if (id == null) return;
        Timeout timeout = timers.remove(id);
        if (timeout != null) timeout.cancelAndRelease();
    }

    private Object _http(Object[] args) {
        String url = str(args, 0);
        Scriptable options = scriptableOf(scope, args, 1);
        if (options == null) return req(args);
        Object complete = ScriptableObject.getProperty(options, "complete");
        if (!(complete instanceof Function)) return req(args);
        requestAsync(url, options, (Function) complete);
        return null;
    }

    private Object req(Object[] args) {
        String url = str(args, 0);
        Scriptable options = scriptableOf(scope, args, 1);
        try {
            Req req = Req.objectFrom(options == null ? "{}" : JSUtil.stringify(cx, scope, options));
            Response res = Connect.to(url, req).execute();
            return Connect.success(cx, scope, req, res);
        } catch (Exception e) {
            return Connect.error(cx, scope);
        }
    }

    private Object tick(Object[] args) {
        Function fn = args.length > 0 && args[0] instanceof Function ? (Function) args[0] : null;
        if (fn == null || destroyed) return null;
        try {
            executor.submit(() -> {
                if (destroyed) return null;
                return fn.call(cx, scope, scope, new Object[0]);
            });
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void requestAsync(String url, Scriptable options, Function complete) {
        try {
            Req req = Req.objectFrom(JSUtil.stringify(cx, scope, options));
            Connect.to(url, req).enqueue(getCallback(complete, req));
        } catch (Throwable e) {
            completeError(complete);
        }
    }

    private Callback getCallback(final Function complete, final Req req) {
        return new Callback() {
            @Override
            public void onResponse(okhttp3.Call call, Response res) {
                completeSuccess(complete, req, res);
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                completeError(complete);
            }
        };
    }

    private void completeSuccess(Function complete, Req req, Response res) {
        postCallback(complete, () -> complete.call(cx, scope, scope, new Object[]{Connect.success(cx, scope, req, res)}));
    }

    private void completeError(Function complete) {
        postCallback(complete, () -> complete.call(cx, scope, scope, new Object[]{Connect.error(cx, scope)}));
    }

    private boolean postCallback(Function callback, Runnable runnable) {
        boolean posted = submit(runnable);
        return posted;
    }

    private boolean submit(Runnable runnable) {
        try {
            if (destroyed) return false;
            if (executor.isShutdown()) return false;
            executor.submit(runnable);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private class Timeout extends TimerTask {

        private final Function func;
        private final int id;
        private volatile boolean canceled;

        private Timeout(int id, Function func) {
            this.func = func;
            this.id = id;
        }

        @Override
        public void run() {
            if (submit(this::fire)) return;
            Global.this.cancel(id);
        }

        private void fire() {
            if (canceled) return;
            try {
                func.call(cx, scope, scope, new Object[0]);
            } finally {
                Global.this.cancel(id);
            }
        }

        private synchronized void cancelAndRelease() {
            canceled = true;
            cancel();
        }
    }
}