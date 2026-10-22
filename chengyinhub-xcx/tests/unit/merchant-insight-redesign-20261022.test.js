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

function loadPage() {
  let definition = null;
  const file = path.join(ROOT, PAGE_JS_PATH);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: (value) => { definition = value; },
    getApp: () => ({ globalData: {}, getUserID: () => 'merchant-a', sendRequest() {}, getRequestErrorMessage: () => '' }),
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 44 }), setNavigationBarColor() {} },
    console,
    require: (id) => require(path.resolve(path.dirname(file), id)),
  });
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

test('旧缓存没有 trends:退回 30 天归因三数,不出窗口切换与格子', () => {
  const page = loadPage();
  page._trends = null;
  const h = page._buildHero(FACTS, 7, 'visitors', false);
  assert.equal(h.hasTrend, false);
  assert.equal(h.num, '21');
  assert.equal(h.title, '近30天，城瘾为你带来');
  assert.equal(h.cells.length, 0);
  assert.match(PAGE_WXML, /<block wx:if="\{\{hero && hero\.hasTrend\}\}">[\s\S]*class="seg"[\s\S]*class="heat"/);
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
  assert.match(PAGE_WXML, /class="greet-line"><text>Hi，<\/text><text>\{\{storeName \|\| '店主'\}\}<\/text>/);
  assert.match(PAGE_WXML, /<cy-avatar class="greet-avatar"[^>]*src="\{\{profile\.logo \|\| ''\}\}"/);
  assert.doesNotMatch(PAGE_WXML.replace(/<!--[\s\S]*?-->/g, ''), /店铺档案|goProfileSetup/);
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/merchant/marketing/ai-insight/index.wxss'), 'utf8');
  assert.doesNotMatch(wxss, /\.visitor[^{]*::?before/, '最近到店玩家卡不加竖线');
  assert.doesNotMatch(fs.readFileSync(path.join(ROOT, PAGE_JS_PATH), 'utf8'), /vibrate/, 'owner:不震动');
});
