const test = require('node:test');
const assert = require('node:assert/strict');
const drafts = require('../../utils/publish/pro-editor-draft.js');

test('广场主包草稿仍能按原槽位往返，其他账号不能恢复', () => {
  const values = {};
  const storage = {
    setStorageSync: (key, value) => { values[key] = value; },
    getStorageSync: key => values[key],
    removeStorageSync: key => { delete values[key]; },
  };
  const identity = { topicId: 'square-club_4' };
  drafts.saveDraft(storage, { memberId: 7, topicId: identity.topicId,
    formData: { title: '保留正文', chapters: [] } });
  assert.equal(drafts.loadDraft(storage, identity, { memberId: 8 }).status, 'member_mismatch');
  assert.equal(drafts.loadDraft(storage, identity, { memberId: 7 }).envelope.formData.title, '保留正文');
  drafts.removeDraft(storage, identity, { memberId: 7 });
  assert.equal(drafts.loadDraft(storage, identity, { memberId: 7 }).status, 'missing');
});
