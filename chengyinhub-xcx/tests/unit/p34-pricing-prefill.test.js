'use strict';
// P-34:作者已填过票价时,定价页从那个价起步,不从地板价 ¥0 起步。
const test = require('node:test');
const assert = require('node:assert/strict');

let config;
let requests;
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest(o) { requests.push(o); },
  getRequestErrorMessage: (_r, f) => f,
});
global.Page = (c) => { config = c; };
global.wx = { getStorageSync: () => '', setStorageSync() {}, getSystemInfoSync: () => ({}), getWindowInfo: () => ({}) };

function load() {
  requests = [];
  delete require.cache[require.resolve('../../pages/topic/pricing/index.js')];
  require('../../pages/topic/pricing/index.js');
  const page = Object.assign({}, config);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.setData = function (p, cb) { Object.assign(this.data, p); if (cb) cb(); };
  page.loadLineupNames = () => {};
  page.data.topicId = 990014;
  return page;
}

function preview(page, data) {
  page.preview();
  requests[0].success({ code: 200, data: Object.assign({ priceMin: 0, canConfirm: true }, data) });
}

test('有已填票价时预填它', () => {
  const page = load();
  page.data.subType = 'self';
  preview(page, { currentPrice: 69 });
  assert.equal(page.data.finalPrice, 69);
  assert.ok(page.data.sliderMax >= 69);
});

test('已填票价低于地板价时回落到地板价;旧后端没这个字段时照旧', () => {
  const page = load();
  page.data.subType = 'self';
  preview(page, { priceMin: 80, currentPrice: 69 });
  assert.equal(page.data.finalPrice, 80);
  const legacy = load();
  legacy.data.subType = 'self';
  preview(legacy, { priceMin: 12.3 });
  assert.equal(legacy.data.finalPrice, 13);
});

// P-34 第三轮:开售后再打开定价页,不能说「合作阵容锁定后可定价」
test('开售后(priced)说终价已确认,不误报阵容未锁定;定价前照旧', () => {
  const fs = require('fs');
  const path = require('path');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/topic/pricing/index.wxml'), 'utf8');
  assert.match(wxml, /<view class="pg-note" wx:if="\{\{priced\}\}">终价已确认<\/view>\s*<view class="pg-note" wx:elif="\{\{phaseBlocked\}\}">合作阵容锁定后可定价<\/view>/);

  const sold = load();
  sold.data.subType = 'guided';
  preview(sold, { canConfirm: false, priced: true, currentPrice: 79 });
  assert.equal(sold.data.priced, true);
  assert.equal(sold.data.finalPrice, 79);
  assert.equal(sold.data.canConfirmPrice, false);
  sold.onConfirmDisabledTap();
  assert.equal(sold.data.submitError, '终价已确认');

  const early = load();
  early.data.subType = 'self';
  preview(early, { canConfirm: false });
  assert.equal(early.data.priced, false);
  early.onConfirmDisabledTap();
  assert.equal(early.data.submitError, '主题还没进入定价阶段');
});
