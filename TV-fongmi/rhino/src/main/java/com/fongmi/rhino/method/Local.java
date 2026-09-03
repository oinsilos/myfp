package com.fongmi.rhino.method;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

/** local 桥：local.get/set/delete 本地缓存读写。 */
public final class Local {

    private String getKey(String rule, String key) {
        return "cache_" + (TextUtils.isEmpty(rule) ? "" : rule + "_") + key;
    }

    public String get(String rule, String key) {
        return Prefers.getString(getKey(rule, key));
    }

    public void set(String rule, String key, String value) {
        Prefers.put(getKey(rule, key), value);
    }

    public void delete(String rule, String key) {
        Prefers.remove(getKey(rule, key));
    }
}