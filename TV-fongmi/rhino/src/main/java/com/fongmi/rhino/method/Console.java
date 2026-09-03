package com.fongmi.rhino.method;

import com.orhanobut.logger.Logger;

/** console 桥。methods 作为 console.log/info/warn/error/debug 调用。 */
public final class Console {

    private static final String TAG = "rhino";

    public static void log(Object info) {
        Logger.t(TAG).d(String.valueOf(info));
    }

    public static void info(Object info) {
        Logger.t(TAG).i(String.valueOf(info));
    }

    public static void warn(Object info) {
        Logger.t(TAG).w(String.valueOf(info));
    }

    public static void error(Object info) {
        Logger.t(TAG).e(String.valueOf(info));
    }

    public static void debug(Object info) {
        Logger.t(TAG).d(String.valueOf(info));
    }
}