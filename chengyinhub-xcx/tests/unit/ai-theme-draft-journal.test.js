const { test } = require('node:test');
const assert = require('node:assert/strict');
const journal = require('../../utils/ai-theme-draft-journal.js');
const PREPARE_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7';
const RID = 'td2_0123456789abcdef0123456789abcdef';
function storage() {
  const values = {};
  return { values, getStorageInfoSync: () => ({ keys: Object.keys(values) }),
    getStorageSync: key => values[key] === undefined ? '' : values[key], setStorageSync: (key, value) => { values[key] = value; } };
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

test('confirmed server identity and unknown execution cannot be replaced or reset to prepared', () => {
  const wx = storage();
  const unknown = { ...prepared(), requestId: RID, stage: 'KNOWN', state: 'UNKNOWN' };
  assert.equal(journal.save(wx, '7', unknown, null).ok, true);
  for (const next of [{ ...unknown, requestId: 'td2_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' },
    { ...unknown, createdAt: 101 }, { ...unknown, state: 'PREPARED', stage: 'PREPARED' },
    { ...prepared(), prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }])
    assert.equal(journal.save(wx, '7', next, unknown).ok, false);
  assert.deepEqual(journal.load(wx, '7').value, unknown);
});

test('known terminal facts may update availability but cannot become unknown or a different terminal fact', () => {
  const wx = storage();
  const success = { ...prepared(), requestId: RID, stage: 'KNOWN', state: 'SUCCEEDED' };
  assert.equal(journal.save(wx, '7', success, null).ok, true);
  for (const state of ['UNKNOWN', 'PROCESSING', 'PREPARED', 'FAILED'])
    assert.equal(journal.save(wx, '7', { ...success, state }, success).ok, false);
  const expired = { ...success, reasonCode: 'AI_DRAFT_RESULT_EXPIRED' };
  assert.equal(journal.save(wx, '7', expired, success).ok, true);
  const newAction = { ...prepared(), prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  assert.equal(journal.save(wx, '7', newAction, expired).ok, true);
  assert.deepEqual(journal.load(wx, '7').value, newAction);
});

test('history selection preserves the previous terminal identity and refuses to displace pending work', () => {
  const selected = { ...prepared(), prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    requestId: 'td2_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', stage: 'KNOWN', state: 'SUCCEEDED', createdAt: 90 };
  const wx = storage();
  assert.equal(journal.selectHistory(wx, '7', selected, null).ok, true);
  const old = { ...prepared(), requestId: RID, stage: 'KNOWN', state: 'FAILED' };
  wx.values[journal.key('7')] = old;
  assert.equal(journal.save(wx, '7', selected, old).ok, false);
  assert.equal(journal.selectHistory(wx, '7', selected, old).ok, true);
  assert.deepEqual(journal.history(wx, '7'), { kind: 'READY', values: [old] });
  for (const pending of [prepared(), { ...old, state: 'UNKNOWN' }, { ...old, state: 'PROCESSING' },
    { ...old, stage: 'PREPARED', state: 'PREPARED' }]) {
    const other = storage(); other.values[journal.key('7')] = pending;
    assert.equal(journal.selectHistory(other, '7', selected, pending).ok, false);
    assert.deepEqual(journal.load(other, '7').value, pending);
    assert.deepEqual(journal.history(other, '7').values, []);
  }
});

test('explicit new action archives the exact unknown identity before CAS and never changes its fields', () => {
  const unknown = { ...prepared(), requestId: RID, stage: 'KNOWN', state: 'UNKNOWN' };
  const next = { ...prepared(), prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', createdAt: 101 };
  const wx = storage(); wx.values[journal.key('7')] = unknown;
  const set = wx.setStorageSync; const writes = [];
  wx.setStorageSync = (key, value) => { writes.push(key); set(key, value); };
  assert.equal(journal.save(wx, '7', next, unknown).ok, false);
  assert.equal(journal.beginConfirmedNewAction(wx, '7', next, unknown).ok, true);
  assert.deepEqual(writes, ['ai_theme_draft_v2:m7:history:' + PREPARE_ID, journal.key('7')]);
  assert.deepEqual(journal.history(wx, '7'), { kind: 'READY', values: [unknown] });
  assert.deepEqual(journal.load(wx, '7').value, next);
  assert.equal(journal.beginConfirmedNewAction(wx, '8', { ...next, memberId: '8' }, unknown).ok, false);
});

test('a timed-out prepare without a server id remains discoverable after an explicitly confirmed new action', () => {
  const wx = storage(); const old = prepared(); wx.values[journal.key('7')] = old;
  const next = { ...old, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  assert.equal(journal.beginConfirmedNewAction(wx, '7', next, old).ok, true);
  assert.deepEqual(journal.history(wx, '7'), { kind: 'READY', values: [old] });
  assert.equal(journal.selectHistory(wx, '7', old, next).ok, false);
  for (const state of ['PREPARED', 'PROCESSING', 'SUCCEEDED', 'FAILED']) {
    const other = storage(); const active = { ...old, requestId: RID, stage: 'KNOWN', state };
    other.values[journal.key('7')] = active;
    assert.equal(journal.beginConfirmedNewAction(other, '7', next, active).ok, false);
  }
});

test('archive write errors, no-ops, corruption and failed readback prohibit replacing the active identity', () => {
  const old = prepared(); const next = { ...old, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  for (const fault of ['throw', 'noop', 'readback', 'corrupt']) {
    const wx = storage(); wx.values[journal.key('7')] = old;
    const get = wx.getStorageSync; const set = wx.setStorageSync; let written = false;
    wx.setStorageSync = (key, value) => {
      if (key.includes(':history:')) {
        if (fault === 'throw') throw new Error('disk');
        if (fault === 'noop') return;
        written = true;
      }
      set(key, value);
    };
    wx.getStorageSync = key => {
      if (key.includes(':history:') && fault === 'readback' && written) throw new Error('read');
      if (key.includes(':history:') && fault === 'corrupt') return { ...old, idea: 'forbidden' };
      return get(key);
    };
    assert.equal(journal.beginConfirmedNewAction(wx, '7', next, old).ok, false, fault);
    assert.deepEqual(journal.load(wx, '7').value, old, fault);
  }
});

test('a changed active identity cannot be overwritten after archival; a failed active write can be retried safely', () => {
  const old = prepared(); const next = { ...old, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  const changed = { ...old, requestId: RID, state: 'UNKNOWN', stage: 'KNOWN' };
  const wx = storage(); wx.values[journal.key('7')] = old; const set = wx.setStorageSync;
  wx.setStorageSync = (key, value) => { set(key, value); if (key.includes(':history:')) set(journal.key('7'), changed); };
  assert.equal(journal.beginConfirmedNewAction(wx, '7', next, old).ok, false);
  assert.deepEqual(journal.load(wx, '7').value, changed);
  assert.deepEqual(journal.history(wx, '7').values, [old]);
  const retry = storage(); retry.values[journal.key('7')] = old; const write = retry.setStorageSync;
  retry.setStorageSync = (key, value) => { if (key === journal.key('7')) throw new Error('disk'); write(key, value); };
  assert.equal(journal.beginConfirmedNewAction(retry, '7', next, old).ok, false);
  assert.deepEqual(journal.load(retry, '7').value, old);
  retry.setStorageSync = write;
  assert.equal(journal.beginConfirmedNewAction(retry, '7', next, old).ok, true);
  assert.deepEqual(journal.history(retry, '7').values, [old]);
});

test('historical metadata enumeration is owner-scoped and storage failures never appear as empty history', () => {
  const wx = storage(); const old = prepared();
  wx.values['ai_theme_draft_v2:m8:history:' + PREPARE_ID] = { ...old, memberId: '8' };
  assert.deepEqual(journal.history(wx, '7'), { kind: 'READY', values: [] });
  wx.values['ai_theme_draft_v2:m7:history:wrong'] = old;
  assert.equal(journal.history(wx, '7').kind, 'CORRUPT');
  delete wx.values['ai_theme_draft_v2:m7:history:wrong'];
  wx.getStorageInfoSync = () => { throw new Error('disk'); };
  assert.equal(journal.history(wx, '7').kind, 'UNAVAILABLE');
  assert.equal(journal.history(wx, '0').kind, 'CORRUPT');
});

test('an archived prepare can bind its later confirmed server identity after the active replacement failed', () => {
  const wx = storage(); const old = prepared(); const set = wx.setStorageSync;
  const next = { ...old, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  wx.values[journal.key('7')] = old;
  wx.setStorageSync = (key, value) => { if (key === journal.key('7')) throw new Error('disk'); set(key, value); };
  assert.equal(journal.beginConfirmedNewAction(wx, '7', next, old).ok, false);
  assert.deepEqual(journal.history(wx, '7').values, [old]);
  wx.setStorageSync = set;
  const confirmed = { ...old, requestId: RID, stage: 'PREPARED', state: 'PREPARED' };
  assert.equal(journal.save(wx, '7', confirmed, old).ok, true);
  const unknown = { ...confirmed, stage: 'KNOWN', state: 'UNKNOWN' };
  assert.equal(journal.save(wx, '7', unknown, confirmed).ok, true);
  assert.equal(journal.beginConfirmedNewAction(wx, '7', next, unknown).ok, true);
  assert.deepEqual(journal.history(wx, '7').values, [unknown]);
  assert.deepEqual(journal.load(wx, '7').value, next);
});

test('archive identity binding never accepts conflicting known ids or hides a failed write or readback', () => {
  const old = prepared(); const unknown = { ...old, requestId: RID, stage: 'KNOWN', state: 'UNKNOWN' };
  const next = { ...old, prepareKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  const archiveKey = 'ai_theme_draft_v2:m7:history:' + PREPARE_ID;
  for (const fault of ['throw', 'noop', 'readback', 'different-id', 'different-created']) {
    const wx = storage(); wx.values[journal.key('7')] = unknown; wx.values[archiveKey] = old;
    if (fault === 'different-id') wx.values[archiveKey] = { ...unknown, requestId: 'td2_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' };
    if (fault === 'different-created') wx.values[archiveKey] = { ...old, createdAt: 99 };
    const get = wx.getStorageSync; const set = wx.setStorageSync; let written = false;
    wx.setStorageSync = (key, value) => {
      if (key === archiveKey) {
        if (fault === 'throw') throw new Error('disk');
        if (fault === 'noop') return;
        written = true;
      }
      set(key, value);
    };
    wx.getStorageSync = key => { if (key === archiveKey && written && fault === 'readback') throw new Error('read'); return get(key); };
    assert.equal(journal.beginConfirmedNewAction(wx, '7', next, unknown).ok, false, fault);
    assert.deepEqual(journal.load(wx, '7').value, unknown, fault);
  }
});
