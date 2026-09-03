package com.fongmi.android.tv.music.model;

/** 播放模式：列表循环 / 单曲循环 / 随机播放。 */
public enum RepeatMode {

    /** 列表循环：播完自动下一首，末尾回到列表头。 */
    LIST,
    /** 单曲循环：next/prev 均保持在当前曲。 */
    ONE,
    /** 随机播放：next 随机挑一首，prev 沿播放历史回退。 */
    SHUFFLE
}