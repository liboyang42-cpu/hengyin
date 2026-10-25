'use strict';
// SC-PRIV-05:pages/privacy/index(回退路由页)与 cy-privacy-gate(宿主页真弹窗)的同意提交。
// 连点同意、提交中点拒绝、留痕失败后补传：原生授权按明确同意结算一次，留痕失败保留待补传及原幂等号。
// 与 v4-priv-auth-host-chain 同一装配:真 app.js + 真 usage-consent/consent-client,只桩 wx 与 sendRequest。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createSessionStore } = require('../../utils/session/session-store.js');
const { VERSIONS } = require('../../utils/compliance/usage-consent.js');

const ROOT = path.resolve(__dirname, '../..');
const settle = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setImmediate(r)); };

function harness() {
  const h = { storage: new Map(), navs: [], toasts: [], requests: [], pages: [], logins: 0, gateShows: 0, backs: 0 };
  const wx = {
    getStorageSync: k => h.storage.get(k), setStorageSync: (k, v) => h.storage.set(k, v), removeStorageSync: k => h.storage.delete(k),
    onNeedPrivacyAuthorization: fn => { h.native = fn; },
    navigateTo: o => { h.navs.push(o); }, navigateBack: () => { h.backs += 1; }, switchTab() {}, reLaunch() {},
    showToast: o => h.toasts.push(o.title), hideToast() {}, showLoading() {}, hideLoading() {},
    openPrivacyContract() {}, stopLocationUpdate() {}, offLocationChange() {}, nextTick: f => f(),
    getWindowInfo: () => ({ windowWidth: 390 }),
  };
  let app;
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'app.js'), 'utf8'), {
    App: v => { app = v; }, wx, console, getCurrentPages: () => h.pages, setTimeout, clearTimeout,
    require: file => file === './utils/toast.js' ? Object.assign(t => h.toasts.push(t), { hide() {} }) : require(path.join(ROOT, file)),
  });
  const session = createSessionStore({ target: app.globalData, wx });
  app.getSession = () => session; app.isDevEnv = () => false; app.loadFeatureFlags = () => {};
  app.firstLogin = async () => { h.logins += 1; session.setUserID('9'); session.setAuthorization('fixture-token'); return { ok: true }; };
  // 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:单独同意隐私不再建号。本文件验的「同意 → 登录 → 留痕」
  // 链路现在发生在写前登录里(游客点了「微信登录」、登录在途时隐私闸弹出),夹具据此标记登录在途。
  app.getSessionManager = () => ({ isLoggingIn: () => true });
  app.sendRequest = o => { h.requests.push(o); };
  app.initPrivacyAuthorization();
  global.getApp = () => app; global.wx = wx;
  h.app = app;
  // 一个「需要隐私授权的调用方」:原生回调交来的 resolve,这里换成可观察的桩
  h.caller = () => { const got = []; h.native(r => got.push(JSON.parse(JSON.stringify(r)))); return got; };
  h.agreeRequests = () => h.requests.filter(r => r.url === '/api/compliance/consents' && JSON.parse(r.data).eventType === 'AGREE');
  h.replyOk = () => {
    for (const r of h.requests) {
      if (r._replied) continue; r._replied = true;
      const b = JSON.parse(r.data);
      r.success({ code: 200, data: { requestId: b.requestId, docType: b.docType, scene: b.scene, eventType: b.eventType, docVersion: VERSIONS[b.docType] } });
    }
  };
  return h;
}

function load(h, file, kind) {
  let def;
  const full = path.join(ROOT, file);
  vm.runInNewContext(fs.readFileSync(full, 'utf8'), {
    Page: v => { def = v; }, Component: v => { def = v; }, getApp: () => h.app, getCurrentPages: () => h.pages,
    wx: global.wx, console, require: createRequire(full), setTimeout, clearTimeout,
  });
  const inst = Object.assign({}, def, def.methods || {}, { data: JSON.parse(JSON.stringify(def.data)), events: [] });
  inst.setData = (patch, cb) => { Object.assign(inst.data, patch); if (cb) cb(); };
  inst.triggerEvent = (name, detail) => inst.events.push({ name, detail });
  if (kind === 'page') inst.route = 'pages/privacy/index';
  return inst;
}
const hostPage = (h) => ({ route: 'pages/shezhi/shezhi', showPrivacyGate() { h.gateShows += 1; } });
const PENDING = 'cy_usage_pending_v1:9';
const ids = (h) => h.requests.map(r => JSON.parse(r.data).docType + ':' + JSON.parse(r.data).requestId);

test('SC-PRIV-05 闸:连点同意只走一趟;提交中点拒绝被忽略;每个调用方只结算一次 agree;共 2 个留痕请求', async () => {
  const h = harness();
  h.pages.push(hostPage(h));
  const callers = [h.caller(), h.caller()];
  const gate = load(h, 'components/cy/privacy-gate/index.js');
  gate.data.serviceAgreed = true;
  gate.agreePrivacy({ detail: { buttonId: 'b1' } });
  gate.agreePrivacy({ detail: { buttonId: 'b2' } });
  gate.disagreePrivacy();
  await settle(); h.replyOk(); await settle(); h.replyOk(); await settle();
  gate.agreePrivacy({ detail: { buttonId: 'b3' } }); // 已结算后再点:不再结算调用方
  await settle(); h.replyOk(); await settle();
  for (const c of callers) assert.deepEqual(c, [{ event: 'agree', buttonId: 'b1' }]);
  assert.equal(h.logins, 2, '第三次点击是已结算后的新提交，前两次连点只登录一次');
  assert.equal(h.agreeRequests().length, 2, '待补传清空后再次同意不重复留痕');
  assert.equal(h.requests.filter(r => JSON.parse(r.data).eventType !== 'AGREE').length, 0);
  assert.equal(h.app.getUsageConsent().granted(), true, '提交中的拒绝不得清掉同意');
  assert.deepEqual(gate.events.map(e => e.detail.event), ['agree', 'agree']);
});

test('SC-PRIV-05 路由页:连点同意只一趟、提交中拒绝无效、成功后只返回一次', async () => {
  const h = harness();
  h.pages.push({ route: 'pages/legacy/no-gate' });
  const callers = [h.caller(), h.caller()];
  h.navs[0].complete();
  const page = load(h, 'pages/privacy/index.js', 'page');
  h.pages.push(page); page.onLoad();
  page.data.serviceAgreed = true;
  page.agreePrivacy({ detail: { buttonId: 'p1' } });
  page.agreePrivacy({ detail: { buttonId: 'p2' } });
  page.disagreePrivacy();
  assert.equal(page.data.submitting, true);
  await settle(); h.replyOk(); await settle(); h.replyOk(); await settle();
  page.onUnload(); // navigateBack 之后的卸载不得把同意翻成拒绝
  for (const c of callers) assert.deepEqual(c, [{ event: 'agree', buttonId: 'p1' }]);
  assert.equal(h.logins, 1);
  assert.equal(h.requests.length, 2);
  assert.equal(h.backs, 1);
  assert.equal(h.app.getUsageConsent().granted(), true);
});

test('SC-PRIV-05 未勾选服务协议导致提交失败:调用方不结算,页面留错误;勾选后重试只结算一次', async () => {
  const h = harness();
  h.pages.push({ route: 'pages/legacy/no-gate' });
  const caller = h.caller();
  h.navs[0].complete();
  const page = load(h, 'pages/privacy/index.js', 'page');
  h.pages.push(page); page.onLoad();
  page.agreePrivacy({ detail: { buttonId: 'p1' } });
  await settle();
  assert.deepEqual(caller, []);
  assert.equal(page.data.failedAction, 'agree'); assert.ok(page.data.submitError);
  assert.equal(page.data.submitting, false); assert.equal(h.requests.length, 0); assert.equal(h.backs, 0);
  page.data.serviceAgreed = true;
  page.retrySubmit();
  page.retrySubmit();
  await settle(); h.replyOk(); await settle(); h.replyOk(); await settle();
  assert.deepEqual(caller, [{ event: 'agree', buttonId: 'p1' }]);
  assert.equal(h.requests.length, 2); assert.equal(h.backs, 1);
});

test('SC-PRIV-05 F-L5C-10:明确同意后结算 agree，留痕失败保留待补传；重试复用幂等号、不再结算', async () => {
  const h = harness();
  h.pages.push(hostPage(h));
  const callers = [h.caller(), h.caller()];
  const gate = load(h, 'components/cy/privacy-gate/index.js');
  gate.data.serviceAgreed = true;
  gate.agreePrivacy({ detail: { buttonId: 'g1' } });
  // usage-consent.js:79-83 先 resolveNative(agree) 并写本机选择,再登录与留痕
  for (const c of callers) assert.deepEqual(c, [{ event: 'agree', buttonId: 'g1' }]);
  await settle();
  assert.equal(h.requests.length, 1);
  const failed = h.requests[0]; failed._replied = true;
  failed.success({ code: 500, msg: '服务繁忙' });
  await settle();
  // 现状:留痕失败,但调用方结果已是 agree;闸以 recordPending 结算并提示稍后补记
  assert.deepEqual(JSON.parse(JSON.stringify(gate.events.map(e => e.detail))), [{ event: 'agree', recordPending: true }]);
  assert.ok(h.toasts.includes('已同意，联网后会自动补记同意记录'));
  assert.equal(Object.keys(h.storage.get(PENDING).pending).length, 2, '两份协议都仍待补传');

  // 重试(登录/续期成功后 app.js 调 syncPending):复用失败那次的幂等号
  const retry = h.app.getUsageConsent().syncPending();
  await settle();
  assert.equal(h.requests.length, 2);
  assert.equal(JSON.parse(h.requests[1].data).requestId, JSON.parse(failed.data).requestId);
  h.replyOk(); await settle(); h.replyOk(); await retry;
  assert.equal(h.requests.length, 3, '1 次失败 + 2 份协议各成功 1 次');
  assert.equal(new Set(ids(h)).size, 2);
  assert.deepEqual(h.storage.get(PENDING).pending, {});
  for (const c of callers) assert.equal(c.length, 1, '重试不得再次结算调用方');
});
