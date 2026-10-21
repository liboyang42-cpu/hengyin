// NOT_RUN: verification deferred to user local session.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const d = require('../../pages/publish/utils/publish/pro-editor-draft.js');
test('existing-topic revision checks preserve drafts when either baseline is unknown', () => {
  for (const [stored, current] of [['v1','v2'], ['','v2'], ['v1',''], ['','']]) {
    const values = {};
    const wx = { getStorageSync: k => values[k], setStorageSync: (k,v) => { values[k]=v; } };
    d.saveDraft(wx, { memberId:42, topicId:77, baseRevision:stored, formData:{ name:'local' } });
    const result = d.loadDraft(wx, { topicId:77 }, { memberId:42, currentBaseRevision:current });
    assert.equal(result.status, 'revision_conflict');
    assert.equal(result.envelope.formData.name, 'local');
    assert.equal(d.loadDraft(wx, { topicId:77 }, { memberId:42 }).status, 'ready');
  }
});
