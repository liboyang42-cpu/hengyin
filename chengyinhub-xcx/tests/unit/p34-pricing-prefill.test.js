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
