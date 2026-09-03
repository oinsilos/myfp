package com.fongmi.rhino.utils;

import org.htmlunit.corejs.javascript.BaseFunction;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.util.List;
import java.util.Map;

/** Rhino（htmlunit-core-js fork）环境下的 JS ↔ Java 转换工具。 */
public final class JSUtil {

    private JSUtil() {
    }

    public static Scriptable toArray(Context cx, VarScope scope, List<String> items) {
        Scriptable array = cx.newArray(scope, items == null ? 0 : items.size());
        if (items == null) return array;
        for (int i = 0; i < items.size(); i++) ScriptableObject.putProperty(array, i, items.get(i));
        return array;
    }

    public static Scriptable toArray(Context cx, VarScope scope, byte[] bytes) {
        Scriptable array = cx.newArray(scope, bytes == null ? 0 : bytes.length);
        if (bytes == null) return array;
        for (int i = 0; i < bytes.length; i++) ScriptableObject.putProperty(array, i, (int) bytes[i]);
        return array;
    }

    public static Scriptable toObject(Context cx, VarScope scope, Map<String, String> map) {
        Scriptable obj = cx.newObject(scope);
        if (map == null) return obj;
        for (String key : map.keySet()) ScriptableObject.putProperty(obj, key, map.get(key));
        return obj;
    }

    public static Object get(Scriptable object, String key) {
        return object == null ? Scriptable.NOT_FOUND : ScriptableObject.getProperty(object, key);
    }

    /** 将 JS 对象序列化为 JSON 字符串。 */
    public static String stringify(Context cx, VarScope scope, Scriptable object) {
        try {
            ScriptableObject.putProperty(scope, "__json__", object);
            return Context.toString(cx.evaluateString(scope, "JSON.stringify(__json__)", "stringify", 1, null));
        } catch (Throwable e) {
            return Context.toString(object);
        } finally {
            ScriptableObject.deleteProperty(scope, "__json__");
        }
    }

    /** 向 parent 上绑定一个 name 函数。 */
    public static void bind(Context cx, VarScope scope, Scriptable parent, String name, final JsFn fn) {
        BaseFunction f = new BaseFunction() {
            @Override
            public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
                try {
                    Object result = fn.apply(args);
                    return result == null ? Context.getUndefinedValue() : result;
                } catch (Throwable e) {
                    return Context.getUndefinedValue();
                }
            }
        };
        ScriptableObject.putProperty(parent, name, f);
    }

    public interface JsFn {
        Object apply(Object[] args) throws Throwable;
    }
}