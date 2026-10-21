'use strict';
// Lifecycle regression extracted from public PR #33; subscriptions stay disabled.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageMember/components/scene-member-participation-detail/index.js');
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
  page.data.id = 7; page.data.info = { id: 7, paymentStatus: 2 };
  page.triggerEvent = name => effects.push('event:' + name);
  page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, prompts, effects, subscriptions,
    account(value) { member = value; }, depth: () => depth,
    detach() { config.lifetimes.detached.call(page); },
    tap() { page.cancelParticipation(); },
    confirm(prompt = prompts.at(-1)) { gesture = true; prompt.success({ confirm: true }); gesture = false; } };
}
for (const invalidate of ['account', 'detach', 'record']) {
  test('E05-04 stale participation confirmation after ' + invalidate, () => {
    const h = mount(); h.tap();
    if (invalidate === 'account') h.account('22');
    else if (invalidate === 'detach') h.detach();
    else h.page._applyRecordId('8');
    h.confirm();
    assert.equal(h.requests.filter(r => r.url.includes('/cancel')).length, 0);
    assert.equal(h.subscriptions.length, 0);
  });
  for (const result of ['success', 'fail']) test('E05-04 late ' + result + ' after ' + invalidate, () => {
    const h = mount(); h.tap(); h.confirm(); const old = h.requests[0];
    if (invalidate === 'account') h.account('22');
    else if (invalidate === 'detach') h.detach();
    else h.page._applyRecordId('8');
    if (result === 'success') old.success({ code: 200, data: {} }); else old.fail({});
    old.complete();
    assert.deepEqual(h.effects, []); assert.equal(h.depth(), 0);
  });
}
test('E05-04 old completion cannot release new account request', () => {
  const h = mount(); h.tap(); h.confirm(); const old = h.requests[0];
  h.account('22'); h.tap(); h.confirm();
  old.complete(); old.success({ code: 200 }); old.fail({});
  assert.equal(h.depth(), 1); assert.deepEqual(h.effects, []);
  h.requests[1].complete(); assert.equal(h.depth(), 0);
});
test('E05-04 record change clears old paid snapshot until new detail arrives', () => {
  const h = mount(); h.tap(); const old = h.prompts[0];
  h.page._applyRecordId('8'); h.tap(); h.confirm(old);
  assert.equal(h.prompts.length, 1); assert.equal(h.subscriptions.length, 0);
  const read = h.requests[0];
  read.success({ code: 200, data: { id: 8, paymentStatus: 0 } });
  h.tap(); h.confirm();
  assert.equal(h.requests[1].url, '/api/registration/cancel');
  assert.equal(h.requests[1].data.id, '8'); assert.equal(h.subscriptions.length, 0);
  h.requests[1].complete();
});
test('E05-04 repeated taps and stale modal completion do not duplicate or unlock', () => {
  const h = mount(); h.tap(); h.tap(); assert.equal(h.prompts.length, 1);
  const old = h.prompts[0]; old.success({ confirm: false }); h.tap();
  old.complete(); old.fail({}); h.confirm(old);
  assert.equal(h.page._cancelPromptOpen, true); assert.equal(h.requests.length, 0);
  h.confirm(); h.tap(); assert.equal(h.requests.length, 1);
  assert.deepEqual(h.subscriptions, []);
  h.requests[0].complete(); h.tap(); h.confirm();
  h.requests[0].complete(); assert.equal(h.depth(), 1); h.requests[1].complete();
});
for (const status of [undefined, 408, 503]) test('E05-04 unknown cancellation status refreshes actual detail: ' + status, () => {
  const h = mount(); h.tap(); h.confirm(); const request = h.requests[0];
  request.fail({}, status); request.complete();
  assert.equal(h.requests[1].url, '/api/registration/info');
  assert.equal(h.requests[1].data.id, 7);
  assert.deepEqual(h.effects, ['取消结果暂未确认，正在刷新订单', 'event:refresh']);
  assert.equal(h.depth(), 0);
});
test('E05-04 free paid registration keeps cancel-free confirmation', () => {
  const h = mount(); h.page.data.info = { paymentStatus: 2, totalAmount: 0, payableAmount: 0 };
  h.tap(); assert.equal(h.prompts[0].dangerKey, 'order.cancel-free');
  h.confirm(); assert.equal(h.requests[0].url, '/api/registration/cancel-refund');
  h.requests[0].complete(); assert.equal(h.depth(), 0);
});
