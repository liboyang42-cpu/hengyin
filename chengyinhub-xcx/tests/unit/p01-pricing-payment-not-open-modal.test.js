'use strict';
// P-01:非平台主题锁价后,后端回「…平台正在办理支付资质…」;定价页要用弹窗让主办方看全,不塞进 toast。
const test = require('node:test');
const assert = require('node:assert/strict');

let config;
let requests;
let modals;
let toasts;
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest(o) { requests.push(o); },
  getRequestErrorMessage: (_r, f) => f,
});
global.Page = (c) => { config = c; };
global.wx = {
  getStorageSync: () => '', setStorageSync() {}, getSystemInfoSync: () => ({}), getWindowInfo: () => ({}),
  showModal(o) { modals.push(o); },
  showToast(o) { toasts.push(o); },
};

function load() {
  requests = []; modals = []; toasts = [];
  delete require.cache[require.resolve('../../pages/topic/pricing/index.js')];
  require('../../pages/topic/pricing/index.js');
  const page = Object.assign({}, config);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.setData = function (p, cb) { Object.assign(this.data, p); if (cb) cb(); };
  page.data.topicId = 990014;
  page.data.canConfirmPrice = true;
  page.data.subType = 'guided';
  page.data.finalPrice = 69;
  let back = 0;
  page.goBack = () => { back++; };
  page.backCount = () => back;
  return page;
}

const NOTE = '终价已确认，已提交开售审核，审核通过后开售。注意：平台正在办理支付资质，开放前这个主题开售后玩家还不能在线付款报名';

test('收不了款的说明用弹窗全文显示,确认后返回', () => {
  const page = load();
  page.confirmPrice();
  requests[0].success({ code: 200, msg: NOTE });
  assert.equal(modals.length, 1);
  assert.equal(modals[0].content, NOTE);
  assert.equal(modals[0].showCancel, false);
  assert.equal(toasts.length, 0);
  assert.equal(page.backCount(), 0);
  modals[0].complete();
  assert.equal(page.backCount(), 1);
});

test('平台主题照旧只给 toast', () => {
  const page = load();
  page.confirmPrice();
  requests[0].success({ code: 200, msg: '终价已确认，已提交开售审核，审核通过后开售' });
  assert.equal(modals.length, 0);
});
