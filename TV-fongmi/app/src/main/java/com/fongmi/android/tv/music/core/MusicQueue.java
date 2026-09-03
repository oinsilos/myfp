package com.fongmi.android.tv.music.core;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.RepeatMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 音乐播放队列（纯逻辑，无 Android 依赖，可 JVM 单测）。
 * <p>
 * 语义对齐常见音乐播放器：
 * - LIST：next 递增（末尾回到 0），prev 递减（开头回到末尾）
 * - ONE：next/prev 均留在当前曲
 * - SHUFFLE：next 随机选一首（排除刚播的且不立即重复当前），prev 沿播放历史回退
 * <p>
 * 额外能力：addNext 插队（下次播放优先）、remove 移除并校正定位、
 * 位置记忆（position 持久在队列上，重建后仍可恢复）。
 */
public final class MusicQueue {

    private final Random random = new Random();
    private final List<MusicMedia> queue = new ArrayList<>();
    private final List<Integer> history = new ArrayList<>();

    private int position = -1;
    private RepeatMode mode = RepeatMode.LIST;

    // ------------------------------------------------------------ 基础访问

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int position() {
        return position;
    }

    public RepeatMode mode() {
        return mode;
    }

    public void setMode(RepeatMode mode) {
        this.mode = mode == null ? RepeatMode.LIST : mode;
        if (this.mode != RepeatMode.SHUFFLE) history.clear();
    }

    public MusicMedia current() {
        return indexOf(position);
    }

    private MusicMedia indexOf(int index) {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    /** 按所在位置取媒体（供 UI 列表展示）。 */
    public MusicMedia get(int index) {
        return indexOf(index);
    }

    public List<MusicMedia> items() {
        return Collections.unmodifiableList(queue);
    }

    // ------------------------------------------------------------ 队列装载

    /** 整表替换队列并跳转到 startIndex（记住位置恢复入口）。 */
    public void setQueue(List<MusicMedia> items, int startIndex) {
        queue.clear();
        history.clear();
        if (items != null) queue.addAll(items);
        position = (startIndex >= 0 && startIndex < queue.size()) ? startIndex : (queue.isEmpty() ? -1 : 0);
    }

    public void clear() {
        queue.clear();
        history.clear();
        position = -1;
    }

    // ------------------------------------------------------------ 播放游标

    /** 跳到指定位置播放。 */
    public MusicMedia play(int index) {
        if (queue.isEmpty()) return null;
        if (index < 0 || index >= queue.size()) return null;
        history.clear();
        position = index;
        return current();
    }

    public boolean hasNext() {
        if (queue.isEmpty() || mode == RepeatMode.ONE) return false;
        if (mode == RepeatMode.SHUFFLE) return queue.size() > 1;
        return true; // LIST：始终可 next（末尾回绕）
    }

    public boolean hasPrev() {
        if (queue.isEmpty() || mode == RepeatMode.ONE) return false;
        if (mode == RepeatMode.SHUFFLE) return history.size() > 0;
        return true;
    }

    /** 下一首：返回 null 表示无可播放项（队列空）。 */
    public MusicMedia next() {
        if (queue.isEmpty()) return null;
        switch (mode) {
            case ONE:
                return current();
            case SHUFFLE:
                return nextShuffle();
            default:
                return nextSequential();
        }
    }

    /** 上一首：SHUFFLE 回退历史，ONE 原地，LIST 回绕。 */
    public MusicMedia prev() {
        if (queue.isEmpty()) return null;
        switch (mode) {
            case ONE:
                return current();
            case SHUFFLE:
                return prevShuffle();
            default:
                return prevSequential();
        }
    }

    /** 插队：插入到当前播放位置之后，下一首优先播放；返回该媒体在列表中的位置。 */
    public int addNext(MusicMedia media) {
        if (media == null) return -1;
        int insert = position < 0 ? queue.size() : position + 1;
        queue.add(insert, media);
        if (position < 0) position = 0;
        return insert;
    }

    /** 追加到队列末尾。 */
    public int append(MusicMedia media) {
        if (media == null) return -1;
        queue.add(media);
        if (position < 0) position = 0;
        return queue.size() - 1;
    }

    /**
     * 移除指定位置：返回被移除的媒体。
     * 若移除的是当前播放项：无后继则 position 指向新队尾（或 -1），有后继则指向原位置（下一首顶上）。
     */
    public MusicMedia remove(int index) {
        if (index < 0 || index >= queue.size()) return null;
        MusicMedia removed = queue.remove(index);
        if (queue.isEmpty()) {
            position = -1;
            return removed;
        }
        if (index < position) {
            position--; // 移除在当前项之前，当前项后移一位
        } else if (index == position) {
            position = Math.min(index, queue.size() - 1);
        }
        // index > position：不调整
        // 历史中的下标引用不再准确，随机模式直接清空回退栈
        if (mode == RepeatMode.SHUFFLE) history.clear();
        return removed;
    }

    // ------------------------------------------------------------ 模式实现

    private MusicMedia nextSequential() {
        history.clear();
        position = nextIndex(position);
        return current();
    }

    private int nextIndex(int from) {
        if (from < 0) return queue.isEmpty() ? -1 : 0;
        return (from + 1) % queue.size(); // LIST 末尾回绕到 0
    }

    private MusicMedia prevSequential() {
        history.clear();
        if (position < 0) position = queue.size() - 1;
        else position = (position - 1 + queue.size()) % queue.size();
        return current();
    }

    private MusicMedia nextShuffle() {
        if (queue.size() <= 1) return current();
        // 记录当前曲到历史（可回退），paylaod 保持
        remember(position);
        int picked = pickRandomExcept(position);
        position = picked;
        return current();
    }

    private MusicMedia prevShuffle() {
        if (history.isEmpty()) return current();
        int back = history.remove(history.size() - 1);
        if (back >= 0 && back < queue.size()) position = back;
        return current();
    }

    /** 随机选一个 != exclude 的位置。 */
    private int pickRandomExcept(int exclude) {
        int candidates = queue.size() - 1;
        if (candidates <= 0) return exclude;
        int offset = random.nextInt(candidates);
        int picked = (exclude + 1 + offset) % queue.size();
        return picked;
    }

    private void remember(int index) {
        if (index < 0) return;
        history.add(index);
        // 简单防膨胀：只保留最近 64 步
        if (history.size() > 64) history.remove(0);
    }
}