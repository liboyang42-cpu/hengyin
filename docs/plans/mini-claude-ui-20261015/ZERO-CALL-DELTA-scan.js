// BL-173：在 BL-133 ZERO-CALL-scan.js 基础上加 ALL=1 输出全部映射（第 4 列 called/zero）。用法：ALL=1 node docs/plans/mini-claude-ui-20261015/ZERO-CALL-DELTA-scan.js <树根> > all.tsv
// 输出 TSV：METHOD PATH FILE:LINE（stderr 打印总数）。只按路径匹配、不区分 HTTP 方法；排除 chengyinhub-xcx 下 tests/scripts/node_modules。
const fs = require('fs'), path = require('path');
const root = process.argv[2];
const ctlDir = path.join(root, 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api');
const xcx = path.join(root, 'chengyinhub-xcx');
function strs(s) { return (s.match(/"([^"]*)"/g) || []).map(x => x.slice(1, -1)); }
function mappingPaths(args) {
  // value=/path= 或首个字符串数组
  const m = args.match(/(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/);
  if (m) return strs(m[1]);
  if (/^\s*(\{[^}]*\}|"[^"]*")/.test(args)) return strs(args.match(/^\s*(\{[^}]*\}|"[^"]*")/)[1]);
  return [''];
}
const eps = [];
for (const f of fs.readdirSync(ctlDir).filter(f => f.endsWith('.java')).sort()) {
  const src = fs.readFileSync(path.join(ctlDir, f), 'utf8');
  const lines = src.split('\n');
  const clsIdx = lines.findIndex(l => /^\s*public\s+(abstract\s+)?class\s/.test(l));
  let bases = [''];
  for (let i = 0; i < clsIdx; i++) { const m = lines[i].match(/@RequestMapping\((.*)\)/); if (m) bases = mappingPaths(m[1]); }
  for (let i = clsIdx + 1; i < lines.length; i++) {
    if (/^\s*\/\//.test(lines[i])) continue;
    const m = lines[i].match(/@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\((.*)\))?\s*$/) || lines[i].match(/@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\(([^)]*)\))?/);
    if (!m) continue;
    const args = m[2] || '';
    let method = m[1] === 'Request' ? ((args.match(/RequestMethod\.(\w+)/) || [, 'ANY'])[1]) : m[1].toUpperCase();
    for (const b of bases) for (const p of mappingPaths(args)) {
      const full = ('/' + b + '/' + p).replace(/\/+/g, '/').replace(/(.)\/$/, '$1');
      eps.push({ method, path: full, loc: f + ':' + (i + 1) });
    }
  }
}
// 收集小程序字符串（排除 tests/scripts/node_modules）
const lits = new Set();
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'tests', 'scripts', '.git'].includes(e.name)) continue;
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.(js|wxml|wxs|json)$/.test(e.name)) {
      const s = fs.readFileSync(p, 'utf8');
      for (const m of s.matchAll(/['"`]((?:\/)?api\/[^'"`\s]*)['"`]?/g)) lits.add(('/' + m[1]).replace(/^\/+/, '/').replace(/\$\{[^}]*\}/g, '{v}'));
    }
  }
})(xcx);
const litArr = [...lits];
function called(p) {
  const re = new RegExp('^' + p.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\{[^}]+\}/g, '[^/]+') + '$');
  return litArr.some(l => {
    const l2 = l.replace(/\{v\}/g, 'x').split('?')[0];
    if (re.test(l2)) return true;
    // 字面量以 / 结尾或含 {v}：作前缀拼接
    if (l.endsWith('/') || l.includes('{v}')) {
      const pre = l.split('{v}')[0];
      return p.replace(/\{[^}]+\}/g, 'x').startsWith(pre) && pre.length > 5 && p.split('/').length > pre.split('/').length - 1;
    }
    return false;
  });
}
const out = process.env.ALL ? eps.map(e => Object.assign({}, e, { called: called(e.path) })) : eps.filter(e => !called(e.path));
for (const e of out) console.log([e.method, e.path, e.loc, e.called === undefined ? "" : (e.called ? "called" : "zero")].join('\t'));
console.error('total endpoints', eps.length, 'zero-call', out.length, 'literals', litArr.length);
