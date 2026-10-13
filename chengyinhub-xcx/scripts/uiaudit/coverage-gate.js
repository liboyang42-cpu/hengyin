#!/usr/bin/env node
'use strict';

/**
 * 截图矩阵**覆盖率**门禁:app.json 里的每一条路由,shot-matrix 都必须拍到(或显式豁免)。
 *
 * ★ 为什么必须有这一条:shot-matrix.js 自带的体检是**反方向**的 ——
 *   它查「矩阵引用了不存在的页」(拍出来是编译错误图)。
 *   但「app.json 新增了一页、矩阵里没有这一行」是**查不出来的**:
 *   没有的行不会报错,漏拍表现为「一切正常」。2026-08-18 实测:自 20260810 那批以来
 *   新增了 14 条路由,一条都不在矩阵里 —— 全量复核照跑会全绿,然后漏掉这 14 页。
 *
 * 这就是本仓反复栽的那类坑的同型:**门禁只有一个方向 = 另一个方向永远不会红。**
 *
 * ⚠️ 本脚本不启动 DevTools、不拍图。
 *
 * 用法:node scripts/uiaudit/coverage-gate.js            覆盖率报告(有缺口即 exit 1)
 *       node scripts/uiaudit/coverage-gate.js --selftest 自证
 */
const fs = require('fs');
const path = require('path');
const S = require('../lib/xcx-scan.js');
const { readAppRoutes } = require('./build-route-matrix.js');

const EXEMPT_FILE = path.join(__dirname, 'coverage-exemptions.json');

function matrixRoutes() {
  const { SHOTS } = require('../shot-matrix.js');
  const map = new Map();   // route -> [shot id]
  SHOTS.forEach((s) => {
    const r = String(s.route || '').replace(/^\//, '').split('?')[0];
    if (!r) return;
    if (!map.has(r)) map.set(r, []);
    map.get(r).push(s.id + (s.blocked ? '(blocked)' : ''));
  });
  return map;
}

// ⚠️ 豁免不是逃生口:每条必须同时有 reason 与 altVerification(替代验证手段)。
//    只写一句「拍不了」而不给替代手段,等于把这一页从复核里删掉 —— 那就是新的「未判定」无限逃生口。
//    缺任一字段的豁免**不予承认**(fail-closed:当作没豁免,该红照红)。
function loadExemptions() {
  if (!fs.existsSync(EXEMPT_FILE)) return { valid: new Map(), malformed: [] };
  const json = JSON.parse(fs.readFileSync(EXEMPT_FILE, 'utf8'));
  const valid = new Map();
  const malformed = [];
  (json.exemptions || []).forEach((e) => {
    const ok = e && e.pagePath && String(e.reason || '').trim() && String(e.altVerification || '').trim();
    if (ok) valid.set(e.pagePath, e);
    else if (e && e.pagePath) malformed.push(e.pagePath);
  });
  return { valid, malformed };
}

function analyze() {
  return analyzeWith(readAppRoutes(), matrixRoutes());
}

function runSelfTest() {
  const res = analyze();
  const routes = readAppRoutes();
  const shot = matrixRoutes();
  // 造一条 app.json 里没有的路由塞进矩阵副本,验「反方向」识别;
  // 再造一条矩阵里没有的路由,验「缺口」识别 —— 两个方向都要能红。
  const fakeMissing = analyzeWith(routes.concat([{ pagePath: '__no_such_page__', pkg: 'main', tabBar: false }]), shot);
  const fakeStaleShot = new Map(shot); fakeStaleShot.set('__ghost_route__', ['Z99']);
  const fakeStale = analyzeWith(routes, fakeStaleShot);

  const checks = {
    '真实仓库里能算出覆盖数(不是解析空了)': res.covered.length > 50,
    '★缺口方向能红:app.json 多一条 ⇒ missing +1':
      fakeMissing.missing.length === res.missing.length + 1,
    '★反方向能识别:矩阵多一条不存在的路由 ⇒ stale +1':
      fakeStale.stale.length === res.stale.length + 1,
    'blocked-only 与 covered 不重复计':
      res.covered.every((c) => !res.onlyBlocked.some((b) => b.pagePath === c)),
    '覆盖 + 缺口 + 仅blocked + 豁免 = 路由总数':
      res.covered.length + res.missing.length + res.onlyBlocked.length + res.exempted.length === res.routes.length,
    '★blocked-only 不被静默放过:未豁免的只有 blocked 行 ⇒ 进 onlyBlocked(会判红)':
      (() => {
        const shot2 = new Map([['__blocked_only__', ['Z01(blocked)']]]);
        const r = analyzeWith([{ pagePath: '__blocked_only__', pkg: 'main', tabBar: false }], shot2);
        return r.onlyBlocked.length === 1 && r.covered.length === 0;
      })(),
    '★豁免必须带 reason + altVerification(缺一不予承认)':
      (() => {
        const raw = JSON.parse(fs.readFileSync(EXEMPT_FILE, 'utf8'));
        return (raw.exemptions || []).length > 0
          && (raw.exemptions || []).every((e) => String(e.reason || '').trim() && String(e.altVerification || '').trim());
      })(),
  };
  const failed = Object.keys(checks).filter((k) => !checks[k]);
  if (failed.length) {
    console.error('coverage-gate 自证失败 ->', failed.join(' / '));
    console.error(JSON.stringify({ covered: res.covered.length, missing: res.missing.length, onlyBlocked: res.onlyBlocked.length, exempt: res.exemptCount, total: res.routes.length }, null, 2));
    process.exit(1);
  }
  console.log('coverage-gate 自证通过(' + Object.keys(checks).length + ' 项,两个方向各有负控)');
}

/** 判据本体。自证要注入 routes/shot,所以显式传参。 */
function analyzeWith(routes, shot) {
  const { valid: exempt, malformed } = loadExemptions();
  const missing = [];
  const covered = [];
  const onlyBlocked = [];
  const exempted = [];
  routes.forEach((r) => {
    const ids = shot.get(r.pagePath);
    const isExempt = exempt.has(r.pagePath);
    if (!ids || !ids.length) {
      if (isExempt) { exempted.push(r.pagePath); return; }
      missing.push(r.pagePath);
      return;
    }
    if (ids.every((i) => i.endsWith('(blocked)'))) {
      // ★ 只有 blocked 行 = 等于没拍。不把它单列成一档"提示"就放过 ——
      //   那样 blocked 本身就成了绕开覆盖率门禁的后门。豁免了才放行。
      if (isExempt) { exempted.push(r.pagePath); return; }
      onlyBlocked.push({ pagePath: r.pagePath, ids });
      return;
    }
    covered.push(r.pagePath);
  });
  const stale = [...shot.keys()].filter((r) => !routes.some((x) => x.pagePath === r));
  return { routes, covered, missing, onlyBlocked, exempted, stale, malformed, exemptCount: exempt.size };
}

if (require.main === module) {
  if (process.argv.includes('--selftest')) { runSelfTest(); process.exit(0); }
  const res = analyze();
  console.log(`路由 ${res.routes.length} 条:已覆盖 ${res.covered.length} / 显式豁免 ${res.exempted.length} / 仅 blocked ${res.onlyBlocked.length} / **未覆盖 ${res.missing.length}**`);
  if (res.exempted.length) {
    console.log(`显式豁免 ${res.exempted.length} 条(每条都带 reason + altVerification):`);
    res.exempted.forEach((r) => console.log('  ' + r));
  }
  if (res.stale.length) {
    console.log(`矩阵里有 ${res.stale.length} 条 app.json 已无的路由(反方向,由 shot-matrix 自带体检负责):`);
    res.stale.forEach((r) => console.log('  ' + r));
  }
  if (res.malformed.length) {
    console.error('以下豁免缺 reason 或 altVerification,**不予承认**(豁免不是逃生口):');
    res.malformed.forEach((r) => console.error('  ' + r));
  }
  if (res.onlyBlocked.length) {
    console.error('以下路由在矩阵里只有 blocked 行 —— 等于没拍:');
    res.onlyBlocked.forEach((b) => console.error(`  ${b.pagePath}  ${b.ids.join(',')}`));
    console.error('要么补前置让它可拍,要么进 coverage-exemptions.json 并写清 reason + altVerification。');
  }
  if (res.missing.length) {
    console.error('以下路由在 app.json 里,但 shot-matrix 一行都没有 —— 全量复核会静默漏拍:');
    res.missing.forEach((r) => console.error('  ' + r));
  }
  if (res.missing.length || res.onlyBlocked.length || res.malformed.length) {
    console.error(`覆盖率门禁不通过:未覆盖 ${res.missing.length} / 仅 blocked ${res.onlyBlocked.length} / 无效豁免 ${res.malformed.length}`);
    process.exit(1);
  }
  console.log('覆盖率门禁:通过');
}

module.exports = { analyze, analyzeWith, matrixRoutes };
