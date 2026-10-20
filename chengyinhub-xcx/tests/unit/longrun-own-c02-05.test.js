// NOT_RUN: local verification deferred. Guards the existing L05 contract without changing it.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const d = require('../../pages/publish/utils/publish/pro-editor-draft.js');
test('restoring and autosaving the same UNKNOWN envelope preserves its original publish identity', () => {
  for (const identity of [{ draftUuid:'new-topic' }, { topicId:77 }]) {
    const values = {};
    const wx = { getStorageSync:k=>values[k], setStorageSync:(k,v)=>{values[k]=v;} };
    const attempt = { status:'UNKNOWN', topicId:identity.topicId ? '77' : '' };
    d.saveDraft(wx, { ...identity, memberId:42, publishAttempt:attempt, formData:{ name:'local' } });
    const restored = d.loadDraft(wx, identity, { memberId:42 }).envelope;
    restored.formData.name = 'later local edits';
    d.saveDraft(wx, { ...restored, ...identity });
    const again = d.loadDraft(wx, identity, { memberId:42 }).envelope;
    assert.deepEqual(again.publishAttempt, attempt);
    assert.equal(again.formData.name, 'later local edits');
    assert.equal(again.draftUuid || again.topicId, identity.draftUuid || identity.topicId);
  }
});
