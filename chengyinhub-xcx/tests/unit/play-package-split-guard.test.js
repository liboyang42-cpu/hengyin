'use strict';
/* play-package-split-guard —— pages/play 分包超 2MB 的上线阻断修复护栏(2026-11-21 fix/play-package-split-1121)
 *
 * 背景:DevTools CLI preview(真编译器)在 #891 树上报
 *   subpackage /pages/play/ source size 2157KB exceed max limit 2048KB  ⇒ 小程序无法上传。
 * 修法:玩法组件(cy-playkit 分发器 + 41 个 playkit-* 及其只被它们用到的组件/工具/样式)整体移到
 *   只放组件的新分包 pages/playkit(subpackagePlaykit,pages: []),目录结构与 pages/play 一一对应;
 *   pages/play 经「跨分包异步组件 + componentPlaceholder」引用,preloadRule 在进 play / 主题详情时预下载。
 *
 * ① 体积:package-graph est(编译后粗估,口径见 scripts/lib/package-graph.js estimate)
 *    pages/play < 1950KB;主包不超过本次基线(见下方说明)。
 * ② 跨分包引用:任何包外文件引用 pages/playkit 里的组件,都必须配 componentPlaceholder;
 *    pages/playkit 里的 JS / WXSS / WXS 不得同步依赖 pages/play(只能依赖主包或本包)。
 * ③ 预下载:pages/play/index、poi-challenge、主题详情预下载 subpackagePlaykit,每页预下载合计 est < 2048KB。
 * ④ 共享件的逐字副本(cy-illus / 4 个工具)与 pages/play 规范实现一致(只允许 /pages/play/ → /pages/playkit/)。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const graph = require('../../scripts/lib/package-graph.js');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const KB = 1024;
const baseline = require('../../scripts/baselines/package-size.json').est;
const PLAY_EST_LIMIT = 1950 * KB;
// 主包:真编译器在 #891 树上只拒了 pages/play,主包未报超限;est 对主包没有真值可校准,
// 所以按「不得增长」棚住(基线 + 不超过 16KB 余量,只许往下调),降到 1950KB 以下需要单独的主包瘦身(见 PR #895)。
const MAIN_EST_CEILING = baseline.mainCeilingBytes;
const PRELOAD_LIMIT = 2048 * KB;

let cached = null;
function measure() {
  if (!cached) { const g = graph.build(); cached = { g, est: graph.estimate(g) }; }
  return cached;
}

test('体积:pages/play est < 1950KB,主包 est 不增长,新分包在 2MB 内', () => {
  const { est } = measure();
  assert.equal(baseline.playLimitBytes, PLAY_EST_LIMIT);
  assert.ok(MAIN_EST_CEILING - baseline.mainBytes <= 16 * KB, '主包 est 棘轮余量不超过 16KB');
  const kb = (n) => (n / KB).toFixed(1) + 'KB';
  assert.ok(est['pages/play'] < PLAY_EST_LIMIT, `pages/play est ${kb(est['pages/play'])} ≥ 1950KB(真编译上限 2048KB,需留余量)`);
  assert.ok(est.MAIN <= MAIN_EST_CEILING, `主包 est ${kb(est.MAIN)} 超过基线 ${kb(MAIN_EST_CEILING)}`);
  assert.ok(est['pages/playkit'] < PLAY_EST_LIMIT, `pages/playkit est ${kb(est['pages/playkit'])}`);
});

test('est 口径负控:往 pages/play 加 400KB 代码就会越线', () => {
  const dir = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'est-'));
  try {
    const rel = 'pages/play/__est_probe__.js';
    fs.mkdirSync(path.join(dir, 'pages/play'), { recursive: true });
    fs.writeFileSync(path.join(dir, rel), 'var a=' + JSON.stringify('x'.repeat(400 * KB)) + ';');
    const probe = graph.estimate({
      files: new Map([[rel, { size: 400 * KB, owner: 'pages/play' }]]),
      reach: new Map([[rel, new Set(['pages/play'])]]),
      packages: { 'pages/play': {} },
    }, dir);
    assert.ok(probe['pages/play'] > 300 * KB);
    assert.ok(measure().est['pages/play'] + probe['pages/play'] >= PLAY_EST_LIMIT);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('新分包:只放组件,pages 为空;玩法组件不再留在 pages/play', () => {
  const app = JSON.parse(read('app.json'));
  const sub = app.subPackages.find((s) => s.root === 'pages/playkit');
  assert.deepEqual(sub, { root: 'pages/playkit', name: 'subpackagePlaykit', pages: [] });
  assert.equal(app.lazyCodeLoading, 'requiredComponents');
  const left = fs.readdirSync(path.join(ROOT, 'pages/play/components')).filter((d) => /^playkit/.test(d));
  assert.deepEqual(left, []);
  assert.ok(fs.existsSync(path.join(ROOT, 'pages/playkit/components/playkit/index.json')));
});

function jsonFiles(dir, out = []) {
  for (const e of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
    if (e.name.startsWith('.') || ['node_modules', 'tests', 'scripts', 'docs', 'artifacts'].includes(e.name)) continue;
    const rel = dir ? dir + '/' + e.name : e.name;
    if (e.isDirectory()) jsonFiles(rel, out); else if (e.name.endsWith('.json')) out.push(rel);
  }
  return out;
}

test('跨分包引用 pages/playkit 的组件都配了 componentPlaceholder', () => {
  const users = [];
  for (const file of jsonFiles('')) {
    if (file.startsWith('pages/playkit/')) continue;
    let j;
    try { j = JSON.parse(read(file)); } catch (_) { continue; }
    for (const [name, spec] of Object.entries(j.usingComponents || {})) {
      if (!/^\/?pages\/playkit\//.test(String(spec))) continue;
      users.push(file + ':' + name);
      assert.ok((j.componentPlaceholder || {})[name], `${file} 引用 ${spec} 缺 componentPlaceholder.${name}`);
    }
  }
  for (const must of ['pages/play/index.json:cy-playkit', 'pages/play/index.json:cy-playkit-journey-check',
    'pages/play/index.json:cy-playkit-album', 'pages/play/poi-challenge/index.json:cy-playkit-qa',
    'pages/publish/temp/index.json:cy-playkit']) assert.ok(users.includes(must), must);
});

test('pages/playkit 不同步依赖 pages/play(JS require / WXSS @import / WXS);组件反向引用必须走占位', () => {
  const { g } = measure();
  for (const [file, info] of g.files) {
    if (info.owner !== 'pages/playkit' || !/\.(js|wxss|wxml|wxs|json)$/.test(file)) continue;
    if (file.endsWith('.json')) {
      const j = JSON.parse(read(file));
      for (const [name, spec] of Object.entries(j.usingComponents || {})) {
        if (/^\/?pages\/play\//.test(spec)) assert.ok((j.componentPlaceholder || {})[name], `${file} → ${spec} 缺 componentPlaceholder`);
      }
      continue;
    }
    const src = graph.stripJsComments(read(file)).replace(/<!--[\s\S]*?-->/g, '');
    assert.doesNotMatch(src, /["'(]\/pages\/play\//, file);
    for (const m of src.matchAll(/(?:require\(|@import\s+|src=)\s*["']((?:\.\.\/)+[^"']+)["']/g)) {
      const target = path.posix.normalize(path.posix.join(path.posix.dirname(file), m[1]));
      assert.ok(!target.startsWith('pages/play/'), `${file} → ${target}`);
    }
  }
  // play-verdict 留在 pages/play(它的 @keyframes 不复制一份),play-stage 经占位异步引用
  const stage = JSON.parse(read('pages/playkit/components/play-stage/index.json'));
  assert.equal(stage.usingComponents['cy-play-verdict'], '/pages/play/components/play-verdict/index');
  assert.equal(stage.componentPlaceholder['cy-play-verdict'], 'view');
});

test('预下载:进 play / poi-challenge / 主题详情时预下载 subpackagePlaykit,每页合计 est < 2MB', () => {
  const app = JSON.parse(read('app.json'));
  const rule = app.preloadRule;
  assert.deepEqual(rule['pages/play/index'], { network: 'all', packages: ['subpackagePlaykit'] });
  assert.deepEqual(rule['pages/play/poi-challenge/index'], { network: 'all', packages: ['subpackagePlaykit'] });
  assert.ok(rule['pages/topic/index/index'].packages.includes('subpackagePlaykit'));
  const { est } = measure();
  const byName = Object.fromEntries(app.subPackages.map((s) => [s.name || s.root, s.root]));
  for (const [page, r] of Object.entries(rule)) {
    const total = r.packages.reduce((s, name) => s + est[byName[name]], 0);
    assert.ok(total < PRELOAD_LIMIT, `${page} 预下载合计 est ${(total / KB).toFixed(0)}KB ≥ 2048KB`);
  }
});

test('共享件的逐字副本与 pages/play 规范实现一致', () => {
  // cy-illus 的副本由 launch-look-tokens-illus.test.js 统一钉住
  const pairs = ['utils/keep-numerals.js', 'utils/sensor-contract.js', 'utils/playkit-steps.js',
    'utils/playkit-questo.js'];
  for (const rel of pairs) {
    const canon = read('pages/play/' + rel).split('/pages/play/').join('/pages/playkit/');
    assert.equal(read('pages/playkit/' + rel), canon, rel + ' 副本与 pages/play 规范实现不一致');
  }
});
