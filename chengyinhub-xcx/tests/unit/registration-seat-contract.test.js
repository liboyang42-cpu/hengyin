'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const seats = require('../../utils/registration-seat.js');
const { resolveVerificationScan } = require('../../utils/verification-scan.js');
const { classifyVerificationOutcome, completionReceiptMessage } = require('../../utils/merchant-verification.js');
const { createVerificationWorkflow } = require('../../utils/verification-readback.js');
const code = (seat = 2, nonce = 7, kind = 'activity') => `v1.71.${kind}_seat_${seat}.1999999999999.${nonce}.signature`;
const request = (seat = 2, nonce = 7) => ({ url: '/api/registration/scan_dynamic_code', data: { code: code(seat, nonce) } });
const receipt = changes => ({ seatId: 102, registrationId: 71, seatNo: 2, topicId: null, activityId: 9,
  verifierMemberId: 11, verifiedAt: '2026-10-24T08:00:00.000+08:00', replayed: true, registrationFirstTime: false, ...changes });
function storage() {
  const rows = {};
  return { rows, getStorageSync: key => rows[key], setStorageSync(key, value) { rows[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete rows[key]; } };
}
function workflow(disk = storage()) {
  const timers = [];
  const flow = createVerificationWorkflow({ storage: disk, getOwner: () => '11', setTimer(fn) { timers.push(fn); return timers.length; }, clearTimer() {} });
  const target = seats.seatTarget(request()), key = seats.seatKey('11', target);
  let current = true, startCount = 0, finishWrite;
  const reads = [], results = [];
  const context = { target, isCurrent: () => current, readReceipt(original, done) { reads.push({ original, done }); } };
  const run = (ctx = context) => flow.run(key, done => { startCount++; finishWrite = done; }, result => results.push(result), ctx);
  return { disk, flow, key, target, reads, results, context, run, timers,
    invalidate() { current = false; }, get starts() { return startCount; }, finish(result) { finishWrite(result); } };
}
function component() {
  const file = path.resolve(__dirname, '../../components/cy/scene-qr-ticket/index.js');
  let definition, owner = '11';
  const disk = storage(), requests = [], polls = [], events = [], intervals = [];
  // 发码请求单独记;码亮着时的核销轮询(探店完局面)另记,不打乱发码序号
  const app = { getSession: () => ({ getRevision: () => 0 }), _loginEpoch: 0, getUserID: () => owner, getAuthorization: () => 'fixture-auth-' + owner,
    sendRequest(options) { (options.url === '/api/verify/dyncode/issue' ? requests : polls).push(options); } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component(value) { definition = value; }, getApp: () => app, wx: disk,
    require: id => require(path.resolve(path.dirname(file), id)), Date, setInterval: (fn) => { intervals.push(fn); return intervals.length; }, clearInterval() {} });
  const page = { ...definition.methods, data: { ...definition.data, registrationId: '71' }, _attached: true,
    setData(patch) { Object.assign(this.data, patch); }, triggerEvent(name, detail) { events.push({ name, detail }); } };
  return { page, requests, polls, events, intervals, app, disk, switchOwner(value) { owner = value; app._loginEpoch++; } };
}
const choice = { code: 200, data: { requiresSeatSelection: true, registrationId: 71, seatCount: 3 } };
const issued = (seat = 2) => ({ code: 200, data: { registrationId: 71, seatId: 100 + seat, seatNo: seat,
  seatStatus: 0, code: code(seat), qrcodeUrl: 'data:image/png;base64,fixture', expiresAt: Date.now() + 300000 } });

test('activity seats use existing signed dynamic route; topic seats require the real group entry', () => {
  assert.equal(resolveVerificationScan(code()).url, '/api/registration/scan_dynamic_code');
  assert.equal(resolveVerificationScan(code(2, 7, 'topic')).kind, 'invalid');
  const group = { url: '/api/registration/scan_group_member_ticket', data: { code: code(2, 7, 'topic'), activityId: 9 } };
  assert.deepEqual(seats.seatTarget(group), { registrationId: '71', seatNo: 2, activityId: '9' });
  assert.equal(seats.seatTarget({ ...group, data: { ...group.data, activityId: null } }), null);
});

test('QR refresh keeps the original seat key; another seat is a distinct identity', () => {
  assert.equal(seats.seatKey(11, seats.seatTarget(request())), seats.seatKey(11, seats.seatTarget(request(2, 99))));
  assert.notEqual(seats.seatKey(11, seats.seatTarget(request())), seats.seatKey(11, seats.seatTarget(request(3))));
});

test('canonical actual seat receipt succeeds; replay message never calls it a chapter', () => {
  assert.equal(classifyVerificationOutcome({ code: 200, data: receipt() }, 'success', 200, request()).status, 'success');
  assert.equal(completionReceiptMessage({ code: 200, data: receipt() }), '该席位已核销，请勿重复提供权益');
});
for (const change of [{ registrationId: 72 }, { seatNo: 3 }, { seatId: null }, { activityId: null },
  { verifierMemberId: null }, { verifiedAt: null }, { verifiedAt: 'unknown' }, { replayed: 'true' }]) {
  test('forged/incomplete seat receipt remains UNKNOWN: ' + JSON.stringify(change), () => {
    assert.equal(classifyVerificationOutcome({ code: 200, msg: '核销成功', data: receipt(change) }, 'success', 200, request()).status, 'unknown');
  });
}
test('group receipt must prove the original activity; generic 200 never proves a seat', () => {
  const group = { url: '/api/registration/scan_group_member_ticket', data: { code: code(), activityId: 8 } };
  assert.equal(classifyVerificationOutcome({ code: 200, data: receipt() }, 'success', 200, group).status, 'unknown');
  assert.equal(classifyVerificationOutcome({ code: 200, msg: '核销成功' }, 'success', 200, request()).status, 'unknown');
});
test('UNKNOWN only reads original seat and resets only after the complete committed receipt', () => {
  const h = workflow(); h.run(); h.finish({ status: 'unknown' }); h.run();
  assert.equal(h.starts, 1); assert.deepEqual(h.reads[0].original, h.target);
  h.reads[0].done({ code: 520 }); assert.equal(h.flow.isBusy(h.key), true);
  h.run(); h.reads[1].done({ code: 200, data: receipt({ seatNo: 3 }) });
  assert.equal(Object.keys(h.disk.rows).length, 1); assert.equal(h.flow.isBusy(h.key), true);
  h.run(); h.reads[2].done({ code: 200, data: receipt() });
  assert.equal(h.results.at(-1).status, 'success'); assert.equal(h.flow.isBusy(h.key), false);
  assert.equal(Object.keys(h.disk.rows).length, 0); assert.equal(h.starts, 1);
});
test('restart preserves UNKNOWN and the original group; another group cannot replace it', () => {
  const first = workflow(); const original = { ...first.context, target: { ...first.target, activityId: '9' } };
  first.run(original); first.flow.destroy();
  const second = workflow(first.disk); second.run({ ...second.context, target: { ...second.target, activityId: '10' } });
  assert.equal(second.starts, 0); assert.equal(second.reads.length, 0);
  second.run({ ...second.context, target: { ...second.target, activityId: '9' } });
  assert.deepEqual(second.reads[0].original, original.target); assert.equal(second.starts, 0);
});
test('storage write/readback failure prevents every seat write', () => {
  for (const silentDrop of [false, true]) {
    const disk = storage(); disk.setStorageSync = () => { if (!silentDrop) throw Error('disk unavailable'); };
    const h = workflow(disk); h.run(); assert.equal(h.starts, 0); assert.equal(h.results[0].status, 'blocked');
  }
});
test('late account/page readback cannot remove pending or publish UI', () => {
  const h = workflow(); h.run(); h.finish({ status: 'unknown' }); h.run(); h.invalidate();
  const count = h.results.length; h.reads[0].done({ code: 200, data: receipt() });
  assert.equal(h.results.length, count); assert.equal(Object.keys(h.disk.rows).length, 1);
});
test('late original readback cannot delete a new attempt with the same seat key', () => {
  const a = workflow(); a.run(); a.finish({ status: 'unknown' }); a.run();
  const b = workflow(a.disk); b.run(); b.reads[0].done({ code: 200, data: receipt() }); b.run();
  const newRow = JSON.stringify(a.disk.rows); a.reads[0].done({ code: 200, data: receipt() });
  assert.equal(JSON.stringify(a.disk.rows), newRow); assert.equal(a.results.at(-1).status, 'unknown');
});
test('seat UNKNOWN/readback timeouts retain original facts; no write retry', () => {
  const h = workflow(); h.run(); h.timers[0](); h.run(); h.timers[1]();
  assert.equal(h.starts, 1); assert.equal(Object.keys(h.disk.rows).length, 1); assert.equal(h.results.at(-1).status, 'unknown');
});
test('multi-seat issuer sends no seat before explicit valid selection', () => {
  const h = component(); h.page.load(); assert.deepEqual(JSON.parse(JSON.stringify(h.requests[0].data)), { registrationId: '71' });
  h.requests[0].success(choice); assert.equal(h.page.data.state, 'choose'); assert.equal(h.page.data.qr, '');
  for (const value of ['', '0', '4', '2.5']) { h.page.inputSeat({ detail: { value } }); h.page.confirmSeat(); }
  assert.equal(h.requests.length, 1);
  h.page.inputSeat({ detail: { value: '2' } }); h.page.confirmSeat();
  assert.equal(h.requests[1].data.seatNo, 2); h.requests[1].success(issued());
  assert.equal(h.page.data.state, 'ready'); assert.equal(h.page.data.seatNo, 2);
});
test('refresh and expired code keep the explicit seat; change requires an explicit new choice', () => {
  const h = component(); h.page.load(); h.requests[0].success(choice); h.page.inputSeat({ detail: { value: '2' } }); h.page.confirmSeat();
  h.requests[1].success(issued()); h.page.retry(); assert.equal(h.requests[2].data.seatNo, 2);
  h.requests[2].success({ ...issued(), data: { ...issued().data, expiresAt: Date.now() - 1000 } });
  assert.equal(h.page.data.state, 'error'); h.page.retry(); assert.equal(h.requests[3].data.seatNo, 2);
  h.page.changeSeat(); assert.equal(h.page.data.state, 'choose'); assert.equal(h.page.data.qr, '');
});
test('issuer refuses a response for another seat and displays actual already-verified status', () => {
  const h = component(); h.page.load(); h.requests[0].success(choice); h.page.inputSeat({ detail: { value: '2' } }); h.page.confirmSeat();
  h.requests[1].success(issued(3)); assert.equal(h.page.data.state, 'error'); assert.equal(h.page.data.qr, '');
  h.page.retry(); h.requests[2].success({ ...issued(), data: { ...issued().data, seatStatus: 1 } });
  assert.equal(h.page.data.seatVerified, true);
});
test('issuer A→B→A callbacks and retries never change UI or send with a new login generation', () => {
  const h = component(); h.page.load(); h.switchOwner('22'); h.switchOwner('11');
  h.requests[0].success(choice); assert.equal(h.page.data.state, 'loading');
  h.page.retry(); assert.equal(h.requests.length, 1); assert.equal(h.page.data.state, 'error');
});
test('issuer registration replacement invalidates the old selection and callback', () => {
  const h = component(); h.page.load(); h.requests[0].success(choice); h.page.inputSeat({ detail: { value: '2' } }); h.page.confirmSeat();
  h.page.data.registrationId = '72'; h.page.load(); h.requests[1].success(issued());
  assert.equal(h.page.data.qr, ''); assert.equal(h.page.data.seatNo, ''); assert.equal(h.requests[2].data.seatNo, undefined);
});

test('码亮着时轮询探店完局面:局面变了才报「已核销」并通知游玩页', () => {
  const h = component(); h.page.load(); h.requests[0].success(issued());
  assert.equal(h.polls[0].url, '/api/registration/explore-completion');
  h.polls[0].success({ code: 200, data: { redeemed: 0 } });
  const poll = h.intervals.at(-1);
  poll(); h.polls[1].success({ code: 200, data: { redeemed: 0 } });
  assert.equal(h.events.length, 0);
  const toasts = []; globalThis.wx = { showToast: (o) => toasts.push(o.title) };   // utils/toast 无宿主页时回落 wx.showToast
  try { poll(); h.polls[2].success({ code: 200, data: { redeemed: 1 } }); } finally { delete globalThis.wx; }
  assert.deepEqual(h.events.map((e) => e.name), ['redeemed']);
  assert.deepEqual(toasts, ['商家已核销']);
});
