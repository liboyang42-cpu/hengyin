// 2026-11-14 业主「主题模板太复杂,能力那块没有排版,要卡片和样式」:主题模板详情(pages/templatedetail/topic-detail)
// 只在既有版式上整理 —— 能力 tab 概要变 xb-stat-card、站点按章分组出卡、采用条件走 xb-kv-card;
// 详情 tab 开场白收 4 行可展开、章节卡不再列站点路径。数据流不变,只加分组与展开两段 JS。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..', '..');
const DIR = 'pages/templatedetail/topic-detail/';
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const art = require('../../pages/templatedetail/shelf/art.js');

function mount(info, wxExtra) {
  const requests = [];
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: (o) => { requests.push(o); },
    getRequestErrorMessage: (_r, d) => d
  });
  global.wx = Object.assign({ setNavigationBarColor() {}, navigateTo() {} }, wxExtra || {});
  let def;
  global.Page = (d) => { def = d; };
  const file = path.join(ROOT, DIR, 'index.js');
  delete require.cache[file];
  require(file);
  const page = Object.assign({}, def);
  page.data = JSON.parse(JSON.stringify(def.data));
  page.setData = function (patch) { Object.assign(this.data, patch); };
  page.onLoad({ id: '7' });
  requests[0].success({ code: '200', data: info });
  return { page, requests };
}

const INFO = {
  name: '城市藏宝猎人', mode: 'CITY', totalTime: 5400, description: '开场白'.repeat(40),
  games: [{ id: 1 }, { id: 2 }],
  chapters: [
    { id: 11, name: '序章', description: '从外滩出发', nodes: [
      { name: '外滩', gameTitle: '街角谜题', gameDuration: 10, hookTeaser: '找线索解暗号' },
      { name: '南京路', gameTitle: '表外玩法', gameDuration: 0, hookTeaser: '' }
    ] },
    { id: 12, name: '第二章 河畔', description: '沿江走', nodes: [{ name: '滨江', gameTitle: '拍下这一刻', gameDuration: 5, hookTeaser: '一张照片过关' }] },
    { id: 13, name: '空章', description: '', nodes: [] }
  ]
};

test('能力:站点按章分组,每站带编号 / 玩法 / 货架道具图 / 玩家做什么 / 时长;空章不出组', () => {
  const { page } = mount(INFO);
  const g = page.data.stopGroups;
  assert.deepEqual(g.map((x) => [x.no, x.name, x.stops.length]), [['1', '序章', 2], ['', '第二章 河畔', 1]], '章名已带「第…章」不再重复编号;没有站的章不出');
  assert.deepEqual(g[0].stops[0], { no: 1, name: '外滩', game: '街角谜题', gameIcon: art.gameArt('街角谜题').prop, doText: '找线索解暗号', dur: '约 10 分钟' });
  assert.deepEqual(g[0].stops[1], { no: 2, name: '南京路', game: '表外玩法', gameIcon: '', doText: '', dur: '' }, '表外玩法不配图、没时长不出时长');
  assert.equal(g[1].stops[0].no, 3, '站号跨章连续');
  assert.equal(page.data.stops.length, 3, '平铺 stops 仍在,供空态与概要计数');
});

test('能力:概要是「值 + 标签」的数字卡,不再是一排 chip', () => {
  const { page } = mount(INFO);
  assert.deepEqual(page.data.summary, [
    { value: '城市定向', label: '模式' }, { value: '3', label: '章节' }, { value: '3', label: '站点' },
    { value: '2', label: '玩法' }, { value: '1 小时30 分', label: '总时长' }
  ]);
  const free = mount(Object.assign({}, INFO, { mode: 'FREE_EXPLORE', totalTime: 0, games: [] })).page.data.summary;
  assert.deepEqual(free.slice(0, 2), [{ value: '自由探索', label: '模式' }, { value: '可以跨天', label: '时间' }]);
  assert.ok(!free.some((s) => s.label === '总时长' || s.label === '玩法'), '缺值的格子整格不出');
});

test('能力:只有一组且没有章名时整组一个头、不出标题', () => {
  const { page } = mount({ chapters: [{ nodes: [{ name: 'A' }, { name: 'B' }] }] });
  assert.equal(page.data.stopGroups.length, 1);
  assert.deepEqual([page.data.stopGroups[0].no, page.data.stopGroups[0].name], ['', '']);
  assert.equal(page.data.stopGroups[0].stops[1].name, 'B');
  assert.equal(mount({ chapters: [{ nodes: [{}] }] }).page.data.stopGroups[0].stops[0].name, '第 1 站', '站名缺省');
});

test('详情:章节卡只剩编号 / 章名 / 一句话,站点路径不再挂在章下', () => {
  const { page } = mount(INFO);
  assert.deepEqual(page.data.chapters, [
    { key: '11', no: '1', name: '序章', text: '从外滩出发' },
    { key: '12', no: '', name: '第二章 河畔', text: '沿江走' },
    { key: '13', no: '3', name: '空章', text: '' }
  ]);
  const wxml = read(DIR + 'index.wxml');
  const details = wxml.slice(wxml.indexOf("activeTab === 'details'"), wxml.indexOf("activeTab === 'capabilities'"));
  assert.doesNotMatch(details, /st-ch__path|st-ch__stop|item\.stops|stopGroups/, '详情 tab 不再画每章站点路径');
  assert.match(wxml, /class="st-ch__t ep1"/);
  assert.match(wxml, /class="st-ch__p ep1"/, '一句话一行省略');
});

test('详情:开场白默认收 4 行,量到更高才出「展开」,点了切「收起」', () => {
  let measured = 0;
  const query = {
    in() { return this; }, select() { return this; }, boundingClientRect() { return this; },
    exec(cb) { measured += 1; cb([{ height: 120 }]); }
  };
  const { page } = mount(INFO, { nextTick: (fn) => fn(), createSelectorQuery: () => query });
  assert.equal(measured, 1);
  assert.equal(page.data.openingNeedMore, true);
  assert.equal(page.data.openingExpanded, false);
  page.toggleOpening();
  assert.equal(page.data.openingExpanded, true);
  page.toggleOpening();
  assert.equal(page.data.openingExpanded, false);

  // 带 tab=capabilities 进来时首屏没有开场白节点,切回详情再量
  let late = 0;
  const lateQuery = { in() { return this; }, select() { return this; }, boundingClientRect() { return this; }, exec(cb) { late += 1; cb(late === 1 ? [null] : [{ height: 120 }]); } };
  const fromCap = mount(INFO, { nextTick: (fn) => fn(), createSelectorQuery: () => lateQuery });
  assert.equal(fromCap.page.data.openingNeedMore, false);
  fromCap.page.switchTab({ detail: { key: 'details' } });
  assert.equal(late, 2);
  assert.equal(fromCap.page.data.openingNeedMore, true);

  const short = { in() { return this; }, select() { return this; }, boundingClientRect() { return this; }, exec(cb) { cb([{ height: 60 }]); } };
  assert.equal(mount(INFO, { nextTick: (fn) => fn(), createSelectorQuery: () => short }).page.data.openingNeedMore, false, '短文不出假按钮');
  assert.equal(mount(INFO).page.data.openingNeedMore, false, '没有量尺(单测环境)时不报错、不出按钮');

  const wxml = read(DIR + 'index.wxml');
  assert.match(wxml, /id="td-opening" class="xb-body xb-body--soft \{\{ openingExpanded \? '' : 'td-opening--clamp' \}\}"/);
  assert.match(wxml, /class="xb-link-more" wx:if="\{\{ openingNeedMore \|\| openingExpanded \}\}" bindtap="toggleOpening"[^>]*>\{\{ openingExpanded \? '收起' : '展开' \}\}/);
  assert.match(read(DIR + 'index.wxss'), /\.td-opening--clamp \{[^}]*-webkit-line-clamp: 4;/);
});

test('能力 wxml:概要用 xb-stat-card,站点是 cap-card(编号 / 站名一行 / 玩法药丸 / 两行做什么 / 时长),采用条件走 xb-kv-card', () => {
  const wxml = read(DIR + 'index.wxml');
  const cap = wxml.slice(wxml.indexOf("activeTab === 'capabilities'"), wxml.indexOf('xb-scroll-pad'));
  assert.match(cap, /<view class="cap-sum" wx:if="\{\{ summary\.length \}\}">\s*<view class="xb-stat-card" wx:for="\{\{ summary \}\}"/);
  assert.match(cap, /<view class="cap-ch" wx:for="\{\{ stopGroups \}\}" wx:key="key">/);
  assert.match(cap, /<view class="cap-ch__hd" wx:if="\{\{ item\.no \|\| item\.name \}\}">/, '没有编号也没有章名的组不出头');
  assert.match(cap, /<view class="cap-card" wx:for="\{\{ item\.stops \}\}" wx:for-item="stop" wx:key="no">/);
  assert.match(cap, /class="cap-card__name ep1"/);
  assert.match(cap, /<view class="cap-card__pill" wx:if="\{\{ stop\.game \}\}">\s*<image wx:if="\{\{ stop\.gameIcon \}\}" class="cap-card__pill-img"[^>]*\/>\s*<cy-icon wx:else name="play"/);
  assert.match(cap, /class="cap-card__do ep2"/, '玩家做什么最多两行');
  assert.match(cap, /<view class="cap-card__dur" wx:if="\{\{ stop\.dur \}\}"><cy-icon name="clock"/);
  assert.doesNotMatch(cap, /hover-class|bindtap/, '站卡不可点、无按压态');
  assert.match(cap, /<cy-empty wx:else kind="empty" title="暂无玩法安排" sub="" \/>/, '空态不变');
  assert.match(cap, /<view class="xb-kv-card">\s*<view class="xb-kv-row" wx:for="\{\{ deliveryRows \}\}" wx:key="key">\s*<text class="xb-kv-k">\{\{ item\.label \}\}<\/text>\s*<text class="xb-kv-v">\{\{ item\.value \}\}<\/text>/);
  assert.doesNotMatch(wxml, /cap-stop|xb-chip|td-cond-v/, '旧的平铺行与 chip 概要已退场');
});

test('wxss:新增样式只用 token,卡面与同页 xb-stat-card 同一张底', () => {
  const wxss = read(DIR + 'index.wxss');
  assert.doesNotMatch(wxss, /#[0-9a-fA-F]{3,8}\b|rgba?\(/, '不写死颜色');
  assert.doesNotMatch(wxss, /font-size:\s*[0-9]/, '字号只用 token');
  assert.doesNotMatch(wxss, /border-radius:\s*[0-9]+rpx/, '圆角只用 token');
  for (const sel of ['.st-ch', '.cap-card']) {
    const m = wxss.match(new RegExp(sel.replace('.', '\\.') + ' \\{[^}]*\\}'));
    assert.ok(m, sel);
    assert.match(m[0], /background: var\(--cy-color-bg-surface-subtle\);/);
    assert.match(m[0], /border-radius: var\(--cy-radius-md\);/);
  }
});
