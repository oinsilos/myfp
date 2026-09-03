const fs = require('fs');
const acorn = require('acorn');
const vm = require('vm');

const src = fs.readFileSync('/workspace/.tmp_cheerio_build/cheerio.es5.min.js', 'utf8');

// 1) ES5 语法校验
try {
  acorn.parse(src, { ecmaVersion: 5, allowHashBang: true });
  console.log('[1] ES5 parse: OK');
} catch (e) {
  console.log('[1] ES5 parse FAIL:', e.message);
  const pos = e.pos != null ? e.pos : 0;
  console.log('context:', JSON.stringify(src.slice(Math.max(0, pos - 80), pos + 80)));
  process.exit(1);
}

// 2) 在沙箱中模拟 Rhino 的加载方式：UMD 环境下 module/exports 局部变量 + global 挂载
//    与 PluginHost.evalLib 的包装一致：var __module = {exports:{}}; var module = __module; ...
const wrapped = '(function(){\nvar __module = { exports: {} };\nvar module = __module;\nvar exports = __module.exports;\n'
  + src
  + '\nreturn __module.exports;\n})();';

const sandbox = { console, Buffer, setTimeout, clearTimeout };
sandbox.globalThis = sandbox;
sandbox.global = sandbox;
sandbox.self = sandbox;
vm.createContext(sandbox);

let cheerio;
try {
  cheerio = vm.runInContext(wrapped, sandbox, { filename: 'cheerio.min.js' });
  console.log('[2] load in sandbox: OK, typeof =', typeof cheerio, '| version =', cheerio && cheerio.version);
} catch (e) {
  console.log('[2] load FAIL:', e.message);
  process.exit(1);
}

// 3) 常用 API 冒烟：load/find/text/attr/each/children/html/toArray/eq/...
try {
  const $ = cheerio.load('<ul id="list"><li class="item" data-k="v1">A</li><li class="item">B<i>inner</i></li></ul>');
  console.log('[3] load: OK');
  console.log('[3] #list count:', $('#list').length);
  console.log('[3] .item count:', $('.item').length);
  console.log('[3] li[data-k] attr:', $('.item[data-k]').attr('data-k'));
  console.log('[3] first text:', $('.item').first().text());
  console.log('[3] children html:', $('li:nth-child(2)').html());
  const items = [];
  $('.item').each(function (i, el) { items.push($(el).text().replace(/\s+/g, '')); });
  console.log('[3] each texts:', JSON.stringify(items));
  console.log('[3] eq(1) text:', $('.item').eq(1).children().length === 1 ? 'OK' : 'FAIL');
} catch (e) {
  console.log('[3] API smoke FAIL:', e.message);
  process.exit(1);
}

// 4) 解码类 API（he/entities 内联）
try {
  const $2 = cheerio.load('<div>&amp;&lt;&nbsp;</div>');
  console.log('[4] entity decode text:', $2('div').text());
} catch (e) {
  console.log('[4] entity smoke FAIL:', e.message);
  process.exit(1);
}

console.log('ALL PASS');