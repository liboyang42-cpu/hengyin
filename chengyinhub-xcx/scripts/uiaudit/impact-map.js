#!/usr/bin/env node
'use strict';

/**
 * 把「改了哪些文件」映射到「哪些页面必须重拍」。
 *
 * 为什么不能凭「改了 pages/xx 就拍 xx」:本仓的 UI 大量在 components/cy/** 里,
 * 一个共享组件改一行,波及的是**引用它的每一页**;而样式变量层的改动更狠 ——
 * 2026-07-31 实证「紫/墨蓝只扫页面硬编码、漏了 token 定义层」。所以这里做的是
 * **组件反向索引 + 传递闭包**,不是路径前缀匹配。
 *
 * 三档:
 *   must   页面自己的 js/wxml/wxss/json 改了,或它(传递地)引用的组件改了
 *   should 全局样式 / 全局脚本 / token 改了 —— 波及面广但多数页只是"可能不同"
 *   skip   以上都不沾
 *
 * ⚠️ 本脚本**不启动 DevTools、不拍图**。
 *
 * 用法:node scripts/uiaudit/impact-map.js <baseRef> <ref1> [ref2 ...] [--json]
 *       node scripts/uiaudit/impact-map.js --selftest
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const S = require('../lib/xcx-scan.js');
const { readAppRoutes } = require('./build-route-matrix.js');

const ROOT = S.ROOT;
const REPO = path.resolve(ROOT, '..');
const XCX_PREFIX = 'chengyinhub-xcx/';

// 全局样式/token:改了它们,每一页的观感都可能变 ⇒ 全量 should。
const GLOBAL_STYLE = [/^app\.wxss$/, /^style\//, /^styles\//];
// app.js 是启动/会话/globalData 层:它改了**不等于**每一页的画面都变。
// 把它摊成 111 条 should 等于没有分档 —— 单列成一条冒烟项(启动 + tabBar 五页),别污染分档。
const APP_ENTRY = [/^app\.js$/];
// 逐页判:这一页(或它的组件)到底 require 没 require 那个被改的 utils/behaviors。
const SHARED_SCRIPT = [/^utils\//, /^behaviors\//];

function changedFiles(baseRef, refs) {
  const set = new Set();
  refs.forEach((ref) => {
    const out = execFileSync('git', ['-C', REPO, 'diff', '--name-only', baseRef + '..' + ref, '--', XCX_PREFIX],
      { encoding: 'utf8' });
    out.split('\n').map((s) => s.trim()).filter(Boolean).forEach((f) => set.add(f.slice(XCX_PREFIX.length)));
  });
  return set;
}

/** 页面/组件 -> 它直接引用的组件基名(相对 xcx 根,不带扩展名)。 */
function directDeps(ownerBaseRel) {
  const owner = path.join(ROOT, ownerBaseRel + '.wxml');
  if (!fs.existsSync(owner)) return [];
  const using = S.loadUsingComponents(owner);
  return [...using.values()].map((abs) => S.rel(abs));
}

/** 传递闭包:一个页面最终依赖的全部组件基名。 */
function transitiveDeps(pageBaseRel, cache) {
  const seen = new Set();
  const stack = directDeps(pageBaseRel);
  while (stack.length) {
    const dep = stack.pop();
    if (seen.has(dep)) continue;
    seen.add(dep);
    const nested = cache.has(dep) ? cache.get(dep) : directDeps(dep);
    cache.set(dep, nested);
    nested.forEach((n) => { if (!seen.has(n)) stack.push(n); });
  }
  return seen;
}

const OWNER_EXT = ['.js', '.wxml', '.wxss', '.json'];

/** 一份 js 里 require 到的仓内模块,归一成「相对 xcx 根、不带扩展名」的基名。 */
function requiredModules(baseRel) {
  const file = path.join(ROOT, baseRel + '.js');
  if (!fs.existsSync(file)) return [];
  const masked = fs.readFileSync(file, 'utf8');     // require 路径是字符串字面量,不能用 maskNonCode
  const out = [];
  const re = /require\(\s*['"]([^'"]+)['"]\s*\)/g;
  let m;
  while ((m = re.exec(masked))) {
    const spec = m[1];
    if (!spec.startsWith('.') && !spec.startsWith('/')) continue;   // npm 包不管
    const abs = spec.startsWith('/')
      ? path.join(ROOT, spec.slice(1))
      : path.resolve(path.dirname(file), spec);
    out.push(S.rel(abs).replace(/\.js$/, ''));
  }
  return out;
}

function classifyRoutes(changed) {
  const routes = readAppRoutes();
  const cache = new Map();
  const globalStyleHits = [...changed].filter((f) => GLOBAL_STYLE.some((re) => re.test(f)));
  const appEntryHits = [...changed].filter((f) => APP_ENTRY.some((re) => re.test(f)));
  const sharedScriptHits = new Set([...changed]
    .filter((f) => SHARED_SCRIPT.some((re) => re.test(f)))
    .map((f) => f.replace(/\.js$/, '')));

  const must = [];
  const should = [];
  const skip = [];
  routes.forEach((r) => {
    const ownHit = OWNER_EXT.map((e) => r.pagePath + e).filter((f) => changed.has(f));
    const deps = transitiveDeps(r.pagePath, cache);
    const depHit = [...deps].filter((d) => OWNER_EXT.some((e) => changed.has(d + e)));
    if (ownHit.length || depHit.length) {
      must.push({ pagePath: r.pagePath, reason: ownHit.length ? '页面自身改动' : '引用的组件改动', ownHit, depHit });
      return;
    }
    // 逐页判共享脚本:这一页或它的组件真的 require 了被改的那个 utils 才算波及。
    const scriptHit = [];
    if (sharedScriptHits.size) {
      const owners = [r.pagePath, ...deps];
      owners.forEach((o) => {
        requiredModules(o).forEach((mod) => { if (sharedScriptHits.has(mod)) scriptHit.push(mod); });
      });
    }
    if (globalStyleHits.length || scriptHit.length) {
      should.push({
        pagePath: r.pagePath,
        reason: globalStyleHits.length ? '全局样式/token 改动' : '引用了被改的共享脚本',
        scriptHit: [...new Set(scriptHit)],
      });
    } else {
      skip.push({ pagePath: r.pagePath });
    }
  });
  return { must, should, skip, globalStyleHits, appEntryHits, sharedScriptHits: [...sharedScriptHits],
    changedCount: changed.size, routeCount: routes.length };
}

// ---------- 自证 ----------
function runSelfTest() {
  const routes = readAppRoutes();
  const cache = new Map();
  const empty = classifyRoutes(new Set());
  const sample = routes.find((r) => r.pagePath === 'pages/index/index') || routes[0];
  const onlyOne = classifyRoutes(new Set([sample.pagePath + '.wxml']));
  const onlyGlobal = classifyRoutes(new Set(['app.wxss']));
  const onlyAppJs = classifyRoutes(new Set(['app.js']));
  const deepened = routes.some((r) => transitiveDeps(r.pagePath, cache).size > directDeps(r.pagePath).length);

  const checks = {
    '空改动集 ⇒ 零 must 零 should': empty.must.length === 0 && empty.should.length === 0,
    '只改一页 ⇒ 该页 must,且不是全量 must':
      onlyOne.must.some((m) => m.pagePath === sample.pagePath) && onlyOne.must.length < routes.length,
    '只改全局样式 ⇒ 零 must、全量 should':
      onlyGlobal.must.length === 0 && onlyGlobal.should.length === routes.length,
    '★组件反向索引起作用了(传递闭包 > 直接依赖)': deepened,
    '★app.js 单改不摊成全量 should(否则分档形同虚设)':
      onlyAppJs.must.length === 0 && onlyAppJs.should.length === 0 && onlyAppJs.appEntryHits.length === 1,
    '三档互斥且合计等于路由总数':
      onlyOne.must.length + onlyOne.should.length + onlyOne.skip.length === routes.length,
  };
  const failed = Object.keys(checks).filter((k) => !checks[k]);
  if (failed.length) {
    console.error('impact-map 自证失败 ->', failed.join(' / '));
    process.exit(1);
  }
  console.log('impact-map 自证通过(' + Object.keys(checks).length + ' 项)');
}

if (require.main === module) {
  if (process.argv.includes('--selftest')) { runSelfTest(); process.exit(0); }
  const args = process.argv.slice(2).filter((a) => !a.startsWith('--'));
  if (args.length < 2) {
    console.error('用法: node scripts/uiaudit/impact-map.js <baseRef> <ref1> [ref2 ...]');
    process.exit(2);
  }
  const base = args[0];
  const refs = args.slice(1);
  const changed = changedFiles(base, refs);
  const res = classifyRoutes(changed);
  if (process.argv.includes('--json')) {
    const out = path.join(__dirname, 'impact-map.json');
    fs.writeFileSync(out, JSON.stringify(Object.assign({
      note: '重拍分档。must=必须重拍,should=建议重拍,skip=可跳过。由 impact-map.js 生成,别手改。',
      base, refs,
    }, res), null, 2) + '\n');
    console.log('已写出 ' + S.rel(out));
  }
  console.log('改动 xcx 文件 ' + res.changedCount + ' 个,路由 ' + res.routeCount + ' 条');
  console.log('  必须重拍 ' + res.must.length + ' 页 / 建议重拍 ' + res.should.length + ' 页 / 可跳过 ' + res.skip.length + ' 页');
  if (res.globalStyleHits.length) console.log('  全局样式改动: ' + res.globalStyleHits.join(', '));
  if (res.sharedScriptHits.length) console.log('  被改的共享脚本: ' + res.sharedScriptHits.join(', '));
  if (res.appEntryHits.length) console.log('  ⚠️ app.js 改了 ⇒ 另配一条启动冒烟(启动 + tabBar 五页),不摊进分档');
}

module.exports = { changedFiles, classifyRoutes, transitiveDeps, directDeps };
