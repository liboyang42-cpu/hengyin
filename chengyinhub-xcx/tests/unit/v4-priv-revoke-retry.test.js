'use strict';
// SC-PRIV-03:设置页(pages/shezhi)的「隐私与定位」半屏 cy-privacy-sheet 撤回漫游定位同意。
// 超时 / HTTP 500 / 业务失败 / 服务端已处理但回包丢失,再重试:
// 失败绝不提示「撤回成功」;重试可用;结果未知时应先读服务端状态再告诉用户。
// 这里接的是真 utils/compliance/consent-client.js(只把 sendRequest 换成可控桩)。
const { test } = require('node:test');
const assert = require('node:assert/strict');

const SHEET = '../../pages/shezhi/components/privacy-sheet/index.js';
const CLIENT = '../../utils/compliance/consent-client.js';
const BG = '../../utils/location/bg-tracker.js';
const SUCCESS_TOAST = '已撤回漫游定位同意';
const settle = (ms = 0) => new Promise(resolve => setTimeout(resolve, ms));

function harness() {
  const h = { requests: [], toasts: [], storage: new Map() };
  for (const file of [SHEET, CLIENT, BG]) delete require.cache[require.resolve(file)];
  const { createConsentClient } = require(CLIENT);
  const client = createConsentClient({
    sendRequest: (options) => { h.requests.push(options); },
    getUserID: () => 9,
    getPending: (key) => h.storage.get(key),
    setPending: (key, value) => { if (value) h.storage.set(key, value); else h.storage.delete(key); },
    settleTimeoutMs: 20,
  });
  global.getApp = () => ({ getUserID: () => 9, recordConsent: (input) => client.record(input) });
  global.wx = {
    showToast: (o) => h.toasts.push(o.title), stopLocationUpdate(o) { if (o && o.complete) o.complete(); },
    offLocationChange() {}, getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
  };
  let def;
  global.Component = (config) => { def = config; };
  require(SHEET);
  h.sheet = Object.assign({}, def.methods, { data: JSON.parse(JSON.stringify(def.data)) });
  h.sheet.setData = (patch) => Object.assign(h.sheet.data, patch);
  h.sheet.triggerEvent = () => {};
  h.revokes = () => h.requests.filter(r => r.url === '/api/compliance/consents');
  h.readbacks = () => h.requests.filter(r => r.url === '/api/compliance/consents/latest');
  return h;
}
// 服务端最新一条仍是同意(撤回没落库)
const notRevoked = { code: 200, data: { requestId: 'consent-old-agree', docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' } };
function okRow(req) {
  const body = JSON.parse(req.data);
  return { code: 200, data: { requestId: body.requestId, docType: body.docType, scene: body.scene,
    eventType: body.eventType, docVersion: 'v2.1' } };
}

test('SC-PRIV-03 超时/HTTP500/业务失败/回包丢失都不提示撤回成功;重试复用同一幂等号并最终成功一次', async () => {
  const h = harness();

  // 1) 超时:请求发出后永不回调
  h.sheet.withdrawRoamLocationConsent();
  assert.equal(h.sheet.data.submitting, true);
  h.sheet.withdrawRoamLocationConsent(); // 在途连点不重复发
  assert.equal(h.revokes().length, 1);
  await settle(30);
  // 超时 = 结果未知:先回读,回读显示未撤回才告诉用户失败
  assert.equal(h.readbacks().length, 1);
  h.readbacks().at(-1).success(notRevoked);
  await settle();
  assert.equal(h.sheet.data.submitting, false, '超时必须释放提交态,否则无法重试');
  assert.deepEqual(h.toasts, ['本机漫游已停，记录待确认']);

  // 2) HTTP 500
  h.sheet.withdrawRoamLocationConsent();
  h.revokes().at(-1).successStatusAbnormal({ code: 500 }, 500);
  await settle();
  // 3) 业务失败(HTTP 200 + code 500)
  h.sheet.withdrawRoamLocationConsent();
  h.revokes().at(-1).success({ code: 500, msg: '服务繁忙' });
  await settle();
  // 3b) code 200 但回执对不上本次请求(例如 eventType 不是 REVOKE)也算失败
  h.sheet.withdrawRoamLocationConsent();
  const wrong = okRow(h.revokes().at(-1)); wrong.data.eventType = 'AGREE';
  h.revokes().at(-1).success(wrong);
  await settle();
  // 4) 服务端已落库但回包丢失(客户端只看到 fail)
  h.sheet.withdrawRoamLocationConsent();
  const lost = h.revokes().at(-1);
  lost.fail({ errMsg: 'request:fail' });
  await settle();
  // HTTP500 / 业务失败 / 回执不符都是明确失败,不回读;只有回包丢失这次回读
  assert.equal(h.readbacks().length, 2);
  h.readbacks().at(-1).success(notRevoked);
  await settle();

  assert.equal(h.toasts.includes(SUCCESS_TOAST), false, '任何失败都不得提示撤回成功');
  assert.equal(h.toasts.length, 5);
  assert.equal(h.sheet.data.submitting, false);

  // 5) 重试:同一 requestId(服务端按幂等号回放已落库的那一行),这次回包到达
  h.sheet.withdrawRoamLocationConsent();
  const retry = h.revokes().at(-1);
  retry.success(okRow(retry));
  await settle();
  assert.deepEqual(h.toasts.filter(t => t === SUCCESS_TOAST), [SUCCESS_TOAST]);
  assert.equal(h.toasts.at(-1), SUCCESS_TOAST);
  const ids = new Set(h.revokes().map(r => JSON.parse(r.data).requestId));
  assert.equal(h.revokes().length, 6);
  assert.equal(ids.size, 1, '所有重试必须复用同一幂等号,回包丢失的那次不会被重复记账');
  assert.equal(h.storage.size, 0, '成功后清掉待重试幂等号');

  // 迟到的旧回包不能再触发第二次成功提示
  lost.success(okRow(lost));
  await settle();
  assert.equal(h.toasts.filter(t => t === SUCCESS_TOAST).length, 1);
});

test('SC-PRIV-03 结果未知(超时/回包丢失)且回读显示仍未撤回:提示失败、可重试', async () => {
  const h = harness();
  h.sheet.withdrawRoamLocationConsent();
  h.revokes().at(-1).fail({ errMsg: 'request:fail' }); // 服务端可能已撤回
  await settle();
  assert.equal(h.readbacks().length, 1, '结果未知必须先回读服务端');
  assert.deepEqual(h.toasts, [], '回读返回前不得提前下结论');
  assert.equal(h.sheet.data.submitting, true);
  h.readbacks().at(-1).fail({ errMsg: 'request:fail' }); // 回读本身也失败 → 失败
  await settle();
  h.sheet.withdrawRoamLocationConsent();
  await settle(30); // 超时
  h.readbacks().at(-1).success(notRevoked);
  await settle();
  assert.deepEqual(h.toasts, ['本机漫游已停，记录待确认', '本机漫游已停，记录待确认']);
  assert.equal(h.sheet.data.submitting, false);
  // 回读也超时:同样按失败,不挂起
  h.sheet.withdrawRoamLocationConsent();
  h.revokes().at(-1).fail({ errMsg: 'request:fail' });
  await settle(40);
  assert.equal(h.toasts.length, 3);
  assert.equal(h.toasts.includes(SUCCESS_TOAST), false);
  assert.equal(h.sheet.data.submitting, false);
});

test('SC-PRIV-03 期望:结果未知时先回读服务端最新状态,再决定提示成功或失败', async () => {
  const h = harness();
  h.sheet.withdrawRoamLocationConsent();
  const revoke = h.revokes().at(-1);
  revoke.fail({ errMsg: 'request:fail' });
  await settle();
  assert.ok(h.requests.some(r => r.url === '/api/compliance/consents/latest'));
  const read = h.readbacks().at(-1);
  assert.deepEqual(JSON.parse(read.data), { docType: 'privacy_policy', scene: 'roam_location' });
  // 服务端已落库:最新一条就是本次撤回
  read.success(okRow(revoke));
  await settle();
  assert.deepEqual(h.toasts, [SUCCESS_TOAST]);
  assert.equal(h.sheet.data.submitting, false);
  assert.equal(h.storage.size, 0, '回读确认撤回后清掉待重试幂等号');
  // 业务失败(有回包)不回读,立即失败
  h.sheet.withdrawRoamLocationConsent();
  h.revokes().at(-1).success({ code: 500, msg: '服务繁忙' });
  await settle();
  assert.equal(h.readbacks().length, 1);
  assert.equal(h.toasts.at(-1), '本机漫游已停，记录待确认');
});
