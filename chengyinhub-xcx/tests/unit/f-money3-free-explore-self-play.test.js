'use strict';
// f-money3:自由探索主题的收费自玩票没有结算出口(后端发布 / 锁价 / 开售都拒)。
// ① 定价页「无人带」:后端预览给的原因挂在终价栏下红字(cy-inline-error kind="field"),确认按钮置灰,不弹窗;
// ② 发布编辑器:自玩票栏位已下线(a5-v3-29),草稿里残留的自玩票只在经典定向上送。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const MESSAGE = '自由探索主题不能设置收费自玩票，请改为给主题门票定价';

let config;
let requests;
let modals;
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest(o) { requests.push(o); },
  getRequestErrorMessage: (_r, f) => f,
});
global.Page = (c) => { config = c; };
global.getCurrentPages = () => [{}];
global.wx = {
  getStorageSync: () => '', setStorageSync() {}, getSystemInfoSync: () => ({}), getWindowInfo: () => ({}),
  navigateBack() {}, showModal(o) { modals.push(o); },
};

function load() {
  requests = [];
  modals = [];
  delete require.cache[require.resolve('../../pages/topic/pricing/index.js')];
  require('../../pages/topic/pricing/index.js');
  const page = Object.assign({}, config);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.setData = function (p, cb) { Object.assign(this.data, p); if (cb) cb(); };
  page.loadLineupNames = () => {};
  page.data.topicId = 990031;
  return page;
}

function preview(page, data) {
  page.preview();
  requests[requests.length - 1].success({ code: 200, data: Object.assign({ priceMin: 20, canConfirm: true }, data) });
}

test('自由探索「无人带」:原因挂在终价栏下红字,确认置灰,点灰按钮不另起提示也不弹窗', () => {
  const page = load();
  page.data.subType = 'self';
  preview(page, { selfPriceError: MESSAGE });
  assert.equal(page.data.selfPriceError, MESSAGE);
  assert.equal(page.data.canConfirmPrice, false);
  page.onSliderChange({ detail: { value: 30 } });
  assert.equal(page.data.canConfirmPrice, false, '拖滑块也不能解禁');
  page.onConfirmDisabledTap();
  assert.equal(page.data.submitError, '');
  page.confirmPrice();
  assert.equal(requests.length, 1, '置灰时不发确认请求');
  assert.equal(modals.length, 0);
});

test('切回有人带(门票)即清掉红字并按原判据可确认;经典定向无人带不受影响', () => {
  const page = load();
  page.data.subType = 'self';
  preview(page, { selfPriceError: MESSAGE });
  page.chooseType({ detail: { key: 'guided' } });
  assert.equal(page.data.selfPriceError, '');
  page.data.teamSize = '4';
  requests[requests.length - 1].success({ code: 200, data: { priceMin: 20, canConfirm: true, selfPriceError: MESSAGE } });
  assert.equal(page.data.selfPriceError, '', '有人带页签不吃无人带的原因');
  assert.equal(page.data.canConfirmPrice, true);

  const classic = load();
  classic.data.subType = 'self';
  preview(classic, {});
  assert.equal(classic.data.selfPriceError, '');
  assert.equal(classic.data.canConfirmPrice, true);
});

test('红字是终价栏下的 field 档 cy-inline-error,不是弹窗', () => {
  const wxml = read('pages/topic/pricing/index.wxml');
  const card = wxml.slice(wxml.indexOf('<slider'), wxml.indexOf('pg-reference', wxml.indexOf('<slider')));
  assert.match(card, /<cy-inline-error class="pg-field-err" wx:if="\{\{selfPriceError\}\}" kind="field" title="\{\{selfPriceError\}\}" \/>/);
  assert.doesNotMatch(read('pages/topic/pricing/index.js'), /selfPriceError[^\n]*modal\./);
});

test('发布编辑器:残留自玩票只在经典定向上送', () => {
  const src = read('pages/publish/fabu/index.js');
  assert.match(src, /selfPlay: productType === 1 && this\.data\.formData\.selfPlay \? 1 : 0,/);
  assert.match(src, /selfPlayPrice: productType === 1 && this\.data\.formData\.selfPlay \? this\.data\.formData\.selfPlayPrice : 0,/);
});
