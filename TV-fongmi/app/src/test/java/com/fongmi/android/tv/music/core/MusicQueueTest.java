package com.fongmi.android.tv.music.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.RepeatMode;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 音乐队列纯逻辑单测（JVM，无 Android 依赖）。 */
public class MusicQueueTest {

    private MusicQueue queue;

    @Before
    public void setUp() {
        queue = new MusicQueue();
    }

    private static MusicMedia media(int n) {
        return new MusicMedia("id" + n, "song" + n, "artist", "album", -1, null, "http://a/" + n, null, null);
    }

    private static List<MusicMedia> list3() {
        List<MusicMedia> list = new ArrayList<>();
        list.add(media(1));
        list.add(media(2));
        list.add(media(3));
        return list;
    }

    // ------------------------------------------------------------ 顺序模式

    @Test
    public void sequential_next_prev_wraps() {
        queue.setQueue(list3(), 0);
        assertEquals("id1", queue.current().id);
        assertNotNull(queue.next());
        assertEquals("id2", queue.current().id);
        queue.next();
        assertEquals("id3", queue.current().id);
        // 末尾回绕到 0（LIST 语义）
        queue.next();
        assertEquals("id1", queue.current().id);
        // prev 回绕到末尾
        queue.prev();
        assertEquals("id3", queue.current().id);
    }

    @Test
    public void play_jumps_without_history() {
        queue.setQueue(list3(), 0);
        queue.play(2);
        assertEquals("id3", queue.current().id);
        queue.next();
        assertEquals("id1", queue.current().id);
    }

    // ------------------------------------------------------------ 单曲模式

    @Test
    public void repeatOne_keeps_current() {
        queue.setQueue(list3(), 1);
        queue.setMode(RepeatMode.ONE);
        MusicMedia current = queue.current();
        assertEquals("id2", current.id);
        queue.next();
        assertEquals("id2", queue.current().id);
        queue.prev();
        assertEquals("id2", queue.current().id);
        assertFalse(queue.hasNext());
        assertFalse(queue.hasPrev());
    }

    // ------------------------------------------------------------ 随机模式

    @Test
    public void shuffle_next_never_repeats_immediately_and_bounds() {
        queue.setQueue(list3(), 0);
        queue.setMode(RepeatMode.SHUFFLE);
        Set<String> seen = new HashSet<>();
        seen.add(queue.current().id);
        for (int i = 0; i < 20; i++) {
            MusicMedia next = queue.next();
            assertNotNull(next);
            assertFalse(seen.contains(next.id) && seen.size() < 3);
            seen.add(next.id);
            assertTrue(queue.position() >= 0 && queue.position() < queue.size());
        }
    }

    @Test
    public void shuffle_prev_walks_back_history() {
        queue.setQueue(list3(), 0);
        queue.setMode(RepeatMode.SHUFFLE);
        String first = queue.current().id;
        queue.next(); // 随机跳到别处
        String second = queue.current().id;
        assertFalse(first.equals(second));
        MusicMedia back = queue.prev();
        assertEquals(first, back.id);
    }

    // ------------------------------------------------------------ 插队 / 追加

    @Test
    public void addNext_inserts_after_current() {
        queue.setQueue(list3(), 0);
        int at = queue.addNext(media(9));
        assertEquals(1, at);
        MusicMedia next = queue.next();
        assertEquals("id9", next.id);
    }

    @Test
    public void append_grows_and_starts_when_empty() {
        assertTrue(queue.isEmpty());
        queue.append(media(1));
        queue.append(media(2));
        assertEquals(2, queue.size());
        assertNotNull(queue.current());
        assertEquals("id1", queue.current().id);
    }

    // ------------------------------------------------------------ 移除

    @Test
    public void remove_before_current_adjusts_position() {
        queue.setQueue(list3(), 2); // 当前 id3
        queue.remove(0);            // 移除 id1
        assertEquals(2, queue.size());
        assertEquals(1, queue.position()); // 当前曲下标前移
        assertEquals("id3", queue.current().id);
    }

    @Test
    public void remove_current_falls_back_to_next() {
        queue.setQueue(list3(), 1); // 当前 id2
        queue.remove(1);
        assertEquals(2, queue.size());
        assertEquals(1, queue.position());
        assertEquals("id3", queue.current().id);
    }

    @Test
    public void remove_last_item_clears() {
        queue.setQueue(new ArrayList<>(java.util.Collections.singletonList(media(1))), 0);
        queue.remove(0);
        assertTrue(queue.isEmpty());
        assertEquals(-1, queue.position());
    }

    // ------------------------------------------------------------ 记住位置

    @Test
    public void position_restores_after_reload() {
        queue.setQueue(list3(), 1);
        assertEquals(1, queue.position());
        // 模拟重建：同一队列数据 + 记住的下标
        MusicQueue rebuilt = new MusicQueue();
        rebuilt.setQueue(list3(), 1);
        assertEquals(1, rebuilt.position());
        assertEquals("id2", rebuilt.current().id);
    }

    @Test
    public void set_queue_empty_resets() {
        queue.setQueue(list3(), 0);
        queue.setQueue(new ArrayList<>(), 0);
        assertTrue(queue.isEmpty());
        assertEquals(-1, queue.position());
        assertNull(queue.current());
    }
}