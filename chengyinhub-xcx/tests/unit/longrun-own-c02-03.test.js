// NOT_RUN: user deferred verification.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const d = require('../../pages/publish/utils/publish/pro-editor-draft.js');
test('pointer write failure leaves durable envelope recoverable and stale pointer resolves only own draft', () => {
  const values = {};
  const wx = { getStorageSync: k => values[k], getStorageInfoSync: () => ({ keys: Object.keys(values) }),
    setStorageSync(k,v) { if (k.startsWith('pro_editor_active_')) throw new Error('index full'); values[k] = v; },
    removeStorageSync: k => { delete values[k]; } };
  assert.doesNotThrow(() => d.saveDraft(wx, { memberId: 42, draftUuid: 'durable', formData: { name: 'retained' } }));
  const old = { memberId: 42, draftUuid: 'older', savedAt: 1, formData: {} };
  values[d.draftKeyFor({ draftUuid: 'older' }, 42)] = old;
  values.pro_editor_active_42 = 'older';
  d.saveDraft(wx, { memberId: 43, draftUuid: 'other', savedAt: Date.now() + 1, formData: {} });
  assert.equal(d.readActiveNewDraft(wx, 42), 'durable');
  assert.equal(d.loadDraft(wx, { draftUuid: 'durable' }, { memberId: 42 }).envelope.formData.name, 'retained');
  delete values[d.draftKeyFor({ draftUuid: 'durable' }, 42)];
  delete values[d.draftKeyFor({ draftUuid: 'older' }, 42)];
  assert.equal(d.readActiveNewDraft(wx, 42), '');
  assert.equal(values.pro_editor_active_42, undefined);
});
