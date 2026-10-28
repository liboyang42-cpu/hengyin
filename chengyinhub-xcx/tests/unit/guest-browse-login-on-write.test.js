'use strict';
// owner 2026-10-24「游客可浏览，写入时弹窗登录」:
//   A) 冷启动没有缓存会话 → 不 wx.login、不 /api/login/code(不在打开时建号);有缓存会话照旧保持登录。
//   B) 主要读页游客可看(无 token 的 mock 请求)。
//   C) 写入口先过唯一的写前登录闸(utils/auth/require-login.js,经 app.ensureLoginFor / app.requireLogin);
//      弹窗复用 cy-modal:「登录后继续」/「暂不登录」/「微信登录」;登录后原动作恢复恰好一次。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createSessionStore } = require('../../utils/session/session-store.js');
const { createSessionManager } = require('../../utils/session/session-manager.js');
const guard = require('../../utils/auth/require-login.js');

const root = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const settle = async () => { for (let i = 0; i < 40; i++) await Promise.resolve(); };

function mountApp({ consent = true, cached = false } = {}) {
  let app; const storage = new Map(), requests = [], logins = [], pages = [{}];
  if (consent) storage.set('cy_usage_choice_v1', { privacy_policy: 'v2.1', user_service_agreement: 'v2.2' });
  const wx = { getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    request: r => { requests.push(r); return { abort() {} }; }, login: r => logins.push(r), showLoading() {}, hideLoading() {}, hideToast() {} };
  vm.runInNewContext(read('app.js'), { App: a => { app = a; }, wx, console, setTimeout, clearTimeout, getCurrentPages: () => pages,
    require: f => f === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(root, f)) });
  app.getSession = (() => { const session = createSessionStore({ target: app.globalData, wx }); return () => session; })();
  app.isDevEnv = () => false; app.globalData.siteBaseUrl = 'http://127.0.0.1:8129';
  app.openPrivacyAuthorizationPage = () => {};
  if (cached) { app.getSession().setUserID('7'); app.getSession().setAuthorization('cached-token'); }
  return { app, wx, storage, requests, logins, pages };
}

// ---------- A) 冷启动 ----------

test('A 冷启动无缓存会话:不调 wx.login、不请求 /api/login/code,保持游客,初始化照常完成', async () => {
  const h = mountApp({ consent: true, cached: false });
  await h.app.appInitial({}); await settle();
  assert.equal(h.logins.length, 0, '打开小程序不得 wx.login');
  assert.equal(h.requests.some(r => r.url.endsWith('/api/login/code')), false, '打开小程序不得换 token 建号');
  assert.ok(h.requests.some(r => r.url.endsWith('/api/config/features')), '公开功能开关照常拉取');
  assert.ok(!h.app.getUserID()); assert.ok(!h.app.getAuthorization());
});

test('A 冷启动有缓存会话:老用户照旧保持登录,也不发多余的 wx.login', async () => {
  const h = mountApp({ consent: true, cached: true });
  await h.app.appInitial({}); await settle();
  assert.equal(h.logins.length, 0);
  assert.equal(h.requests.some(r => r.url.endsWith('/api/login/code')), false);
  assert.equal(h.app.getUserID(), '7'); assert.equal(h.app.getAuthorization(), 'cached-token');
});

test('A 未同意隐私:隐私闸仍在首个数据处理接口之前,启动不建号', async () => {
  const h = mountApp({ consent: false, cached: false });
  await h.app.appInitial({}); await settle();
  assert.equal(h.logins.length, 0); assert.equal(h.requests.some(r => r.url.endsWith('/api/login/code')), false);
  assert.equal(h.app.getUsageConsent().granted(), false);
});

test('A 源码:onLaunch/appInitial 只在 hasCachedSession 时走 firstLogin(契约 #18/#19 的「不阻断初始化」保留)', () => {
  const src = read('app.js');
  const body = src.slice(src.indexOf('appInitial: function'), src.indexOf('onPageNotFound'));
  assert.match(body, /if \(!that\.hasCachedSession\(\)\) \{\s*that\.loadFeatureFlags\(\);\s*return Promise\.resolve\(\);/);
  assert.match(src, /hasCachedSession: function \(\) \{\s*return !!\(this\.getUserID\(\) && this\.getAuthorization\(\)\);/);
});

// ---------- C) 写前登录闸 ----------

function guardHarness({ loggedIn = false, confirm = true, loginOk = true } = {}) {
  const shown = []; let logins = 0;
  const app = { DEV_TOKEN: 'dev-token', id: loggedIn ? '7' : '', token: loggedIn ? 'T' : '',
    getUserID() { return this.id; }, getAuthorization() { return this.token; },
    firstLogin() { logins++; if (loginOk) { this.id = '7'; this.token = 'NEW'; } return Promise.resolve({ ok: loginOk }); } };
  const g = guard.createRequireLogin({ getApp: () => app, show: opts => { shown.push(opts); opts.success({ confirm, cancel: !confirm }); } });
  return { g, app, shown, logins: () => logins };
}

test('C 已登录:requireLogin 直接 true,不弹窗、不登录', async () => {
  const h = guardHarness({ loggedIn: true });
  assert.equal(await h.g.requireLogin({ reason: 'club' }), true);
  assert.equal(h.shown.length, 0); assert.equal(h.logins(), 0);
});

test('C 游客:弹窗文案复用 cy-modal 形状 —— 登录后继续 / 一句话动作 / 暂不登录 / 微信登录', async () => {
  const h = guardHarness({ confirm: false });
  assert.equal(await h.g.requireLogin({ reason: 'club' }), false);
  assert.equal(h.shown.length, 1);
  assert.equal(h.shown[0].title, '登录后继续'); assert.equal(h.shown[0].content, '登录后即可加入俱乐部');
  assert.equal(h.shown[0].cancelText, '暂不登录'); assert.equal(h.shown[0].confirmText, '微信登录');
  assert.equal(h.shown[0].showCancel, true); assert.equal(h.logins(), 0, '暂不登录不得建号');
  assert.equal(guard.resolveReason({}), '登录后即可报名、加入俱乐部和发帖');
  const src = read('utils/auth/require-login.js');
  assert.match(src, /require\('\.\.\/modal\.js'\)/, '弹窗必须走 utils/modal.js → cy-modal-host,不另做视觉');
});

test('C 游客点「微信登录」:走 app.firstLogin(此刻才建号),成功后 withLogin 恢复原动作恰好一次', async () => {
  const h = guardHarness({ confirm: true });
  let resumed = 0;
  const ok = await h.g.withLogin({ reason: 'post' }, () => { resumed++; });
  assert.equal(ok, true); assert.equal(resumed, 1); assert.equal(h.logins(), 1);
  await h.g.withLogin({ reason: 'post' }, () => { resumed++; });
  assert.equal(resumed, 2, '登录后再点:直接执行,不再弹窗'); assert.equal(h.shown.length, 1);
});

test('C 登录失败:不恢复原动作', async () => {
  const h = guardHarness({ confirm: true, loginOk: false });
  let resumed = 0;
  assert.equal(await h.g.withLogin({ reason: 'signup' }, () => { resumed++; }), false);
  assert.equal(resumed, 0);
});

test('C 并发写入口共享一个弹窗', async () => {
  const shown = []; let settleModal;
  const app = { id: '', token: '', getUserID() { return this.id; }, getAuthorization() { return this.token; },
    firstLogin() { this.id = '7'; this.token = 'N'; return Promise.resolve({ ok: true }); } };
  const g = guard.createRequireLogin({ getApp: () => app, show: o => { shown.push(o); settleModal = o.success; } });
  const a = g.requireLogin({ reason: 'like' }), b = g.requireLogin({ reason: 'post' });
  settleModal({ confirm: true });
  assert.deepEqual(await Promise.all([a, b]), [true, true]); assert.equal(shown.length, 1);
});

test('C app.ensureLoginFor:已登录同步放行;游客返回 false 并在登录后恢复一次', async () => {
  const h = mountApp({ consent: true, cached: true });
  let resumed = 0;
  assert.equal(h.app.ensureLoginFor('club', () => { resumed++; }), true); assert.equal(resumed, 0);
  const g = mountApp({ consent: true, cached: false });
  const shown = [];
  g.app._loginGuard = guard.createRequireLogin({ getApp: () => g.app, show: o => { shown.push(o); o.success({ confirm: true }); } });
  assert.equal(g.app.ensureLoginFor('club', () => { resumed++; }), false);
  await settle();
  g.logins.at(-1).success({ code: 'c' }); await settle();
  const exchange = g.requests.find(r => r.url.endsWith('/api/login/code'));
  assert.ok(exchange, '「微信登录」才换 token 建号');
  exchange.success({ statusCode: 200, data: { token: 'NEW', data: { id: '8', userType: 1, role: 'player' } } }); await settle();
  assert.equal(shown[0].title, '登录后继续'); assert.equal(resumed, 1); assert.equal(g.app.getUserID(), '8');
});

// 写入口清单:每个都在函数体第一行先过闸(登录后 resume 原函数恰好一次)。
const WRITE_ENTRIES = [
  ['pages/activity/baoming/baoming.js', 'handlePayment', 'checkout'],
  ['pages/activity/baoming/baoming.js', 'joinWaitlist', 'signup'],
  ['pages/activity/official-detail/index.js', 'doSignup', 'signup'],
  ['pages/topic/index/index.js', 'bmClick', 'signup'],
  ['pages/topic/index/index.js', 'selfPlayBuy', 'checkout'],
  ['pages/topic/index/index.js', 'onSelfPlayGoCheckout', 'checkout'],
  ['pages/topic/index/index.js', 'toggleFav', 'like'],
  ['pages/topic/index/index.js', 'submitComment', 'post'],
  ['pages/topic/index/index.js', 'reportTopic', 'report'],
  ['pages/club/detail/index.js', 'onJoin', 'club'],
  ['pages/talent/list/index.js', 'onClubJoin', 'club'],
  // 2026-10-27 P-20:俱乐部发帖改走 cy-post-compose(下面 onPublish 那一条),页内 onPostSubmit 已撤
  ['pages/club/detail/index.js', 'onCommentSubmit', 'post'],
  ['pages/club/detail/index.js', 'onLikePost', 'like'],
  ['pages/club/detail/index.js', 'onReportPost', 'report'],
  ['pages/club/detail/index.js', 'onReportComment', 'report'],
  ['pages/club/detail/index.js', 'reportClubMember', 'report'],
  ['pages/club/detail/index.js', 'reportClub', 'report'],
  ['pages/club/detail/index.js', 'reportTopicActivity', 'report'],
  ['pages/square/list/index.js', 'lickClick', 'like'],
  ['pages/square/list/index.js', 'favoriteClick', 'like'],
  ['pages/square/list/index.js', 'followClick', 'like'],
  ['pages/square/list/index.js', 'reportSquare', 'report'],
  // 2026-10-27 P-20:广场行内发布框(submitFormData)撤掉,新建帖同走 cy-post-compose 的 onPublish(下方已列)
  ['pages/square/detail/index.js', 'addComment', 'post'],
  ['pages/square/detail/index.js', 'addReplyComment', 'post'],
  ['pages/square/detail/index.js', 'followClick', 'like'],
  ['pages/square/detail/index.js', 'lickClick', 'like'],
  ['pages/square/detail/index.js', 'favoriteClick', 'like'],
  ['pages/square/detail/index.js', 'commentFollowClick', 'like'],
  ['pages/square/detail/index.js', 'commentLikeClick', 'like'],
  ['pages/square/detail/index.js', 'reportSquare', 'report'],
  ['pages/square/detail/index.js', 'reportSquareFromActions', 'report'],
  ['pages/square/detail/index.js', 'reportComment', 'report'],
  ['pages/square/components/cy/post-compose/index.js', 'onPublish', 'post'],
  ['pages/team/join/index.js', 'join', 'team'],
  ['pages/templatedetail/topic-preview/index.js', 'useTemplate', 'publish'],
  ['pages/publish/fabu/index.js', 'submitForm', 'publish'],
  ['pages/publish/fabu/index.js', 'confirmPublishCheck', 'publish'],
  ['pages/publish/activity/index.js', 'submitForm', 'publish'],
  ['pages/publish/temp/index.js', 'publishGame', 'publish'],
  ['pages/publish/temp/index.js', 'saveDraft', 'publish'],
  ['subpackageMember/components/cy/report-sheet/index.js', 'submit', 'report'],
];

for (const [file, name, reason] of WRITE_ENTRIES) {
  test('C 写入口先过写前登录闸: ' + file + ' ' + name, () => {
    const src = read(file);
    const head = new RegExp('^ {2,4}' + name + '(?:\\s*:\\s*function\\s*)?\\s*\\(([^)]*)\\)\\s*\\{[ \\t]*\\n([^\\n]*)\\n', 'm');
    const m = src.match(head);
    assert.ok(m, file + ' 找不到 ' + name);
    assert.match(m[2], new RegExp("if \\(app\\.ensureLoginFor && !app\\.ensureLoginFor\\('" + reason + "', \\(\\) => this\\." + name + '\\('),
      name + ' 第一行必须是写前登录闸,登录后恢复的是同一个函数');
    assert.match(m[2], /owner: this, target: \(\) => \[/, name + ' 必须声明原页面/组件和稳定业务身份');
  });
}

test('C 工具层写入口:举报 run() 与建队 createActivityTeam 也先过闸', () => {
  assert.match(read('utils/content-report.js'), /app\.ensureLoginFor && !app\.ensureLoginFor\('report', function \(\) \{ run\(page, app, id, submit\); \}, \{ owner: page, target: \(\) => \[id, page\.data && page\.data\.id, page\.data && page\.data\.clubId\] \}\)\) return false;/);
  assert.match(read('subpackageMember/utils/team-up.js'), /app\.ensureLoginFor && !app\.ensureLoginFor\('team', \(\) => createActivityTeam\(app, activityId, maxMembers, joinMode, callbacks\), callbacks && callbacks\.loginContext\)\) return/);
});

test('C 漫游开局:游客走 app.requireLogin({ reason: roam }),不再静默 firstLogin;取消不报错', () => {
  const src = read('pages/roam/index.js');
  const body = src.slice(src.indexOf('  goStart() {'), src.indexOf('if (this._roamCompleted) this._resetRoamRecoverySession();'));
  assert.match(body, /loginApp\.requireLogin\(\{ reason: 'roam' \}\)/);
  assert.match(body, /if \(ok === false\) return;/);
});

test('C 写入口在游客下的真实运行:俱乐部「加入」先弹窗,登录成功后恢复并只发一次 /api/club/join', async () => {
  const requests = []; const prompts = [];
  let id = '';
  const app = { globalData: {}, getUserID: () => id, getAuthorization: () => (id ? 'T' : ''), getImgUrl: x => x,
    sendRequest(r) { requests.push(r); },
    ensureLoginFor(reason, resume) {
      if (id) return true; prompts.push(reason);
      Promise.resolve().then(() => { id = '9'; resume(); }); return false;
    } };
  let def;
  vm.runInNewContext(read('pages/talent/list/index.js'), { getApp: () => app, Page: p => { def = p; },
    require: f => require(path.resolve(root, 'pages/talent/list', f)), wx: { stopPullDownRefresh() {} }, console, setTimeout, clearTimeout });
  const page = Object.assign({}, def, { data: Object.assign({}, def.data, { joinedClubs: [], nearbyClubs: [{ id: 5 }] }), setData(p) { Object.assign(this.data, p); } });
  page.onClubJoin({ currentTarget: { dataset: { id: 5 } }, detail: { id: 5, club: { id: 5 } } });
  assert.deepEqual(prompts, ['club']); assert.equal(requests.filter(r => r.url === '/api/club/join').length, 0);
  await settle();
  assert.equal(requests.filter(r => r.url === '/api/club/join').length, 1, '登录后恢复加入恰好一次');
});

// ---------- B) 游客读页 ----------

test('B 游客读俱乐部 tab:只读公开目录 /api/club/list,不请求私有聚合', () => {
  const requests = [];
  const app = { globalData: {}, getUserID: () => '', getAuthorization: () => '', getImgUrl: x => x, sendRequest(r) { requests.push(r); } };
  let def;
  vm.runInNewContext(read('pages/talent/list/index.js'), { getApp: () => app, Page: p => { def = p; },
    require: f => require(path.resolve(root, 'pages/talent/list', f)), wx: { stopPullDownRefresh() {} }, console, setTimeout, clearTimeout });
  const page = Object.assign({}, def, { data: Object.assign({}, def.data), setData(p) { Object.assign(this.data, p); } });
  page.loadClubHome();
  assert.deepEqual(requests.map(r => r.url), ['/api/club/list']);
  requests[0].success({ code: 200, data: [{ id: 1, name: '公开团' }] });
  assert.equal(page.data.clubsState, 'ready'); assert.equal(page.data.nearbyClubs.length, 1);
  assert.equal(page.data.clubsLoginRequired, false);
});

test('B 游客读请求被 401 拦下时页面拿到 fail(401),不弹窗、不建号(request-client 读写判定)', () => {
  const { createRequestClient, isWriteRequest } = require('../../utils/transport/request-client.js');
  const calls = []; let prompts = 0, relogins = 0; const events = [];
  const client = createRequestClient({ wxRequest: o => { calls.push(o); return { abort() {} }; }, getBaseUrl: () => 'https://x',
    getUserID: () => '', getAuthorization: () => '', reLogin: cb => { relogins++; cb(true); },
    promptLogin: () => { prompts++; return Promise.resolve(true); } });
  for (const url of ['/api/topic/info-to-user', '/api/club/detail', '/api/creativesquare/info', '/api/club/list']) {
    assert.equal(isWriteRequest({ url }, 'POST'), false, url);
    client.send({ url, method: 'POST', fail: (b, s) => events.push(s) });
    calls.at(-1).success({ statusCode: 401, data: {} });
  }
  assert.deepEqual(events, [401, 401, 401, 401]); assert.equal(prompts, 0); assert.equal(relogins, 0);
});

test('B 游客读页的公开读接口均为后端匿名可读(@Anonymous 或既有白名单)', () => {
  const backend = p => fs.readFileSync(path.join(root, '..', p), 'utf8');
  const topic = backend('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java');
  assert.match(topic, /@Anonymous\s*\n\s*public AjaxResult topicInfo\(String id\)/);
  const club = backend('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java');
  assert.match(club, /@PostMapping\("\/list"\)[\s\S]{0,300}?@Anonymous\s*\n\s*public AjaxResult list\(/);
  assert.match(club, /@PostMapping\("\/detail"\)[\s\S]{0,300}?@Anonymous\s*\n\s*public AjaxResult detail\(/);
  const square = backend('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCreativeSquareController.java');
  assert.match(square, /@Anonymous\s*\n\s*public AjaxResult viewCreativeSquareInfo\(String id\)/);
  const security = backend('chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java');
  for (const wl of ['"/api/activity/list", "/api/topic/list"', '"/api/creativesquare/list"', '"/api/template/homeData"', '"/api/merchant/public-detail"'])
    assert.ok(security.includes(wl), wl);
});

test('B 主题详情页游客渲染:info-to-user 成功即出页;本人收藏/角色等私有读在游客下不发', () => {
  const src = read('pages/topic/index/index.js');
  assert.match(src, /_loadFav\(topicId\) \{\s*if \(!topicId \|\| !this\._loggedIn\(\)\) return;/);
  assert.match(src, /_loadChars\(data\) \{\s*if \(!this\._loggedIn\(\)\) return;/);
});

test('C 扫门口码开始游玩:scan-entry 声明为写请求,游客 401 由请求层弹登录窗;取消不报「码无法打开」', () => {
  const src = read('pages/index/index.js');
  const call = src.slice(src.indexOf("url: '/api/play/scan-entry'"), src.indexOf('finishDoorSceneFallback() {'));
  assert.match(call, /write: true, loginReason: 'play',/);
  assert.match(call, /if \(!\(Number\(status\) === 401 && !app\.getUserID\(\)\)\) cyToast\('这张码无法打开'\);/);
});

// H: exercise the real login helper, app facade and original team Page with a controlled login response.
function continuationFixture() {
  const h = mountApp(), modals = [];
  let definition, finishLogin;
  vm.runInNewContext(read('pages/team/join/index.js'), {
    Page: page => { definition = page; }, getApp: () => h.app,
    require: id => { assert.equal(id, '../../../utils/toast.js'); return () => {}; },
    getCurrentPages: () => h.pages, wx: h.wx, setTimeout: () => 0,
  });
  const page = Object.assign({}, definition, { route: 'pages/team/join/index', inviteCode: 'original-invite' });
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = patch => Object.assign(page.data, patch);
  h.pages.splice(0, h.pages.length, page);
  h.app._loginGuard = guard.createRequireLogin({ getApp: () => h.app, getPages: () => h.pages,
    show: options => modals.push(options) });
  const manager = createSessionManager({ session: h.app.getSession(), wxLogin: () => Promise.resolve({ code: 'controlled-code' }),
    exchangeCode: () => new Promise(resolve => { finishLogin = () => resolve({ token: 'signed-in',
      data: { id: '7', userType: 1, role: 'player' } }); }) });
  h.app.firstLogin = () => manager.ensureSession().then(result => {
    if (h.afterLogin) h.afterLogin();
    return result;
  });
  return Object.assign(h, { page, modals, finish: () => finishLogin(),
    confirm: async () => { modals[0].success({ confirm: true }); await settle(); },
    writes: () => h.requests.filter(request => request.url.endsWith('/api/team/join')) });
}

test('H continuation: login in flight then leaving the original Page must not join', async () => {
  const h = continuationFixture(); h.page.join(); await h.confirm();
  if (h.page.onUnload) h.page.onUnload(); h.pages.length = 0;
  h.finish(); await settle();
  assert.equal(h.writes().length, 0, 'leaving during login must cancel the old join');
});

test('H continuation: changing the original invite must not write the replacement target', async () => {
  const h = continuationFixture(); h.page.join(); await h.confirm();
  h.page.inviteCode = 'replacement-invite'; h.finish(); await settle();
  assert.equal(h.writes().length, 0, 'login cannot migrate the original action to another invite');
});

test('H continuation: same live Page and original invite continue exactly once', async () => {
  const h = continuationFixture(); h.page.join(); h.page.join();
  assert.equal(h.modals.length, 1); assert.equal(h.writes().length, 0);
  await h.confirm(); h.finish(); await settle();
  assert.equal(h.writes().length, 1);
  assert.equal(JSON.parse(h.writes()[0].data).inviteCode, 'original-invite');
});

test('H continuation: hide then return and a different instance of the same route cannot revive the old join', async () => {
  for (const mode of ['hide-return', 'replacement-page']) {
    const h = continuationFixture(); h.page.join(); await h.confirm();
    if (mode === 'hide-return') {
      if (h.page.onHide) h.page.onHide(); if (h.page.onShow) h.page.onShow();
    } else h.pages[0] = { route: h.page.route };
    h.finish(); await settle(); assert.equal(h.writes().length, 0, mode);
  }
});

test('H continuation: account change or same-token A-B-A after login result cannot inherit the original join', async () => {
  for (const aba of [false, true]) {
    const h = continuationFixture(); h.page.join(); await h.confirm();
    h.afterLogin = () => {
      h.app.getSession().clearSession();
      h.app.getSession().applySnapshot({ user_id: '8', authorization: 'other', user_type: 1, role: 'player' });
      if (aba) h.app.getSession().applySnapshot({ user_id: '7', authorization: 'signed-in', user_type: 1, role: 'player' });
    };
    h.finish();
    await settle(); assert.equal(h.writes().length, 0, 'account/session revision changed');
  }
});

test('H continuation: cancelling login preserves pending UNKNOWN storage without dispatch', async () => {
  const h = continuationFixture();
  const voice = { version: 1, active: '11111111-1111-4111-8111-111111111111', records: [{
    requestId: '11111111-1111-4111-8111-111111111111', intentId: '7', previousIntentId: null, state: 'UNKNOWN', createdAt: 1 }] };
  const director = { activityId: '31', requestId: 'original-request', action: 'BROADCAST', nodeId: null, expectedRevision: 0 };
  h.wx.setStorageSync('npc_voice_v1:7:merchant', voice);
  h.wx.setStorageSync('cy.gameDirector.pendingWrite.m7.a31', director);
  h.page.join(); h.modals[0].success({ confirm: false }); await settle();
  assert.equal(h.writes().length, 0);
  assert.deepEqual(h.wx.getStorageSync('npc_voice_v1:7:merchant'), voice);
  assert.deepEqual(h.wx.getStorageSync('cy.gameDirector.pendingWrite.m7.a31'), director);
});

test('H continuation: guest revision ABA while the modal is open cannot start a stale login', async () => {
  const h = continuationFixture(); let loginCalls = 0;
  const login = h.app.firstLogin; h.app.firstLogin = () => { loginCalls++; return login(); };
  h.page.join(); h.app.getSession().setUserID('8'); h.app.getSession().clearSession();
  await h.confirm(); assert.equal(loginCalls, 0); assert.equal(h.writes().length, 0);
});

test('H continuation: real login attempt rejects guest-account ABA before the exchange reply', async () => {
  const h = continuationFixture(); h.page.join(); await h.confirm();
  h.app.getSession().setUserID('8'); h.app.getSession().clearSession();
  h.finish(); await settle();
  assert.equal(h.writes().length, 0); assert.ok(!h.app.getUserID(), 'a retired attempt must not restore an account');
});

test('H continuation: a fresh explicit click after hiding can use its own live context', async () => {
  const h = continuationFixture(); h.page.join(); await h.confirm();
  h.page.onHide(); h.page.join(); h.finish(); await settle();
  assert.equal(h.writes().length, 1, 'only the new explicit action may continue');
  assert.equal(JSON.parse(h.writes()[0].data).inviteCode, 'original-invite');
});
