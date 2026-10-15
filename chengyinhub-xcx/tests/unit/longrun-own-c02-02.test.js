// NOT_RUN: local verification deferred by user.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const d = require('../../utils/publish/pro-editor-draft.js');
test('wrong owner cannot delete scoped or legacy envelopes, and own delete removes stale legacy copy', () => {
  const values = {};
  const wx = { getStorageSync: k => values[k], setStorageSync: (k,v) => { values[k]=v; }, removeStorageSync: k => { delete values[k]; } };
  const identity = { draftUuid: 'shared' };
  const ownKey = d.saveDraft(wx, { ...identity, memberId: 42, formData: { name: 'A' } });
  values[d.draftKeyFor(identity)] = values[ownKey];
  assert.equal(d.removeDraft(wx, identity, { memberId: 43 }), false);
  assert.equal(d.loadDraft(wx, identity, { memberId: 42 }).envelope.formData.name, 'A');
  assert.equal(d.readActiveNewDraft(wx, 42), 'shared');
  assert.equal(d.removeDraft(wx, identity, { memberId: 42 }), true);
  assert.equal(d.loadDraft(wx, identity, { memberId: 42 }).status, 'missing');
  assert.equal(d.readActiveNewDraft(wx, 42), '');
});
