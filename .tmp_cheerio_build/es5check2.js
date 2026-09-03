const fs = require('fs');
const acorn = require('acorn');
const vm = require('vm');

function check(name, file) {
  const src = fs.readFileSync(file, 'utf8');
  let ok = true;
  try { acorn.parse(src, { ecmaVersion: 5 }); console.log(`[${name}] ES5: OK (${(src.length/1024).toFixed(0)}KB)`); }
  catch (e) { ok = false; console.log(`[${name}] ES5 FAIL: ${e.message} @ ${e.pos}`); const p = e.pos||0; console.log('  ctx:', JSON.stringify(src.slice(Math.max(0,p-60), p+60))); }
  return ok;
}

check('big-integer', 'bigint.js');
check('he', 'he.js');
check('dayjs', 'dayjs.min.js');