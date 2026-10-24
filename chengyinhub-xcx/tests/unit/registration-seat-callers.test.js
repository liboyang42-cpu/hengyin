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
const files = ['pages/club/detail/index.js', 'pages/merchant/index/index.js', 'subpackageMember/components/cy/profile/index.js',
  'subpackageMember/components/scene-member-participation-detail/index.js'];
for (const file of files) test(file + ' 相机开启后A→B→A不能用新登录提交旧扫码', () => {
  const scans = [], sends = [], disk = {}, app = { _featureFlagsEpoch: 0, getUserID: () => 11, getSession: () => ({ getRevision: () => 0 }), getAuthorization: () => 'synthetic' };
  const wx = { scanCode: options => scans.push(options), getStorageSync: key => disk[key] };
  const page = new Function('app', 'wx', 'captureLogin', 'loginCurrent',
    'return ({' + method(file, file.includes('/club/') ? 'onClubScan' : 'goScanQR') + '});')(app, wx, seats.captureLogin, seats.loginCurrent);
  page.data = { clubId: 7, club: { isOwner: true } }; page.canGovern = club => club && club.isOwner;
  page._submitVerification = page.submitClubScan = () => sends.push('sent');
  page[file.includes('/club/') ? 'onClubScan' : 'goScanQR'](); app._featureFlagsEpoch += 2;
  scans[0].success({ result: 'v1.71.activity_seat_2.1999999999999.7.signature' });
  assert.deepEqual(sends, []);
});
function lead() {
  const scans = [], requests = [], messages = [], rows = {}, haptics = [], timers = [];
  const disk = { scanCode: options => scans.push(options), getStorageSync: key => rows[key],
    setStorageSync(key, value) { rows[key] = JSON.parse(JSON.stringify(value)); }, removeStorageSync(key) { delete rows[key]; } };
  const app = { _featureFlagsEpoch: 0, getUserID: () => 11, getSession: () => ({ getRevision: () => 0 }), getAuthorization: () => 'synthetic',
    sendRequest: options => requests.push(options) };
  const login = seats.captureLogin(app, disk);
  const names = ['leadVerifyMemberTicket', '_leadOperationContext', '_isLeadOperationContextCurrent', '_canUseLeadTools'];
  const page = new Function('app', 'wx', 'seatTarget', 'seatKey', 'seatRecovery', 'captureLogin', 'loginCurrent', 'createVerificationWorkflow',
    'classifyVerificationOutcome', 'resolveLeadMemberTicket', 'motion', 'cyLoading', 'req',
    'return ({' + names.map(name => method('pages/play/index.js', name)).join(',') + '});')(
    app, disk, seats.seatTarget, seats.seatKey, seats.seatRecovery, seats.captureLogin, seats.loginCurrent,
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
  const page = new Function('req', 'seatChoice', 'app', 'cyToast', 'wx', 'captureLogin', 'loginCurrent',
    'return ({' + method('pages/play/index.js', 'ensureTicket') + '});')(
    (...args) => { requests.push(args); return Promise.resolve({ code: 200, data: { requiresSeatSelection: true, registrationId: 71, seatCount: 3 } }); },
    seats.seatChoice, { getUserID: () => 11, getSession: () => ({ getRevision: () => 0 }) }, () => {}, {}, seats.captureLogin, seats.loginCurrent);
  Object.assign(page, { data: { regId: 71, ticket: {} }, _playOwner: '11', _boundPlayOwner: () => '11',
    _sessionKey: () => 'session', _isPlayAccountCurrent: () => true, setData: patch => updates.push(patch),
    openScene: (...args) => scenes.push(args) });
  page.ensureTicket(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(requests[0][2], { registrationId: 71 });
  assert.deepEqual(scenes, [['qr-ticket', { registrationId: 71 }]]);
  assert.ok(updates.some(patch => patch['ticket.code'] === ''));
});


test('俱乐部A→B→A选择递增页面代次，原相机回调不能提交到新页面上下文', () => {
  const scans = [], sends = [], app = { getUserID: () => 11, getSession: () => ({ getRevision: () => 0 }), getAuthorization: () => 'synthetic' };
  const wx = { scanCode: options => scans.push(options), getStorageSync() {} };
  const page = new Function('app', 'wx', 'captureLogin', 'loginCurrent', 'loading',
    'return ({' + ['onClubScan', 'onPickOwnerClub'].map(name => method('pages/club/detail/index.js', name)).join(',') + '});')(
      app, wx, seats.captureLogin, seats.loginCurrent, { hide() {} });
  Object.assign(page, { data: { clubId: 7, club: { isOwner: true } }, canGovern: club => club && club.isOwner,
    submitClubScan: () => sends.push('sent'), setData() {}, loadAll() {} });
  page.onClubScan();
  for (const id of [8, 7]) page.onPickOwnerClub({ currentTarget: { dataset: { id } } });
  scans[0].success({ result: 'v1.71.activity_seat_2.1999999999999.7.signature' });
  assert.deepEqual(sends, []); assert.equal(page._scanGeneration, 2);
});

test('参与详情复用组件切换记录A→B→A时，原扫码不得落入新记录代次', () => {
  const file = 'subpackageMember/components/scene-member-participation-detail/index.js';
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const lexical = source.slice(source.indexOf('function trimmedString('), source.indexOf('// H12'));
  const scans = [], sends = [], app = { getUserID: () => 11, getSession: () => ({ getRevision: () => 0 }), getAuthorization: () => 'synthetic' };
  const wx = { scanCode: options => scans.push(options), getStorageSync() {} };
  const page = new Function('app', 'wx', 'captureLogin', 'loginCurrent', 'resolveVerificationScan', 'cyLoading',
    lexical + 'return ({' + ['goScanQR', '_applyRecordId'].map(name => method(file, name)).join(',') + '});')(
    app, wx, seats.captureLogin, seats.loginCurrent, require('../../utils/verification-scan.js').resolveVerificationScan, { hide() {} });
  Object.assign(page, { data: { id: '71' }, _invalidateParticipationCancellation() {}, getData() {},
    setData(patch) { Object.assign(this.data, patch); }, _submitVerification: () => sends.push('sent') });
  page.goScanQR(); page._applyRecordId('72'); page._applyRecordId('71');
  scans[0].success({ result: 'v1.71.activity_seat_2.1999999999999.7.signature' });
  assert.deepEqual(sends, []); assert.equal(page._detailGeneration, 2);
});

test('参与详情换记录保留原UNKNOWN，迟到回包不能清新待办，返回原记录只读回执', () => {
  const file = 'subpackageMember/components/scene-member-participation-detail/index.js';
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const lexical = source.slice(source.indexOf('function trimmedString('), source.indexOf('// H12'));
  const rows = {}, requests = [], messages = [];
  const wx = { getStorageSync: key => rows[key], setStorageSync(key, value) { rows[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete rows[key]; } };
  const app = { getUserID: () => 11, getSession: () => ({ getRevision: () => 0 }), getAuthorization: () => 'synthetic', sendRequest(options) { requests.push(options); return { abort() {} }; } };
  const functions = ['seatTarget', 'seatKey', 'captureLogin', 'loginCurrent', 'seatRecovery'];
  const page = new Function('app', 'wx', 'cyLoading', 'cyToast', 'createVerificationWorkflow', 'classifyVerificationOutcome', 'offerVerificationReadback',
    ...functions, lexical + 'return ({' + ['_submitVerification', '_applyRecordId'].map(name => method(file, name)).join(',') + '});')(
      app, wx, { hide() {} }, () => {}, options => verification.createVerificationWorkflow({ ...options, setTimer: () => 1, clearTimer() {} }),
      verification.classifyVerificationOutcome, () => {}, ...functions.map(name => seats[name]));
  Object.assign(page, { data: { id: '71' }, _invalidateParticipationCancellation() {}, getData() {},
    setData(patch) { Object.assign(this.data, patch); } });
  const submit = id => page._submitVerification('ignored-raw-code', '核销中', { url: '/api/registration/scan_dynamic_code', method: 'POST',
    data: { code: `v1.${id}.activity_seat_2.1999999999999.7.signature` } }, response => messages.push(response));
  submit(71); page._applyRecordId('72'); submit(72);
  const bothPending = JSON.stringify(rows); requests[0].success(receipt);
  assert.equal(JSON.stringify(rows), bothPending); assert.deepEqual(messages, []);
  page._applyRecordId('71'); submit(71);
  assert.deepEqual(requests.map(r => r.url), ['/api/registration/scan_dynamic_code', '/api/registration/scan_dynamic_code', '/api/verify/seat/receipt']);
  assert.deepEqual(requests[2].data, { registrationId: '71', seatNo: 2 });
  requests[2].success(receipt); assert.equal(Object.keys(rows).length, 1); assert.equal(messages.at(-1).data.registrationId, 71);
});
