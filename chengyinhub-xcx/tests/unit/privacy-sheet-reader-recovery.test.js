'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/shezhi/components/privacy-sheet/index.js');
function mount() {
  let definition, owner = '1', token = 'a', resolve, reject;
  const toasts = [], changes = [], revoked = [];
  const pending = new Promise((done, fail) => { resolve = done; reject = fail; });
  const app = { getUserID: () => owner, getAuthorization: () => token, recordConsent: () => pending };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component: d => { definition = d; }, getApp: () => app,
    wx: {}, require: name => name.endsWith('/toast.js') ? value => toasts.push(value)
      : name.endsWith('/roam-presence-privacy.js') ? { setSharing: () => pending }
      : name.endsWith('/bg-tracker.js') ? { revoke: purpose => revoked.push(purpose) } : createRequire(file)(name) });
  const component = { ...definition.methods, data: { ...definition.data }, setData(patch) { changes.push(patch); Object.assign(this.data, patch); } };
  return { component, definition, toasts, changes, revoked, resolve, reject,
    switch(kind) { if (kind === 'owner') owner = '2'; else token = 'b'; } };
}
for (const action of ['withdrawRoamLocationConsent', 'disablePresenceSharing']) {
  for (const outcome of ['resolve', 'reject']) for (const transition of ['owner', 'token', 'hide', 'detach']) test(action + ' ignores late ' + outcome + ' after ' + transition, async () => {
    const h = mount(); h.component[action]();
    await Promise.resolve(); // Let the original request dispatch before changing reader/lifecycle.
    if (transition === 'hide') h.definition.pageLifetimes?.hide.call(h.component);
    else if (transition === 'detach') h.definition.lifetimes?.detached.call(h.component);
    else h.switch(transition);
    const before = h.changes.length;
    h[outcome](new Error('offline')); await new Promise(r => setImmediate(r));
    assert.equal(h.toasts.length, 0); assert.equal(h.changes.length, before);
  });
}
test('same reader receives confirmed revoke and can use the sheet after reentry', async () => {
  const h = mount(); h.component.withdrawRoamLocationConsent();
  assert.deepEqual(h.revoked, ['roam']); h.resolve(); await new Promise(r => setImmediate(r));
  assert.equal(h.toasts[0], '已撤回漫游定位同意'); assert.equal(h.component.data.submitting, false);
  h.definition.pageLifetimes.hide.call(h.component); h.definition.pageLifetimes.show.call(h.component);
  assert.equal(h.component.data.submitting, false);
});
