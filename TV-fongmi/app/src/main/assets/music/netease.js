// 网易云音乐插件（Rhino 宿主内置示例）：真实音乐源闭环验证用。
// - search：music.163.com/api/search/get（公开、无签名）
// - getMediaSource：官方外链接口（部分老歌返回 404，播放内核会自动换源/跳过）
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

    module.exports = {
        platform: 'netease',
        version: '0.1.0',
        appVersion: '^0.0.1',
        fullVersion: '0.1.0',
        // 搜索：keyword / page(1 起) / type
        async search(keyword, page, type) {
            var resp = await axios.get(SEARCH_URL, {
                params: { s: keyword, limit: 30, p: page || 1, type: type || 1 },
                headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/' }
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
                            album: album.name,
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
        // 歌词：网易云标准 LRC（[mm:ss.xx]文本），接口不可用/无词返回 null 交由 UI 忽略
        async getLyric(musicItem) {
            var songId = (musicItem && musicItem.songId) || (musicItem && musicItem.id);
            if (!songId) return null;
            try {
                var resp = await axios.get('https://music.163.com/api/song/lyric', {
                    params: { id: String(songId), lv: 1, kv: 1, tv: -1 },
                    headers: { 'User-Agent': COMMON_UA, Referer: 'https://music.163.com/' }
                });
                var lrc = resp.data && resp.data.lrc && resp.data.lrc.lyric;
                if (!(lrc && lrc.length)) console.log('netease lyric: empty for id=' + songId + ' data=' + JSON.stringify(resp.data).slice(0, 200));
                return (lrc && lrc.length) ? lrc : null;
            } catch (e) {
                console.log('netease lyric error id=' + songId + ' err=' + (e && e.message));
                return null;
            }
        }
    };
})();