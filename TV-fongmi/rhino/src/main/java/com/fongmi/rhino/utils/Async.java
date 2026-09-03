package com.fongmi.rhino.utils;

import org.htmlunit.corejs.javascript.BaseFunction;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Function;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.util.concurrent.CompletableFuture;

/** 调用 JS 函数并把（可能返回的）Promise 桥回 Java CompletableFuture。 */
public class Async {

    private final Context cx;
    private final VarScope scope;
    private CompletableFuture<Object> future;

    private final Function success = new BaseFunction() {
        @Override
        public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
            future.complete(args != null && args.length > 0 ? args[0] : null);
            return Context.getUndefinedValue();
        }
    };

    private final Function error = new BaseFunction() {
        @Override
        public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
            String msg = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
            future.completeExceptionally(new Exception(msg));
            return Context.getUndefinedValue();
        }
    };

    private Async(Context cx, VarScope scope) {
        this.cx = cx;
        this.scope = scope;
        this.future = new CompletableFuture<>();
    }

    public static CompletableFuture<Object> run(Context cx, VarScope scope, Scriptable object, String name, Object... args) {
        return new Async(cx, scope).call(object, name, args);
    }

    private CompletableFuture<Object> call(Scriptable object, String name, Object... args) {
        Object func = ScriptableObject.getProperty(object, name);
        if (!(func instanceof Function)) {
            future.complete(null);
            return future;
        }
        call((Function) func, args);
        return future;
    }

    private void call(Function func, Object... args) {
        try {
            Object result = func.call(cx, scope, object(Scriptable.NOT_FOUND), args);
            if (result instanceof Scriptable) then((Scriptable) result);
            else future.complete(result);
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
    }

    /** 备用 this 值：物化为普通对象，避免把 VarScope 当作 this。 */
    private Scriptable object(Object fallback) {
        return fallback instanceof Scriptable ? (Scriptable) fallback : cx.newObject(scope);
    }

    private void then(Scriptable promise) {
        Object then = ScriptableObject.getProperty(promise, "then");
        if (then instanceof Function) {
            try {
                ((Function) then).call(cx, scope, promise, new Object[]{success});
            } catch (Throwable ignored) {
            }
            Object cat = ScriptableObject.getProperty(promise, "catch");
            if (cat instanceof Function) {
                try {
                    ((Function) cat).call(cx, scope, promise, new Object[]{error});
                } catch (Throwable ignored) {
                }
            }
        } else {
            future.complete(promise);
        }
    }
}