// 验证 netease 插件：真实数据（fetch 经代理）+ mock req 桥（保持 axios.js 同步语义）
const fs = require('fs');

const g = global;
global.globalThis = global;

// 0) 用 fetch（undici支持env代理）拉真实接口数据
async function fetchText(url, referer) {
  const res = await fetch(url, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36',
      Referer: referer || 'https://music.163.com/',
    },
    redirect: 'follow',
  });
  return res.text();
}

(async () => {
  const searchUrl = 'https://music.163.com/api/search/get?s=' + encodeURIComponent('晴天') + '&limit=30&p=1&type=1';
  const searchContent = await fetchText(searchUrl);
  console.log('[fetch] search content len =', searchContent.length, '| head:', searchContent.slice(0, 200));

  // 1) mock 同步 req 桥（与 Rhino Global.req 等价：返回 {code, content}）
  const fixtures = { [searchUrl]: searchContent };
  g.req = (url) => {
    const hit = fixtures[url.replace(' ', '%20')];
    if (hit !== undefined) return { code: '200', content: hit };
    return { code: '0', content: '' };
  };

  // 2) 加载 axios 兼容层
  const axiosSrc = fs.readFileSync('/workspace/TV-fongmi/rhino/src/main/assets/js/mf/axios.js', 'utf8');
  (eval)(axiosSrc);

  // 3) 加载插件（require 由 host 提供：与 PluginHost.require 同契约）
  const code = fs.readFileSync('/workspace/TV-fongmi/app/src/main/assets/music/netease.js', 'utf8');
  const module_ = { exports: {} };
  const hostRequire = (name) => g.__mf_lib_axios; // 宿主 require：任意库都返回 axios（验证脚本够用）
  const func = new Function('module', 'exports', 'require', code + '; return module.exports;');
  const plugin = func(module_, module_.exports, hostRequire);
  console.log('plugin platform =', plugin.platform);

  const level = await plugin.search('晴天', 1, 1);
  console.log('[plugin] search isEnd =', level.isEnd, '| items =', (level.data || []).length);
  console.log('[plugin] first item =', JSON.stringify(level.data[0]).slice(0, 300));

  const src = await plugin.getMediaSource(level.data[0], 'standard');
  console.log('[plugin] getMediaSource url =', src.url);

  // 4) 探测播放 url 可用性（外链 302 → 音频 或 404 版权受限 → 换源场景）
  const probeRes = await fetch(src.url, { method: 'HEAD', redirect: 'manual' });
  console.log('[probe] status =', probeRes.status, '| location =', (probeRes.headers.get('location') || '').slice(0, 70));
  if (probeRes.status === 302 && probeRes.headers.get('location')) {
    const final = await fetch(probeRes.headers.get('location'), { method: 'HEAD', redirect: 'follow' });
    console.log('[probe] follow status =', final.status, '| type =', final.headers.get('content-type'));
  }
  console.log('ALL PASS');
})().catch((e) => {
  console.log('FAIL:', e && e.stack || e);
  process.exit(1);
});