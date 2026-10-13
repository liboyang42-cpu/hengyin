/*
 * 全量 DS 一致性扫描：把「这页看着怪」变成「哪个文件的哪条声明和别人不一样」。
 *
 * 六个维度：按钮 / 标题 / 字体颜色 / 卡片 / 排版间距 / 动效。
 * 每个维度输出：取值分布 + 少数派的具体 file:line —— 少数派才是要改的那些。
 *
 *   node scripts/_ds_consistency_scan.js [--json]
 *
 * ⚠️ 只看**声明**，不看运行时。声明了 88rpx 也可能被父级覆盖。报出来的少数派
 * 是**候选不是判决**，每条都要读代码确认。
 * ⚠️ 选择器按类名关键字匹配（btn/card/item…），会误收子元素 ——  * 的 40rpx 是图标尺寸不是按钮高度。看到不合常理的值先确认选择器指的是什么。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const wxss = [];
const wxml = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.wxss')) wxss.push(p);
    else if (e.name.endsWith('.wxml')) wxml.push(p);
  }
}(ROOT));

const rel = (p) => path.relative(ROOT, p);
const isPage = (p) => /^(pages|subpackage)/.test(rel(p));

// 解析 token 到底值，一层层展开 var(--x)。名字不同不代表值不同 ——
// --cy-btn-solid-bg 就是 --cy-color-action-primary-bg 的别名，按名字比会
// 报出一堆假的「不一致」。
const tokenDefs = new Map();
for (const f of wxss) {
  const s = fs.readFileSync(f, 'utf8');
  for (const m of s.matchAll(/(--cy-[\w-]+)\s*:\s*([^;]+);/g)) {
    if (!tokenDefs.has(m[1])) tokenDefs.set(m[1], m[2].trim());
  }
}
// ⚠️ 不做 token 解析。同一个 token 名在不同主题块（深色/浅色/商家）下取不同
// 值是**设计**，不是 bug —— 早期版本把 var() 展开成底值来比，于是把
// --cy-color-action-primary-bg 在两个主题下的两个值报成「46 处灰 vs 24 处黑」，
// 是彻头彻尾的假发现。这里只比**写法**：走的是不是同一个语义 token。
// 真正的问题只有两类：① 压根没走 token（硬编码）② 走了语义不对的 token。
function resolve(v) { return v.trim(); }

// 收集「选择器 { 属性 }」，只在页面文件里找（组件内部的自成体系，另算）
function collect(propRe, selRe) {
  const hits = [];
  for (const f of wxss) {
    if (!isPage(f)) continue;
    const lines = fs.readFileSync(f, 'utf8').split('\n');
    let sel = null;
    let open = 0;
    lines.forEach((line, i) => {
      const sm = /^\s*([.#][^{]+)\{/.exec(line);
      if (sm) { sel = sm[1].trim(); open = 1; }
      if (open && selRe.test(sel || '')) {
        const pm = propRe.exec(line);
        if (pm) hits.push({ file: rel(f), line: i + 1, sel, raw: pm[1].trim(), resolved: resolve(pm[1]) });
      }
      if (line.includes('}')) { open = 0; sel = null; }
    });
  }
  return hits;
}

function report(title, hits, note) {
  const byVal = new Map();
  for (const h of hits) {
    const k = h.resolved;
    if (!byVal.has(k)) byVal.set(k, []);
    byVal.get(k).push(h);
  }
  const sorted = [...byVal.entries()].sort((a, b) => b[1].length - a[1].length);
  console.log(`\n## ${title}　共 ${hits.length} 处 / ${sorted.length} 种取值`);
  if (note) console.log(`   ${note}`);
  sorted.slice(0, 8).forEach(([v, arr], i) => {
    const tag = i === 0 ? '多数' : (arr.length <= 2 ? '少数派' : '');
    console.log(`   ${String(arr.length).padStart(3)}  ${v.slice(0, 46).padEnd(48)} ${tag}`);
  });
  // 少数派（≤2 处）点名到行
  const rare = sorted.filter(([, a]) => a.length <= 2).flatMap(([v, a]) => a.map((h) => ({ ...h, v })));
  if (rare.length) {
    console.log(`   —— 少数派 ${rare.length} 处（改这些）：`);
    rare.slice(0, 12).forEach((h) => console.log(`      ${h.file}:${h.line}  ${h.sel.slice(0, 26)}  ${h.v.slice(0, 26)}`));
    if (rare.length > 12) console.log(`      …还有 ${rare.length - 12} 处`);
  }
}

const BTN = /btn|cta|button|submit/i;
const CARD = /card|panel|tile|item|cell/i;

console.log('# 城瘾小程序 DS 一致性扫描');
console.log(`扫描范围：${wxss.filter(isPage).length} 个页面 wxss（组件内部另算）`);

report('按钮 · 背景色', collect(/background(?:-color)?\s*:\s*([^;]+);/, BTN),
  '比的是写法（走哪个 token / 是否硬编码），不是底值 —— 见文件头注释');
report('按钮 · 高度', collect(/height\s*:\s*([^;]+);/, BTN),
  '⚠️ 部分页面把 --cy-btn-h 内联覆盖成 104rpx，且被 contract 测试写死断言，改前先看 tests/');
report('按钮 · 圆角', collect(/border-radius\s*:\s*([^;]+);/, BTN));
report('按钮 · 字号', collect(/font-size\s*:\s*([^;]+);/, BTN));
report('卡片 · 背景', collect(/background(?:-color)?\s*:\s*([^;]+);/, CARD));
report('卡片 · 圆角', collect(/border-radius\s*:\s*([^;]+);/, CARD));
report('卡片 · 内边距', collect(/padding\s*:\s*([^;]+);/, CARD));
report('动效 · transition', collect(/transition\s*:\s*([^;]+);/, /./));
report('动效 · animation', collect(/animation\s*:\s*([^;]+);/, /./));

// 硬编码颜色：整个页面层，不限选择器
const hard = [];
for (const f of wxss) {
  if (!isPage(f)) continue;
  fs.readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
    if (/--cy-|ds-ok/.test(line)) return; // 定义行和已豁免的跳过
    const m = /(?:color|background(?:-color)?)\s*:\s*(#[0-9a-fA-F]{3,8}|rgba?\([^)]+\))/.exec(line);
    if (m) hard.push({ file: rel(f), line: i + 1, v: m[1] });
  });
}
console.log(`\n## 硬编码颜色（未走 token、未标 ds-ok）　${hard.length} 处`);
const byFile = new Map();
for (const h of hard) byFile.set(h.file, (byFile.get(h.file) || 0) + 1);
[...byFile.entries()].sort((a, b) => b[1] - a[1]).slice(0, 15)
  .forEach(([f, n]) => console.log(`   ${String(n).padStart(3)}  ${f}`));
