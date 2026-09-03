package com.fongmi.rhino.crawler;

import dalvik.system.DexClassLoader;

public class Loader {

    public Loader() {
    }

    public Spider spider(String api, DexClassLoader dex) {
        return new Spider(api, dex);
    }
}