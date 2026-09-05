// 网易云音乐插件（MusicFree 契约内置音源）：搜索 / 播放 / 歌词 / 榜单 / 歌单 / 歌手全闭环。
// - search：music.163.com/api/search/get（公开、无签名）
// - getMediaSource：官方 player/url 接口 + 公开外链接口兜底（VIP/无版权歌自动抛错换源）
// ES5 子集书写（var/普通函数/对象 async 方法简写），依赖宿主内置 axios 兼容层。
(function () {
    'use strict';

    var axios = require('axios');

    var SEARCH_URL = 'https://music.163.com/api/search/get';
    var COMMON_UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36';

    function outerUrl(songId) {
        return 'https://music.163.com/song/media/outer/url?id=' + songId + '.mp3';
    }

    function pickName(arr) {
        if (!arr || !arr.length) return '';
        var out = [];
        for (var i = 0; i < arr.length; i++) {
            if (arr[i] && arr[i].name) out.push(arr[i].name);
        }
        return out.join('/');
    }

    // ---------------- 歌单 / 榜单 / 歌手 / 导入（对齐 MusicFree 插件契约） ----------------

    // 网易云歌单广场常见分类（getRecommendSheetTags 的「推荐歌单」入口，不走 hotcats 失效接口）
    var SHEET_CATS = ['华语', '流行', '经典', '摇滚', '民谣', '电子', '轻音乐', '影视原声', 'ACG', '怀旧', '治愈', '睡前', '驾车', '运动', '学习'];

    // playlist/detail 的 tracks → MusicItem[]（与 search 的 item 同构：songId/title/artist/album/cover/duration/vip/url）。
// 注意：playlist/detail 老接口用 artists/album/duration，artist/top/song 用 ar/al/dt —— 两套字段都兼容。
function tracksToItems(tracks) {
        var out = [];
        if (!tracks || !tracks.length) return out;
        for (var i = 0; i < tracks.length; i++) {
            var t = tracks[i];
            if (!t || !t.id) continue;
            var al = t.al || t.album || {};
            var fee = (t.fee === undefined || t.fee === null) ? 0 : t.fee;
            var dt = t.dt || t.duration || 0;
            out.push({
                songId: String(t.id),
                title: t.name,
                artist: pickName(t.ar || t.artists),
                album: al.name || '',
                cover: al.picUrl || al.coverImgUrl || '',
                duration: Math.round(dt / 1000),
                fee: fee,
                vip: fee > 0,
                url: outerUrl(t.id)
            });
        }
        return out;
    }

    // 歌单详情统一入口：playlist/detail 老接口（公开可用，含完整 tracks）。
    // 带 os=pc cookie 时返回完整 tracks（冷启动无 cookie 只回前 10 首）；响应较大(数百 KB)，
    // 属插件规范内可控范围——已通过移除默认自动搜索避免与歌单加载并发抢占沙箱线程。
    function sheetMusicById(id) {
        return axios.get('https://music.163.com/api/playlist/detail', {
            params: { id: String(id) },
            headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/', Cookie: 'os=pc; appver=8.9.40' }
        }).then(function (resp) {
            var tracks = (resp.data && resp.data.result && resp.data.result.tracks) || [];
            return tracksToItems(tracks);
        });
    }

    function sheetIdOf(item) {
        if (!item) return '';
        return String(item.playlistId || item.id || '');
    }

    module.exports = {
        platform: 'netease',
        version: '0.3.0',
        appVersion: '^0.0.1',
        fullVersion: '0.3.0',
        // 搜索：keyword / page(1 起) / type。公开接口带 cookie 提成功率。
        // 注意：不做 weapi 搜索兜底——4096-bit RSA 模幂在 Rhino 解释模式+模拟器翻译层下
        // 要跑几十秒，会把沙箱线程占死导致整机卡死（进音乐页转圈→进程被杀）。空结果直接返回。
        async search(keyword, page, type) {
            var resp = await axios.get(SEARCH_URL, {
                params: { s: keyword, limit: 30, p: page || 1, type: type || 1 },
                headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/', Cookie: 'os=pc; appver=8.9.40' }
            });
            var result = resp.data && resp.data.result;
            var songs = (result && result.songs) || [];
            // 专辑封面回填：搜索接口的 album 已不带 picUrl（只有 picId），
            // 批量 song/detail 换取 https 直链封面；失败则不阻断搜索（fallback 歌手图）。
            var detailMap = null;
            try {
                var idcs = [];
                for (var i2 = 0; i2 < songs.length; i2++) {
                    if (songs[i2] && songs[i2].id) idcs.push(songs[i2].id);
                }
                if (idcs.length) {
                    var dr = await axios.get('https://music.163.com/api/song/detail', {
                        params: { ids: '[' + idcs.join(',') + ']' },
                        // 封面回填非关键路径：短超时快速失败降级，避免拖慢搜索（弱网下两个串行请求叠加会撞界面兜底）
                        timeout: 4000,
                        headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/' }
                    });
                    var ds = (dr.data && dr.data.songs) || [];
                    detailMap = {};
                    for (var j = 0; j < ds.length; j++) {
                        if (ds[j] && ds[j].id && ds[j].album && ds[j].album.picUrl) detailMap[String(ds[j].id)] = ds[j].album.picUrl;
                    }
                }
            } catch (e) { /* 封面回填失败不阻断搜索 */ }
            var data = [];
            for (var i = 0; i < songs.length; i++) {
                var s = songs[i];
                if (!s || !s.id) continue;
                var album = s.album || {};
                var durationSec = Math.round((s.duration || 0) / 1000);
                var cover = (detailMap && detailMap[String(s.id)]) || album.picUrl || (album.artist && album.artist.img1v1Url) || '';
                data.push({
                            title: s.name,
                            artist: pickName(s.artists),
                            artistId: (s.artists && s.artists[0] && s.artists[0].id) ? String(s.artists[0].id) : '',
                            album: album.name,
                            albumId: album.id ? String(album.id) : '',
                            duration: durationSec,
                            cover: cover,
                            songId: s.id,
                            fee: s.fee,
                            vip: (s.fee || 0) > 0,
                            url: outerUrl(s.id)
                        });
            }
            return { isEnd: true, data: data };
        },
        // 播放源：优先官方 player/url 接口（匿名可用，免费歌返回 320k CDN 直链；
        // VIP/无版权歌返回 url=null → 抛错由内核自动跳下一首），失败再走公开外链兜底。
        async getMediaSource(musicItem, quality) {
            var songId = (musicItem && musicItem.songId) || (musicItem && musicItem.id);
            if (!songId) throw new Error('netease: no songId for media source');
            songId = String(songId);
            var brs = [320000, 192000, 128000];
            for (var i = 0; i < brs.length; i++) {
                try {
                    var resp = await axios.get('https://music.163.com/api/song/enhance/player/url', {
                        params: { ids: '[' + songId + ']', br: brs[i] },
                        headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/' }
                    });
                    var arr = resp.data && resp.data.data;
                    var first = arr && arr[0];
                    var url = first && first.url;
                    if (url && url.length && url.indexOf('http') === 0) return { url: url };
                } catch (e) { /* 接口异常：继续尝试下一档/兜底 */ }
            }
            // 兜底：公开外链（无明显版权问题的 CDN 直链仍可放）
            return { url: outerUrl(songId) };
        },
        // 歌词：公开接口（带 cookie）。不做 weapi——重 RSA 加密在 Rhino 解释模式+模拟器下卡死沙箱线程。
        // (VIP/未收录歌公开接口返回 uncollected → null，UI 显示「暂无歌词」)
        async getLyric(musicItem) {
            var songId = (musicItem && musicItem.songId) || (musicItem && musicItem.id);
            if (!songId) return null;
            var attempts = [
                function () {
                    return axios.get('https://music.163.com/api/song/lyric', {
                        params: { id: String(songId), lv: -1, kv: -1, tv: -1, os: 'pc' },
                        headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/', Cookie: 'os=pc; appver=8.9.40' }
                    });
                },
                function () {
                    return axios.get('https://music.163.com/api/song/lyric', {
                        params: { id: String(songId), lv: -1, kv: -1, tv: -1, os: 'linux' },
                        headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/' }
                    });
                }
            ];
            var noLyric = false;
            var last = '';
            for (var i = 0; i < attempts.length; i++) {
                try {
                    var resp = await attempts[i]();
                    var body = resp.data;
                    if (body && typeof body === 'object') {
                        if (body.nolyric || body.uncollected) {
                            noLyric = true;
                            last = 'attempt#' + (i + 1) + ' no lyric (nolyric/uncollected)';
                            console.log('[netease] lyric: ' + last + ' id=' + songId);
                            continue;
                        }
                        var lrc = body.lrc && body.lrc.lyric;
                        if (lrc && typeof lrc === 'string' && lrc.length && !/暂无歌词/.test(lrc)) {
                            console.log('[netease] lyric ok from attempt#' + (i + 1) + ' len=' + lrc.length + ' id=' + songId);
                            return lrc;
                        }
                        last = 'attempt#' + (i + 1) + ' empty lrc';
                    } else {
                        last = 'attempt#' + (i + 1) + ' unexpected body: ' + String(body).substring(0, 80);
                    }
                    console.log('[netease] lyric: ' + last + ' id=' + songId);
                } catch (e) {
                    last = 'attempt#' + (i + 1) + ' err=' + ((e && e.message) || String(e));
                    console.log('[netease] lyric: ' + last + ' id=' + songId);
                }
            }
            // 至少有一个接口正常响应并判定该歌无词 → 真无词，返回 null（UI 显示「该歌曲暂无歌词」）
            if (noLyric) return null;
            throw new Error('lyric failed (' + last + ')');
        },
        // 榜单分组：toplist/detail 公开接口（63 个榜，含云音乐新歌榜/热歌榜等）。
        // 只取前 20 个主流榜：一次性渲染 63 张封面在低端机/模拟器上首屏过重（上图+解码并发压垮 UI 线程），
        // 截断后既覆盖主流榜单又保证首屏轻快；后续想扩容只需调大 TOP_LIMIT。
        async getTopLists() {
            var resp = await axios.get('https://music.163.com/api/toplist/detail', {
                headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/', Cookie: 'os=pc; appver=8.9.40' }
            });
            var list = (resp.data && resp.data.list) || [];
            var items = [];
            for (var i = 0; i < list.length && items.length < 20; i++) {
                var t = list[i];
                if (!t || !t.id) continue;
                items.push({
                    id: String(t.id),
                    title: t.name,
                    coverImgUrl: t.coverImgUrl || t.coverText || '',
                    description: t.updateFrequency || t.description || '',
                    playCount: (t.playCount === undefined || t.playCount === null) ? -1 : t.playCount,
                    worksNum: (t.tracks && t.tracks.length) ? t.tracks.length : -1
                });
            }
            return [{ id: 'netease-top', name: '官方榜', data: items }];
        },
        // 榜单详情：榜单 id 复用 playlist/detail（榜单在服务端即歌单）
        async getTopListDetail(topListItem, page) {
            var id = sheetIdOf(topListItem);
            if (!id) throw new Error('netease: no id for top list');
            return { isEnd: true, musicList: await sheetMusicById(id) };
        },
        // 推荐歌单分类：固定词（网易云歌单广场常用分类）
        async getRecommendSheetTags() {
            var data = [];
            for (var i = 0; i < SHEET_CATS.length; i++) {
                data.push({ name: SHEET_CATS[i], data: [] });
            }
            return { data: data };
        },
        // 某分类的推荐歌单：search type=1000（歌单搜索，公开）
        async getRecommendSheetsByTag(tag, page) {
            var kw = (tag && (tag.name || tag.id)) || '华语';
            var resp = await axios.get('https://music.163.com/api/search/get', {
                params: { s: kw, type: 1000, limit: 30, p: page || 1 },
                headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/', Cookie: 'os=pc; appver=8.9.40' }
            });
            var playlists = (resp.data && resp.data.result && resp.data.result.playlists) || [];
            var data = [];
            for (var i = 0; i < playlists.length; i++) {
                var p = playlists[i];
                if (!p || !p.id) continue;
                data.push({
                    id: String(p.id),
                    title: p.name,
                    coverImgUrl: p.coverImgUrl || '',
                    artist: (p.creator && p.creator.nickname) || '',
                    description: p.description || '',
                    trackCount: p.trackCount || -1,
                    playCount: p.playCount || 0
                });
            }
            return { isEnd: page && page > 1, data: data };
        },
        // 歌单详情：playlist/detail（完整 tracks）
        async getMusicSheetInfo(sheetItem, page) {
            var id = sheetIdOf(sheetItem);
            if (!id) throw new Error('netease: no id for sheet info');
            return { isEnd: true, musicList: await sheetMusicById(id) };
        },
        // 歌手热门 50 首：artist/top/song（专辑作品 type='album' 暂未实现，返回空）
        async getArtistWorks(artistItem, page, type) {
            if (type && type !== 'song') return { isEnd: true, data: [] };
            var id = (artistItem && String(artistItem.artistId || artistItem.playlistId || artistItem.id)) || '';
            if (!id) return { isEnd: true, data: [] };
            var resp = await axios.get('https://music.163.com/api/artist/top/song', {
                params: { id: id },
                headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/', Cookie: 'os=pc; appver=8.9.40' }
            });
            return { isEnd: true, data: tracksToItems((resp.data && resp.data.songs) || []) };
        },
        // 歌单导入：粘贴网易云歌单/榜单链接（或裸 id）→ playlist/detail → MusicItem[]
        async importMusicSheet(urlLike) {
            var id = null;
            if (typeof urlLike === 'string') {
                var m1 = urlLike.match(/(?:playlist|toplist)[?/#=&:.\-\w]*?id=(\d+)/);
                if (m1) id = m1[1];
                if (!id) {
                    var m2 = urlLike.match(/(?:playlist|toplist)\/(\d+)/);
                    if (m2) id = m2[1];
                }
                if (!id) {
                    var m3 = urlLike.match(/^(\d{5,20})$/);
                    if (m3) id = m3[1];
                }
            }
            if (!id) return [];
            return sheetMusicById(id);
        }
    };
})();