/**
 * 2026-10-22 owner 店铺参谋改版:问候区 + 主卡(7/30/90 天逐日格)+ 到店时段 + 客源主题 + 主题详情弹层。
 * 跑真代码:把后端回包喂进页面 builder,看卡面吐出的数;再验清场时新字段一并清掉。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_JS_PATH = 'pages/merchant/marketing/ai-insight/index.js';
const PAGE_WXML = fs.readFileSync(path.join(ROOT, 'pages/merchant/marketing/ai-insight/index.wxml'), 'utf8');

function loadPage(extraGlobals) {
  let definition = null;
  const file = path.join(ROOT, PAGE_JS_PATH);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), Object.assign({
    Page: (value) => { definition = value; },
    getApp: () => ({ globalData: {}, getUserID: () => 'merchant-a', sendRequest() {}, getRequestErrorMessage: () => '' }),
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 44 }), setNavigationBarColor() {} },
    console,
    require: (id) => require(path.resolve(path.dirname(file), id)),
  }, extraGlobals));
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = function (patch) {
    Object.keys(patch).forEach((key) => {
      const parts = key.split('.');
      let target = this.data;
      while (parts.length > 1) { const k = parts.shift(); target[k] = target[k] || {}; target = target[k]; }
      target[parts[0]] = patch[key];
    });
  };
  return page;
}

function windowOf(days, totals, values) {
  const daily = [];
  for (let i = 0; i < days; i++) {
    daily.push({ date: 'd' + i, visitors: values[i] || 0, checkins: 0, redeems: 0 });
  }
  return Object.assign({ days, daily }, totals);
}

const TRENDS = {
  windows: [
    windowOf(7, { visitors: 5, checkins: 9, redeems: 3, prevVisitors: 4, prevCheckins: 10, prevRedeems: 0 }, [0, 1, 3, 0, 2, 0, 6]),
    windowOf(30, { visitors: 20, checkins: 40, redeems: 12, prevVisitors: 25, prevCheckins: 0, prevRedeems: 6 }, []),
    windowOf(90, { visitors: 60, checkins: 99, redeems: 30, prevVisitors: 0, prevCheckins: 0, prevRedeems: 0 }, []),
  ],
};
const FACTS = { window: '近30天', attribution: { visitors: 21, checkins: 41, redeems: 12, prevVisitors: 25 } };

test('trends 三个窗口齐全才启用;缺窗口/天数对不上当没有', () => {
  const page = loadPage();
  assert.ok(page._indexTrends(TRENDS));
  assert.equal(page._indexTrends({ windows: TRENDS.windows.slice(0, 2) }), null);
  const broken = JSON.parse(JSON.stringify(TRENDS));
  broken.windows[0].daily.pop();
  assert.equal(page._indexTrends(broken), null);
  assert.equal(page._indexTrends(undefined), null);
});

test('主卡:大数字/环比/上期/逐日格档位按所选窗口与指标', () => {
  const page = loadPage();
  page._trends = page._indexTrends(TRENDS);
  const h7 = page._buildHero(FACTS, 7, 'visitors', false);
  assert.equal(h7.hasTrend, true);
  assert.equal(h7.title, '近7天，城瘾为你带来');
  assert.equal(h7.num, '5');
  assert.equal(h7.deltaText, '+25%');
  assert.equal(h7.prevText, '上期 4位');
  assert.equal(h7.cols, 7);
  assert.deepEqual(h7.cells.map((c) => c.lv), [0, 1, 2, 0, 1, 0, 3], '按窗口内最大值分 3 档,0 单独一档');
  assert.equal(h7.trio.filter((t) => t.on)[0].key, 'visitors');

  const h30 = page._buildHero(FACTS, 30, 'visitors', true);
  assert.equal(h30.num, '0', '首帧从 0 起滚');
  assert.equal(h30.target, 20);
  assert.equal(h30.deltaText, '-20%');
  assert.equal(h30.deltaDown, true);
  assert.equal(h30.cols, 15);
  assert.equal(h30.cells.length, 30);

  // 上期为 0:不算百分比
  assert.equal(page._buildHero(FACTS, 30, 'checkins', false).deltaText, '');
  assert.equal(page._buildHero(FACTS, 90, 'visitors', false).deltaText, '');
});

// 2026-10-27 业主 M-21:方块图每次打开都要画 —— 没有 trends 时铺 30 格全浅色,窗口切换仍不出
test('旧缓存没有 trends:退回 30 天归因三数,不出窗口切换,格子铺满全浅色', () => {
  const page = loadPage();
  page._trends = null;
  const h = page._buildHero(FACTS, 7, 'visitors', false);
  assert.equal(h.hasTrend, false);
  assert.equal(h.num, '21');
  assert.equal(h.title, '近30天，城瘾为你带来');
  assert.equal(h.cells.length, 30);
  assert.equal(h.cols, 15);
  assert.ok(h.cells.every((cell) => cell.lv === 0), '无数据时全部最浅一档');
  assert.match(h.axisFrom, /^\d{2}-\d{2}$/, '轴起点与后端 daily.date 同格式');
  // 2026-10-28 m689 业主「还原之前 HTML 的设计」:7/30/90 切换每次都在(旧后端没有 trends 时点了不变)
  assert.match(PAGE_WXML, /<view class="seg" aria-role="tablist" wx:if="\{\{hero\}\}">/);
  assert.match(PAGE_WXML, /<block wx:if="\{\{hero && hero\.cells\.length\}\}">\s*<view class="heat[ "]/);
});

test('切窗口/切指标重建主卡;无 trends 时窗口点击不动', () => {
  const page = loadPage();
  page._trends = page._indexTrends(TRENDS);
  page.data.facts = FACTS;
  page._countTo = function (target) { this.setData({ 'hero.num': String(target) }); };
  page.onWinTap({ currentTarget: { dataset: { win: '7' } } });
  assert.equal(page.data.win, 7);
  assert.equal(page.data.winIdx, 0);
  assert.equal(page.data.hero.num, '5');
  page.onMetricTap({ currentTarget: { dataset: { metric: 'checkins' } } });
  assert.equal(page._metric, 'checkins');
  assert.equal(page.data.hero.num, '9');
  page.onMetricTap({ currentTarget: { dataset: { metric: 'bogus' } } });
  assert.equal(page._metric, 'checkins');
  page._trends = null;
  page.onWinTap({ currentTarget: { dataset: { win: '90' } } });
  assert.equal(page.data.win, 7);
});

test('到店时段:最高柱标记、占比、零值柱留一截;全零不画', () => {
  const page = loadPage();
  const slots = page._buildSlots({ checkin: { slotBuckets: [
    { slot: '早', count: 0 }, { slot: '午', count: 10 }, { slot: '晚', count: 30 }, { slot: '夜', count: 0 },
  ] } });
  assert.deepEqual(slots.bars.map((b) => b.max), [false, false, true, false]);
  assert.deepEqual(slots.bars.map((b) => b.pct), ['0%', '25%', '75%', '0%']);
  assert.equal(slots.bars[0].h, 6);
  assert.equal(slots.peakText, '晚上到店最多');
  assert.equal(slots.total, 40);
  assert.equal(page._buildSlots({ checkin: { slotBuckets: [{ slot: '早', count: 0 }] } }), null);
});

test('客源主题:最多 5 行,条长相对第一名,占比相对总数', () => {
  const page = loadPage();
  const rows = page._buildSources({ sources: [
    { name: '城南夜行记', count: 6 }, { name: '老街寻味', count: 3 }, { name: '空', count: 0 }, { name: '咖啡', count: 1 },
  ] });
  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map((r) => r.w), [100, 50, 17]);
  assert.deepEqual(rows.map((r) => r.pct), ['60%', '30%', '10%']);
  assert.equal(rows[0].initial, '城');
});

test('身份失效清场:店名/头像/主卡/时段/客源/弹层一并清掉', () => {
  const page = loadPage();
  Object.assign(page.data, {
    storeName: '城南小馆', profile: { logo: 'x.png' }, hero: { num: '5' }, slots: { bars: [] },
    sources: [{ name: 'a' }], grown: true, sheetTopic: { topicId: 1 }, sheetPhase: 'open',
  });
  page._trends = {};
  page._clearSensitiveState('请重新登录');
  assert.equal(page.data.storeName, '');
  assert.equal(page.data.profile, null);
  assert.equal(page.data.hero, null);
  assert.equal(page.data.slots, null);
  assert.equal(page.data.sources.length, 0);
  assert.equal(page.data.sheetTopic, null);
  assert.equal(page.data.sheetPhase, '');
  assert.equal(page._trends, null);
});

test('页头是问候区 + 店铺头像,不再有店铺档案入口;最近到店玩家无竖线', () => {
  // 2026-10-27 业主 M-21:真实店名与 Logo,不写「店主」/「店」;头像不描边
  assert.match(PAGE_WXML, /class="greet-line"><text>Hi\{\{storeName \? '，' : ''\}\}<\/text><text>\{\{storeName\}\}<\/text>/);
  assert.match(PAGE_WXML, /<cy-avatar class="greet-avatar"[^>]*src="\{\{profile\.logo \|\| ''\}\}"[^>]*name="\{\{storeName\}\}"/);
  assert.doesNotMatch(PAGE_WXML.replace(/<!--[\s\S]*?-->/g, ''), /'店主'|'店'|greet-avatar"[^>]*bordered/);
  assert.doesNotMatch(PAGE_WXML.replace(/<!--[\s\S]*?-->/g, ''), /店铺档案|goProfileSetup/);
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/merchant/marketing/ai-insight/index.wxss'), 'utf8');
  assert.doesNotMatch(wxss, /\.visitor[^{]*::?before/, '最近到店玩家卡不加竖线');
  assert.doesNotMatch(fs.readFileSync(path.join(ROOT, PAGE_JS_PATH), 'utf8'), /vibrate/, 'owner:不震动');
});

test('核销进度 10 格:每次打开先整排点亮再退回真实格数,零核销也扫;减少动态效果不扫', () => {
  assert.match(PAGE_WXML, /class="seg-pip \{\{seg\.on \|\| pipSweep \? 'seg-pip--on' : ''\}\}"/);
  const timers = [];
  const page = loadPage({
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimeout() {},
  });
  page.data.valueCard = { segs: Array.from({ length: 10 }, () => ({ on: false })) };
  {
    page._sweepPips();
    assert.equal(page.data.pipSweep, true, '零核销也整排点亮');
    assert.equal(timers.length, 1);
    assert.ok(timers[0].ms >= 400 + 9 * 45 + 300, '亮满之后才退回');
    timers[0].fn();
    assert.equal(page.data.pipSweep, false, '退回真实格数');

    page._sweepPips();
    // onHide 里 merchant-theme 还原导航色要用全局 getApp/wx
    const saved = { getApp: global.getApp, wx: global.wx };
    global.getApp = () => ({ globalData: {} });
    global.wx = { setNavigationBarColor() {}, setBackgroundColor() {}, setBackgroundTextStyle() {} };
    try { page.onHide(); } finally {
      ['getApp', 'wx'].forEach((k) => { if (saved[k] === undefined) delete global[k]; else global[k] = saved[k]; });
    }
    assert.equal(page.data.pipSweep, false, '离开页面时不留在点亮态');
    page._hidden = false;

    page.data.reducedMotion = true;
    page._sweepPips();
    assert.equal(page.data.pipSweep, false);
  }
  const js = fs.readFileSync(path.join(ROOT, PAGE_JS_PATH), 'utf8');
  assert.match(js, /if \(firstPaint\) that\._playIn\(\);\s*\n\s*else if \(replaying\) that\._countTo\(hero\.target\);\s*\/\/[^\n]*\n\s*that\._sweepPips\(\);/, '每次加载成功都扫，不只首帧');
});

test('进度条下一行只说核销率(不重复玩家环比,不随主卡窗口说错);环比 0 写持平', () => {
  const page = loadPage();
  assert.equal(page._buildValueCard({ window: '近30天', checkin: { redeemRate: 0.38 } }).caption, '近30天核销率 38%');
  assert.equal(page._buildValueCard({ window: '近30天', checkin: { redeemRate: null } }).caption, ''); // 业主 10-23:缺失就不显示
  assert.doesNotMatch(PAGE_WXML, /核销进度 · /, '旧的「核销进度 · 比上期 +N 位玩家」不再出现');
  page._trends = page._indexTrends({ windows: TRENDS.windows.map((w) => Object.assign({}, w, { prevVisitors: w.visitors })) });
  assert.equal(page._buildHero(FACTS, 7, 'visitors', false).deltaText, '持平');
  assert.equal(page._buildHero(FACTS, 7, 'visitors', false).deltaDown, false);
});

test('切 7/30/90 天:进度条换成该窗口的打卡/核销并重扫;切指标不重扫', () => {
  const timers = [];
  const page = loadPage({ setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; }, clearTimeout() {} });
  page._trends = page._indexTrends(TRENDS);
  page.data.facts = FACTS;
  page._countTo = function () {};
  page.data.valueCard = page._buildValueCard(FACTS, 30);
  assert.equal(page.data.valueCard.caption, '近30天打卡 40 次 · 核销 12 次');
  assert.equal(page.data.valueCard.segs.filter((s) => s.on).length, 3, '12/40 → 3 格');

  page.onWinTap({ currentTarget: { dataset: { win: '7' } } });
  assert.equal(page.data.valueCard.caption, '近7天打卡 9 次 · 核销 3 次');
  assert.equal(page.data.valueCard.segs.filter((s) => s.on).length, 3, '3/9 → 3 格');
  assert.equal(page.data.pipSweep, true, '切窗口重扫');
  timers.pop().fn();
  assert.equal(page.data.pipSweep, false);

  page.onWinTap({ currentTarget: { dataset: { win: '90' } } });
  assert.equal(page.data.valueCard.caption, '近90天打卡 99 次 · 核销 30 次');
  assert.equal(page.data.pipSweep, true);
  timers.pop().fn();

  page.onMetricTap({ currentTarget: { dataset: { metric: 'checkins' } } });
  assert.equal(page.data.pipSweep, false, '切指标不动进度条');
  assert.equal(page.data.valueCard.caption, '近90天打卡 99 次 · 核销 30 次');

  const empty = JSON.parse(JSON.stringify(TRENDS));
  empty.windows.forEach((w) => { w.checkins = 0; w.redeems = 0; });
  page._trends = page._indexTrends(empty);
  const card = page._buildValueCard(FACTS, 7);
  assert.equal(card.caption, '近7天暂无打卡核销');
  assert.equal(card.segs.filter((s) => s.on).length, 0);
});

test('7 天格子与 30/90 天同大;推荐主题卡是整卡封面 + 底部渐暗,底栏短日期与主办', () => {
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/merchant/marketing/ai-insight/index.wxss'), 'utf8');
  assert.match(PAGE_WXML, /class="heat \{\{hero\.cols === 7 \? 'heat--week' : ''\}\}"/);
  assert.match(wxss, /\.heat--week \.heat-cell \{ width: calc\(\(100% - 112rpx\) \/ 15\); \}/, '15 列格宽,gap 8rpx × 14');
  assert.match(PAGE_WXML, /<view class="tcard-cover">[\s\S]*?<view class="tcard-shade"><\/view>[\s\S]*?<view class="tcard-over">[\s\S]*?class="tcard-cta">看看</);
  assert.match(wxss, /--mi-shade:\s*linear-gradient\(180deg/);
  const page = loadPage();
  const [t] = page._decorateTopics([{ topicId: 1, name: 'a', startDate: '2026-10-20', endDate: '2026-11-30', hostName: '城市乐居人' }]);
  assert.equal(t.dateText, '10/20–11/30');
  assert.equal(t.metaText, '10月20日 – 11月30日 · 主办 城市乐居人', '弹层与读屏仍用完整写法');
});

test('每次打开都重播入场:返回页面先无过渡复位再飞入,数字从 0 滚;卡片只在入场期带飞入过渡', () => {
  const timers = [];
  const page = loadPage({ setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; }, clearTimeout() {} });
  page._countTo = function (t) { this._counted = t; };
  page.data.facts = FACTS;
  page.data.hero = { num: '21', target: 21 };
  Object.assign(page.data, { shown: true, grown: true });
  page._replayIn();
  assert.deepEqual([page.data.replay, page.data.shown, page.data.grown, page.data.hero.num], [true, false, false, '0'], '复位帧');
  timers.shift().fn();
  assert.equal(page.data.replay, false);
  assert.equal(page.data.landing, true, '入场期开始');
  assert.equal(page._counted, 21, '大数字从 0 重滚');
  const land = timers.find((t) => t.ms >= 1300);
  const show = timers.find((t) => t.ms === 30);
  show.fn();
  assert.equal(page.data.shown, true);
  land.fn();
  assert.equal(page.data.landing, false, '入场结束撤掉飞入过渡，按压不被延迟');

  const js = fs.readFileSync(path.join(ROOT, PAGE_JS_PATH), 'utf8');
  assert.match(js, /this\._replayIn\(\);\s*\n\s*this\.load\(\);\s*\n\s*\},/, 'onShow 返回路径重播');
  assert.match(PAGE_WXML, /\{\{shown \? 'mi-shown' : 'mi-pre'\}\} \{\{landing \? 'mi-landing' : ''\}\} \{\{replay \? 'mi-replay' : ''\}\}/);
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/merchant/marketing/ai-insight/index.wxss'), 'utf8');
  assert.match(wxss, /\.mi-pre \.tcard \{ opacity: 0; transform: translateX/);
  assert.doesNotMatch(wxss, /\.mi-shown \.tcard/, '入场后不覆盖 transform,cy-pressed 缩放照常');
  assert.match(wxss, /\.cy-motion-reduced \.mi-in, \.cy-motion-reduced\.mi-pre \.tcard/);
});
