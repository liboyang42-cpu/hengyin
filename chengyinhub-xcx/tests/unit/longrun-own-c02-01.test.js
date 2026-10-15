// NOT_RUN: deferred to the next local verification session.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const drafts = require('../../utils/publish/pro-editor-draft.js');
function storage() {
  const values = {};
  return { values, getStorageSync: k => values[k], setStorageSync: (k, v) => { values[k] = v; } };
}
test('same identity remains independently recoverable for two accounts, including legacy fallback', () => {
  for (const identity of [{ topicId: 77 }, { draftUuid: 'shared' }]) {
    const wx = storage();
    wx.values[drafts.draftKeyFor(identity)] = { memberId: 42, formData: { name: 'legacy A' } };
    assert.equal(drafts.loadDraft(wx, identity, { memberId: 42 }).envelope.formData.name, 'legacy A');
    for (const memberId of [42, 43]) drafts.saveDraft(wx, { ...identity, memberId, formData: { name: String(memberId) } });
    for (const memberId of [42, 43]) assert.equal(drafts.loadDraft(wx, identity, { memberId }).envelope.formData.name, String(memberId));
    assert.equal(wx.values[drafts.draftKeyFor(identity)].formData.name, 'legacy A');
  }
});
