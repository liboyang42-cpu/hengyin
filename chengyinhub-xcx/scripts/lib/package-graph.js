'use strict';
/* package-graph.js —— 小程序包体依赖图(主包 / 各分包可达文件与字节)(2026-11-21)
 *
 * 近似微信「上传时过滤无依赖文件」(project.config.json setting.ignoreUploadUnusedFiles):
 * 从 app.* / custom-tab-bar / 每个页面出发,沿
 *   JS   require('…') / require.async('…')(字面量)
 *   JSON usingComponents / componentGenerics.default / app.json tabBar 图标
 *   WXML <import|include|wxs src> 与字面量资源路径
 *   WXSS @import 与 url(…)
 *   JS/WXML/WXS 里出现的 /images/… 字面量(含 '/images/x/' + name 这种目录前缀 ⇒ 目录整体计入)
 * 求出每个包实际会带上的文件。packOptions.ignore 里的路径不计。
 *
 * ⚠️ 它是源码字节(未压缩,和 preflight-release.js 的棘轮口径一致),不是 DevTools 编译值。
 *    用途是:定位体积大头、证明某文件只被某一个分包用到、给单测当体积棘轮的基线。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const CODE_EXT = ['.js', '.json', '.wxml', '.wxss', '.wxs'];

function loadConfig(root = ROOT) {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const cfg = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
  const ignore = (cfg.packOptions && cfg.packOptions.ignore) || [];
  const ignoredFiles = new Set(ignore.filter((x) => x.type === 'file').map((x) => x.value));
  const ignoredDirs = ignore.filter((x) => x.type === 'folder').map((x) => x.value.replace(/\/$/, ''));
  const subs = (app.subPackages || app.subpackages || []).map((s) => ({
    root: s.root.replace(/\/$/, ''), name: s.name || s.root.replace(/\/$/, ''), pages: s.pages || [],
  }));
  return { app, ignoredFiles, ignoredDirs, subs };
}

function packaged(rel, conf) {
  if (rel.split('/').some((seg) => seg.startsWith('.') || seg === 'node_modules')) return false;
  if (conf.ignoredFiles.has(rel)) return false;
  return !conf.ignoredDirs.some((d) => rel === d || rel.startsWith(d + '/'));
}

function listFiles(root, conf) {
  const out = [];
  (function walk(abs, rel) {
    for (const e of fs.readdirSync(abs, { withFileTypes: true })) {
      const r = rel ? rel + '/' + e.name : e.name;
      if (!packaged(r, conf)) continue;
      if (e.isDirectory()) walk(path.join(abs, e.name), r);
      else out.push(r);
    }
  })(root, '');
  return out;
}

function ownerOf(rel, subs) {
  const hit = subs.find((s) => rel.startsWith(s.root + '/'));
  return hit ? hit.root : 'MAIN';
}

function stripJsComments(src) {
  // 只去 // 与 /* */ 注释,保留字符串;够依赖扫描用。
  let out = '';
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (quote) {
      out += c;
      if (c === '\\') { out += n || ''; i += 2; continue; }
      if (c === quote) quote = null;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') { quote = c; out += c; i += 1; continue; }
    if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i += 1; continue; }
    if (c === '/' && n === '*') { const end = src.indexOf('*/', i + 2); i = end < 0 ? src.length : end + 2; continue; }
    out += c;
    i += 1;
  }
  return out;
}

function makeResolver(fileSet) {
  const dirPrefixes = new Set();
  for (const f of fileSet) {
    const parts = f.split('/');
    for (let k = 1; k < parts.length; k += 1) dirPrefixes.add(parts.slice(0, k).join('/'));
  }
  function norm(from, spec) {
    if (typeof spec !== 'string' || !spec || /^[a-z][\w+.-]*:/i.test(spec) || spec.startsWith('{{')) return null;
    const clean = spec.split(/[?#]/)[0];
    const p = clean.startsWith('/') ? clean.slice(1) : path.posix.join(path.posix.dirname(from), clean);
    return path.posix.normalize(p);
  }
  return {
    norm,
    /** 解析成真实存在的文件;exts 依次尝试,含 index 解析 */
    file(from, spec, exts) {
      const p = norm(from, spec);
      if (!p) return null;
      if (fileSet.has(p)) return p;
      for (const e of exts) if (fileSet.has(p + e)) return p + e;
      for (const e of exts) if (fileSet.has(p + '/index' + e)) return p + '/index' + e;
      return null;
    },
    /** 组件 / 页面基名:返回存在的 4 件套 */
    component(from, spec) {
      const p = norm(from, spec);
      if (!p) return [];
      const bases = [p, p + '/index'];
      for (const b of bases) {
        const hit = CODE_EXT.map((e) => b + e).filter((f) => fileSet.has(f));
        if (hit.some((f) => f.endsWith('.json') || f.endsWith('.js'))) return hit;
      }
      return [];
    },
    isDir(p) { return dirPrefixes.has(p); },
  };
}

const RE_REQUIRE = /\brequire(?:\.async)?\(\s*(['"`])([^'"`]+)\1\s*\)/g;
const RE_ASSET = /(['"`(\s=])((?:\.{1,2}\/|\/)?(?:[\w-]+\/)*images\/[\w./-]*)/g;

function assetRefs(from, src, R, fileSet) {
  const out = [];
  let m;
  RE_ASSET.lastIndex = 0;
  while ((m = RE_ASSET.exec(src))) {
    let p = R.norm(from, m[2]);
    if (!p) continue;
    if (!m[2].startsWith('/') && !m[2].startsWith('.')) p = path.posix.normalize(m[2]);
    if (fileSet.has(p)) { out.push(p); continue; }
    // '/images/roam/pin/' + name ⇒ 目录前缀:整目录计入(保守)
    const d = p.replace(/\/$/, '');
    if (R.isDir(d)) for (const f of fileSet) if (f.startsWith(d + '/') && !CODE_EXT.includes(path.posix.extname(f))) out.push(f);
  }
  return out;
}

function edgesOf(rel, src, R, fileSet) {
  const ext = path.posix.extname(rel);
  const out = [];
  if (ext === '.js' || ext === '.wxs') {
    const code = stripJsComments(src);
    let m;
    RE_REQUIRE.lastIndex = 0;
    while ((m = RE_REQUIRE.exec(code))) {
      const f = R.file(rel, m[2], ['.js', '.wxs', '.json']);
      if (f) out.push(f);
    }
    out.push(...assetRefs(rel, code, R, fileSet));
  } else if (ext === '.json') {
    let j = null;
    try { j = JSON.parse(src); } catch (_) { j = null; }
    if (j && typeof j === 'object') {
      const comps = Object.assign({}, j.usingComponents || {});
      for (const [k, g] of Object.entries(j.componentGenerics || {})) if (g && g.default) comps['generic:' + k] = g.default;
      for (const spec of Object.values(comps)) out.push(...R.component(rel, spec));
      if (j.tabBar && Array.isArray(j.tabBar.list)) {
        for (const it of j.tabBar.list) for (const k of ['iconPath', 'selectedIconPath']) {
          const f = it[k] && R.file('app.json', '/' + it[k].replace(/^\//, ''), []);
          if (f) out.push(f);
        }
      }
    }
  } else if (ext === '.wxml') {
    let m;
    const re = /<(import|include|wxs)\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']/g;
    while ((m = re.exec(src))) {
      const f = R.file(rel, m[2], m[1] === 'wxs' ? ['.wxs'] : ['.wxml']);
      if (f) out.push(f);
    }
    const src2 = /\bsrc\s*=\s*["']([^"'{}]+)["']/g;
    while ((m = src2.exec(src))) {
      const f = R.file(rel, m[1], []);
      if (f) out.push(f);
    }
    out.push(...assetRefs(rel, src, R, fileSet));
  } else if (ext === '.wxss') {
    let m;
    const imp = /@import\s+["']([^"']+)["']/g;
    while ((m = imp.exec(src))) {
      const f = R.file(rel, m[1], ['.wxss']);
      if (f) out.push(f);
    }
    const url = /url\(\s*["']?([^"')]+)["']?\s*\)/g;
    while ((m = url.exec(src))) {
      const f = R.file(rel, m[1], []);
      if (f) out.push(f);
    }
  }
  return out;
}

function entriesOf(conf, R) {
  const main = ['app.js', 'app.json', 'app.wxss', 'sitemap.json'];
  if (conf.app.tabBar && conf.app.tabBar.custom) main.push(...R.component('app.json', '/custom-tab-bar/index'));
  for (const p of conf.app.pages || []) main.push(...R.component('app.json', '/' + p));
  for (const spec of Object.values(conf.app.usingComponents || {})) main.push(...R.component('app.json', spec));
  const bySub = {};
  for (const s of conf.subs) {
    bySub[s.root] = [];
    for (const p of s.pages) bySub[s.root].push(...R.component('app.json', '/' + s.root + '/' + p));
  }
  return { main, bySub };
}

/**
 * build() ⇒ {
 *   files: Map<rel, {size, owner}>,      源码树里所有会进包(未被 packOptions.ignore 排除)的文件
 *   reach: Map<rel, Set<entryPkg>>,      每个文件被哪些包(MAIN / 分包 root)的入口走到
 *   packages: { MAIN: {bytes, files}, <root>: {...} },  每个包实际带上的(可达)字节
 *   raw: { MAIN: bytes, ... }            每个包目录下全部文件字节(preflight 口径)
 * }
 */
function build(root = ROOT) {
  const conf = loadConfig(root);
  const list = listFiles(root, conf);
  const fileSet = new Set(list);
  const R = makeResolver(fileSet);
  const files = new Map();
  for (const f of list) files.set(f, { size: fs.statSync(path.join(root, f)).size, owner: ownerOf(f, conf.subs) });
  const edgeCache = new Map();
  const edges = (f) => {
    if (!edgeCache.has(f)) {
      const ext = path.posix.extname(f);
      edgeCache.set(f, CODE_EXT.includes(ext) ? edgesOf(f, fs.readFileSync(path.join(root, f), 'utf8'), R, fileSet) : []);
    }
    return edgeCache.get(f);
  };
  const reach = new Map();
  const walkFrom = (pkg, starts) => {
    const seen = new Set();
    const stack = [...starts];
    while (stack.length) {
      const f = stack.pop();
      if (seen.has(f) || !fileSet.has(f)) continue;
      seen.add(f);
      if (!reach.has(f)) reach.set(f, new Set());
      reach.get(f).add(pkg);
      for (const n of edges(f)) stack.push(n);
      // 页面 / 组件:同名 4 件套一起带上
      const base = f.replace(/\.(js|json|wxml|wxss)$/, '');
      if (base !== f) for (const e of ['.js', '.json', '.wxml', '.wxss']) if (fileSet.has(base + e)) stack.push(base + e);
    }
  };
  const { main, bySub } = entriesOf(conf, R);
  walkFrom('MAIN', main);
  for (const [r, starts] of Object.entries(bySub)) walkFrom(r, starts);

  const packages = { MAIN: { bytes: 0, files: 0 } };
  const raw = { MAIN: 0 };
  for (const s of conf.subs) { packages[s.root] = { bytes: 0, files: 0 }; raw[s.root] = 0; }
  for (const [f, info] of files) {
    raw[info.owner] += info.size;
    if (reach.has(f)) { packages[info.owner].bytes += info.size; packages[info.owner].files += 1; }
  }
  return { conf, files, reach, packages, raw };
}

/** 主包里只被分包走到(主包入口走不到)的文件:可下沉候选 */
function mainOnlyUsedBySubpackages(g) {
  const out = [];
  for (const [f, info] of g.files) {
    if (info.owner !== 'MAIN') continue;
    const r = g.reach.get(f);
    if (r && !r.has('MAIN')) out.push({ file: f, size: info.size, users: [...r] });
  }
  return out.sort((a, b) => b.size - a.size);
}

/* ---- est:编译后体积粗估(无第三方依赖,CI 可跑) ----------------------------------------
 * 微信编译(project.config.json:es6 + minified + minifyWXSS + minifyWXML)= babel 转 ES5 再压缩。
 * 这里不带 babel/terser,用「去注释 + 折叠空白」的轻量压缩量出每个代码文件,再乘 EST_CODE_FACTOR;
 * 图片 / 音频等非代码文件按原字节计。
 * 校准(2026-11-21,#891 树):
 *   - DevTools CLI preview 真值:pages/play 2157KB(超 2048KB 被拒)。本函数给 2181KB(高 1%,偏保守)。
 *   - 用 @babel/preset-env + terser 在本机模拟微信流水线:play 2162KB(与真值差 5KB);
 *     各包「模拟值 / 轻量压缩值」(只算代码)稳定在 0.91–0.92,取 0.92 偏保守。
 * 仍是估算:发版前以 DevTools --info-output 为准(scripts/wechat-release-contract.js)。
 */
const EST_CODE_FACTOR = 0.92;

function lightMinify(rel, src) {
  const ext = path.posix.extname(rel);
  if (ext === '.js' || ext === '.wxs') return stripJsComments(src).replace(/^[ \t]+|[ \t]+$/gm, '').replace(/\n+/g, '\n');
  if (ext === '.wxss') return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s+/g, ' ').replace(/\s*([{};:,>])\s*/g, '$1');
  if (ext === '.wxml') return src.replace(/<!--[\s\S]*?-->/g, '').replace(/>\s+</g, '><').replace(/\s+/g, ' ');
  if (ext === '.json') { try { return JSON.stringify(JSON.parse(src)); } catch (_) { return src; } }
  return src;
}

/** estimate(g) ⇒ { <pkg>: estBytes } —— 每个包可达文件的编译后粗估字节 */
function estimate(g, root = ROOT) {
  const out = {};
  for (const k of Object.keys(g.packages)) out[k] = 0;
  for (const [f, info] of g.files) {
    if (!g.reach.has(f)) continue;
    const ext = path.posix.extname(f);
    const n = CODE_EXT.includes(ext)
      ? Math.ceil(Buffer.byteLength(lightMinify(f, fs.readFileSync(path.join(root, f), 'utf8'))) * EST_CODE_FACTOR)
      : info.size;
    out[info.owner] += n;
  }
  return out;
}

module.exports = { ROOT, build, loadConfig, mainOnlyUsedBySubpackages, stripJsComments, estimate, EST_CODE_FACTOR };

if (require.main === module) {
  const g = build();
  const kb = (n) => (n / 1024).toFixed(1) + 'KB';
  const est = estimate(g);
  console.log('package'.padEnd(26), 'raw'.padStart(10), 'reachable'.padStart(10), 'est'.padStart(10));
  for (const k of Object.keys(g.packages).sort((a, b) => g.raw[b] - g.raw[a])) {
    console.log(k.padEnd(26), kb(g.raw[k]).padStart(10), kb(g.packages[k].bytes).padStart(10), kb(est[k]).padStart(10));
  }
  const total = Object.values(g.packages).reduce((s, p) => s + p.bytes, 0);
  console.log('TOTAL reachable', kb(total));
  if (process.argv.includes('--movable')) {
    for (const x of mainOnlyUsedBySubpackages(g)) console.log(kb(x.size).padStart(8), x.file, '<=', x.users.join(','));
  }
  if (process.argv.includes('--unreached')) {
    for (const [f, info] of g.files) if (info.owner === 'MAIN' && !g.reach.has(f)) console.log(kb(info.size).padStart(8), f);
  }
}
