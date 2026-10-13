/*
 * 规范达标总核验：把《小程序UI规范真源》§11「能自动判红」那 6 条逐条跑一遍，
 * 给出全仓当前的达标状况。
 *
 *   node scripts/_audit_spec_compliance.js
 *
 * 为什么要它：「所有页面都达标了吗」这个问题，靠翻 227 张截图答不了 ——
 * 人眼看不出 8rpx 的间距差、看不出某个颜色走没走 token。
 * 规范自己划了线：哪些能机器判、哪些只能人工验收。这个脚本负责前者，
 * 后者交给 UI走查.html 让人逐页写。
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');

const SPACE = [8, 12, 16, 20, 24, 28, 32, 48, 64, 80, 96];
const RADIUS = [8, 12, 16, 20, 24, 32, 999];
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'miniprogram_npm', 'scripts', 'tests'].includes(e.name)) continue;
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else files.push(p);
  }
})(ROOT);
const wxss = files.filter((f) => f.endsWith('.wxss'));
const wxml = files.filter((f) => f.endsWith('.wxml'));
const rel = (f) => path.relative(ROOT, f);
const read = (f) => fs.readFileSync(f, 'utf8');
const results = [];

// ① 硬编码颜色（未走 token、未标 ds-ok）
// ⚠️ 判据必须跟既有门禁 _ds_consistency_scan 对齐，否则两套数字并存、谁也不知道信哪个。
//    我第一版自己写了套更宽的（连 rgba(0,0,0,.5) 这类遮罩层也算），报 728 处，
//    而既有门禁报 255 —— 差 473 全是判据不同造成的假数字。
//    对齐三点：①只看 color/background 属性 ②跳过含 --cy- 的定义行 ③跳过 ds-ok。
{
  const hits = [];
  const compHits = [];
  for (const f of wxss) {
    // 与既有门禁同一个 isPage：只认 pages/ 和 subpackage*/ 开头，
    // style/ 那些全局样式表既不是页面也不是组件，单独算第三类（下面不计入两档）。
    const r = rel(f);
    const isPage = /^(pages|subpackage)/.test(r);
    const isComponent = /^components\//.test(r);
    if (!isPage && !isComponent) continue;
    read(f).split('\n').forEach((line, i) => {
      if (/--cy-|ds-ok/.test(line)) return;
      if (!/(?:color|background(?:-color)?)\s*:\s*(#[0-9a-fA-F]{3,8}|rgba?\([^)]+\))/.test(line)) return;
      (isComponent ? compHits : hits).push(`${r}:${i + 1}`);
    });
  }
  results.push(['硬编码颜色(页面)', hits.length, hits.slice(0, 3)]);
  results.push(['硬编码颜色(组件层)', compHits.length, compHits.slice(0, 3)]);
}
// ② 描边与填充并存
{
  const hits = [];
  for (const f of wxss) {
    for (const m of read(f).matchAll(/(?:^|\})\s*([^{}@]+)\{([^}]*)\}/g)) {
      const body = m[2];
      if (/ds-ok/.test(body)) continue;
      // ⚠️ 判据要排掉 border:none / 0 / transparent —— 那三种都不是「描边」。
      //    第一版写成 (?!none|0) 只挡紧跟其后的情况，`border: none`(有空格)照样命中，
      //    把 .cy-card--flat 这种明确写了 none 的也算进去，168 处里一大半是这么来的。
      const bm = /(?<!-)border\s*:\s*([^;]+);/.exec(body);
      const hasBorder = !!bm && !/^\s*(none|0|transparent|.*\stransparent\s*$)/.test(bm[1]);
      const hasBg = /background(-color)?\s*:\s*(?!transparent|none)/.test(body);
      if (hasBorder && hasBg) hits.push(`${rel(f)} ${m[1].replace(/\/\*[\s\S]*?\*\//g, '').trim().slice(0, 30)}`);
    }
  }
  // ⚠️ 这个总数会误导：规范里「描边与填充并存」针对的是**按钮**，
  //    而 150 处里只有 12 处是按钮类选择器，其余 138 处是卡片 / 徽章 / 输入框 ——
  //    它们有描边+填充是正常的（认证徽章的白圈、输入框的聚焦边…）。
  //    所以分两档报，别拿总数当「待修数」。
  const btnHits = hits.filter((h) => /btn|button|cta|action|submit|confirm|cancel/i.test(h));
  results.push(['描边与填充并存(按钮·规范针对)', btnHits.length, btnHits.slice(0, 3)]);
  results.push(['描边与填充并存(卡片/徽章等·多为正常)', hits.length - btnHits.length, []]);
}
// ③ 间距 / 圆角不在梯度上
{
  const hits = [];
  for (const f of wxss) {
    read(f).split('\n').forEach((line, i) => {
      if (/ds-ok/.test(line)) return;
      for (const m of line.matchAll(/(?<!-)(padding|margin|gap)\s*:\s*([^;]+);/g)) {
        for (const v of m[2].match(/(\d+)rpx/g) || []) {
          if (!SPACE.includes(Number(v.replace('rpx', '')))) hits.push(`${rel(f)}:${i + 1} ${m[1]}:${v}`);
        }
      }
      for (const m of line.matchAll(/border-radius\s*:\s*([^;]+);/g)) {
        for (const v of m[1].match(/(\d+)rpx/g) || []) {
          if (!RADIUS.includes(Number(v.replace('rpx', '')))) hits.push(`${rel(f)}:${i + 1} radius:${v}`);
        }
      }
    });
  }
  results.push(['间距/圆角不在梯度上', hits.length, hits.slice(0, 3)]);
}
// ④ variant="accent" / kind="blue"
{
  const hits = [];
  for (const f of wxml) {
    read(f).split('\n').forEach((line, i) => {
      if (/variant="accent"|kind="blue"/.test(line)) hits.push(`${rel(f)}:${i + 1}`);
    });
  }
  results.push(['已废止的 accent / blue', hits.length, hits.slice(0, 3)]);
}
// ⑤ 原生 <switch>（组件自身除外）
{
  const hits = [];
  for (const f of wxml) {
    if (/components\/cy\/switch\//.test(f)) continue;
    read(f).split('\n').forEach((line, i) => {
      if (/<switch[\s/>]/.test(line)) hits.push(`${rel(f)}:${i + 1}`);
    });
  }
  results.push(['原生 <switch>', hits.length, hits.slice(0, 3)]);
}
// ⑥ 灰阶 token 饱和度 > 5%
{
  const hits = [];
  const src = read(path.join(ROOT, 'style/tokens.wxss'));
  for (const m of src.matchAll(/(--cy-(?:color-)?(?:bg|text|border)[\w-]*)\s*:\s*(#[0-9a-fA-F]{6})\b/g)) {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(m[2].slice(i, i + 2), 16));
    const mx = Math.max(r, g, b); const mn = Math.min(r, g, b);
    if (mx === mn) continue;
    const l = (mx + mn) / 2 / 255;
    const sat = ((mx - mn) / 255) / (l > 0.5 ? 2 - (mx + mn) / 255 : (mx + mn) / 255) * 100;
    // tokens.wxss 开头写明的例外：半透明叠加色（--cy-bg-subtle / --cy-white-*）
    // 保留字面值，它们不是"灰阶阶梯"的一员，不该按饱和度判。
    if (/(bg-subtle|white-\d+)/.test(m[1])) continue;
    if (sat > 5) hits.push(`${m[1]} ${m[2]} sat=${sat.toFixed(0)}%`);
  }
  results.push(['灰阶饱和度 >5%', hits.length, hits.slice(0, 3)]);
}

console.log('《小程序UI规范真源》§11「能自动判红」六条 —— 全仓达标核验\n');
let clean = 0;
for (const [name, n, sample] of results) {
  console.log(`  ${n === 0 ? '✅' : '⚠️ '} ${name.padEnd(22)} ${n === 0 ? '达标' : n + ' 处'}`);
  if (n) for (const s of sample) console.log(`        ${s}`);
  if (n === 0) clean += 1;
}
console.log(`\n${clean}/6 条完全达标。剩余项见上（部分为既有存量，走棘轮只减不增）。`);
console.log('⚠️ 规范另列了「只能人工验收」的四条（什么算状态、何时可用品牌紫、');
console.log('   输入框该用哪档灰、层级与信息密度）—— 那些交给 UI走查.html 逐页判。');
