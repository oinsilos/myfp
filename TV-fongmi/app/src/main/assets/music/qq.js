// 腾讯 QQ 音乐插件（内置）：接口逻辑参考 listen1 chrome 扩展 js/provider/qq.js。
// - search:         u.y.qq.com musicu.fcg DoSearchForQQMusicDesktop
// - getMediaSource:  musicu.fcg vkey.GetVkeyServer（128k mp3）
// - getLyric:        i.y.qq.com fcg_query_lyric_new（nobase64=1 明文）
// ES5 子集书写（var/普通函数/对象 async 方法简写），依赖宿主内置 axios 兼容层。
(function () {
    'use strict';

    var axios = require('axios');
    var COMMON_UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36';
    var MUSICU = 'https://u.y.qq.com/cgi-bin/musicu.fcg';
    var REFERER = 'https://y.qq.com/';

    // 封面图：y.gtimg.cn/music/photo_new/T002R300x300M000<albummid>.jpg
    function qqImg(mid, type) {
        if (!mid) return '';
        var cat = type === 'artist' ? 'T001R300x300M000' : 'T002R300x300M000';
        return 'https://y.gtimg.cn/music/photo_new/' + cat + mid + '.jpg';
    }

    // MusicFree 搜索结果项；duration 为秒（interval 字段）
    function songToItem(s) {
        if (!s || !s.mid) return null;
        return {
            songId: String(s.mid),
            title: s.name || '',
            artist: (s.singer && s.singer[0] && s.singer[0].name) || '',
            album: (s.album && s.album.name) || '',
            duration: Math.round(s.interval || s.duration || 0),
            cover: qqImg(s.album && s.album.mid, 'album'),
            url: ''
        };
    }

    module.exports = {
        platform: 'qq',
        version: '0.1.0',
        appVersion: '^0.0.1',
        fullVersion: '0.1.0',
        // 搜索歌曲（type=1 歌曲）：musicu.fcg 新版搜索
        async search(keyword, page, type) {
            var body = {
                comm: { ct: '19', cv: '1859', uin: '0' },
                req: {
                    method: 'DoSearchForQQMusicDesktop',
                    module: 'music.search.SearchCgiService',
                    param: { grp: 1, num_per_page: 30, page_num: page || 1, query: keyword || '', search_type: 0 }
                }
            };
            var resp = await axios.post(MUSICU, body, { headers: { 'User-Agent': COMMON_UA, Referer: REFERER } });
            var song = resp.data && resp.data.req && resp.data.req.data
                    && resp.data.req.data.body && resp.data.req.data.body.song;
            var list = (song && song.list) || [];
            var data = [];
            for (var i = 0; i < list.length; i++) {
                var item = songToItem(list[i]);
                if (item) data.push(item);
            }
            return { isEnd: true, data: data };
        },
        // 播放源：vkey 接口（免登录，免费歌返回 CDN 直链；VIP/无版权 purl 为空 → 抛错由内核跳下一首）
        async getMediaSource(musicItem, quality) {
            var songId = (musicItem && musicItem.songId) || (musicItem && musicItem.id);
            if (!songId) throw new Error('qq: no songId for media source');
            songId = String(songId);
            var file = 'M500' + songId + songId + '.mp3';
            var req = {
                req_1: {
                    module: 'vkey.GetVkeyServer',
                    method: 'CgiGetVkey',
                    param: { filename: [file], guid: '10000', songmid: [songId], songtype: [0], uin: '0', loginflag: 1, platform: '20' }
                },
                loginUin: '0',
                comm: { uin: '0', format: 'json', ct: 24, cv: 0 }
            };
            var resp = await axios.post(MUSICU, req, { headers: { 'User-Agent': COMMON_UA, Referer: REFERER } });
            var d = resp.data && resp.data.req_1 && resp.data.req_1.data;
            var info = d && d.midurlinfo && d.midurlinfo[0];
            var purl = info && info.purl;
            if (!purl) throw new Error('qq: no playable url (vip or no copyright)');
            var sip = (d && d.sip && d.sip[0]) || 'https://isure.stream.qqmusic.qq.com/';
            var url = sip + purl;
            // 偶发 sip 返回相对/无协议前缀，补全为 https
            if (url.indexOf('http') !== 0) url = 'https://' + url;
            return { url: url };
        },
        // 歌词：明文 LRC（nobase64=1）
        async getLyric(musicItem) {
            var songId = (musicItem && musicItem.songId) || (musicItem && musicItem.id);
            if (!songId) return null;
            var resp = await axios.get('https://i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg', {
                params: { songmid: String(songId), g_tk: 5381, format: 'json', inCharset: 'utf8', outCharset: 'utf-8', nobase64: 1 },
                headers: { 'User-Agent': COMMON_UA, Referer: 'https://y.qq.com/portal/player.html' }
            });
            var lrc = resp.data && resp.data.lyric;
            if (typeof lrc === 'string' && lrc.length && !/暂无歌词/.test(lrc)) return lrc;
            return null;
        }
    };
})();