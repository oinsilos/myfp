package com.fongmi.android.tv.music.core;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;

import com.fongmi.android.tv.music.model.MusicMedia;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐扫描：MediaStore.Audio 查询设备上的 mp3（content:// 播放）。
 * <p>
 * 仅收录 audio/mpeg：播放内核为「单 Mp3Extractor」直解（见 MusicPlayer），
 * flac/m4a 等容器暂不进入本地曲库，避免解析失败误导用户。
 */
public final class LocalMusicScanner {

    private LocalMusicScanner() {
    }

    /** 扫描本地 mp3（需 READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE 权限）。 */
    public static List<MusicMedia> scan(Context context) {
        List<MusicMedia> out = new ArrayList<>();
        if (context == null) return out;
        try {
            android.net.Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
            };
            String selection = "((" + MediaStore.Audio.Media.IS_MUSIC + " IS NULL) OR "
                    + MediaStore.Audio.Media.IS_MUSIC + " != 0) AND "
                    + MediaStore.Audio.Media.MIME_TYPE + "='audio/mpeg'";
            Cursor c = context.getContentResolver().query(collection, projection, selection, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
            if (c == null) return out;
            try {
                int colId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int colT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int colA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int colAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int colD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                while (c.moveToNext()) {
                    long id = c.getLong(colId);
                    String title = c.getString(colT);
                    String artist = c.getString(colA);
                    String album = c.getString(colAl);
                    long dur = c.getLong(colD);
                    String url = ContentUris.withAppendedId(collection, id).toString();
                    MusicMedia m = new MusicMedia("local_" + id,
                            title == null ? "" : title,
                            artist == null ? "" : artist,
                            album == null ? "" : album,
                            dur <= 0 ? 0 : dur,
                            null,
                            url,
                            null,
                            null);
                    // 本地曲目播放/失败换源不需要插件路由，source 仅作展示标记
                    m.source = "local";
                    out.add(m);
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}