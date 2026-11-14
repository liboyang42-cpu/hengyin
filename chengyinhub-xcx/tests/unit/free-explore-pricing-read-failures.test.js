'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

let config;
let request;
let messages;
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest(options) { request = options; },
  getRequestErrorMessage(_res, fallback) { return fallback; },
});
global.Page = (value) => { config = value; };
global.getCurrentPages = () => [];
global.wx = { showToast(options) { messages.push(options.title); } };
require('../../pages/topic/pricing/index.js');

for (const [method, url, fallback, payload, expected] of [
  ['loadSaleStatus', '/api/topic/pricing/sale-status', '开售状态加载失败，请重试',
    { message: '已锁价，等待运营开售' }, { saleStatusText: '已锁价，等待运营开售' }],
  ['loadPendingSettlements', '/api/topic/pricing/settlement-pending', '待处理结算加载失败，请重试',
    [{ activityId: 9, topicId: 7, money: 12 }],
    { pendingRows: [{ key: '9', name: '场次', state: '结算待处理', reason: '', money: '¥12.00' }] }],
]) {
  test(method + ': 业务/网络失败可见、成功只读、卸载后不提示', () => {
    const page = Object.assign({}, config, { _scope: 'MERCHANT', data: { topicId: 7 } });
    const patches = [];
    page.setData = (patch) => patches.push(patch);
    messages = [];
    page[method]();
    assert.equal(request.url, url);
    assert.equal(request.method, 'GET');
    request.success({ code: 500 });
    request.success({ code: 200, data: null });
    request.fail({ errMsg: 'timeout' });
    assert.deepEqual(messages, [fallback, fallback, fallback]);
    assert.deepEqual(patches, []);
    request.success({ code: 200, data: payload });
    assert.deepEqual(patches, [expected]);
    page._unloaded = true;
    request.fail({});
    request.success({ code: 500 });
    request.success({ code: 200, data: payload });
    assert.equal(messages.length, 3);
    assert.deepEqual(patches, [expected]);
  });
}


test('定价内部状态不传视图，成本变化仍禁止旧地板提交且保留商家 scope', () => {
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch) => Object.assign(page.data, patch);
  page.loadLineupNames = () => {};
  page.loadPendingSettlements = () => {};
  page.onLoad({ topicId: '7', scope: 'MERCHANT' });
  assert.equal(JSON.parse(request.data).scope, 'MERCHANT');
  request.success({ code: 200, data: { priceMin: 9.5, canConfirm: true, productType: 2, platformRate: 0.03 } });
  assert.equal(page.data.sliderMin, 10);
  assert.equal(page.data.feeText, '¥0.30（3%）');
  assert.equal(page.data.canConfirmPrice, true);
  for (const key of ['scope', 'floor', 'floorStale', 'platformRate']) assert.equal(Object.hasOwn(page.data, key), false);
  page.onCostInput({ detail: { value: '120' } });
  assert.equal(page.data.canConfirmPrice, false);
  assert.equal(page._canConfirmPrice(), false);
  page.recalculate();
  assert.equal(Object.hasOwn(JSON.parse(request.data), 'leadCost'), false);
  request.success({ code: 200, data: { priceMin: 12, canConfirm: true, productType: 2, platformRate: 0.03 } });
  assert.equal(page.data.canConfirmPrice, true);
  page.confirmPrice();
  assert.deepEqual(JSON.parse(request.data), { topicId: 7, scope: 'MERCHANT', guidedPrice: 12 });
  page.onUnload();
});
