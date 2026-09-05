package com.fongmi.android.tv.reader;

import java.util.ArrayList;
import java.util.List;

/** 书籍与章节模型（书源搜索/详情/目录/正文共用）。 */
public final class Book {

    /** 详情页 URL（点击搜索项进入目录/简介）。 */
    public String url = "";
    public String name = "";
    public String author = "";
    public String cover = "";
    public String intro = "";
    /** 所属书源 url。 */
    public String source = "";

    public static final class Chapter {
        public String name = "";
        public String url = "";

        public Chapter(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public final List<Chapter> chapters = new ArrayList<>();
    /** 当前阅读的章节（正文字符串 HTML 片段，直接给 WebView 渲染）。 */
    public String content = "";
    public int chapterIndex = -1;

    public Book() {
    }

    public Book(String url, String name, String author, String cover) {
        this.url = url;
        this.name = name == null ? "" : name;
        this.author = author == null ? "" : author;
        this.cover = cover == null ? "" : cover;
    }
}