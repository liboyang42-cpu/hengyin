// 2026-11-21 lead 在 DevTools 里复查 #887 编辑页试玩(pages/publish/temp,previewGameFromSheet)找到的四处:
// 1) 「试玩 · 不计成绩」钉在右上,压在微信胶囊底下被裁;
// 2) 翻卡卡面左上「这一关」白字白底看不见,卡面只有一块通用图案,读不出这张卡说什么;
// 3) 找东西试玩只有标题、三枚清单和 0/3,一张图都没有,也点不了;
// 4) 每个试玩都在屏幕下方挂一颗灰色禁用的「继续故事」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const rule = (css, sel) => {
  const m = css.match(new RegExp(sel.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&') + ' \\{([^}]*)\\}'));
  assert.ok(m, sel + ' 规则不存在');
  return m[1];
};
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
const preview = require('../../pages/publish/utils/publish/advanced-game-preview.js');
const config = require('../../pages/publish/utils/publish/advanced-game-config.js');
const verdict = require('../../pages/publish/temp/preview-verdict.js');

/* ---------- 加载编辑页 Page,只为调它自己的方法(不跑 onLoad) ---------- */
function loadPage(wxStub) {
  const file = require.resolve('../../pages/publish/temp/index.js');
  let def = null;
  const saved = { Page: global.Page, getApp: global.getApp, wx: global.wx, Component: global.Component, Behavior: global.Behavior };
  global.Page = (d) => { def = d; };
  global.Component = () => {};
  global.Behavior = (b) => b;
  global.getApp = () => ({ globalData: { statusBarHeight: 20 }, tips() {} });
  global.wx = Object.assign({ getStorageSync: () => '', setStorageSync() {}, getSystemInfoSync: () => ({ windowWidth: 375 }) }, wxStub);
  try {
    delete require.cache[file];
    require(file);
  } finally {
    Object.assign(global, saved);
  }
  return def;
}
function instance(def, data, wxStub) {
  const inst = Object.create(def);
  inst.data = JSON.parse(JSON.stringify(def.data));
  Object.assign(inst.data, data || {});
  inst.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => { this.data[k] = patch[k]; });
    if (cb) cb();
  };
  inst._wx = wxStub;
  return inst;
}

test('1) 「试玩 · 不计成绩」不再钉在右上:内嵌时是玩法内容最上面一枚左对齐小标,整屏时浮在胶囊下沿之下', () => {
  const wxml = read('pages/publish/temp/index.wxml');
  const css = read('pages/publish/temp/index.wxss');
  const sim = rule(css, '.cg-pv-sim');
  assert.doesNotMatch(sim, /right:/, '小标不能再贴右边(微信胶囊在右上)');
  assert.doesNotMatch(sim, /position:\s*fixed/, '内嵌时小标在内容流里,不是悬浮');
  assert.match(sim, /align-self:\s*flex-start/);
  // 内嵌:在滚动区里,排在 cy-playkit 前面
  const scroll = wxml.match(/<scroll-view[^>]*class="cg-pv-inline-scroll"[\s\S]*?<\/scroll-view>/)[0];
  const simAt = scroll.indexOf('class="cg-pv-sim"');
  assert.ok(simAt > 0 && simAt < scroll.indexOf('<cy-playkit'), '小标要排在玩法内容最上面');
  // 整屏:左对齐浮标,top 走 pvSimTop(退出键与胶囊下沿取大),不再是 statusBarHeight + 12(与胶囊同一行)
  const float = rule(css, '.cg-pv-sim--float');
  assert.match(float, /left:\s*var\(--cy-page-x\)/);
  assert.doesNotMatch(float, /right:/);
  assert.match(wxml, /class="cg-pv-sim cg-pv-sim--float"[^>]*style="top:\{\{pvSimTop\}\}px"/);
  assert.doesNotMatch(wxml, /class="cg-pv-sim[^"]*"[^>]*style="top:\{\{statusBarHeight \+ 12\}\}px"/);
});

test('1) pvSimTop 取退出键下沿与微信胶囊下沿的较大者,再空 8px', () => {
  const capsule = { getMenuButtonBoundingClientRect: () => ({ top: 24, bottom: 120, height: 32 }) };
  const def = loadPage(capsule);
  const saved = global.wx;
  try {
    global.wx = Object.assign({ getSystemInfoSync: () => ({ windowWidth: 375 }) }, capsule);
    const page = instance(def, { statusBarHeight: 20 });
    assert.equal(page._pvSimTop(), 128, '胶囊更低时跟胶囊');
    global.wx = { getSystemInfoSync: () => ({ windowWidth: 375 }), getMenuButtonBoundingClientRect: () => ({ bottom: 56 }) };
    assert.equal(page._pvSimTop(), 20 + 12 + 44 + 8, '退出键更低时跟退出键');
    global.wx = { getSystemInfoSync: () => { throw new Error('x'); }, getMenuButtonBoundingClientRect: () => { throw new Error('x'); } };
    assert.equal(page._pvSimTop(), 20 + 12 + 44 + 8, '量不到也不崩');
  } finally { global.wx = saved; }
});

test('2) 翻卡卡面眉标用卡面 token(与详情 .dk__pill 同组),不再是近白字配近白底', () => {
  const css = read('pages/playkit/components/playkit-random/index.wxss');
  const tag = rule(css, '.dk__tag');
  assert.doesNotMatch(tag, /--lk-bg|--pk-embed-accent/, '这两个在试玩里都是近白');
  assert.match(tag, /color:\s*var\(--lk-dim,/);
  assert.match(tag, /background:\s*var\(--lk-ctl,/);
  const pill = rule(css, '.dk__pill');
  assert.equal(tag.match(/color:[^;]+;/)[0], pill.match(/color:[^;]+;/)[0]);
  assert.equal(tag.match(/background:[^;]+;/)[0], pill.match(/background:[^;]+;/)[0]);
});

test('2) 翻卡有正文就把这句写大在卡面上,图案退成右下角小角标', () => {
  const wxml = read('pages/playkit/components/playkit-random/index.wxml');
  const card = wxml.match(/<view class="dk__card[\s\S]*?<\/view>\s*<\/view>/)[0];
  assert.match(card, /<view class="dk__line" wx:if="\{\{item\.body\}\}">\{\{item\.body\}\}<\/view>/);
  assert.match(card, /class="dk__art \{\{item\.body \? 'dk__art--corner' : ''\}\}"/);
  const css = read('pages/playkit/components/playkit-random/index.wxss');
  const line = rule(css, '.dk__line');
  assert.match(line, /font-size:\s*var\(--cy-type-sheet-title\)/, '正文要写大,不是正文字号');
  const corner = rule(css, '.dk__art--corner');
  const w = Number((corner.match(/width:\s*(\d+)rpx/) || [])[1]);
  assert.ok(w > 0 && w <= 160, '角标要小');
  // 范例数据真的带正文到卡面上
  const draft = catalog.applyToConfig(config.defaultConfig(), 'random', { demo: true });
  const kit = preview.buildPreviewKit(draft, { title: '', limitSeconds: 0 });
  assert.ok(kit.cards.length && kit.cards.every((c) => c.title && c.body));
  // 组件把 body 带进牌堆每张卡
  assert.match(read('pages/playkit/components/playkit-random/index.js'), /id: c\.id \|\| \('c' \+ i\), title: c\.title \|\| '', body: c\.body \|\| ''/);
});

test('3) 找东西图区内嵌时有最小高(不再塌成 0 高只剩标题和清单),范例图带固有尺寸', () => {
  const css = read('pages/playkit/components/playkit-hidden/index.wxss');
  const scene = rule(css, '.hd__scene');
  const min = Number((scene.match(/min-height:\s*(\d+)rpx/) || [])[1]);
  assert.ok(min >= 600, '.hd__scene 内嵌时会塌成 0 高');
  const svg = read('pages/publish/images/sample-window.svg');
  assert.match(svg, /<svg[^>]*width="600" height="800"/, 'bindload 要拿到宽高才能按原比例收画框');
  assert.ok(Buffer.byteLength(svg) < 4096, '范例图守住包体预算');
});

test('3) 找东西试玩可以点:本机按范例热区判中,命中写回 targets,坐标仍不进 kit', () => {
  const draft = catalog.applyToConfig(config.defaultConfig(), 'hidden', { demo: true });
  const kit = preview.buildPreviewKit(draft, { title: '', limitSeconds: 0 });
  assert.equal(kit.targets.some((t) => 'x' in t), false);
  assert.equal(verdict.hiddenHit(draft, kit, { x: 20, y: 20 }), 'spot_1');
  assert.equal(verdict.hiddenHit(draft, kit, { x: 45, y: 65 }), 'spot_2');
  assert.equal(verdict.hiddenHit(draft, kit, { x: 82, y: 62 }), 'spot_3');
  assert.equal(verdict.hiddenHit(draft, kit, { x: 60, y: 10 }), '', '空白处不算');
  assert.equal(verdict.hiddenHit(draft, kit, null), '');
  const found = Object.assign({}, kit, { targets: kit.targets.map((t) => (t.id === 'spot_1' ? Object.assign({}, t, { found: true }) : t)) });
  assert.equal(verdict.hiddenHit(draft, found, { x: 20, y: 20 }), '', '找到过的不再算一次');

  const def = loadPage({});
  const page = instance(def, { pvKit: kit, previewVisible: true });
  page._pvAdvanced = draft;
  page.onPvKitAction({ detail: { action: 'submit', detail: { x: 45, y: 65 } } });
  const t = page.data.pvKit.targets.find((x) => x.id === 'spot_2');
  assert.deepEqual([t.found, t.x, t.y], [true, 45, 65]);
  assert.equal(page.data.pvDone, false, '点中一个还没玩完');
});

test('3) 找东西组件按带 found 的 targets 画点、计数,全找到由台面判赢', () => {
  const file = require.resolve('../../pages/playkit/components/playkit-hidden/index.js');
  let def = null;
  const saved = { Component: global.Component, wx: global.wx, Behavior: global.Behavior };
  global.Component = (d) => { def = d; };
  global.Behavior = (b) => b;
  try { delete require.cache[file]; require(file); } finally { Object.assign(global, saved); }
  let won = '';
  let begun = 0;
  const stage = { begin() { begun++; }, win(t) { won = t; } };
  const comp = { data: Object.assign({}, def.data, { total: 2, reducedMotion: true, targets: [] }),
    setData(p) { Object.assign(this.data, p); }, selectComponent: () => stage, _fitFrame() {} };
  const obs = def.observers['show, targets'];
  comp.data.targets = [{ id: 'a', label: '钥匙' }, { id: 'b', label: '茶杯' }];
  obs.call(comp, true);
  assert.equal(begun, 1);
  comp.data.targets = [{ id: 'a', label: '钥匙', found: true, x: 20, y: 20 }, { id: 'b', label: '茶杯' }];
  obs.call(comp, true);
  assert.equal(begun, 1, '命中不重开一局');
  assert.deepEqual(comp.data.hits, [{ id: 'a', x: 20, y: 20 }]);
  assert.equal(comp.data.foundCount, 1);
  assert.equal(won, '');
  comp.data.targets = [{ id: 'a', label: '钥匙', found: true, x: 20, y: 20 }, { id: 'b', label: '茶杯', found: true, x: 45, y: 65 }];
  obs.call(comp, true);
  assert.equal(comp.data.foundCount, 2);
  assert.equal(won, '全找到了');
});

test('4) 试玩里「继续故事」玩完才出,是共享 cy-btn primary,紧跟玩法内容', () => {
  const wxml = read('pages/publish/temp/index.wxml');
  const css = read('pages/publish/temp/index.wxss');
  assert.doesNotMatch(wxml, /cg-pv-inline-continue/, '灰色示意胶囊撤掉');
  assert.doesNotMatch(css, /\.cg-pv-inline-continue/);
  const scroll = wxml.match(/<scroll-view[^>]*class="cg-pv-inline-scroll"[\s\S]*?<\/scroll-view>/)[0];
  const body = scroll.match(/<view class="cg-pv-inline-body">[\s\S]*<\/view>\s*<\/view>\s*<\/scroll-view>/);
  assert.ok(body, '玩法与「继续故事」同在一块里');
  assert.match(body[0], /<view class="cg-pv-inline-end" wx:if="\{\{ pvDone \}\}"><cy-btn variant="primary" bindtap="onPvContinue">继续故事<\/cy-btn><\/view>/);
  assert.ok(body[0].indexOf('<cy-playkit') < body[0].indexOf('cg-pv-inline-end'), '按钮排在玩法内容后面');
  const end = rule(css, '.cg-pv-inline-end');
  assert.doesNotMatch(end, /300rpx|position/, '不再垫到屏幕下沿');
  assert.match(rule(css, '.cg-pv-inline-body'), /margin-top:\s*auto;\s*margin-bottom:\s*auto/);
});

test('4) pvDone:开局为假;玩完(判定 / 抽到 / 提交)为真;再来一局与关预览复位;继续 = 判定屏「继续」同去向', () => {
  const def = loadPage({});
  assert.equal(def.data.pvDone, false);
  const kit = { type: 'random', cards: [{ id: 'c0', title: '线索卡', body: '去吧台问一句' }] };
  const page = instance(def, { pvKit: kit, previewVisible: true, pvSteps: ['challenge'], pvStep: 0, pvDone: false,
    pvVerdict: { show: false, kind: 'win', text: '', images: [] }, formData: {}, storyBeats: [] });
  page.syncNativeTheme = () => {};
  page.showPvVerdict = () => true;
  page.onPvKitAction({ detail: { action: 'drawn' } });
  assert.equal(page.data.pvDone, true);
  page._applyPvStep(0);
  assert.equal(page.data.pvDone, false, '回到玩法这一屏重新开局');
  page.onPvKitAction({ detail: { action: 'verdict', detail: { kind: 'fail' } } });
  assert.equal(page.data.pvDone, true);
  let closed = 0;
  page.closePreview = () => { closed++; };
  page.onPvContinue();
  assert.equal(closed, 1, '只演玩法这一屏时继续 = 回编辑页');
  const js = read('pages/publish/temp/index.js');
  assert.match(js, /closePreview: function \(\) \{[\s\S]*?pvDone: false/);
  assert.match(read('pages/publish/temp/r5-editor.js'), /setData\(\{ pvKit: null, pvDone: false \}/, '失败「再来一次」重开时按钮收起');
});
