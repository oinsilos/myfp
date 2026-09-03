// 验证 netease 插件：Node 环境 + 我们的 axios.js 兼容层 + 真实网络（网易云接口）
const fs = require('fs');
const http = require('http');
const https = require('https');

const g = global;
global.globalThis = global;

// 1) 加载 Rhino 宿主 assets 里的 axios 兼容层（ES5 IIFE，依赖全局 req）
const axiosSrc = fs.readFileSync('/workspace/TV-fongmi/rhino/src/main/assets/js/mf/axios.js', 'utf8');
(eval)(axiosSrc);

// 2) 实现与 Rhino Global.req 同语义的 req 桥：请求返回 {code, content, headers}
function reqFn(url, options) {
  options = options || {};
  const method = (options.method || 'get').toUpperCase();
  const u = new URL(url);
  const mod = u.protocol === 'https:' ? https : http;
  const headers = options.header || {};
  headers['User-Agent'] = headers['User-Agent'] || 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36';
  const body = options.body;
  return new Promise((resolve) => {
    const r = mod.request({
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      method,
      headers,
      timeout: 10000,
    }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve({ code: String(res.statusCode), content: data }));
    });
    r.on('error', () => resolve({ code: '0', content: '' }));
    if (body) r.write(body);
    r.end();
  });
}
g.req = (url, options) => reqFn(url, options);

// 3) 加载插件（用 CommonJS 风格）
const code = fs.readFileSync('/workspace/TV-fongmi/app/src/main/assets/music/netease.js', 'utf8');
const module_ = { exports: {} };
const exports_ = module_.exports;
const func = new Function('module', 'exports', 'axios', code + '; return module.exports;');
const plugin = func(module_, exports_, g.__mf_lib_axios);

console.log('plugin platform =', plugin.platform);

(async () => {
  // search
  const level = await plugin.search('晴天', 1, 1);
  console.log('search isEnd =', level.isEnd, '| items =', (level.data || []).length);
  const first = level.data[0];
  console.log('first item:', JSON.stringify(first, null, 1).slice(0, 400));

  // getMediaSource
  const src = await plugin.getMediaSource(first, 'standard');
  console.log('getMediaSource url =', src.url);

  // 验证 url 可播：HEAD 检测（外链可能 404，观察换源场景）
  const probe = await new Promise((resolve) => {
    const req = https.request(src.url, { method: 'HEAD', timeout: 8000 }, (res) => {
      resolve({ code: res.statusCode, loc: (res.headers.location || '').slice(0, 80) });
      res.resume();
    });
    req.on('error', (e) => resolve({ code: 'ERR', loc: e.message }));
    req.end();
  });
  console.log('probe:', JSON.stringify(probe));
})().catch((e) => {
  console.log('FAIL:', e && e.stack || e);
  process.exit(1);
});