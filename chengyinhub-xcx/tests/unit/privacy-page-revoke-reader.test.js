'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/privacy/index.js');
function mount() {
  let definition, owner = '1', token = 'a';
  const requests = [], toasts = [], changes = [], revoked = [];
  const app = { getUserID: () => owner, getAuthorization: () => token,
    hasPendingPrivacyAuthorization: () => false,
    recordConsent(input) { return new Promise((resolve, reject) => requests.push({ input, resolve, reject })); } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { definition = d; }, getApp: () => app,
    wx: {}, require: name => name.endsWith('/toast.js') ? value => toasts.push(value)
      : name.endsWith('/modal.js') ? {} : name.endsWith('/usage-consent.js') ? { VERSIONS: {} }
      : name.endsWith('/roam-presence-privacy.js') ? {}
      : name.endsWith('/bg-tracker.js') ? { revoke: purpose => { revoked.push(purpose); return Promise.resolve({ ok: true }); }, stopState: () => 'confirmed', hasOwner: () => false, isRunning: () => false }
      : createRequire(file)(name) });
  const page = { ...definition, data: { ...definition.data }, setData(patch) { changes.push(patch); Object.assign(this.data, patch); } };
  // This suite exercises the real revoke and lifecycle methods, without sending media requests.
  page.loadOwnMedia = () => {};
  return { page, requests, toasts, changes, revoked, switch(kind) { if (kind === 'owner') owner = '2'; else token = 'b'; } };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
for (const result of ['resolve', 'reject']) for (const transition of ['owner', 'token', 'onHide', 'onUnload']) {
  test('revoke ignores ' + result + ' after ' + transition, async () => {
    const h = mount(); h.page.withdrawRoamLocationConsent();
    assert.deepEqual(h.revoked, ['roam']); assert.equal(h.requests.length, 1);
    if (transition.startsWith('on')) h.page[transition](); else h.switch(transition);
    const before = h.changes.length;
    h.requests[0][result](new Error('offline')); await settle();
    assert.equal(h.toasts.length, 0); assert.equal(h.changes.length, before);
  });
}
test('same reader failure preserves retry and immediate local stop', async () => {
  const h = mount(); h.page.withdrawRoamLocationConsent(); h.requests[0].reject(new Error('offline')); await settle();
  assert.equal(h.page.data.submitting, false); assert.equal(h.page._failedAction, 'revoke');
  assert.match(h.page.data.submitError, /待确认/); h.page.retrySubmit();
  assert.equal(h.requests.length, 2); assert.equal(h.requests[1].input.eventType, 'REVOKE');
  h.requests[1].resolve(); await settle(); assert.equal(h.toasts.length, 1);
});
test('hide/show unlocks original consent retry; old reply cannot settle new operation', async () => {
  const h = mount(); h.page.withdrawRoamLocationConsent(); h.page.onHide(); h.page.onShow();
  assert.equal(h.page.data.submitting, false); assert.equal(h.page._failedAction, 'revoke');
  h.page.retrySubmit(); assert.equal(h.requests.length, 2);
  h.requests[0].resolve(); await settle(); assert.equal(h.page.data.submitting, true); assert.equal(h.toasts.length, 0);
  h.requests[1].resolve(); await settle(); assert.equal(h.page.data.submitting, false); assert.equal(h.toasts.length, 1);
});
test('other reader reentry clears old revoke feedback without replay', async () => {
  const h = mount(); h.page.withdrawRoamLocationConsent(); h.requests[0].reject(new Error('offline')); await settle();
  h.page.onHide(); h.switch('owner'); h.page.onShow();
  assert.equal(h.page.data.submitError, ''); assert.equal(h.page._failedAction, '');
  assert.equal(h.requests.length, 1);
});
