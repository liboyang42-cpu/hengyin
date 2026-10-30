'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const BG = '../../utils/location/bg-tracker.js';
const ROAM = '../../pages/roam/index.js';
const PRIVACY = '../../pages/privacy/index.js';
const SHEET = '../../pages/shezhi/components/privacy-sheet/index.js';
const { createConsentClient } = require('../../utils/compliance/consent-client.js');
const { VERSIONS } = require('../../utils/compliance/usage-consent.js');
const settle = () => new Promise(resolve => setImmediate(resolve));

// 真实 Page/Component、漫游 consumer、tracker 和 consent-client；仅隔离系统定位及网络。
function harness(t, entry, storage = new Map(), realTransport = false) {
  const h = { storage, requests: [], atPost: [], toasts: [], starts: [], listeners: new Set(), modals: [], stops: 0, uid: '9' };
  const app = { globalData: { user_id: 9, features: {} }, getUserID: () => h.uid, isDevEnv: () => false,
    sendRequest(options) { if (options.url === '/api/compliance/consents') h.atPost.push(h.tracker.hasOwner('roam')); h.requests.push(options); } };
  if (realTransport) {
    const { createRequestClient } = require('../../utils/transport/request-client.js');
    app.sendRequest = createRequestClient({ getBaseUrl: () => '', getUserID: app.getUserID,
      getAuthorization: () => 'test-session-' + h.uid,
      wxRequest(options) {
        if (options.url === '/api/compliance/consents') h.atPost.push(h.tracker.hasOwner('roam'));
        h.requests.push({ ...options, success(body) { options.success({ statusCode: 200, data: body }); } });
        return { abort() {} };
      } }).send;
  }
  const client = createConsentClient({ sendRequest: app.sendRequest, getUserID: app.getUserID,
    getPending: key => storage.get(key), setPending(key, value) { if (value) storage.set(key, value); else storage.delete(key); },
    settleTimeoutMs: 1000 });
  app.recordConsent = input => client.record(input);
  h.app = app;
  global.getApp = () => app;
  global.wx = {
    getStorageSync: () => '', setStorageSync() {}, showToast: options => h.toasts.push(options.title),
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    startLocationUpdateBackground(options) { h.starts.push(options); },
    stopLocationUpdate(options) { h.stops++; if (options && options.success) options.success(); },
    onLocationChange(fn) { h.listeners.add(fn); }, offLocationChange(fn) { h.listeners.delete(fn); },
    setKeepScreenOn() {}, showModal(options) { h.modals.push(options); },
  };
  for (const file of [BG, ROAM, PRIVACY, SHEET]) delete require.cache[require.resolve(file)];
  let definition;
  global.Page = value => { definition = value; };
  require(ROAM);
  const setData = function (patch, done) { Object.assign(this.data, patch); if (done) done(); };
  h.roam = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData };
  h.roam._syncGoal = () => {};
  h.roam._track = []; h.roam._reveals = []; h.roam._pendingTiles = [];
  h.tracker = require(BG);
  require(PRIVACY);
  h.privacy = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData };
  global.Component = value => { definition = value; };
  require(SHEET);
  h.sheetLifecycle = definition.pageLifetimes;
  h.sheet = { ...definition.methods, data: JSON.parse(JSON.stringify(definition.data)), setData };
  h.target = h[entry];
  h.posts = () => h.requests.filter(row => row.url === '/api/compliance/consents');
  h.reads = () => h.requests.filter(row => row.url === '/api/compliance/consents/latest');
  h.start = () => { const before = h.starts.length; h.roam._startReal(true); if (h.starts.length > before) h.starts.at(-1).success(); };
  h.errorText = () => h.target.data.submitError || h.toasts.at(-1) || '';
  t.after(async () => {
    for (let round = 0; round < 3; round++) { h.requests.slice().forEach(row => row.fail && row.fail({ errMsg: 'request:fail' })); await settle(); }
    h.roam._stopReal(); h.tracker.release('independent-purpose');
  });
  return h;
}
function receipt(request) {
  return { code: 200, data: { ...JSON.parse(request.data), docVersion: VERSIONS.privacy_policy } };
}
async function failAudit(h) {
  h.posts().at(-1).fail({ errMsg: 'request:fail' });
  assert.equal(h.reads().length > 0, true);
  h.reads().at(-1).fail({ errMsg: 'request:fail' }); await settle();
}
for (const entry of ['privacy', 'sheet']) {
  test(entry + ' 审计仍在途即同步停止真实漫游consumer，不等Promise', async t => {
    const h = harness(t, entry); h.start(); const oldLocation = h.roam._onRealLoc;
    h.target.withdrawRoamLocationConsent();
    assert.equal(h.posts().length, 1); assert.equal(h.atPost[0], false); assert.equal(h.target.data.submitting, true);
    assert.equal(h.tracker.hasOwner('roam'), false); assert.equal(h.roam._realOn, false);
    assert.equal(h.listeners.size, 0); assert.equal(h.stops, 1);
    h.roam.data.screen = 'map'; const requests = h.requests.length;
    oldLocation({ latitude: 31, longitude: 121 });
    assert.equal(h.requests.length, requests); assert.equal(h.roam._track.length, 0);
    await failAudit(h);
    assert.match(h.errorText(), /本机.*已停/); assert.match(h.errorText(), /记录.*待确认/);
    assert.ok(!h.toasts.includes('已撤回漫游定位同意'));
  });
  test(entry + ' POST及latest均失败仍停该purpose，独立purpose继续持有系统采集', async t => {
    const h = harness(t, entry); h.start(); let ready = 0;
    h.tracker.acquire('independent-purpose', { success() { ready++; } });
    h.target.withdrawRoamLocationConsent();
    assert.equal(h.tracker.hasOwner('roam'), false); assert.equal(h.tracker.hasOwner('independent-purpose'), true);
    assert.equal(h.tracker.isRunning(), true); assert.equal(h.stops, 0); assert.equal(ready, 1);
    await failAudit(h);
    assert.equal(h.tracker.hasOwner('independent-purpose'), true); assert.equal(h.stops, 0);
    assert.equal(h.storage.size, 1); assert.match(h.errorText(), /本机.*已停/);
  });
  test(entry + ' 审计补传跨consumer重建保留原owner和原requestId，不假报已记录', async t => {
    const h = harness(t, entry); h.start(); h.target.withdrawRoamLocationConsent();
    const original = JSON.parse(h.posts()[0].data); await failAudit(h);
    assert.equal(h.storage.size, 1); assert.ok(!h.toasts.includes('已撤回漫游定位同意'));
    const next = harness(t, entry, h.storage); next.target.withdrawRoamLocationConsent();
    assert.deepEqual(JSON.parse(next.posts()[0].data), original);
    next.posts()[0].success(receipt(next.posts()[0])); await settle();
    assert.equal(next.storage.size, 0); assert.equal(next.toasts.at(-1), '已撤回漫游定位同意');
  });
  for (const late of ['POST', 'latest']) test(entry + ' 旧账号迟到' + late + '不能停新账号用途或向其报成功', async t => {
    const h = harness(t, entry, new Map(), true); h.start(); h.target.withdrawRoamLocationConsent();
    const original = h.posts()[0];
    if (late === 'latest') original.fail({ errMsg: 'request:fail' });
    h.uid = '22'; h.roam._stopReal(); h.start(); h.tracker.acquire('independent-purpose', { success() {} });
    const revision = h.tracker.revision('roam'), stops = h.stops;
    if (late === 'POST') original.success(receipt(original));
    else h.reads().at(-1).success(receipt(original));
    await settle();
    assert.equal(h.tracker.revision('roam'), revision); assert.equal(h.stops, stops);
    assert.equal(h.tracker.hasOwner('roam'), true); assert.equal(h.tracker.hasOwner('independent-purpose'), true);
    assert.equal(h.roam._realOn, true); assert.equal(h.listeners.size, 1);
    assert.deepEqual(h.toasts, []); assert.equal(h.target.data.submitError || '', ''); assert.equal(h.storage.size, 1);
  });
  test(entry + ' 迟到审计成功不重复revoke，独立purpose与新领取保持有效', async t => {
    const h = harness(t, entry); h.start(); h.tracker.acquire('independent-purpose', { success() {} });
    h.target.withdrawRoamLocationConsent(); const revision = h.tracker.revision('roam');
    h.start(); h.posts()[0].success(receipt(h.posts()[0])); await settle();
    assert.equal(h.tracker.revision('roam'), revision); assert.equal(h.tracker.hasOwner('roam'), true);
    assert.equal(h.tracker.hasOwner('independent-purpose'), true); assert.equal(h.storage.size, 0);
    assert.deepEqual(h.toasts, ['已撤回漫游定位同意']);
  });
  test(entry + ' 正常成功仅停止被撤回purpose，独立purpose仍运行', async t => {
    const h = harness(t, entry); h.start(); h.tracker.acquire('independent-purpose', { success() {} });
    h.target.withdrawRoamLocationConsent(); h.posts()[0].success(receipt(h.posts()[0])); await settle();
    assert.equal(h.tracker.hasOwner('roam'), false); assert.equal(h.tracker.hasOwner('independent-purpose'), true);
    assert.equal(h.tracker.isRunning(), true); assert.equal(h.stops, 0); assert.equal(h.listeners.size, 0);
    assert.equal(h.storage.size, 0); assert.deepEqual(h.toasts, ['已撤回漫游定位同意']);
  });
  test(entry + ' B的新审计不冒充A的原待办，返回A仍复用原request', async t => {
    const first = harness(t, entry); first.start(); first.target.withdrawRoamLocationConsent();
    const original = JSON.parse(first.posts()[0].data); await failAudit(first);
    const second = harness(t, entry, first.storage); second.uid = '22'; second.target.withdrawRoamLocationConsent();
    const another = JSON.parse(second.posts()[0].data);
    assert.notEqual(another.requestId, original.requestId); assert.equal(second.storage.size, 2);
    second.posts()[0].success(receipt(second.posts()[0])); await settle(); assert.equal(second.storage.size, 1);
    const returned = harness(t, entry, first.storage); returned.target.withdrawRoamLocationConsent();
    assert.deepEqual(JSON.parse(returned.posts()[0].data), original);
    returned.posts()[0].success(receipt(returned.posts()[0])); await settle(); assert.equal(returned.storage.size, 0);
  });
  test(entry + ' 审计调用同步抛错也已停采，错误可见且释放busy', async t => {
    const h = harness(t, entry); h.start(); h.app.recordConsent = () => { throw Error('fixture unavailable'); };
    h.target.withdrawRoamLocationConsent(); await settle();
    assert.equal(h.tracker.hasOwner('roam'), false); assert.equal(h.listeners.size, 0);
    assert.equal(h.target.data.submitting, false); assert.equal(h.requests.length, 0);
    assert.match(h.errorText(), /本机.*已停.*待确认/);
  });
  test(entry + ' 真实AGREE尚在途时撤回也立刻取消consumer，迟到AGREE不能acquire', async t => {
    const h = harness(t, entry); h.roam._startReal(); h.modals[0].success({ confirm: true });
    h.reads()[0].success({ code: 200, data: null }); await settle();
    const agree = h.posts()[0]; assert.equal(JSON.parse(agree.data).eventType, 'AGREE');
    h.target.withdrawRoamLocationConsent();
    assert.equal(h.roam._realStarting, false);
    agree.success(receipt(agree)); await settle();
    assert.equal(h.starts.length, 0); assert.equal(h.tracker.hasOwner('roam'), false);
    await failAudit(h);
  });
}

for (const entry of ['privacy', 'sheet']) {
  for (const change of ['old-request', 'missing-request', 'wrong-version', 'missing-version', 'scope-type', 'scope-id', 'wrong-scene', 'wrong-doc', 'wrong-event']) {
    test(entry + ' real transport latest ' + change + ' cannot confirm this REVOKE; recreation retries original identity', async t => {
      const h = harness(t, entry, new Map(), true); h.start();
      h.tracker.acquire('independent-purpose', { success() {} });
      h.target.withdrawRoamLocationConsent();
      const original = h.posts()[0], body = JSON.parse(original.data);
      original.fail({ errMsg: 'request:fail timeout' });
      const response = receipt(original);
      if (change === 'old-request') response.data.requestId = 'historical-revoke';
      if (change === 'missing-request') delete response.data.requestId;
      if (change === 'wrong-version') response.data.docVersion = 'v1.0';
      if (change === 'missing-version') delete response.data.docVersion;
      if (change === 'scope-type') response.data.scopeType = 'MERCHANT';
      if (change === 'scope-id') response.data.scopeId = 17;
      if (change === 'wrong-scene') response.data.scene = 'home_city_location';
      if (change === 'wrong-doc') response.data.docType = 'user_service_agreement';
      if (change === 'wrong-event') response.data.eventType = 'AGREE';
      h.reads().at(-1).success(response); await settle();
      assert.equal(h.storage.size, 1); assert.match(h.errorText(), /记录.*待确认/);
      assert.ok(!h.toasts.includes('已撤回漫游定位同意'));
      assert.equal(h.tracker.hasOwner('roam'), false);
      assert.equal(h.tracker.hasOwner('independent-purpose'), true);
      const next = harness(t, entry, h.storage, true);
      next.target.withdrawRoamLocationConsent();
      assert.deepEqual(JSON.parse(next.posts()[0].data), body);
      next.posts()[0].fail({ errMsg: 'request:fail timeout' });
      next.reads().at(-1).success(receipt(next.posts()[0])); await settle();
      assert.equal(next.storage.size, 0);
      assert.equal(next.toasts.at(-1), '已撤回漫游定位同意');
    });
  }
}

test('privacy leave and return after old latest retains original pending and retry key', async t => {
  const h = harness(t, 'privacy', new Map(), true); h.start();
  h.target.withdrawRoamLocationConsent(); const original = h.posts()[0];
  h.app.hasPendingPrivacyAuthorization = () => false;
  h.target.onHide(); h.target.onUnload();
  original.fail({ errMsg: 'request:fail timeout' });
  const old = receipt(original); old.data.requestId = 'previous-revoke';
  h.reads().at(-1).success(old); await settle();
  assert.equal(h.storage.size, 1);
  const next = harness(t, 'privacy', h.storage, true); next.target.withdrawRoamLocationConsent();
  assert.deepEqual(JSON.parse(next.posts()[0].data), JSON.parse(original.data));
  await failAudit(next); assert.equal(h.storage.size, 1);
});

for (const scope of ['missing-type', 'wrong-type', 'missing-id', 'wrong-id', 'exact']) {
  test('scoped REVOKE latest requires original scope: ' + scope, async () => {
    const requests = [], storage = new Map();
    const client = createConsentClient({ sendRequest: row => requests.push(row), getUserID: () => '9',
      getPending: key => storage.get(key), setPending: (key, value) => value ? storage.set(key, value) : storage.delete(key) });
    const promise = client.record({ docType: 'merchant_onsite_data_sharing', scene: 'merchant_redeem',
      eventType: 'REVOKE', scopeType: 'MERCHANT', scopeId: 17 });
    const outcome = promise.then(() => true, () => false);
    const original = requests[0], row = { ...JSON.parse(original.data), docVersion: 'v1.0' };
    original.fail({ errMsg: 'request:fail timeout' });
    assert.deepEqual(JSON.parse(requests[1].data), { docType: row.docType, scene: row.scene, scopeType: 'MERCHANT', scopeId: 17 });
    if (scope === 'missing-type') delete row.scopeType;
    if (scope === 'wrong-type') row.scopeType = 'OTHER';
    if (scope === 'missing-id') delete row.scopeId;
    if (scope === 'wrong-id') row.scopeId = 18;
    requests[1].success({ code: 200, data: row });
    assert.equal(await outcome, scope === 'exact');
    assert.equal(storage.size, scope === 'exact' ? 0 : 1);
  });
}

for (const entry of ['privacy', 'sheet']) test(entry + ': native stop failure is visible; retry only stops and never repeats confirmed audit', async t => {
  const h = harness(t, entry); h.start(); let failed = true;
  global.wx.stopLocationUpdate = options => { h.stops++; failed ? options.fail({ errMsg: 'stopLocationUpdate:fail unavailable' }) : options.success(); };
  h.target.withdrawRoamLocationConsent(); h.posts()[0].success(receipt(h.posts()[0])); await settle();
  assert.match(h.target.data.submitError, /系统停止定位.*未确认/); assert.equal(h.target.data.submitting, false);
  assert.equal(h.tracker.hasOwner('roam'), false); const posts = h.posts().length, starts = h.starts.length;
  failed = false;
  if (entry === 'privacy') h.target.retrySubmit(); else h.target.withdrawRoamLocationConsent();
  await settle(); assert.equal(h.target.data.submitError, ''); assert.equal(h.posts().length, posts);
  assert.equal(h.starts.length, starts); assert.equal(h.tracker.stopState(), 'confirmed');
});

for (const entry of ['privacy', 'sheet']) test(entry + ': confirmed audit survives hide while native stop remains pending; retry shares native flight', async t => {
  const h = harness(t, entry); h.start(); const stops = [];
  global.wx.stopLocationUpdate = options => { h.stops++; stops.push(options); };
  h.target.withdrawRoamLocationConsent();
  h.posts()[0].success(receipt(h.posts()[0])); await settle();
  assert.equal(h.target._roamAuditConfirmed, true); assert.equal(h.stops, 1);
  if (entry === 'privacy') {
    h.app.hasPendingPrivacyAuthorization = () => true; h.target.onHide(); h.target.onShow();
    assert.equal(h.target._failedAction, 'stop'); h.target.retrySubmit();
  } else {
    h.sheetLifecycle.hide.call(h.target); h.sheetLifecycle.show.call(h.target);
    h.target.withdrawRoamLocationConsent();
  }
  assert.equal(h.posts().length, 1); assert.equal(h.stops, 1, 'in-flight native stop is shared');
  stops[0].fail({ errMsg: 'stopLocationUpdate:fail unavailable' }); await settle();
  assert.match(h.target.data.submitError, /系统停止定位.*未确认/);
  if (entry === 'privacy') h.target.retrySubmit(); else h.target.withdrawRoamLocationConsent();
  assert.equal(h.posts().length, 1); assert.equal(h.stops, 2);
  stops[1].success(); await settle();
  assert.equal(h.target.data.submitError, ''); assert.equal(h.tracker.stopState(), 'confirmed');
  assert.equal(h.starts.length, 1);
});

for (const entry of ['privacy', 'sheet']) test(entry + ': a later independent withdrawal records a new audit after full success', async t => {
  const h = harness(t, entry); h.start();
  h.target.withdrawRoamLocationConsent(); h.posts()[0].success(receipt(h.posts()[0])); await settle();
  h.start(); h.target.withdrawRoamLocationConsent();
  assert.equal(h.posts().length, 2);
  assert.notEqual(JSON.parse(h.posts()[0].data).requestId, JSON.parse(h.posts()[1].data).requestId);
  h.posts()[1].success(receipt(h.posts()[1])); await settle();
  assert.equal(h.target.data.submitError, ''); assert.equal(h.target.data.submitting, false);
});
