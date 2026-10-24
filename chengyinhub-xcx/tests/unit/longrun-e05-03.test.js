'use strict';
// Lifecycle regression extracted from public PR #33. B9(L21):已付退款确认时在同一手势内申请 refund/refundRejected 订阅;
// 失效的确认(换号/detach/旧弹窗)既不写也不申请。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-member-order-history/index.js');
function mount() {
  let config, member = '11', gesture = false, depth = 0;
  const requests = [], prompts = [], effects = [], subscriptions = [];
  const app = { globalData: {}, getUserID: () => member, getPageSize: () => 10,
    getRequestErrorMessage: (_r, fallback) => fallback,
    sendRequest: request => requests.push({ ...request, member }) };
  const toast = text => effects.push(text); toast.success = toast;
  const localRequire = createRequire(file);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Component: value => { config = value; },
    require(name) {
      if (name.endsWith('/modal.js')) return { show: options => prompts.push(options) };
      if (name.endsWith('/toast.js')) return toast;
      if (name.endsWith('/loading.js')) return { show() { depth++; }, hide() { depth--; } };
      if (name.endsWith('/subscribe.js')) return { request(keys) {
        assert.equal(gesture, true); subscriptions.push(Array.from(keys));
        return Promise.resolve({ status: 'rejected' });
      } };
      return localRequire(name);
    }, console, setTimeout, clearTimeout,
  }, { filename: file });
  const page = Object.assign({}, config.methods);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.data.list = [{ id: 7 }];
  page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, prompts, effects, subscriptions,
    account(value) { member = value; }, depth: () => depth,
    detach() { config.lifetimes.detached.call(page); },
    tap() { page.cancelOrder({ currentTarget: { dataset: { id: 7, index: 0, status: 2 } } }); },
    confirm(prompt = prompts.at(-1)) { gesture = true; prompt.success({ confirm: true }); gesture = false; } };
}
for (const invalidate of ['account', 'detach']) {
  test('E05-03 stale history confirmation after ' + invalidate, () => {
    const h = mount(); h.tap();
    if (invalidate === 'account') h.account('22'); else h.detach();
    h.confirm();
    assert.equal(h.requests.length, 0); assert.equal(h.subscriptions.length, 0);
  });
  for (const outcome of ['success', 'fail']) test('E05-03 history late ' + outcome + ' after ' + invalidate, () => {
    const h = mount(); h.tap(); h.confirm();
    const old = h.requests[0];
    if (invalidate === 'account') h.account('22'); else h.detach();
    if (outcome === 'success') old.success({ code: 200, data: {} }); else old.fail({});
    old.complete();
    assert.equal(h.requests.length, 1); assert.deepEqual(h.effects, []);
    assert.equal(h.depth(), 0); assert.equal(h.page.data.cancellingId, 0);
  });
}
test('E05-03 old account completion cannot release B cancellation resources', () => {
  const h = mount(); h.tap(); h.confirm(); const old = h.requests[0];
  h.account('22'); h.tap(); h.confirm();
  assert.equal(h.requests[1].member, '22'); assert.equal(h.depth(), 1);
  old.complete(); old.success({ code: 200, data: {} }); old.fail({});
  assert.equal(h.depth(), 1); assert.equal(h.page.data.cancellingId, 7);
  assert.equal(h.page._cancelRequestInFlight, true); assert.deepEqual(h.effects, []);
  h.requests[1].complete(); assert.equal(h.depth(), 0);
});
test('E05-03 duplicate old complete cannot release a subsequent same-account request', () => {
  const h = mount(); h.tap(); h.confirm(); const old = h.requests[0];
  old.fail({}); old.complete(); h.tap(); h.confirm();
  old.complete(); assert.equal(h.depth(), 1); assert.equal(h.page.data.cancellingId, 7);
  const cancellations = h.requests.filter(r => r.url.includes('/cancel'));
  assert.equal(cancellations.length, 2);
  assert.equal(h.requests[1].url, '/api/registration/list');
  assert.ok(h.effects.includes('取消结果暂未确认，正在刷新订单'));
  cancellations[1].complete(); assert.equal(h.depth(), 0);
});
test('E05-03 old modal completion cannot unlock a new modal', () => {
  const h = mount(); h.tap(); const old = h.prompts[0];
  h.account('22'); h.tap(); old.complete(); old.fail({}); h.confirm(old);
  assert.equal(h.page._cancelPromptOpen, true); assert.equal(h.requests.length, 0);
  h.confirm(); assert.equal(h.requests.length, 1);
  assert.deepEqual(h.subscriptions, [['refund', 'refundRejected']]);
  h.requests[0].complete();
});
test('E05-03 confirmation rejection prevents writes; refund proceeds without subscription opt-in', () => {
  const h = mount(); h.tap(); h.prompts[0].success({ confirm: false });
  assert.equal(h.requests.length, 0); h.tap(); h.confirm();
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, '/api/registration/cancel-refund');
  assert.equal(h.requests[0].data.id, 7); h.requests[0].complete();
});
for (const status of [undefined, 408, 503]) test('E05-03 unknown cancellation status refreshes actual orders: ' + status, () => {
  const h = mount(); h.tap(); h.confirm(); const request = h.requests[0];
  request.fail({}, status); request.complete();
  assert.equal(h.requests[1].url, '/api/registration/list');
  assert.deepEqual(h.effects, ['取消结果暂未确认，正在刷新订单']);
  assert.equal(h.depth(), 0); assert.equal(h.page.data.cancellingId, 0);
});
test('E05-03 free paid order keeps cancel-free confirmation', () => {
  const h = mount(); h.page.data.list = [{ id: 7, paymentStatus: 2, totalAmount: 0, payableAmount: 0 }];
  h.tap(); assert.equal(h.prompts[0].dangerKey, 'order.cancel-free');
  h.confirm(); assert.equal(h.requests[0].url, '/api/registration/cancel-refund');
  h.requests[0].complete(); assert.equal(h.depth(), 0);
});
