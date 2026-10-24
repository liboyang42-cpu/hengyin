'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const seats = require('../../utils/registration-seat.js');
const verification = require('../../utils/merchant-verification.js');
const { createSessionStore } = require('../../utils/session/session-store.js');
const root = path.resolve(__dirname, '../..');
function method(file, name) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const match = source.match(new RegExp('^( +)' + name + '(?:\\(|: function)', 'm'));
  assert.ok(match); const start = match.index + match[1].length, end = source.indexOf('\n' + match[1] + '},', start);
  assert.ok(end > start); return source.slice(start, end + match[1].length + 2);
}
function fixture() {
  const rows = {}, requests = [], results = [], timers = [];
  const wx = { getStorageSync: key => rows[key], setStorageSync(key, value) { rows[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete rows[key]; } };
  const session = createSessionStore({ wx, target: {}, now: () => 1000 });
  session.applySnapshot({ user_id: 11, authorization: 'synthetic-authorization', role: 'merchant' });
  // Execute the actual app.setUserRole delegate with the actual session-store, not a fake revision increment.
  const app = Object.assign(new Function('wx', 'return ({' + method('app.js', 'setUserRole') + '});')(wx), {
    _featureFlagsEpoch: 17, getSession: () => session, getUserID: () => session.getUserID(),
    getAuthorization: () => session.getAuthorization(), sendRequest(options) { requests.push(options); return { abort() {} }; } });
  const flow = verification.createVerificationWorkflow({ storage: wx, getOwner: () => app.getUserID(),
    setTimer(fn) { timers.push(fn); return timers.length; }, clearTimer() {} });
  const request = { url: '/api/registration/scan_dynamic_code', method: 'POST', data: { code: 'v1.71.activity_seat_2.1999999999999.7.signature' } };
  const target = seats.seatTarget(request), key = seats.seatKey(11, target);
  function context() {
    const login = seats.captureLogin(app, wx);
    return { login, recovery: seats.seatRecovery(app, target, () => seats.loginCurrent(app, wx, login)) };
  }
  function run(original) {
    return flow.run(key, done => app.sendRequest({ ...request,
      success: response => done(verification.classifyVerificationOutcome(response, 'success', undefined, request)),
      fail: () => done({ status: 'unknown' }) }), result => results.push(result), original.recovery);
  }
  const pending = () => Object.keys(rows).filter(key => key.startsWith('verification_unknown_v1:'));
  return { rows, requests, results, timers, wx, session, app, flow, request, target, key, context, run, pending };
}
const receipt = { code: 200, msg: '该席位已核销，请勿重复提供权益', data: { seatId: 102, registrationId: 71,
  seatNo: 2, topicId: null, activityId: 9, verifierMemberId: 11, verifiedAt: '2026-10-24T08:00:00+08:00', replayed: true } };

test('real app.setUserRole changes revision alone: late seat success must keep UNKNOWN and suppress callback', () => {
  const h = fixture(), original = h.context(); h.run(original);
  const authorization = h.app.getAuthorization(), issuedAt = h.wx.getStorageSync('authorization_issued_at');
  assert.equal(h.session.getRevision(), 1); assert.equal(h.pending().length, 1);
  h.app.setUserRole('club'); assert.equal(h.session.getRevision(), 2);
  assert.equal(h.app.getAuthorization(), authorization); assert.equal(h.wx.getStorageSync('authorization_issued_at'), issuedAt);
  assert.equal(h.app._featureFlagsEpoch, 17);
  h.requests[0].success(receipt);
  assert.equal(h.pending().length, 1); assert.equal(h.results.length, 0);
  assert.equal(seats.loginCurrent(h.app, h.wx, original.login), false);
  h.flow.destroy();
});

test('real role snapshot delegate also invalidates the original callback without replacing token or pending identity', () => {
  const h = fixture(), original = h.context(); h.run(original);
  const key = h.pending()[0], row = JSON.stringify(h.rows[key]);
  h.app.setUserRole({ role: 'club', permissions: [] }); h.requests[0].success(receipt);
  assert.equal(h.session.getRevision(), 2); assert.equal(JSON.stringify(h.rows[key]), row); assert.equal(h.results.length, 0);
  assert.equal(h.rows.role_permission_cache_v1.role, 'club'); h.flow.destroy();
});
test('in-flight readback after role change cannot delete original UNKNOWN or trigger a callback', () => {
  const h = fixture(), original = h.context(); h.run(original); h.requests[0].fail(); h.run(original);
  assert.equal(h.requests[1].url, '/api/verify/seat/receipt');
  const previousCallbacks = h.results.length, previousPending = JSON.stringify(h.rows[h.pending()[0]]);
  h.app.setUserRole('club'); h.requests[1].success(receipt);
  assert.equal(h.pending().length, 1); assert.equal(JSON.stringify(h.rows[h.pending()[0]]), previousPending);
  assert.equal(h.results.length, previousCallbacks); assert.equal(h.run(original), false); assert.equal(h.requests.length, 2);
  h.flow.destroy();
});
test('new explicit context after role change only reads the unchanged original target and clears only its own pending', () => {
  const h = fixture(), original = h.context(); h.run(original);
  const otherKey = 'verification_unknown_v1:other-owner-fixture'; h.rows[otherKey] = { state: 'UNKNOWN', identity: 'other-owner' };
  const otherRow = JSON.stringify(h.rows[otherKey]); h.app.setUserRole('club'); h.requests[0].success(receipt);
  assert.equal(h.run(original), false); assert.equal(h.requests.length, 1);
  const fresh = h.context(); h.run(fresh);
  assert.equal(h.requests[1].url, '/api/verify/seat/receipt'); assert.deepEqual(h.requests[1].data, h.target);
  h.requests[1].success(receipt); assert.equal(h.results.length, 1); assert.equal(h.results[0].status, 'success');
  assert.deepEqual(h.pending(), [otherKey]); assert.equal(JSON.stringify(h.rows[otherKey]), otherRow); h.flow.destroy();
});
test('role ABA still invalidates original callbacks even when owner/token/role/issue-time/feature epoch match again', () => {
  const h = fixture(), original = h.context(); h.run(original); h.app.setUserRole('club'); h.app.setUserRole('merchant');
  assert.equal(h.session.getUserRole(), 'merchant'); assert.equal(h.session.getRevision(), 3);
  assert.equal(seats.loginCurrent(h.app, h.wx, original.login), false); h.requests[0].success(receipt);
  assert.equal(h.pending().length, 1); assert.equal(h.results.length, 0); h.flow.destroy();
});
test('same account and session revision confirms normally; avatar/nickname do not change the session revision', () => {
  const h = fixture(), original = h.context(); h.run(original); const keys = h.pending();
  h.session.setAvatar('fixture-avatar'); h.session.setNickname('fixture-name');
  assert.equal(h.session.getRevision(), 1); assert.equal(seats.loginCurrent(h.app, h.wx, original.login), true);
  h.requests[0].success({ ...receipt, data: { ...receipt.data, replayed: false } });
  assert.equal(h.results.length, 1); assert.equal(h.results[0].status, 'success'); assert.equal(h.pending().length, 0);
  assert.equal(keys.length, 1); h.flow.destroy();
});
test('missing/unreadable/malformed session revision fails closed without persisting a replacement identity', () => {
  const h = fixture(), original = h.context(); const before = JSON.stringify(h.rows);
  for (const getSession of [undefined, () => { throw Error('unavailable'); }, () => ({}),
    ...[undefined, null, -1, NaN, '1'].map(revision => () => ({ getRevision: () => revision }))]) {
    const app = { ...h.app, getSession };
    assert.equal(seats.captureLogin(app, h.wx), null); assert.equal(seats.loginCurrent(app, h.wx, original.login), false);
  }
  assert.equal(JSON.stringify(h.rows), before); h.flow.destroy();
});

function lead(h) {
  const scans = [], messages = [], haptics = [];
  h.wx.scanCode = options => scans.push(options);
  const file = 'pages/play/index.js';
  const names = ['leadVerifyMemberTicket', '_leadOperationContext', '_isLeadOperationContextCurrent', '_canUseLeadTools'];
  const functions = ['seatTarget', 'seatKey', 'seatRecovery', 'captureLogin', 'loginCurrent'];
  const page = new Function('app', 'wx', ...functions, 'createVerificationWorkflow', 'classifyVerificationOutcome',
    'resolveLeadMemberTicket', 'motion', 'cyLoading', 'req', 'return ({' + names.map(name => method(file, name)).join(',') + '});')(
      h.app, h.wx, ...functions.map(name => seats[name]),
      options => verification.createVerificationWorkflow({ ...options, setTimer: () => 1, clearTimer() {} }),
      verification.classifyVerificationOutcome, require('../../pages/play/utils/lead-ticket-scan.js').resolveLeadMemberTicket,
      { haptic: value => haptics.push(value) }, { show() { throw Error('seat must not own global loading'); }, hide() { throw Error('seat cannot hide other loading'); } },
      () => { throw Error('seat must not use raw legacy POST'); });
  Object.assign(page, { data: { activityId: 9, lead: { exists: true, isLeader: true } }, _playOwner: '11',
    _boundPlayOwner: () => '11', _sessionKey: () => 'topic:8:activity:9',
    // Deliberately keep the old play-level guard true: the seat-level revision fence must protect this path.
    _isPlayAccountCurrent: () => true, diegetic: message => messages.push(message) });
  const scan = () => { page.leadVerifyMemberTicket(); scans.at(-1).success({ result: 'v1.71.topic_seat_2.1999999999999.7.signature' }); };
  return { page, scans, scan, messages, haptics };
}
test('lead camera freezes revision before opening; role change before scan completion sends no seat command', () => {
  const h = fixture(), l = lead(h); l.page.leadVerifyMemberTicket(); h.app.setUserRole('club');
  l.scans[0].success({ result: 'v1.71.topic_seat_2.1999999999999.7.signature' });
  assert.equal(h.requests.length, 0); assert.equal(h.pending().length, 0); assert.deepEqual(l.messages, []); h.flow.destroy();
});
test('lead late seat write receipt after role revision cannot clear pending, show success, or haptic', () => {
  const h = fixture(), l = lead(h); l.scan(); h.app.setUserRole('club');
  h.requests[0].success({ ...receipt, data: { ...receipt.data, topicId: 8 } });
  assert.equal(h.pending().length, 1); assert.deepEqual(l.messages, []); assert.deepEqual(l.haptics, []);
  l.page._leadVerificationWorkflow.destroy(); h.flow.destroy();
});
test('lead readback uses the same revision fence; a new explicit scan reads the original seat rather than resending', () => {
  const h = fixture(), l = lead(h); l.scan(); h.requests[0].fail(); l.scan();
  assert.equal(h.requests[1].url, '/api/verify/seat/receipt'); const count = l.messages.length;
  h.app.setUserRole('club'); h.requests[1].success({ ...receipt, data: { ...receipt.data, topicId: 8 } });
  assert.equal(l.messages.length, count); assert.equal(h.pending().length, 1); assert.equal(l.haptics.length, 0);
  l.scan(); assert.equal(h.requests[2].url, '/api/verify/seat/receipt');
  assert.deepEqual(h.requests[2].data, { registrationId: '71', seatNo: 2, activityId: '9' });
  h.requests[2].success({ ...receipt, data: { ...receipt.data, topicId: 8 } });
  assert.equal(h.pending().length, 0); assert.equal(l.haptics.length, 1);
  assert.equal(h.requests.filter(r => r.url === '/api/registration/scan_group_member_ticket').length, 1);
  l.page._leadVerificationWorkflow.destroy(); h.flow.destroy();
});
test('legacy ticket issuer cannot open a new seat scene from a response belonging to an old role revision', async () => {
  const h = fixture(), requests = [], scenes = [];
  const page = new Function('app', 'wx', 'captureLogin', 'loginCurrent', 'seatChoice', 'req',
    'return ({' + method('pages/play/index.js', 'ensureTicket') + '});')(
      h.app, h.wx, seats.captureLogin, seats.loginCurrent, seats.seatChoice,
      () => new Promise(resolve => requests.push(resolve)));
  Object.assign(page, { data: { regId: 71, ticket: {} }, _playOwner: '11', _boundPlayOwner: () => '11',
    _sessionKey: () => 'original', _isPlayAccountCurrent: () => true, setData() {}, openScene: (...args) => scenes.push(args) });
  page.ensureTicket(); h.app.setUserRole('club');
  requests[0]({ code: 200, data: { registrationId: 71, seatCount: 3, requiresSeatSelection: true } });
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(scenes, []); h.flow.destroy();
});

test('real role change invalidates issuer callbacks and retry without selecting another seat or issuing another code', () => {
  const h = fixture(); let definition;
  const file = path.join(root, 'components/cy/scene-qr-ticket/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component: value => { definition = value; }, getApp: () => h.app,
    wx: h.wx, require: id => require(path.resolve(path.dirname(file), id)), Date, setInterval: () => 1, clearInterval() {} });
  const page = { ...definition.methods, data: { ...definition.data, registrationId: '71' }, _attached: true,
    setData(patch) { Object.assign(this.data, patch); } };
  page.load(); h.requests[0].success({ code: 200, data: { requiresSeatSelection: true, registrationId: 71, seatCount: 3 } });
  page.inputSeat({ detail: { value: '2' } }); page.confirmSeat();
  assert.equal(h.requests[1].data.seatNo, 2); h.app.setUserRole('club');
  h.requests[1].success({ code: 200, data: { registrationId: 71, seatId: 102, seatNo: 2, seatStatus: 0,
    code: h.request.data.code, qrcodeUrl: 'fixture-qr', expiresAt: Date.now() + 300000 } });
  assert.equal(page.data.code, ''); assert.equal(page.data.state, 'loading');
  page.retry(); assert.equal(h.requests.length, 2); assert.equal(page.data.seatNo, 2);
  assert.equal(page.data.state, 'error'); assert.match(page.data.errorText, /登录状态已变化/); h.flow.destroy();
});

for (const [file, name] of [
  ['pages/club/detail/index.js', 'submitClubScan'],
  ['pages/merchant/index/index.js', '_submitVerification'],
  ['subpackageMember/components/cy/profile/index.js', '_submitVerification'],
  ['subpackageMember/components/scene-member-participation-detail/index.js', '_submitVerification'],
]) test(file + ' actual adapter suppresses late receipt after real app.setUserRole and retains original pending', () => {
  const h = fixture(), messages = [];
  const functions = ['seatTarget', 'seatKey', 'seatRecovery', 'captureLogin', 'loginCurrent'];
  const loading = { show() { throw Error('seat cannot own global loading'); }, hide() { throw Error('seat cannot hide global loading'); } };
  const page = new Function('app', 'wx', ...functions, 'createVerificationWorkflow', 'classifyVerificationOutcome',
    'cyToast', 'modal', 'loading', 'cyLoading', 'offerVerificationReadback',
    'return ({' + method(file, name) + '});')(
      h.app, h.wx, ...functions.map(name => seats[name]),
      options => verification.createVerificationWorkflow({ ...options, setTimer: () => 1, clearTimer() {} }),
      verification.classifyVerificationOutcome, message => messages.push(message), { show: value => messages.push(value) }, loading, loading, () => {});
  page.data = { id: '71', clubId: 7 };
  if (name === 'submitClubScan') page[name](h.request.url, { code: h.request.data.code, data: h.request.data });
  else page[name]('original-raw-code', '核销中', h.request, response => messages.push(response));
  assert.equal(h.requests.length, 1); const before = JSON.stringify(h.rows[h.pending()[0]]);
  h.app.setUserRole('club'); h.requests[0].success(receipt);
  assert.equal(JSON.stringify(h.rows[h.pending()[0]]), before); assert.equal(h.pending().length, 1); assert.deepEqual(messages, []);
  (page._scanWorkflow || page._verificationWorkflow).destroy(); h.flow.destroy();
});

test('legacy issuer with unreadable mandatory session revision sends no request and changes no ticket state', () => {
  const h = fixture(), requests = [], updates = []; h.app.getSession = () => { throw Error('unavailable'); };
  const page = new Function('app', 'wx', 'captureLogin', 'loginCurrent', 'req',
    'return ({' + method('pages/play/index.js', 'ensureTicket') + '});')(
      h.app, h.wx, seats.captureLogin, seats.loginCurrent, () => { requests.push('sent'); return Promise.resolve({}); });
  Object.assign(page, { data: { regId: 71, ticket: {} }, _playOwner: '11', _boundPlayOwner: () => '11',
    _sessionKey: () => 'original', _isPlayAccountCurrent: () => true, setData: patch => updates.push(patch) });
  page.ensureTicket(); assert.deepEqual(requests, []); assert.deepEqual(updates, []); h.flow.destroy();
});
