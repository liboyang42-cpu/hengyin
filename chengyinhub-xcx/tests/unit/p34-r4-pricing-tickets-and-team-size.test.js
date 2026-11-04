'use strict';
// P-34 第四轮(业主):
// ① 成团人数缺失不再是卡片底部的蓝色提示框,而是成团人数栏下的红字(第十轮 f3 起不再用粉底 cy-inline-error),标签带红 *;
// ② 已开售主题整块隐藏带队成本与「重新计算地板」;
// ③ 作者发了几张票时逐张确认终价:每张预填作者的价、各自对地板校验,确认时逐张提交;一张票照旧用滑块。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/pricing/index.wxml'), 'utf8');

let config;
let requests;
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest(o) { requests.push(o); },
  getRequestErrorMessage: (_r, f) => f,
});
global.Page = (c) => { config = c; };
global.getCurrentPages = () => [{}];
global.wx = { getStorageSync: () => '', setStorageSync() {}, getSystemInfoSync: () => ({}), getWindowInfo: () => ({}), navigateBack() {} };

function load() {
  requests = [];
  delete require.cache[require.resolve('../../pages/topic/pricing/index.js')];
  require('../../pages/topic/pricing/index.js');
  const page = Object.assign({}, config);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.setData = function (p, cb) { Object.assign(this.data, p); if (cb) cb(); };
  page.loadLineupNames = () => {};
  page.data.topicId = 990021;
  return page;
}

function preview(page, data) {
  page.preview();
  requests[requests.length - 1].success({ code: 200, data: Object.assign({ priceMin: 50, canConfirm: true }, data) });
}

const TWO = [{ ticketId: 931, name: '日场', price: 69 }, { ticketId: 932, name: '夜场', price: 99 }];

// 第十轮 f3 p3 F6(ROUND10 业主口径「输入问题是字段下的红字」):原来的 cy-inline-error 是粉底带图标的块,
// 改成栏下纯红字;且打开页面未动人数时不报,改过人数或点确认后才报。
test('成团人数的问题显示在成团人数栏下的红字,标签带红 *;卡片底部不再有蓝色提示框', () => {
  const field = wxml.match(/成团人数<text class="pg-req"[^>]*>\*<\/text><\/text>[\s\S]*?<\/view>/);
  assert.ok(field, '成团人数标签要带红 *');
  assert.match(field[0], /<text class="pg-field-err" wx:if="\{\{teamSizeError\}\}" aria-role="alert">\{\{teamSizeError\}\}<\/text>/);
  assert.doesNotMatch(wxml, /<cy-inline-error[^>]*teamSizeError/, '人数提示不再是粉底图标块');
  assert.match(fs.readFileSync(path.join(ROOT, 'pages/topic/pricing/index.wxss'), 'utf8'), /\.pg-field-err \{[^}]*color: var\(--cy-color-status-danger\)/);
  const between = wxml.slice(wxml.indexOf('重新计算地板'), wxml.indexOf('pg-floor'));
  assert.doesNotMatch(between, /teamSizeError/, '「重新计算地板」下面不再有人数提示框');
  assert.doesNotMatch(wxml, /<cy-inline-error(?![^>]*kind=)[^>]*teamSizeError/, '人数提示不能用默认(蓝色 data)档');
  const page = load();
  page.data.subType = 'guided';
  page.data.teamSize = '';
  preview(page, {});
  assert.equal(page.data.teamSizeError, '', '打开页面、还没动过人数时不报错');
  page.onTeamInput({ detail: { value: '0' } });
  assert.equal(page.data.teamSizeError, '请填写大于 0 的成团人数');
  // 动过之后刷新预览仍保留提示
  preview(page, {});
  assert.equal(page.data.teamSizeError, '请填写大于 0 的成团人数');
});

test('第十轮 f3 p3 F6:滑块右端标出「最高」,不再是一个看着像第二个价格的裸金额', () => {
  assert.match(wxml, /<text>地板 \{\{money\.amount2\(floorText\)\}\}<\/text><text>最高 \{\{money\.amount2\(sliderMax\)\}\}<\/text>/);
});

test('已开售主题隐藏带队成本整块(含重新计算地板)', () => {
  assert.match(wxml, /wx:if="\{\{subType==='guided' && !priced\}\}"[\s\S]*?带队成本[\s\S]*?重新计算地板/);
});

test('作者两张不同价的票:逐张列出并预填作者的价,一张票照旧用滑块', () => {
  const page = load();
  page.data.subType = 'guided';
  page.data.teamSize = '4';
  preview(page, { tickets: TWO });
  assert.deepEqual(page.data.tickets.map((t) => [t.ticketId, t.name, t.price, t.error]),
    [[931, '日场', '69', ''], [932, '夜场', '99', '']]);
  assert.equal(page.data.canConfirmPrice, true);
  assert.match(wxml, /wx:if="\{\{subType==='guided' && tickets.length > 1\}\}"/);
  assert.match(wxml, /bindinput="onTicketPriceInput"/);

  const single = load();
  single.data.subType = 'guided';
  single.data.teamSize = '4';
  preview(single, { tickets: [TWO[0]], currentPrice: 69 });
  assert.deepEqual(single.data.tickets, [], '一张票不显示逐张栏');
  assert.equal(single.data.finalPrice, 69);
});

test('每张票各自对地板校验:低于地板在那张票下红字说明,按钮置灰', () => {
  const page = load();
  page.data.subType = 'guided';
  page.data.teamSize = '4';
  preview(page, { priceMin: 80, tickets: TWO });
  assert.equal(page.data.tickets[0].error, '不能低于最低价 ¥80.00');
  assert.equal(page.data.tickets[1].error, '');
  assert.equal(page.data.canConfirmPrice, false);
  page.onConfirmDisabledTap();
  assert.equal(page.data.submitError, '', '票价问题只在那张票下面说');

  page.onTicketPriceInput({ currentTarget: { dataset: { idx: 0 } }, detail: { value: '85' } });
  assert.equal(page.data.tickets[0].error, '');
  assert.equal(page.data.canConfirmPrice, true);
  page.onTicketPriceInput({ currentTarget: { dataset: { idx: 1 } }, detail: { value: '' } });
  assert.equal(page.data.tickets[1].error, '请填写终价');
  assert.equal(page.data.canConfirmPrice, false);
});

test('确认时逐张提交各自终价', () => {
  const page = load();
  page.data.subType = 'guided';
  page.data.teamSize = '4';
  preview(page, { tickets: TWO });
  page.onTicketPriceInput({ currentTarget: { dataset: { idx: 0 } }, detail: { value: '79' } });
  page.confirmPrice();
  const req = requests[requests.length - 1];
  assert.equal(req.url, '/api/topic/pricing/confirm');
  const body = JSON.parse(req.data);
  assert.deepEqual(body.ticketPrices, [{ ticketId: 931, price: 79 }, { ticketId: 932, price: 99 }]);
  assert.equal(body.guidedPrice, 79);
});

test('单张票的确认请求不带 ticketPrices(老路径不变)', () => {
  const page = load();
  page.data.subType = 'guided';
  page.data.teamSize = '4';
  preview(page, { tickets: [TWO[0]], currentPrice: 69 });
  page.confirmPrice();
  const body = JSON.parse(requests[requests.length - 1].data);
  assert.equal(body.ticketPrices, undefined);
  assert.equal(body.guidedPrice, 69);
});
