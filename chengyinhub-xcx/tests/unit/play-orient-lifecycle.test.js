'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const lifecycle = require('../../pages/play/utils/orient-lifecycle.js');
let owner, auth, storage, requests, page, modal;
const row = (patch = {}) => ({ sessionId: '9007199254740993', topicId: '17', activityId: '0', phase: 'PREPARED',
  configVersion: 4, startedAt: null, elapsedMs: null, ...patch });
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function create() {
  const result = { ...lifecycle.methods((url, method, data) => new Promise(resolve => requests.push({ url, method, data, resolve }))),
    data: { ...JSON.parse(JSON.stringify(lifecycle.data)), topicId: '17', activityId: 0, mode: 1 },
    setData(patch) { Object.assign(this.data, patch); } };
  return result;
}
function reply(index, data) { requests[index].resolve({ code: 200, data }); }
async function load(rows = [row()]) { const p = page.openOrientLifecycle(); reply(requests.length - 1, { sessions: rows }); await p; }
function saved() { return Object.values(storage)[0]; }
beforeEach(() => {
  owner = '9'; auth = 'fixture-auth'; storage = {}; requests = []; modal = null;
  global.getApp = () => ({ getUserID: () => owner, getAuthorization: () => auth });
  global.wx = { getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync: key => { delete storage[key]; }, showModal: options => { modal = options; }, navigateTo() {} };
  page = create();
});
test('prepare persists identity before POST, double click cannot duplicate, receipt refreshes facts', async () => {
  await load([]);
  page.prepareOrientSession(); page.prepareOrientSession();
  assert.equal(requests.length, 2); assert.equal(requests[1].method, 'POST');
  assert.equal(requests[1].data.idempotencyKey, saved().key);
  assert.equal(requests[1].data.topicId, '17');
  reply(1, row()); await flush();
  assert.equal(requests[2].method, 'GET'); reply(2, { sessions: [row()] }); await flush();
  assert.equal(page.data.orientLifecycle.row.sessionId, '9007199254740993'); assert.equal(saved(), undefined);
});
test('start confirms frozen version; same session/server clock on reentry', async () => {
  await load(); page.startOrientSession();
  assert.match(modal.content, /版本 4/); modal.success({ confirm: true });
  assert.deepEqual(requests[1].data, { sessionId: '9007199254740993', ackConfigVersion: 4, idempotencyKey: saved().key });
  const running = row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 2000 });
  reply(1, running); await flush(); reply(2, { sessions: [running] }); await flush();
  page = create(); await load([row({ ...running, elapsedMs: 9000 })]);
  assert.equal(page.data.orientLifecycle.row.startedAt, 1000);
  assert.match(page.data.orientLifecycle.row.elapsedText, /^9 秒/);
  assert.equal(requests.filter(r => r.method === 'POST').length, 1);
});
test('UNKNOWN freezes request across reentry, negative receipt cannot unlock', async () => {
  await load(); page.startOrientSession(); modal.success({ confirm: true });
  requests[1].resolve({ code: 'fail' }); await flush(); const original = JSON.stringify(saved());
  page.startOrientSession(); page.abandonOrientSession(); assert.equal(requests.length, 2);
  page = create(); const reopening = page.openOrientLifecycle(); reply(2, { sessions: [row()] }); await flush();
  assert.equal(requests[3].url.endsWith('/receipt'), true);
  assert.equal(requests[3].data.idempotencyKey, saved().key);
  requests[3].resolve({ code: 404, err: 'ORIENT_RECEIPT_NOT_FOUND' }); await reopening;
  assert.equal(page.data.orientLifecycle.pending, true); assert.equal(JSON.stringify(saved()), original);
});
test('old successful start receipt cannot resurrect terminal current session', async () => {
  await load(); page.startOrientSession(); modal.success({ confirm: true }); requests[1].resolve({ code: 500 }); await flush();
  page.openOrientLifecycle(); reply(2, { sessions: [row({ phase: 'FINISHED', startedAt: 1000, elapsedMs: 5000 })] }); await flush();
  reply(3, row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 0 })); await flush();
  reply(4, { sessions: [row({ phase: 'FINISHED', startedAt: 1000, elapsedMs: 5000 })] }); await flush();
  assert.equal(page.data.orientLifecycle.row.phase, 'FINISHED'); page.startOrientSession();
  assert.equal(requests.filter(r => r.method === 'POST').length, 1);
});
test('unknown prepare recovers original receipt through current sessions without repeating prepare', async () => {
  await load([]); page.prepareOrientSession(); requests[1].resolve({ code: 500 }); await flush();
  page = create(); page.openOrientLifecycle(); reply(2, { sessions: [row()] }); await flush();
  reply(3, row()); await flush(); reply(4, { sessions: [row()] }); await flush();
  assert.equal(page.data.orientLifecycle.pending, false); assert.equal(saved(), undefined);
  assert.equal(requests.filter(r => r.method === 'POST').length, 1);
});
test('switching account, auth or scope ignores late response and retains original request', async () => {
  for (const change of [() => { owner = '10'; }, () => { auth = 'fixture-new'; }, () => { page.data.topicId = '18'; }]) {
    owner = '9'; auth = 'fixture-auth'; storage = {}; requests = []; page = create();
    await load(); page.startOrientSession(); modal.success({ confirm: true }); change();
    reply(1, row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 0 })); await flush();
    assert.ok(saved()); assert.equal(requests.length, 2);
  }
});
test('hide/unload then return recovers pending and ignores late callbacks', async () => {
  await load(); page.startOrientSession(); modal.success({ confirm: true }); page.suspendOrientLifecycle();
  reply(1, row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 0 })); await flush();
  assert.ok(saved()); assert.equal(page.data.orientLifecycle.open, false);
  page.openOrientLifecycle(); reply(2, { sessions: [row()] }); await flush();
  assert.equal(requests[3].url.endsWith('/receipt'), true);
});
test('storage failure prevents any write', async () => {
  await load([]); wx.setStorageSync = () => { throw new Error('full'); }; page.prepareOrientSession();
  assert.equal(requests.length, 1); assert.equal(page.data.orientLifecycle.pending, true);
});
test('unsafe numeric ID, wrong scope, duplicate rows and invalid version fail closed', async () => {
  for (const rows of [[row({ sessionId: 9007199254740992 })], [row({ topicId: '18' })], [row(), row()], [row({ configVersion: '4' })]]) {
    page = create(); await load(rows); page.prepareOrientSession();
    assert.equal(page.data.orientLifecycle.ready, false); assert.equal(requests.filter(r => r.method === 'POST').length, 0);
  }
});
test('terminal sessions cannot start or abandon and multiple sessions require explicit selection', async () => {
  await load([row({ phase: 'FINISHED' }), row({ sessionId: '2' })]);
  page.startOrientSession(); assert.equal(modal, null);
  page.chooseOrientSession({ currentTarget: { dataset: { sessionId: '9007199254740993' } } });
  page.startOrientSession(); page.abandonOrientSession(); assert.equal(modal, null);
  page.chooseOrientSession({ currentTarget: { dataset: { sessionId: '2' } } }); page.startOrientSession(); assert.ok(modal);
});
test('abandon requires confirmation and reward facts use separate existing score page', async () => {
  await load(); page.abandonOrientSession(); assert.match(modal.content, /不可恢复/); modal.success({ confirm: false });
  assert.equal(requests.length, 1);
  let url; wx.navigateTo = value => { url = value.url; }; page.openOrientRewards(); assert.match(url, /sessionId=9007199254740993/);
});
test('disabled session flow cannot submit a prepare request', async () => {
  const p = page.openOrientLifecycle(); requests[0].resolve({ code: 409, err: 'ORIENT_SESSION_DISABLED' }); await p;
  page.prepareOrientSession(); assert.equal(requests.length, 1); assert.match(page.data.orientLifecycle.notice, /尚未启用/);
});
test('transport abnormal carrying a precondition code must retain UNKNOWN', async () => {
  await load(); page.startOrientSession(); modal.success({ confirm: true });
  requests[1].resolve({ code: 'http-error', err: 'ORIENT_ACK_REQUIRED' }); await flush();
  assert.ok(saved()); assert.equal(page.data.orientLifecycle.pending, true);
});
test('actual play Page sends form-bound lifecycle writes and suspends their late callbacks', async () => {
  let config; global.Page = value => { config = value; };
  const app = { globalData: { user_id: 9, features: {} }, getUserID: () => owner, getAuthorization: () => auth,
    sendRequest: request => requests.push(request), isDevEnv: () => false };
  global.getApp = () => app;
  const path = require.resolve('../../pages/play/index.js'); delete require.cache[path]; require(path);
  page = { ...config, data: JSON.parse(JSON.stringify(config.data)), setData(patch) { Object.assign(this.data, patch); } };
  page.data.topicId = '17'; page.data.activityId = 0; page.data.mode = 1;
  page.openOrientLifecycle(); requests[0].success({ code: 200, data: { sessions: [] } }); await flush();
  page.prepareOrientSession();
  assert.equal(requests[1].header['content-type'], 'application/x-www-form-urlencoded');
  assert.equal(requests[1].url, '/api/play/orient/session/prepare');
  assert.equal(requests[1].data.idempotencyKey, saved().key);
  page.suspendOrientLifecycle(); requests[1].success({ code: 200, data: row() }); await flush();
  assert.ok(saved()); assert.equal(page.data.orientLifecycle.open, false);
});
test('photo pending is not a task completion; only completed primary tasks can request server evidence', async () => {
  page.data.nodes = [{ nodeId: '21', name: '待审照片', done: false }, { nodeId: '22', name: '答题', done: true }];
  await load([row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 2000 })]);
  page.recordOrientTask({ currentTarget: { dataset: { taskId: '21' } } }); assert.equal(requests.length, 1);
  page.recordOrientTask({ currentTarget: { dataset: { taskId: '22' } } });
  assert.equal(requests[1].url, '/api/play/orient/session/task/22/result'); assert.equal(saved().payload.taskId, '22');
  requests[1].resolve({ code: 409, err: 'ORIENT_TASK_EVIDENCE_MISSING' }); await flush();
  assert.equal(saved(), undefined); assert.equal(page.data.nodes[0].done, false);
});
test('task UNKNOWN original receipt recovers without a second task or scoring write', async () => {
  page.data.nodes = [{ nodeId: '22', done: true }];
  await load([row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 2000 })]);
  page.recordOrientTask({ currentTarget: { dataset: { taskId: '22' } } }); requests[1].resolve({ code: 500 }); await flush();
  page.openOrientLifecycle(); reply(2, { sessions: [row({ phase: 'FINISHED', startedAt: 1000, elapsedMs: 3000 })] }); await flush();
  reply(3, { sessionId: '9007199254740993', taskId: '22', receiptId: 'orient:task:22', alternativeTaskType: null,
    completedAt: 4000, elapsedMs: 3000, phase: 'FINISHED' }); await flush();
  reply(4, { sessions: [row({ phase: 'FINISHED', startedAt: 1000, elapsedMs: 3000 })] }); await flush();
  assert.equal(saved(), undefined); assert.equal(requests.filter(r => r.method === 'POST').length, 1);
});
test('real Page flow: prepare, start UNKNOWN, pending photo, authoritative completion, terminal and separate rewards', async () => {
  let config; global.Page = value => { config = value; };
  global.getApp = () => ({ globalData: { user_id: 9, features: {} }, getUserID: () => owner,
    getAuthorization: () => auth, sendRequest: request => requests.push(request), isDevEnv: () => false });
  const path = require.resolve('../../pages/play/index.js'); delete require.cache[path]; require(path);
  page = { ...config, data: JSON.parse(JSON.stringify(config.data)), setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback(); } };
  Object.assign(page.data, { topicId: '17', activityId: 0, mode: 1, nodes: [{ nodeId: '22', done: false }] });
  page.stopNav = () => {}; page.rebuild = () => {}; page.diegetic = () => {};
  // Service reads are controlled at the harness boundary; real Page completion handler must not mark pending done.
  page._readGenericProof = () => Promise.resolve(false);
  const answer = (i, data) => requests[i].success({ code: 200, data });
  page.openOrientLifecycle(); answer(0, { sessions: [] }); await flush();
  page.prepareOrientSession(); answer(1, row()); await flush(); answer(2, { sessions: [row()] }); await flush();
  page.startOrientSession(); modal.success({ confirm: true }); requests[3].fail(); await flush();
  const original = saved().key;
  page.suspendOrientLifecycle(); page.openOrientLifecycle();
  const running = row({ phase: 'RUNNING', startedAt: 1000, elapsedMs: 2000 });
  answer(4, { sessions: [running] }); await flush(); assert.equal(requests[5].data.idempotencyKey, original);
  answer(5, running); await flush(); answer(6, { sessions: [running] }); await flush();
  await page.onComplete('22', { nodeId: '22', done: false, disabledReason: 'PROOF_PENDING' });
  assert.equal(page.data.nodes[0].done, false); assert.equal(page.data.orientLifecycle.tasks.length, 0);
  // The next authoritative nodes read supplies completion after review, not the upload response.
  page.data.nodes = [{ nodeId: '22', done: true }];
  page.openOrientLifecycle(); answer(7, { sessions: [running] }); await flush();
  page.recordOrientTask({ currentTarget: { dataset: { taskId: '22' } } });
  answer(8, { sessionId: row().sessionId, taskId: '22', alternativeTaskType: null, receiptId: 'orient:task:22',
    completedAt: 4000, elapsedMs: 3000, phase: 'FINISHED' }); await flush();
  answer(9, { sessions: [row({ phase: 'FINISHED', startedAt: 1000, elapsedMs: 3000 })] }); await flush();
  page.startOrientSession(); page.prepareOrientSession(); assert.equal(requests.length, 10);
  let destination; wx.navigateTo = value => { destination = value.url; }; page.openOrientRewards();
  assert.match(destination, /orient-score/); assert.equal(page.data.orientLifecycle.row.startedAt, 1000);
  assert.equal(requests.filter(r => r.method === 'POST').length, 3);
  assert.equal(requests.some(r => /scoring.*settle/.test(r.url)), false);
});
test('terminal status and rewards are separate facts: null, settled and unreadable are distinct', async () => {
  await load([row({ phase: 'FINISHED', startedAt: 1000, elapsedMs: 2000 })]);
  let p = page.readOrientRewards(); reply(1, { ...row(), settlement: null }); await p;
  assert.equal(page.data.orientLifecycle.reward.state, 'unsettled');
  p = page.readOrientRewards(); reply(2, { ...row(), settlement: { state: 'SETTLED', sessionId: row().sessionId,
    settlementId: '9007199254740995', totalCenti: '500', pointsAwarded: '2', xpAwarded: '3' } }); await p;
  assert.equal(page.data.orientLifecycle.reward.points, '2');
  assert.equal(page.data.orientLifecycle.reward.settlementId, '9007199254740995');
  p = page.readOrientRewards(); requests[3].resolve({ code: 500 }); await p;
  assert.equal(page.data.orientLifecycle.reward.state, 'unknown');
  assert.equal(requests.filter(r => r.method === 'POST').length, 0);
});
test('reward reply from previous selection cannot populate another session', async () => {
  await load([row(), row({ sessionId: '2' })]);
  page.chooseOrientSession({ currentTarget: { dataset: { sessionId: row().sessionId } } });
  const p = page.readOrientRewards();
  page.chooseOrientSession({ currentTarget: { dataset: { sessionId: '2' } } });
  reply(1, { ...row(), settlement: null }); await p;
  assert.equal(page.data.orientLifecycle.reward, null);
});
