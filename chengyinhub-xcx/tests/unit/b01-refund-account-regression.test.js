'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const entries = [
  ['detail', 'subpackageMember/components/cy/scene-member-order-detail/index.js', 'cancelRegistration'],
  ['history', 'subpackageMember/components/cy/scene-member-order-history/index.js', 'cancelOrder'],
  ['participation', 'subpackageMember/components/scene-member-participation-detail/index.js', 'cancelParticipation'],
];
const plain = value => JSON.parse(JSON.stringify(value));

// Execute production handlers and the real subscription helper in isolated VM contexts.
// Only platform boundaries (modal, toast, loading, transport, clock) are controlled.
function mount(file, kind = 'Component', emptyTemplates = false) {
  let definition, member = '11', phase = 'idle';
  const requests = [], dialogs = [], subscriptions = [], effects = [], timers = new Map();
  let timerId = 0;
  const app = { globalData: {}, getUserID: () => member, getPageSize: () => 10,
    getRequestErrorMessage: (_r, fallback) => fallback,
    sendRequest: options => requests.push({ ...options, sentAs: member }) };
  const wx = { getStorageSync: () => '', setNavigationBarColor() {},
    requestSubscribeMessage(options) {
      assert.equal(phase, 'confirm', 'subscription must start synchronously inside confirmation gesture');
      subscriptions.push(options);
    } };
  const sandbox = { getApp: () => app, wx, console: { warn() {}, log() {}, error() {} },
    setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
    [kind]: config => { definition = config; } };
  const subscribeModule = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/subscribe.js'), 'utf8'),
    { ...sandbox, module: subscribeModule }, { filename: 'utils/subscribe.js' });
  if (emptyTemplates) {
    subscribeModule.exports.TEMPLATES.refund = '';
    subscribeModule.exports.TEMPLATES.refundRejected = '';
  }
  const toast = text => effects.push(['toast', text]);
  toast.success = toast; toast.error = toast;
  const localRequire = createRequire(path.join(root, file));
  sandbox.require = name => {
    if (name.endsWith('/subscribe.js')) return subscribeModule.exports;
    if (name.endsWith('/modal.js')) return { show: options => dialogs.push(options) };
    if (name.endsWith('/toast.js')) return toast;
    if (name.endsWith('/loading.js')) return { show() {}, hide() {} };
    if (name.endsWith('/merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} };
    return localRequire(name);
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), sandbox, { filename: file });
  const page = Object.assign({}, kind === 'Component' ? definition.methods : definition);
  page.data = plain(definition.data);
  page.setData = patch => Object.assign(page.data, patch);
  page.triggerEvent = (...args) => effects.push(['event', ...args]);
  page._orderId = '7'; page._orderGeneration = 1; page._attached = true;
  page.data.id = 7; page.data.info = { id: 7, paymentStatus: 2, canRequestRefund: true };
  page.data.list = [{ id: 7, paymentStatus: 2 }];
  return { page, requests, dialogs, subscriptions, effects, timers,
    confirm(value = true) { phase = 'confirm'; dialogs.at(-1).success({ confirm: value }); phase = 'idle'; },
    account(value) { member = value; },
    detach() { definition.lifetimes.detached.call(page); },
    tap(handler) { page[handler]({ currentTarget: { dataset: { id: 7, index: 0, status: 2 } } }); } };
}

for (const [label, file, handler] of entries) {
  for (const outcome of ['accept', 'reject', 'empty']) {
    test(`${label}: refund dispatch precedes late subscription ${outcome}`, async () => {
      const h = mount(file, 'Component', outcome === 'empty');
      h.tap(handler);
      assert.equal(h.requests.length, 0);
      assert.equal(h.subscriptions.length, 0);
      h.confirm();
      assert.equal(h.requests.length, 1, 'unsettled subscription must not delay refund');
      assert.equal(h.requests[0].url, '/api/registration/cancel-refund');
      assert.equal(h.requests[0].data.id, 7);
      assert.equal(h.subscriptions.length, outcome === 'empty' ? 0 : 1);
      const before = plain(h.page.data);
      h.account('22');
      if (outcome !== 'empty') {
        const sub = h.subscriptions[0];
        sub.success(Object.fromEntries(sub.tmplIds.map(id => [id, outcome])));
      }
      await Promise.resolve();
      assert.equal(h.requests.length, 1, 'late subscription must not send another refund under B');
      assert.deepEqual(plain(h.page.data), before);
      assert.deepEqual(h.effects, []);
    });
  }
  for (const invalidate of ['account', 'detach']) {
    test(`${label}: stale confirmation after ${invalidate} cannot dispatch refund`, () => {
      const h = mount(file); h.tap(handler);
      if (invalidate === 'account') h.account('22'); else h.detach();
      h.confirm();
      assert.equal(h.requests.length, 0, 'stale confirmation must not write under changed owner/lifecycle');
      assert.equal(h.subscriptions.length, 0);
    });
    test(`${label}: late refund success after ${invalidate} has no business effects`, () => {
      const h = mount(file); h.tap(handler); h.confirm();
      const refund = h.requests[0];
      if (invalidate === 'account') h.account('22'); else h.detach();
      const before = plain(h.page.data);
      refund.success({ code: 200, data: { cancellationStatus: 'CANCELLED', cashRefundStatus: 'PROCESSING' } });
      assert.equal(h.requests.length, 1, 'old refund must not trigger new-owner refresh');
      assert.deepEqual(h.effects, [], 'old refund must not emit toast, refresh, back or orderchanged');
      assert.deepEqual(plain(h.page.data), before);
    });
  }
}

test('favorites: post unlike removes captured stable ID after rows reorder', () => {
  const h = mount('pages/mylike/mylike.js', 'Page');
  h.page.data.postList = [{ id: 7 }, { id: 8 }];
  h.page.postUnlike({ detail: { index: 0 } });
  h.page.data.postList = [{ id: 8 }, { id: 7 }, { id: 9 }];
  h.requests[0].success({ code: 200 });
  assert.deepEqual(plain(h.page.data.postList), [{ id: 8 }, { id: 9 }]);
});

test('favorites: A unlike completion cannot unlock B request for the same stable ID', () => {
  const h = mount('pages/mylike/mylike.js', 'Page');
  h.page.data.postList = [{ id: 7 }]; h.page.postUnlike({ detail: { index: 0 } });
  const old = h.requests[0];
  h.account('22'); h.page._syncFavoriteAccount();
  h.page.data.postList = [{ id: 7 }]; h.page.postUnlike({ detail: { index: 0 } });
  old.success({ code: 200 }); old.fail({}); old.complete();
  h.page.postUnlike({ detail: { index: 0 } });
  assert.equal(h.requests.length, 2, 'B in-flight lock must survive A complete');
  assert.deepEqual(plain(h.page.data.postList), [{ id: 7 }]);
  h.requests[1].success({ code: 200 }); h.requests[1].complete();
  assert.deepEqual(plain(h.page.data.postList), []);
  assert.deepEqual(h.effects, []);
});

test('favorites: topic confirmation opened by A cannot send after switching to B', () => {
  const h = mount('pages/mylike/mylike.js', 'Page');
  h.page.data.ztList1 = [{ id: 7 }];
  h.page.topicUnlike({ currentTarget: { dataset: { id: 7 } } });
  h.account('22'); h.page._syncFavoriteAccount(); h.confirm();
  assert.equal(h.requests.length, 0);
});
