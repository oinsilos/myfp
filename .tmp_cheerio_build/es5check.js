const fs = require('fs');
const acorn = require('acorn');
const src = fs.readFileSync('/workspace/.tmp_cheerio_build/cheerio.bundle.js', 'utf8');
try {
  acorn.parse(src, { ecmaVersion: 5, allowHashBang: true });
  console.log('ES5 parse: OK');
} catch (e) {
  console.log('ES5 parse FAIL:', e.message);
  // 定位错误位置附近的源码
  const pos = e.pos != null ? e.pos : 0;
  console.log('context:', JSON.stringify(src.slice(Math.max(0, pos - 80), pos + 80)));
}