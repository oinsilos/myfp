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
            var data = [];  
            for (var i = 0; i < songs.length; i++) {
                var s = songs[i];
                if (!s || !s.id) continue;
                var album = s.album || {};
                var durationSec = Math.round((s.duration || 0) / 1000);
                data.push({
                    title: s.name,
                    artist: pickName(s.artists),
                    album: album.name,
                    duration: durationSec,
                    cover: album.picUrl,
                    songId: s.id,
                    url: outerUrl(s.id)
                });
            }
            return { isEnd: true, data: data };
        },
        // 播放源：返回 { url }
        async getMediaSource(musicItem, quality) {
            var url = musicItem && musicItem.url;
            if (!url && musicItem && musicItem.songId) url = outerUrl(musicItem.songId);
            if (!url) throw new Error('netease: no songId for media source');
            return { url: url };
        },
        // 歌词（可选，暂返回 null 交由内核忽略）
        async getLyric() {
            return null;
        }
    };
})();