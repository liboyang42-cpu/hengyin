'use strict';
// SC-PRIV-04:隐私授权宿主链路
//   app.js wx.onNeedPrivacyAuthorization → 当前页 showPrivacyGate()(cy-privacy-gate 真弹窗)
//   → 当前页没挂闸时回退 navigateTo /pages/privacy/index。
// 同意 / 拒绝 / 系统返回 / 宿主销毁 / 多个待决调用方:每个调用方都拿到明确结果,不永久挂起,拒绝不记 AGREE。
// 原生回调要真机,这里装真 app.js,并用桩 resolve 直接调用它注册给 wx.onNeedPrivacyAuthorization 的 handler。
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
  // 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:单独同意隐私不再建号;这里的待决调用方来自
  // 写前登录(游客点了「微信登录」、登录在途),夹具据此标记登录在途,同意后照旧补传留痕。
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
// 真机 navigateTo 失败:先 fail 再 complete。
const failNav = (nav) => { if (nav.fail) nav.fail({ errMsg: 'navigateTo:fail webview count limit exceed' }); nav.complete(); };
const hostPage = (h) => ({ route: 'pages/shezhi/shezhi', showPrivacyGate() { h.gateShows += 1; } });

test('SC-PRIV-04 宿主页有闸:多个待决调用方→弹真闸不跳路由;同意后每个调用方各拿到一次 agree', async () => {
  const h = harness();
  h.pages.push(hostPage(h));
  const callers = [h.caller(), h.caller(), h.caller()];
  assert.ok(h.gateShows >= 1); assert.equal(h.navs.length, 0, '有闸的宿主不得回退路由页');
  assert.ok(callers.every(c => c.length === 0));
  const gate = load(h, 'components/cy/privacy-gate/index.js');
  gate.data.serviceAgreed = true;
  gate.agreePrivacy({ detail: { buttonId: 'agree-btn' } });
  for (const c of callers) assert.deepEqual(c, [{ event: 'agree', buttonId: 'agree-btn' }]);
  await settle(); h.replyOk(); await settle(); h.replyOk(); await settle();
  assert.deepEqual(gate.events.map(e => e.detail.event), ['agree']);
  assert.equal(h.agreeRequests().length, 2, 'privacy_policy + user_service_agreement 各一次');
  // 同意后宿主销毁不得把已同意翻成拒绝
  gate.lifetimes.detached.call(gate);
  for (const c of callers) assert.equal(c.length, 1);
  assert.equal(h.app.getUsageConsent().granted(), true);
});

test('SC-PRIV-04 闸内拒绝:所有待决调用方拿到 disagree,零 AGREE 请求,本机不留同意', async () => {
  const h = harness();
  h.pages.push(hostPage(h));
  const callers = [h.caller(), h.caller()];
  const gate = load(h, 'components/cy/privacy-gate/index.js');
  gate.disagreePrivacy();
  for (const c of callers) assert.deepEqual(c, [{ event: 'disagree' }]);
  await settle();
  assert.equal(h.requests.length, 0);
  assert.equal(h.app.getUsageConsent().granted(), false);
  assert.deepEqual(gate.events.map(e => e.detail.event), ['disagree']);
});

test('SC-PRIV-04 宿主页销毁(闸 detached):待决调用方按 disagree 结算,不挂起也不记 AGREE', async () => {
  const h = harness();
  h.pages.push(hostPage(h));
  const callers = [h.caller(), h.caller()];
  const gate = load(h, 'components/cy/privacy-gate/index.js');
  gate.lifetimes.detached.call(gate);
  for (const c of callers) assert.deepEqual(c, [{ event: 'disagree', reason: 'gate_detached' }]);
  await settle();
  assert.equal(h.agreeRequests().length, 0);
  assert.equal(h.app.getUsageConsent().granted(), false);
});

test('SC-PRIV-04 回退路由页:并发调用方只压一次隐私页;系统返回(onUnload)全部按 disagree 结算', async () => {
  const h = harness();
  h.pages.push({ route: 'pages/legacy/no-gate' });
  const callers = [h.caller(), h.caller()];
  assert.equal(h.navs.length, 1); assert.equal(h.navs[0].url, '/pages/privacy/index');
  h.navs[0].complete();
  const page = load(h, 'pages/privacy/index.js', 'page');
  h.pages.push(page);
  // 第三个调用方在隐私页已在栈顶时到达:不再压页,同页结算
  callers.push(h.caller());
  assert.equal(h.navs.length, 1);
  page.onLoad();
  assert.equal(page.data.pendingAuthorization, true);
  page.onUnload(); // 系统返回
  for (const c of callers) assert.deepEqual(c, [{ event: 'disagree', reason: 'page_unload' }]);
  await settle();
  assert.equal(h.agreeRequests().length, 0);
});

test('SC-PRIV-04 回退路由页:页内点「不同意」结算全部调用方,随后的 onUnload 不重复结算', async () => {
  const h = harness();
  h.pages.push({ route: 'pages/legacy/no-gate' });
  const callers = [h.caller(), h.caller()];
  h.navs[0].complete();
  const page = load(h, 'pages/privacy/index.js', 'page');
  h.pages.push(page); page.onLoad();
  page.disagreePrivacy();
  page.onUnload();
  for (const c of callers) assert.deepEqual(c, [{ event: 'disagree' }]);
  assert.equal(h.backs, 1);
  await settle();
  assert.equal(h.agreeRequests().length, 0);
});

test('SC-PRIV-04 回退路由 navigateTo 失败(如页面栈满):每个待决调用方恰好拿到一次 disagree,不挂起也不记 AGREE', async () => {
  const h = harness();
  h.pages.push({ route: 'pages/legacy/no-gate' });
  const callers = [h.caller(), h.caller()];
  assert.equal(h.navs.length, 1, '并发调用方只压一次隐私页');
  failNav(h.navs[0]);
  for (const c of callers) assert.deepEqual(c, [{ event: 'disagree' }]);
  assert.equal(h.app._privacyResolvers.length, 0);
  assert.equal(h.app._privacyNavigationPending, false, 'complete 必须清掉在途标记');
  // 下一次触发能重新尝试打开隐私页,而不是被残留的在途标记吞掉
  const next = h.caller();
  assert.equal(h.navs.length, 2);
  assert.deepEqual(next, []);
  await settle();
  assert.equal(h.agreeRequests().length, 0);
  assert.equal(h.app.getUsageConsent().granted(), false);
});
