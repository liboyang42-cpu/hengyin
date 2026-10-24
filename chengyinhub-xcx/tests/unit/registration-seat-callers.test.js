'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const seats = require('../../utils/registration-seat.js');
const verification = require('../../utils/merchant-verification.js');
const root = path.resolve(__dirname, '../..');
function method(file, name) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const match = source.match(new RegExp('^( +)' + name + '(?:\\(|: function)', 'm'));
  assert.ok(match, name); const start = match.index + match[1].length;
  const end = source.indexOf('\n' + match[1] + '},', start); assert.ok(end > start, name);
  return source.slice(start, end + match[1].length + 2);
}
const files = ['pages/merchant/index/index.js', 'subpackageMember/components/cy/profile/index.js',
  'subpackageMember/components/scene-member-participation-detail/index.js'];
for (const file of files) test(file + ' 相机开启后A→B→A不能用新登录提交旧扫码', () => {
  const scans = [], sends = [], disk = {}, app = { _featureFlagsEpoch: 0, getUserID: () => 11, getAuthorization: () => 'synthetic' };
  const wx = { scanCode: options => scans.push(options), getStorageSync: key => disk[key] };
  const page = new Function('app', 'wx', 'captureLogin', 'loginCurrent',
    'return ({' + method(file, 'goScanQR') + '});')(app, wx, seats.captureLogin, seats.loginCurrent);
  page._submitVerification = () => sends.push('sent'); page.goScanQR(); app._featureFlagsEpoch += 2;
  scans[0].success({ result: 'v1.71.activity_seat_2.1999999999999.7.signature' });
  assert.deepEqual(sends, []);
});
function lead() {
  const scans = [], requests = [], messages = [], rows = {}, haptics = [], timers = [];
  const disk = { scanCode: options => scans.push(options), getStorageSync: key => rows[key],
    setStorageSync(key, value) { rows[key] = JSON.parse(JSON.stringify(value)); }, removeStorageSync(key) { delete rows[key]; } };
  const app = { _featureFlagsEpoch: 0, getUserID: () => 11, getAuthorization: () => 'synthetic',
    sendRequest: options => requests.push(options) };
  const login = seats.captureLogin(app, disk);
  const names = ['leadVerifyMemberTicket', '_leadOperationContext', '_isLeadOperationContextCurrent', '_canUseLeadTools'];
  const page = new Function('app', 'wx', 'seatTarget', 'seatKey', 'seatRecovery', 'createVerificationWorkflow',
    'classifyVerificationOutcome', 'resolveLeadMemberTicket', 'motion', 'cyLoading', 'req',
    'return ({' + names.map(name => method('pages/play/index.js', name)).join(',') + '});')(
    app, disk, seats.seatTarget, seats.seatKey, seats.seatRecovery,
    options => verification.createVerificationWorkflow({ ...options, setTimer(fn) { timers.push(fn); return timers.length; }, clearTimer() {} }),
    verification.classifyVerificationOutcome, require('../../pages/play/utils/lead-ticket-scan.js').resolveLeadMemberTicket,
    { haptic: options => haptics.push(options) }, { show() { throw Error('seat must not own global loading'); }, hide() { throw Error('seat cannot hide global loading'); } },
    () => { throw Error('seat must use persistent workflow, not raw Promise write'); });
  Object.assign(page, { data: { activityId: 9, lead: { exists: true, isLeader: true } }, _playOwner: '11',
    _boundPlayOwner: () => '11', _sessionKey: () => 'topic:8:activity:9',
    _isPlayAccountCurrent: () => seats.loginCurrent(app, disk, login) && !page._playDisposed,
    diegetic: msg => messages.push(msg) });
  function scan(nonce = 7) { page.leadVerifyMemberTicket(); scans.at(-1).success({ result: `v1.71.topic_seat_2.1999999999999.${nonce}.signature` }); }
  return { page, scan, requests, messages, rows, app, haptics };
}
const receipt = { code: 200, msg: '该席位已核销，请勿重复提供权益', data: { seatId: 102, registrationId: 71,
  seatNo: 2, topicId: 8, activityId: 9, verifierMemberId: 11, verifiedAt: '2026-10-24T08:00:00+08:00', replayed: true } };
test('带队入口捕获原场次，丢包后刷新码只查原席位事实', () => {
  const h = lead(); h.scan(); assert.equal(h.requests[0].url, '/api/registration/scan_group_member_ticket');
  assert.equal(h.requests[0].data.activityId, 9); assert.equal(Object.keys(h.rows).length, 1);
  h.requests[0].fail(); assert.match(h.messages[0], /原席位结果待确认/); h.scan(99);
  assert.equal(h.requests[1].url, '/api/verify/seat/receipt');
  assert.deepEqual(h.requests[1].data, { registrationId: '71', seatNo: 2, activityId: '9' });
  h.requests[1].success(receipt); assert.equal(h.haptics.length, 1); assert.equal(Object.keys(h.rows).length, 0);
  assert.equal(h.requests.filter(r => r.url.includes('scan_group')).length, 1);
});
test('带队入口拒绝其他场次回执，保留UNKNOWN与原身份', () => {
  const h = lead(); h.scan(); h.requests[0].success({ code: 200, data: { ...receipt.data, activityId: 10 } }); h.scan();
  assert.equal(h.haptics.length, 0); assert.equal(h.requests[1].url, '/api/verify/seat/receipt');
  h.requests[1].success({ code: 520 }); assert.equal(Object.keys(h.rows).length, 1); assert.equal(h.haptics.length, 0);
});
test('带队入口A→B→A迟回执与离页回执不触UI或清理待办', () => {
  for (const unload of [false, true]) {
    const h = lead(); h.scan(); if (unload) { h.page._playDisposed = true; h.page._leadVerificationWorkflow.destroy(); }
    else h.app._featureFlagsEpoch += 2;
    h.requests[0].success(receipt); assert.deepEqual(h.messages, []); assert.equal(h.haptics.length, 0);
    assert.equal(Object.keys(h.rows).length, 1);
  }
});
test('优惠券旧出码调用遇多人选择信息时移交同一显式选席组件，绝不默认第一席', async () => {
  const requests = [], scenes = [], updates = [];
  const page = new Function('req', 'seatChoice', 'app', 'cyToast',
    'return ({' + method('pages/play/index.js', 'ensureTicket') + '});')(
    (...args) => { requests.push(args); return Promise.resolve({ code: 200, data: { requiresSeatSelection: true, registrationId: 71, seatCount: 3 } }); },
    seats.seatChoice, {}, () => {});
  Object.assign(page, { data: { regId: 71, ticket: {} }, _playOwner: '11', _boundPlayOwner: () => '11',
    _sessionKey: () => 'session', _isPlayAccountCurrent: () => true, setData: patch => updates.push(patch),
    openScene: (...args) => scenes.push(args) });
  page.ensureTicket(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(requests[0][2], { registrationId: 71 });
  assert.deepEqual(scenes, [['qr-ticket', { registrationId: 71 }]]);
  assert.ok(updates.some(patch => patch['ticket.code'] === ''));
});
