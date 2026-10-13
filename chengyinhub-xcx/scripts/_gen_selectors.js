/*
 * 按每个页面 wxml 的真实结构，给 shot-matrix 的每一条生成一个**该状态特有**的
 * selector，覆盖掉原矩阵里凭空写的 .cy-tabs__item / .cy-page-title / .cy-empty。
 *
 * 原矩阵的 selector 全是猜的：merchant/ledger 的 tab 真实类名是 period-tab，
 * 空态是 <cy-empty> 组件标签而不是 .cy-empty 类 —— 221 条断言只有 7 条通过。
 *
 * 每个 state 锚到只有该状态才渲染的节点，否则断言退化成恒真：
 *   empty   -> <cy-empty> 组件 / class 含 empty|nodata 的块
 *   loading -> <cy-skeleton> 组件
 *   error   -> class 含 err 的块
 *   form    -> input / textarea
 *   sheet   -> 该页的 sheet/picker 组件标签
 *   其它    -> 页面根容器 class（弱锚点，标 weak:true）
 *
 * 输出 selectors.json：{ "路由|状态": {selector, min, weak?} }
 */
const fs = require('fs');
const path = require('path');
const { SHOTS } = require('./shot-matrix');
// fixture 决定了页面会渲染哪一支，锚点必须挑那一支里的类（见 stepBlock）
const FIXTURES = fs.existsSync(path.join(__dirname, 'fixtures.json')) ? require('./fixtures.json') : {};

const ROOT = path.resolve(__dirname, '..');
const extra = process.env.SHOT_EXTRA ? JSON.parse(fs.readFileSync(process.env.SHOT_EXTRA, 'utf8')) : [];
const OUTPUT = path.join(__dirname, 'selectors.json');
// 生成产物不能反向成为下一轮输入；实跑审校过的锚点全部显式保存在
// selectors-overrides.json。这样删/改 override 会在下一轮真实生效，不会被旧产物复活。

const wxmlOf = (route) => {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.wxml');
  return fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null;
};

// class="a b {{cond ? 'c' : ''}}" -> 只取静态部分，动态类名不能当锚点
const staticClasses = (wxml) => {
  const out = [];
  for (const m of wxml.matchAll(/class="([^"]*)"/g)) {
    const head = m[1].split('{{')[0].trim();
    for (const c of head.split(/\s+/)) if (c) out.push(c);
  }
  return out;
};

const hasTag = (wxml, tag) => new RegExp(`<${tag}[\\s/>]`).test(wxml);

// 多步表单：矩阵给了 step=N 时，锚点必须挑**那一步块里**的类。
// club/create 的 .cc-field 在 step===3 块内，C40/C41 给的是 step 1/2 ⇒ 断言恒红，
// 页面其实好好的。selector 生成器不看 fixture 就会挑错步 —— 两个生成器各自推断
// 是这类假红的通病。
function stepBlock(wxml, step) {
  const lines = wxml.split('\n');
  const re = new RegExp(`wx:(?:if|elif)="\\{\\{\\s*step\\s*===?\\s*${step}\\s*\\}\\}"`);
  const start = lines.findIndex((l) => re.test(l));
  if (start < 0) return null;
  const nextRe = /wx:(?:elif|else)/;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (nextRe.test(lines[i]) && /wx:(?:elif)="\{\{\s*step\s*===?/.test(lines[i])) { end = i; break; }
  }
  return lines.slice(start, end).join('\n');
}
const firstMatching = (classes, re) => classes.find((c) => re.test(c));

function pick(route, state, fxData) {
  let wxml = wxmlOf(route);
  if (!wxml) return null;
  let scoped = false;
  // 矩阵指定了第几步 → 只在那一步的块里挑锚点
  const step = fxData && (fxData.step != null ? fxData.step : fxData.currentStep);
  if (step != null && ['form', 'keyboard', 'long-value'].includes(state)) {
    const blk = stepBlock(wxml, step);
    if (blk) { wxml = blk; scoped = true; }
  }
  const cls = staticClasses(wxml);

  if (state === 'empty') {
    if (hasTag(wxml, 'cy-empty')) return { selector: 'cy-empty', min: 1 };
    const c = firstMatching(cls, /empty|nodata|no-data/i);
    if (c) return { selector: `.${c}`, min: 1 };
  }
  if (state === 'loading') {
    if (hasTag(wxml, 'cy-skeleton')) return { selector: 'cy-skeleton', min: 1 };
    const c = firstMatching(cls, /skeleton|loading/i);
    if (c) return { selector: `.${c}`, min: 1 };
  }
  if (state === 'error') {
    const c = firstMatching(cls, /err/i);
    if (c) return { selector: `.${c}`, min: 1 };
    if (hasTag(wxml, 'cy-error')) return { selector: 'cy-error', min: 1 };
  }
  if (state === 'form' || state === 'keyboard' || state === 'long-value') {
    // automator 的 $$ 选不到原生表单组件 —— 标签 'input' 和它自己的类
    // '.cc-input' 都返回 0，即便 step 已调对、DOM 里 4 个 input 都在。
    // 所以锚包裹表单的那个 view，它是普通节点，选得到。
    const wrap = formWrapper(wxml);
    if (wrap) return { selector: wrap.map((c) => `.${c}`).join(', '), min: 1 };
  }
  if (state === 'sheet') {
    const tag = ['cy-sheet', 'cy-date-sheet', 'cy-category-sheet', 'cy-story-sheet', 'cy-agreement-sheet', 'cy-privacy-sheet', 'picker-view', 'picker']
      .find((t) => hasTag(wxml, t));
    if (tag) return { selector: tag, min: 1 };
  }
  if (state === 'permission') {
    const c = firstMatching(cls, /perm|auth|noloc|denied/i);
    if (c) return { selector: `.${c}`, min: 1 };
  }
  // 兜底：页面根容器的静态类名。锚得住"页面渲染了"，锚不住"处于该状态"，
  // 所以显式标 weak —— 报告里不能把它当成状态被验证过。
  const roots = rootClasses(wxml, !!scoped);
  // 顶多取 4 个：baoming 那种顶层 view 很多的页面全连起来就退化成恒真了
  if (roots.length) return { selector: roots.slice(0, 4).map((c) => `.${c}`).join(', '), min: 1, weak: true };
  return { selector: 'view', min: 1, weak: true };
}

// 只取第一个 class 会踩顶层分支：merchantinfo 的 <view class="page"> 挂在
// wx:if="{{!fromMerchantJoin}}" 上，带 ?id= 进来走的是第 140 行那个
// <view class="topic">，于是 .page 恒 0 —— 断言红，页面其实好好的。
// 所以把所有顶层容器都收进来，命中任一即算页面渲染了。
// 包裹第一个 input/textarea 的最近祖先 <view class>。注意排掉
// placeholder-class="ph" 这类同样长得像 class 的属性。
function formWrapper(wxml) {
  // ⚠️ 只取**第一个** input 的祖先会踩多步表单：club/create 的 wxml 里 step4 的
  // 输入区排在前面，挑出来的 .cc-field 挂在 wx:if="{{step === 4}}" 下，而 form 态的
  // fixture 给的是 step=1 ⇒ 断言恒红，页面其实好好的。selector 生成器不知道 fixture
  // 会给哪一步，两边各自推断就会对不上。
  // ⇒ 收集所有表单容器类，命中任一即算表单渲染了。宁可弱一点，也不要恒红 ——
  //   恒红的断言把好页判成坏页，比没有断言更糟。
  const lines = wxml.split('\n');
  const out = [];
  lines.forEach((line, i) => {
    if (!/<input[\s>]|<textarea[\s>]/.test(line)) return;
    for (let j = i; j >= 0; j -= 1) {
      const m = /<view\s[^>]*(?<!placeholder-)class="([^"{]*)"/.exec(lines[j]);
      if (m) {
        const c = m[1].trim().split(/\s+/)[0];
        if (c && !out.includes(c)) out.push(c);
        break;
      }
    }
  });
  return out.length ? out.slice(0, 4) : null;
}

function rootClasses(wxml, loose) {
  // loose：截出来的 step 块里，view 都是带缩进的，行首正则一个都抓不到 ⇒ 退化成
  // selector:'view' 恒真弱锚点。块场景下放宽到「任意缩进的 view」。
  const re = loose ? /^\s*<view\s[^>]*class="([^"{]*)"/ : /^<view\s[^>]*class="([^"{]*)"/;
  const out = [];
  for (const line of wxml.split('\n')) {
    const m = re.exec(line);
    if (m) for (const c of m[1].trim().split(/\s+/)) if (c) out.push(c);
  }
  return [...new Set(out)];
}

// 正向弱锚点撑不住 normal 态：C07 标称 content 却整页显示"订单加载失败"，
// .ledger 照样命中。反向断言才抓得住 —— normal 态不该同时渲染空态/错误块。
function forbidFor(route, state) {
  if (!['normal', 'scrolled', 'filter', 'success'].includes(state)) return [];
  const wxml = wxmlOf(route);
  if (!wxml) return [];
  const out = [];
  if (hasTag(wxml, 'cy-empty')) out.push('cy-empty');
  if (hasTag(wxml, 'cy-error')) out.push('cy-error');
  if (hasTag(wxml, 'cy-skeleton')) out.push('cy-skeleton');
  return out;
}

const map = {};
const stats = { total: 0, strong: 0, weak: 0, missing: 0, withForbid: 0 };
for (const s of SHOTS.concat(extra)) {
  if (s.blocked) continue;
  stats.total += 1;
  const got = pick(s.route, s.state, (FIXTURES[s.id] || {}).data);
  if (!got) { stats.missing += 1; continue; }
  // forbid 的初衷是给**弱锚点**兜底：C07 标称 content 却整页显示「订单加载失败」，
  // 而正向锚点 .ledger 是根容器、照样命中 ⇒ 只有反向断言抓得住。
  // ⚠️ 但强锚点命中时 forbid 不该否决它：talent/list 内容态里「附近还没有俱乐部」
  // 那个分区空态是合法的，页面里存在一个 cy-empty 不等于整页退化成空态。
  // 分区空态在多分区页面上很常见，一律判红就是把好页判成坏页。
  const forbid = forbidFor(s.route, s.state);
  if (forbid.length && got.weak) got.forbid = forbid;
  // 新增截图若只能自动推断出恒真页面壳，宁可继续使用 shot-matrix 的显式 expect
  // 并让门禁判定，也不能把弱锚写进全局生成物、悄悄扩大假绿分母。
  if (got.weak) continue;
  map[`${s.id}`] = got;
}
// 人工核实后的修正覆盖自动推断。生成器只能按「哪个类带 wx:if 又显眼」挑锚点，
// 多分支页面会挑反（citynode-code 的 ok/error 两态都被挑成 .cc-missing，而那是
// 第三支 missing 才有的）。挑反的断言恒红，比没有断言更糟 —— 它把好页判成坏页。
const ovPath = path.join(__dirname, 'selectors-overrides.json');
if (fs.existsSync(ovPath)) {
  const ov = JSON.parse(fs.readFileSync(ovPath, 'utf8'));
  let n = 0;
  for (const [k, v] of Object.entries(ov)) { if (k.startsWith('_')) continue; map[k] = v; n += 1; }
  console.log(`人工修正覆盖 ${n} 条`);
}
for (const entry of Object.values(map)) {
  if (entry.weak && !(entry.forbid || []).length) stats.weak += 1;
  else stats.strong += 1;
  if ((entry.forbid || []).length) stats.withForbid += 1;
}
fs.writeFileSync(OUTPUT, JSON.stringify(map, null, 1));
console.log(JSON.stringify(stats));
console.log('strong 锚点占比', (stats.strong / stats.total * 100).toFixed(1) + '%');
