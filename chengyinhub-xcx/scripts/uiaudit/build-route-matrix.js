#!/usr/bin/env node
'use strict';

/**
 * 全量 UI 复核的**截图矩阵真源**:把 app.json 的全部路由 + 孪生的「场景弹窗」那一半
 * 拉成一张表,并标出拍每一张图各自需要的前置条件。
 *
 * 为什么要有这个文件(2026-08-18):上一次全量是 ~/Desktop/城瘾全量截图_20260810,
 * 那批 230 张的矩阵是**手写**的。手写矩阵的问题不是麻烦,是**它不会告诉你漏了什么** ——
 * 新增页 / 搬家页在手写清单里表现为「没有这一行」,而没有的行不会报错。
 * 这里改成从 app.json 生成,再跟上一批的 manifest 对账,漏没漏由对账说了算。
 *
 * ⚠️ 本脚本**不启动 DevTools、不拍图**,只产清单。
 *
 * 用法:node scripts/uiaudit/build-route-matrix.js            打印摘要
 *       node scripts/uiaudit/build-route-matrix.js --json     写出 route-matrix.json
 *       node scripts/uiaudit/build-route-matrix.js --selftest 自证
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const S = require('../lib/xcx-scan.js');

const ROOT = S.ROOT;
const PREV_BATCH = path.join(os.homedir(), 'Desktop', '城瘾全量截图_20260810');

// ---------- 路由 ----------
function readAppRoutes() {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const tabBar = new Set(((app.tabBar || {}).list || []).map((t) => t.pagePath));
  const routes = [];
  (app.pages || []).forEach((p) => routes.push({ pagePath: p, pkg: 'main', tabBar: tabBar.has(p) }));
  (app.subPackages || app.subpackages || []).forEach((s) => {
    (s.pages || []).forEach((p) => {
      const full = s.root.replace(/\/$/, '') + '/' + p;
      routes.push({ pagePath: full, pkg: s.root, tabBar: tabBar.has(full) });
    });
  });
  return routes;
}

// ---------- 前置条件识别(启发式,报告里已声明要人工复核) ----------
const READ_USER = /getAppUserId|getUserID\s*\(|getAuthorization|sendRequest|createGameSessionClient|createClubGameDirectorAdapter/;
const MERCHANT_HINT = /merchant-theme|theme-merchant|merchantPageShow/;
// 官方活动详情是商家侧官方活动入口；玩家活动继续使用玩家活动详情页。
// 账户收益 / 分析 / 收益明细是玩家资金页;商家经 ?theme=merchant 进同一页的浅色版,根节点的 theme-merchant 只是条件类。
const ROLE_OVERRIDES = new Map([
  ['pages/activity/official-detail/index', 'merchant'],
  ['subpackageA/pages/assetcenter/earnings/index', 'player'],
  ['subpackageA/pages/assetcenter/analysis/index', 'player'],
  ['subpackageA/pages/assetcenter/income-detail/income-detail', 'player'],
]);

// These existing factories enforce identity in onShow before constructing a client.
// Match the resolved module AND the exported function used by Page(), not a package/name hint.
function usesLoginPageFactory(js, entryFile, masked = S.maskNonCode(js)) {
  const factories = new Map([
    [path.join(ROOT, 'subpackageTools/cloud-ten-runtime.js'), 'page'],
    [path.join(ROOT, 'subpackageTools/lib/runtime.js'), 'runtime'],
  ]);
  const imports = /\b(?:const|let|var)\s*\{([^}]+)\}\s*=\s*require\s*\(\s*(['"])([^'"]+)\2\s*\)/g;
  for (const match of js.matchAll(imports)) {
    // An apparent declaration inside a comment/string is not a dependency.
    if (!/^(?:const|let|var)\b/.test(masked.slice(match.index))) continue;
    if (!match[3].startsWith('.')) continue;
    const resolved = path.resolve(path.dirname(entryFile), match[3].replace(/\.js$/, '') + '.js');
    const exported = factories.get(resolved);
    if (!exported) continue;
    const open = match.index + match[0].indexOf('{') + 1;
    const bindings = masked.slice(open, match.index + match[0].indexOf('}'));
    for (const binding of bindings.split(',')) {
      const parsed = /^\s*([\w$]+)(?:\s*:\s*([\w$]+))?\s*$/.exec(binding);
      if (!parsed || parsed[1] !== exported) continue;
      const local = (parsed[2] || parsed[1]).replace(/\$/g, () => '\\$');
      if (new RegExp('\\bPage\\s*\\(\\s*' + local + '\\s*\\(').test(masked)) return true;
    }
  }
  return false;
}

function classify(route) {
  const base = path.join(ROOT, route.pagePath);
  const js = fs.existsSync(base + '.js') ? fs.readFileSync(base + '.js', 'utf8') : '';
  const wxml = fs.existsSync(base + '.wxml') ? fs.readFileSync(base + '.wxml', 'utf8') : '';
  const masked = S.maskNonCode(js);          // 注释里的字样不算信号
  const maskedWxml = S.maskWxmlComments(wxml);

  // 角色:路径是强信号,主题/工具是佐证。平台侧在 Web 后台,不在小程序里。
  let role = 'player';
  if (/^pages\/merchant\//.test(route.pagePath) || MERCHANT_HINT.test(masked) || /theme-merchant/.test(maskedWxml)) role = 'merchant';
  if (/^pages\/club\//.test(route.pagePath)) role = 'club';
  if (/^pages\/coop\//.test(route.pagePath)) role = 'merchant';   // 协作/招商都是商家侧入口
  if (ROLE_OVERRIDES.has(route.pagePath)) role = ROLE_OVERRIDES.get(route.pagePath);

  // 登录态:页面完全不碰会话也不发请求的,才算公开页(协议页、裁图页这类)
  const needsLogin = READ_USER.test(masked) || usesLoginPageFactory(js, base + '.js', masked);

  // 数据前置:onLoad 从 options 里取业务 id ⇒ 必须先有一条真数据才拍得出正常态
  const optionKeys = new Set();
  // ⚠️ 别写死 `options.` —— onLoad 的形参名是作者自己起的。2026-09-03 实证:
  //    活动详情页写的是 `onLoad(query)`,于是 topicId/activityId 一个都没被认出来,
  //    这一页被当成「不需要前置数据」,截图矩阵会以为它随便拍都能拍到正常态。
  //    这里先读出真实形参名,读不到才退回常见几个。
  const paramNames = new Set(['options']);
  for (const om of masked.matchAll(/onLoad\s*\(\s*([A-Za-z_$][\w$]*)\s*\)/g)) paramNames.add(om[1]);
  for (const name of paramNames) {
    const re = new RegExp(`${name}\\s*\\.\\s*([A-Za-z_$][\\w$]*)`, 'g');
    let m;
    while ((m = re.exec(masked))) optionKeys.add(m[1]);
  }
  const dataKeys = [...optionKeys].filter((k) => /id$/i.test(k) || /^(mode|type|scene|code|regId|no)$/.test(k));

  return {
    role,
    needsLogin,
    needsData: dataKeys.length > 0,
    dataKeys,
    hasJs: !!js,
    hasWxml: !!wxml,
  };
}

// ---------- 孪生:页面 ↔ 场景弹窗 ----------
// ★ 本仓有一批「同一块 UI 两份实现」:一份是独立页面,一份是挂在别的页里的 scene-* 组件。
//   只扫 pages/** 会**只拍到一半**,而改错那一份是零报红的(2026-08-08 收口 6 对时 0→49 fail)。
//   这里把 scene-* 组件全部拉出来,并按名字给出最可能的配对页,配不上的也留在矩阵里 ——
//   「配不上」只说明我猜不出宿主,不代表它不用拍。
const SCENE_INFRA = new Set(['scene-sheet', 'scene-deep-link']);   // 容器/路由基建,不是一屏 UI

function findSceneComponents() {
  const out = [];
  S.walkFiles(ROOT, '.wxml').forEach((f) => {
    const dir = path.dirname(f);
    const name = path.basename(dir);
    if (!/^scene-/.test(name)) return;
    if (SCENE_INFRA.has(name)) return;
    if (path.basename(f) !== 'index.wxml' && path.basename(f) !== name + '.wxml') return;
    out.push({ component: name, dir: S.rel(dir) });
  });
  return out.sort((a, b) => a.component.localeCompare(b.component));
}

/** 用组件名的词块去猜宿主页面:scene-member-order-history → 含 member+order+history 的路由。 */
function guessTwinRoute(componentName, routes) {
  const words = componentName.replace(/^scene-/, '').split('-').filter(Boolean);
  let best = null;
  let bestScore = 0;
  routes.forEach((r) => {
    const hay = r.pagePath.toLowerCase();
    const score = words.reduce((acc, w) => acc + (hay.includes(w) ? w.length : 0), 0);
    if (score > bestScore) { bestScore = score; best = r.pagePath; }
  });
  // 只认「至少命中两个词块或一个长词块」的猜测,免得给出一堆噪音配对
  return bestScore >= 8 ? { route: best, score: bestScore } : null;
}

// ---------- 与上一批(20260810)对账 ----------
function loadPreviousRoutes() {
  const merged = path.join(PREV_BATCH, 'manifest.merged.json');
  if (!fs.existsSync(merged)) return null;
  const json = JSON.parse(fs.readFileSync(merged, 'utf8'));
  const set = new Map();   // route(去掉前导 /) -> 张数
  (json.shots || []).forEach((s) => {
    const r = String(s.route || '').replace(/^\//, '').split('?')[0];
    if (!r) return;
    set.set(r, (set.get(r) || 0) + 1);
  });
  return set;
}

function build() {
  const routes = readAppRoutes().map((r) => Object.assign(r, classify(r)));
  const scenes = findSceneComponents().map((s) => Object.assign(s, { twin: guessTwinRoute(s.component, routes) }));
  const prev = loadPreviousRoutes();

  let added = [];
  let removed = [];
  if (prev) {
    const now = new Set(routes.map((r) => r.pagePath));
    added = routes.filter((r) => !prev.has(r.pagePath)).map((r) => r.pagePath);
    removed = [...prev.keys()].filter((r) => !now.has(r));
  }
  return { routes, scenes, prev, added, removed };
}

function materialize(result = build()) {
  return {
    note: '截图矩阵真源。由 build-route-matrix.js 从 app.json 生成,不要手改 —— 手改就会漏新增页。',
    generatedFrom: 'app.json + scene-* 组件扫描',
    previousBatch: result.prev ? '城瘾全量截图_20260810' : null,
    counts: {
      routes: result.routes.length,
      scenes: result.scenes.length,
      added: result.added.length,
      removed: result.removed.length,
    },
    routes: result.routes,
    scenes: result.scenes,
    added: result.added,
    removed: result.removed,
  };
}

// 上一批截图目录是本机外部证据，CI 不一定存在；freshness 只比较由当前仓库
// app.json / WXML / JS 决定的 routes、scenes 及其角色和前置条件。
function currentSourceSnapshot(matrix) {
  return {
    counts: {
      routes: matrix && matrix.counts && matrix.counts.routes,
      scenes: matrix && matrix.counts && matrix.counts.scenes,
    },
    routes: matrix && matrix.routes,
    scenes: matrix && matrix.scenes,
  };
}

function inventoryMatchesCurrent(inventory) {
  if (!inventory || !Array.isArray(inventory.routes) || !Array.isArray(inventory.scenes)) return false;
  return JSON.stringify(currentSourceSnapshot(inventory))
    === JSON.stringify(currentSourceSnapshot(materialize(build())));
}

// ---------- 自证 ----------
function runSelfTest() {
  const { routes, scenes } = build();
  const current = materialize(build());
  const stale = JSON.parse(JSON.stringify(current));
  stale.routes.pop();
  stale.counts.routes -= 1;
  const checks = {
    '路由数量落在合理量级(不是解析空了)': routes.length > 80 && routes.length < 300,
    'tabBar 页被识别出来(自定义 tabBar 也算)': routes.filter((r) => r.tabBar).length >= 4,
    '主包与分包都进来了': routes.some((r) => r.pkg === 'main') && routes.some((r) => r.pkg !== 'main'),
    '商家侧路由被识别': routes.some((r) => r.role === 'merchant'),
    '俱乐部侧路由被识别': routes.some((r) => r.role === 'club'),
    '官方活动详情归入商家浅色工作域':
      routes.some((r) => r.pagePath === 'pages/activity/official-detail/index' && r.role === 'merchant'),
    '存在需要数据前置的页(不是全判成不需要)': routes.some((r) => r.needsData) && routes.some((r) => !r.needsData),
    '★孪生:scene-* 组件真的扫到了(只扫 pages/** 会漏掉这半边)': scenes.length >= 20,
    '孪生配对不是全空也不是全配上(全配上说明打分阈值形同虚设)':
      scenes.some((s) => s.twin) && scenes.some((s) => !s.twin),
    '每条路由都拿到了 wxml(拿不到说明路径拼错了)': routes.every((r) => r.hasWxml),
    '当前生成物 freshness 正例通过': inventoryMatchesCurrent(current),
    '★负控:少一条路由必须判为过期': !inventoryMatchesCurrent(stale),
  };
  const failed = Object.keys(checks).filter((k) => !checks[k]);
  if (failed.length) {
    console.error('route-matrix 自证失败 ->', failed.join(' / '));
    process.exit(1);
  }
  console.log(`route-matrix 自证通过(${Object.keys(checks).length} 项)`);
}

if (require.main === module) {
  if (process.argv.includes('--selftest')) { runSelfTest(); process.exit(0); }
  const result = build();
  const { routes, scenes, prev, added, removed } = result;
  if (process.argv.includes('--check')) {
    const out = path.join(__dirname, 'route-matrix.json');
    const inventory = fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, 'utf8')) : null;
    if (!inventoryMatchesCurrent(inventory)) {
      console.error('route-matrix FAIL: 生成物已漂移；运行 build-route-matrix.js --json 后审阅变更');
      process.exitCode = 1;
    } else {
      console.log(`route-matrix PASS: ${routes.length} routes / ${scenes.length} scenes`);
    }
    return;
  }
  if (process.argv.includes('--json')) {
    const out = path.join(__dirname, 'route-matrix.json');
    fs.writeFileSync(out, JSON.stringify(materialize(result), null, 2) + '\n');
    console.log('已写出 ' + S.rel(out));
  }
  console.log(`路由 ${routes.length} 条 / scene-* 孪生组件 ${scenes.length} 个`);
  console.log(`  tabBar 页 ${routes.filter((r) => r.tabBar).length}`);
  ['player', 'merchant', 'club'].forEach((role) => {
    console.log(`  ${role}: ${routes.filter((r) => r.role === role).length}`);
  });
  console.log(`  需登录 ${routes.filter((r) => r.needsLogin).length} / 需数据前置 ${routes.filter((r) => r.needsData).length}`);
  if (prev) {
    console.log(`与 20260810 对账:上批覆盖 ${prev.size} 条路由;新增 ${added.length} 条、上批有本次无 ${removed.length} 条`);
  } else {
    console.log('与 20260810 对账:找不到上批 manifest.merged.json,跳过');
  }
}

module.exports = {
  build,
  materialize,
  inventoryMatchesCurrent,
  readAppRoutes,
  classify,
  usesLoginPageFactory,
  findSceneComponents,
  guessTwinRoute,
};
