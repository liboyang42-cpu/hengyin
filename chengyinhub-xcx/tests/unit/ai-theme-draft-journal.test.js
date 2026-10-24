const { test } = require('node:test');
const assert = require('node:assert/strict');
const journal = require('../../utils/ai-theme-draft-journal.js');
const PREPARE_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7';
const RID = 'td2_0123456789abcdef0123456789abcdef';
function storage() {
  const values = {};
  return { values, getStorageSync: key => values[key] === undefined ? '' : values[key], setStorageSync: (key, value) => { values[key] = value; } };
}
function prepared() {
  return { version: 2, memberId: '7', prepareKey: PREPARE_ID, requestId: null, stage: 'PREPARE_UNCONFIRMED',
    state: null, reasonCode: null, createdAt: 100 };
}

test('identity must be persisted and read back before becoming usable; it contains no input or token', () => {
  const wx = storage(); const input = prepared();
  const result = journal.save(wx, '7', input, null);
  assert.equal(result.ok, true);
  input.prepareKey = 'changed'; result.value.prepareKey = 'changed-again';
  assert.equal(journal.load(wx, '7').value.prepareKey, PREPARE_ID);
  assert.deepEqual(Object.keys(wx.values[journal.key('7')]).sort(),
    ['version', 'memberId', 'prepareKey', 'requestId', 'stage', 'state', 'reasonCode', 'createdAt'].sort());
});
test('failed or silent-no-op writes are not executable identity evidence', () => {
  for (const setStorageSync of [() => {}, () => { throw new Error('disk'); }]) {
    const wx = storage(); wx.setStorageSync = setStorageSync;
    assert.equal(journal.save(wx, '7', prepared(), null).ok, false);
    assert.equal(journal.load(wx, '7').kind, 'ABSENT');
  }
});
test('read exceptions, malformed records and unexpected sensitive fields never become absence', () => {
  const wx = storage(); wx.getStorageSync = () => { throw new Error('disk'); };
  assert.equal(journal.load(wx, '7').kind, 'UNAVAILABLE');
  for (const value of [false, 0, [], {}, { ...prepared(), idea: 'must not persist' },
    { ...prepared(), memberId: '8' }, { ...prepared(), createdAt: NaN },
    { ...prepared(), prepareKey: { toString: () => PREPARE_ID } }, { ...prepared(), requestId: RID }]) {
    const other = storage(); other.values[journal.key('7')] = value;
    assert.equal(journal.load(other, '7').kind, 'CORRUPT');
    assert.equal(journal.save(other, '7', prepared(), null).ok, false);
  }
});
test('server identity is a verified update of the original prepare identity, not a new action', () => {
  const wx = storage(); const original = prepared();
  assert.equal(journal.save(wx, '7', original, null).ok, true);
  const next = { ...original, requestId: RID, stage: 'PREPARED', state: 'PREPARED' };
  assert.equal(journal.save(wx, '7', next, original).ok, true);
  assert.equal(journal.load(wx, '7').value.requestId, RID);
  assert.equal(journal.save(wx, '7', { ...original, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }, original).ok, false);
  assert.equal(journal.load(wx, '7').value.requestId, RID);
});
test('old-account callbacks cannot save into another owner key or replace a changed journal', () => {
  const wx = storage(); const a = prepared(); const b = { ...prepared(), memberId: '8' };
  assert.equal(journal.save(wx, '7', a, null).ok, true);
  assert.equal(journal.save(wx, '8', b, null).ok, true);
  assert.equal(journal.save(wx, '8', a, b).ok, false);
  const changed = { ...a, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  wx.values[journal.key('7')] = changed;
  assert.equal(journal.save(wx, '7', { ...a, requestId: RID, stage: 'PREPARED' }, a).ok, false);
  assert.deepEqual(journal.load(wx, '8').value, b);
  assert.deepEqual(journal.load(wx, '7').value, changed);
});
test('a post-write verification error preserves the written identity for future recovery', () => {
  const wx = storage(); const read = wx.getStorageSync; let written = false;
  const set = wx.setStorageSync;
  wx.setStorageSync = (key, value) => { set(key, value); written = true; };
  wx.getStorageSync = key => { if (written) throw new Error('readback'); return read(key); };
  assert.equal(journal.save(wx, '7', prepared(), null).ok, false);
  wx.getStorageSync = read;
  assert.equal(journal.load(wx, '7').value.prepareKey, PREPARE_ID);
});
test('terminal and legacy facts retain identity without a 24-hour deletion rule or execution upgrade', () => {
  const wx = storage(); const original = prepared();
  const success = { ...original, requestId: RID, stage: 'KNOWN', state: 'SUCCEEDED', reasonCode: 'AI_DRAFT_RESULT_EXPIRED' };
  assert.equal(journal.save(wx, '7', success, null).ok, true);
  assert.deepEqual(journal.load(wx, '7').value, success);
  const legacy = { ...original, prepareKey: null, requestId: PREPARE_ID, stage: 'KNOWN', state: 'UNKNOWN' };
  assert.equal(journal.valid(legacy, '7'), true);
  assert.equal(journal.valid({ ...legacy, state: 'PREPARED' }, '7'), false);
  assert.equal(journal.isServerId(PREPARE_ID), false);
  assert.equal(journal.isServerId(RID), true);
  assert.equal(journal.key(''), ''); assert.equal(journal.key('0'), '');
});
