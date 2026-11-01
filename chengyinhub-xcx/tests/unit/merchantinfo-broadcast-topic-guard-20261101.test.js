process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

/* 走查 v2 R07 定向广播(2026-11-01):旧服务端不认 topicId,预览会按全店客户算人数、真发也发全店。
   预览没回显本主题就不让发;回显了才放开发送。页面桩照 g4-merchantinfo-actions-contract.test.js。 */

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const stripWxmlComments = (source) => source.replace(/<!--[\s\S]*?-->/g, '');

let pageConfig;
let navigations;
let toasts;
let requests;
let currentMemberId = 1;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => currentMemberId,
  sendRequest: (options) => { requests.push(options); return { abort() {} }; },
  tips: (msg) => toasts.push(String(msg)),
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
});
global.wx = {
  navigateTo: (options) => navigations.push(options),
  reLaunch: () => {},
  showToast: (options) => toasts.push(String(options && options.title)),
  showLoading: () => {},
  hideLoading: () => {},
  setNavigationBarColor: () => {},
  setBackgroundColor: () => {},
  navigateBack: () => {},
  pageScrollTo: () => {},
  nextTick: (fn) => fn(),
};
global.Page = (config) => { pageConfig = config; };
require('../../pages/topic/merchantinfo/merchantinfo.js');

function makePage(overrides) {
  const page = Object.assign({}, pageConfig);
  page.data = Object.assign(JSON.parse(JSON.stringify(pageConfig.data)), overrides || {});
  page.setData = (patch, cb) => {
    Object.keys(patch).forEach((key) => {
      if (key.indexOf('.') < 0) { page.data[key] = patch[key]; return; }
      const parts = key.split('.');
      let cur = page.data;
      for (let i = 0; i < parts.length - 1; i += 1) {
        if (cur[parts[i]] == null || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {};
        cur = cur[parts[i]];
      }
      cur[parts[parts.length - 1]] = patch[key];
    });
    if (cb) cb();
  };
  return page;
}

function findRequest(url) {
  return requests.filter((r) => r.url === url)[0] || null;
}


function beforeEach() { navigations = []; toasts = []; requests = []; }

test('定向广播:预览没回显本主题(旧服务端) ⇒ 报错不让发', () => {
  beforeEach();
  const page = makePage({ topicId: 990045 });
  page.openBroadcast();
  const preview = findRequest('/api/merchant/crm/broadcast/preview');
  assert.deepEqual(JSON.parse(preview.data), { topicId: 990045 });
  preview.success({ code: 200, data: { deliverableCount: 120, merchantDailyRemaining: 3 } });
  assert.equal(page.data.broadcastSheet.state, 'error');
  assert.match(page.data.broadcastSheet.error, /只发给本主题/);
  page.setData({ 'broadcastSheet.content': '明天 10 点集合' });
  page.sendBroadcast();
  assert.equal(findRequest('/api/merchant/crm/broadcast'), null, '没发出去');
});

test('定向广播:预览回显本主题 ⇒ 可以发,发送带 topicId', () => {
  beforeEach();
  const page = makePage({ topicId: 990045 });
  page.openBroadcast();
  findRequest('/api/merchant/crm/broadcast/preview').success({ code: 200, data: { topicId: 990045, deliverableCount: 12, merchantDailyRemaining: 3 } });
  assert.equal(page.data.broadcastSheet.state, 'ready');
  assert.equal(page.data.broadcastSheet.deliverable, 12);
  page.setData({ 'broadcastSheet.content': '明天 10 点集合' });
  page.sendBroadcast();
  const sent = findRequest('/api/merchant/crm/broadcast');
  assert.equal(JSON.parse(sent.data).topicId, 990045);
  assert.equal(JSON.parse(sent.data).content, '明天 10 点集合');
});
