'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { createAudioSession } = require('../../utils/audio-session.js');
const flush = () => new Promise(resolve => setImmediate(resolve));

function load(relative, kind = 'Page') {
  let definition;
  let member = '11';
  const requests = [], modals = [], subscriptions = [], writes = [], routes = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member,
    sendRequest: options => requests.push(options), getRequestErrorMessage: (_r, fallback) => fallback });
  global[kind] = config => { definition = config; };
  global.wx = { getStorageSync: () => '', setStorageSync: (k, v) => writes.push([k, v]), removeStorageSync() {},
    nextTick: f => f(), showToast() {}, setNavigationBarColor() {}, showLoading() {}, hideLoading() {},
    showModal: options => modals.push(options), requestSubscribeMessage: options => subscriptions.push(options),
    offLocationChange() {}, stopLocationUpdate() {}, reLaunch: options => routes.push(options.url) };
  const filename = path.resolve(__dirname, '../..', relative);
  delete require.cache[filename]; require(filename);
  const page = Object.assign({}, kind === 'Page' ? definition : definition.methods);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = (patch, callback) => { for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.'); let target = page.data;
    for (const p of parts.slice(0, -1)) target = target[p] || (target[p] = {});
    target[parts.at(-1)] = value;
  } if (callback) callback(); };
  page.triggerEvent = () => {};
  return { page, requests, modals, subscriptions, writes, routes, account: value => { member = value; } };
}

test('interruption → page hide → interruption end cannot replay background audio', () => {
  let begin, end;
  const session = createAudioSession({ onAudioInterruptionBegin: f => { begin = f; }, onAudioInterruptionEnd: f => { end = f; } });
  const calls = [];
  const ctx = { paused: false, pause() { this.paused = true; calls.push('pause'); }, play() { this.paused = false; calls.push('play'); } };
  session.register(ctx); begin(); session.pauseAll(); end();
  assert.deepEqual(calls, ['pause']);
  session.resumeAll(); assert.deepEqual(calls, ['pause', 'play']);
});

test('topic unlike removes stable ID after a refresh reorders rows', () => {
  const { page, requests, modals } = load('pages/mylike/mylike.js');
  page.data.ztList1 = [{ id: 1 }, { id: 2 }];
  page.topicUnlike({ currentTarget: { dataset: { id: 1, index: 0 } } }); modals[0].success({ confirm: true });
  page.data.ztList1 = [{ id: 2 }, { id: 1 }, { id: 3 }];
  requests[0].success({ code: 200 });
  assert.deepEqual(page.data.ztList1.map(row => row.id), [2, 3]);
});

test('favorites old account list and unlike callbacks cannot change the new account', () => {
  const h = load('pages/mylike/mylike.js');
  h.page.loadLikeData(); const old = h.requests[0];
  h.account('22'); h.page.onShow();
  old.success({ code: 200, data: { rows: [{ id: 111 }] } }); old.complete();
  assert.deepEqual(h.page.data.postList, []);
  assert.equal(h.page.data.loading, true, 'old completion must not end the new request');
  const current = h.requests.at(-1); current.success({ code: 200, data: { rows: [{ id: 2 }] } }); current.complete();
  h.page.postUnlike({ detail: { index: 0 } }); const unlike = h.requests.at(-1);
  h.account('33'); h.page.onShow(); h.page.data.postList = [{ id: 2 }, { id: 3 }];
  unlike.success({ code: 200 }); assert.deepEqual(h.page.data.postList.map(row => row.id), [2, 3]);
});

for (const lifecycle of ['onHide', 'onUnload']) test('team progress late response after ' + lifecycle + ' cannot restart polling', async () => {
  const h = load('pages/play/index.js'); h.page.data.activityId = '9';
  const pending = h.page.loadTeamProgress();
  h.page[lifecycle]();
  h.requests[0].success({ code: 200, data: { exists: true, total: 5 } }); await pending;
  assert.equal(h.page.data.lead.exists, false);
  assert.ok(!h.page._leadPoller || !h.page._leadPoller.isActive());
});

test('play load response and pause snapshot from A cannot enter B account', async () => {
  const h = load('pages/play/index.js'); h.page.data.activityId = '9'; h.page.loadData(true);
  h.account('22'); h.requests[0].success({ code: 402, msg: 'old account pass' }); await flush();
  assert.notEqual(h.page.data.emptyKind, 'needPass');
  h.page._runStarted = true; h.page._persistRunSession(); await flush();
  assert.equal(h.writes.length, 0); assert.equal(h.requests.length, 1);
});

for (const [relative, handler, info] of [
  ['subpackageMember/components/cy/scene-member-order-detail/index.js', 'cancelRegistration', { id: 7, paymentStatus: 2, canRequestRefund: true }],
  ['subpackageMember/components/cy/scene-member-order-history/index.js', 'cancelOrder', null],
  ['subpackageMember/components/scene-member-participation-detail/index.js', 'cancelParticipation', { paymentStatus: 2 }],
]) test('refund subscription starts on confirmed gesture and rejection does not block ' + handler, () => {
  const h = load(relative, 'Component'); h.page.data.info = info; h.page.data.id = 7;
  h.page.data.list = [{ id: 7 }];
  h.page[handler]({ currentTarget: { dataset: { id: 7, index: 0, status: 2 } } });
  assert.equal(h.subscriptions.length, 0);
  h.modals[0].success({ confirm: true });
  assert.equal(h.subscriptions.length, 1);
  const subscribe = require('../../utils/subscribe.js');
  assert.deepEqual(h.subscriptions[0].tmplIds, [subscribe.TEMPLATES.refund, subscribe.TEMPLATES.refundRejected]);
  h.subscriptions[0].fail({ errMsg: 'reject' });
  assert.equal(h.requests.filter(r => r.url === '/api/registration/cancel-refund').length, 1);
});


test('invite response belonging to A cannot populate B circle-session cache', async () => {
  const h = load('pages/play/index.js'); h.page.data.topicId = '8'; h.page._pendingInviteCode = 'TEST-CODE';
  const pending = h.page._consumeInviteCode(); h.account('22');
  h.requests[0].success({ code: 200, data: { id: 99 } }); await pending;
  assert.deepEqual(h.writes, []);
});

test('queued A pause save cannot send under B credentials', async () => {
  const h = load('pages/play/index.js'); h.page.data.activityId = '9'; h.page._runStarted = true;
  h.page._persistRunSession(); h.account('22'); await flush();
  assert.equal(h.requests.length, 0);
  assert.match(h.writes[0][0], /:m11:a9$/);
});

test('A projection late callback cannot write B player-pending state or UI', async () => {
  const h = load('pages/play/index.js'); h.page.data.activityId = '9';
  h.page._loadPlayerGameModule(); h.account('22');
  h.requests[0].fail({}); await flush();
  assert.equal(h.page.data.gameModule.error, '');
  assert.equal(h.page._playerPendingKey(), '');
  assert.equal(h.page._roleSeenKey('9'), '');
});


test('returning to A play page after account switch leaves the stale session before any reload', () => {
  const h = load('pages/play/index.js'); h.page.data.activityId = '9'; h.page.loadData(true);
  h.account('22'); h.page.onShow();
  assert.deepEqual(h.routes, ['/pages/index/index']);
  assert.equal(h.requests.length, 1);
});

test('A paused server restore late response cannot restore or persist B run', async () => {
  const h = load('pages/play/index.js'); h.page.data.activityId = '9';
  h.page._restoreRunSession({}, {}, [{ nodeId: 1, done: false }]);
  h.account('22'); h.requests[0].success({ code: 200, data: { runState: 'PAUSED', elapsedSeconds: 120, savedAt: 300 } });
  await flush(); assert.ok(!h.page._runStarted); assert.deepEqual(h.writes, []);
});

for (const activeFavoriteTab of ['posts', 'topics']) {
  for (const invalidate of ['account', 'unload']) {
    test('favorites ' + activeFavoriteTab + ' late callbacks ignored after ' + invalidate, () => {
      const h = load('pages/mylike/mylike.js');
      h.page.data.activeFavoriteTab = activeFavoriteTab;
      h.page.loadLikeData();
      const old = h.requests[0];
      if (invalidate === 'account') { h.account('22'); h.page.onShow(); }
      else h.page.onUnload();
      const state = JSON.parse(JSON.stringify(h.page.data));
      old.success({ code: 200, data: { rows: [{ id: 111 }] } });
      old.fail({}); old.successStatusAbnormal({}); old.complete();
      assert.deepEqual(h.page.data, state);
    });
  }
}
