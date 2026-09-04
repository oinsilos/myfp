package com.fongmi.android.tv.music.model;

/**
 * 歌单 / 榜单 / 歌手 / 专辑 聚合条目（MusicFree IMusicSheetItemBase 的本地镜像）。
 * <ul>
 *   <li>歌单详情：id=playlistId，title=歌单名</li>
 *   <li>榜单：id=榜单 id（playlist/detail 可解析），title=榜名，playCount=播放量</li>
 *   <li>歌手：id=artistId，title=歌手名，extra 标记 type=artist</li>
 * </ul>
 */
public final class MusicSheet {

    /** 歌单/榜单/歌手 id（传给插件的 sheetId）。 */
    public final String id;
    public final String title;
    /** 封面图 URL（歌单 coverImgUrl / 歌手头像）。 */
    public final String cover;
    /** 作者/创建者。 */
    public final String artist;
    /** 简介（可空）。 */
    public final String description;
    /** 歌曲数量（未知 -1）。 */
    public final long worksNum;
    /** 播放量（未知 -1）。 */
    public final long playCount;
    /** 来源插件 platform（多源路由，空=用当前源）。 */
    public String source = "";
    /** 插件原始字段透传：如 {"type":"sheet"}、{"genre":"华语"}。 */
    public String extra = "{}";

    public MusicSheet(String id, String title, String cover, String artist, String description, long worksNum, long playCount) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.cover = cover;
        this.artist = artist == null ? "" : artist;
        this.description = description;
        this.worksNum = worksNum;
        this.playCount = playCount;
    }

    @Override
    public String toString() {
        return "MusicSheet{id='" + id + "', title='" + title + "', playCount=" + playCount + "}";
    }
}