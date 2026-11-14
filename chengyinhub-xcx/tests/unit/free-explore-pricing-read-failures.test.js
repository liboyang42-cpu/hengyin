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
    const page = Object.assign({}, config, { data: { topicId: 7, scope: 'MERCHANT' } });
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
