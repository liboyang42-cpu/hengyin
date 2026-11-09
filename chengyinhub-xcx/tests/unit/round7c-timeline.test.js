'use strict';
// 第七轮 C 第 1 项:朋友圈分享与单页模式(场景 1154)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const singlePage = require('../../utils/single-page-mode.js');
const { createRequireLogin } = require('../../utils/auth/require-login.js');

const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const plain = (v) => JSON.parse(JSON.stringify(v));

// 只为取到 Page/Component 定义对象:真实模块能加载就用真实的,加载不了给空桩。
function loadDefinition(file, kind) {
  let definition;
  const localRequire = createRequire(path.join(root, file));
  const stub = new Proxy(function () {}, { get: (t, k) => (k === Symbol.toPrimitive ? () => '' : stub), apply: () => stub });
  const app = { globalData: { statusBarHeight: 20, navBarHeight: 44 }, getImgUrl: (v) => v, getUserID: () => '', sendRequest() {} };
  vm.runInNewContext(read(file), {
    getApp: () => app, wx: {}, console, setTimeout, clearTimeout, getCurrentPages: () => [],
    [kind || 'Page']: (def) => { definition = def; }, Behavior: (b) => b,
    require(name) { try { return localRequire(name); } catch (e) { return stub; } },
    module: {}, exports: {},
  }, { filename: file });
  return definition;
}

test('单页模式判定:场景 1154,优先 getEnterOptionsSync,退化到 getLaunchOptionsSync', () => {
  assert.equal(singlePage.isSinglePageMode({ getEnterOptionsSync: () => ({ scene: 1154 }) }), true);
  assert.equal(singlePage.isSinglePageMode({ getEnterOptionsSync: () => ({ scene: 1007 }), getLaunchOptionsSync: () => ({ scene: 1154 }) }), false);
  assert.equal(singlePage.isSinglePageMode({ getLaunchOptionsSync: () => ({ scene: 1154 }) }), true);
  assert.equal(singlePage.isSinglePageMode({ getEnterOptionsSync: () => { throw Error('x'); } }), false);
  assert.equal(singlePage.isSinglePageMode({}), false);
});

test('单页模式守卫:登录/跳页/支付不再无声失败,给提示并走 fail/complete;切回完整模式原样透传', () => {
  let scene = 1154;
  const calls = [];
  const notes = [];
  const api = { getEnterOptionsSync: () => ({ scene }) };
  singlePage.GUARDED_APIS.forEach((name) => { api[name] = (o) => { calls.push(name); return 'native'; }; });
  let t = 0;
  const notify = singlePage.createNotifier((text) => notes.push(text), () => t);
  const wrapped = singlePage.installSinglePageGuard(api, notify);
  assert.deepEqual(wrapped, singlePage.GUARDED_APIS);
  const results = [];
  api.navigateTo({ url: '/pages/x', fail: (r) => results.push(r.errMsg), complete: () => results.push('complete') });
  api.login({ fail: (r) => results.push(r.errMsg) });
  assert.deepEqual(calls, []);
  assert.deepEqual(results, ['navigateTo:fail single page mode', 'complete', 'login:fail single page mode']);
  assert.deepEqual(notes, [singlePage.SINGLE_PAGE_NOTICE], '连续点击 1.5s 内只提示一次');
  t = 2000; api.requestPayment({});
  assert.equal(notes.length, 2);
  scene = 1001;
  assert.equal(api.navigateTo({ url: '/pages/x' }), 'native');
  assert.deepEqual(calls, ['navigateTo']);
  assert.deepEqual(singlePage.installSinglePageGuard(api, notify), [], '不重复安装');
});

test('单页模式守卫:宿主 wx 对未知键返回函数(代理)时照样安装', () => {
  const target = { getEnterOptionsSync: () => ({ scene: 1154 }), navigateTo: () => 'native' };
  const api = new Proxy(target, { get: (t, k) => (k in t ? t[k] : () => {}) });
  const notes = [];
  assert.ok(singlePage.installSinglePageGuard(api, () => notes.push(1)).includes('navigateTo'));
  assert.equal(api.navigateTo({}), undefined);
  assert.equal(notes.length, 1);
});

test('app.js 只在单页模式安装守卫,单页模式不跳隐私页', () => {
  const src = read('app.js');
  assert.match(src, /if \(singlePage\.isSinglePageMode\(wx\)\) \{\n\s*singlePage\.installSinglePageGuard\(wx,/);
  assert.match(src, /if \(!singlePage\.isSinglePageMode\(wx\)\) setTimeout\(\(\) => \{ if \(!that\._usageDeclined\) that\.openPrivacyAuthorizationPage\(\); \}, 0\);/);
});

test('登录闸:单页模式不弹「登录后继续」、不调 firstLogin,只提示去完整小程序', async () => {
  const shown = [];
  const notes = [];
  let logins = 0;
  const app = { getUserID: () => '', getAuthorization: () => '', firstLogin: () => { logins += 1; return Promise.resolve({ ok: true }); } };
  const guard = createRequireLogin({ getApp: () => app, show: (o) => shown.push(o), getPages: () => null,
    isSinglePage: () => true, notifySinglePage: () => notes.push('notice') });
  assert.equal(await guard.requireLogin({ reason: 'club' }), false);
  let resumed = false;
  assert.equal(await guard.withLogin({ reason: 'signup' }, () => { resumed = true; }), false);
  assert.deepEqual(shown, []);
  assert.equal(logins, 0);
  assert.equal(resumed, false);
  assert.equal(notes.length, 2);
});

test('主题详情朋友圈:主题名 + 封面 + id', () => {
  const def = loadDefinition('pages/topic/index/index.js');
  const ctx = { data: { id: 42, info: { name: '老城夜行', imgUrl: 'https://img.example/a.jpg' } } };
  ctx.onShareAppMessage = def.onShareAppMessage;
  assert.deepEqual(plain(def.onShareTimeline.call(ctx)), { title: '老城夜行', query: 'id=42', imageUrl: 'https://img.example/a.jpg' });
});

test('官方活动朋友圈:活动名 + 封面 + id', () => {
  const def = loadDefinition('pages/activity/official-detail/index.js');
  const ctx = { data: { id: '15', e: { title: '城市夜跑', coverImg: 'https://img.example/b.jpg' } } };
  ctx.onShareAppMessage = def.onShareAppMessage;
  assert.deepEqual(plain(def.onShareTimeline.call(ctx)), { title: '城市夜跑', query: 'id=15', imageUrl: 'https://img.example/b.jpg' });
});

test('俱乐部朋友圈:俱乐部名 + 封面(无封面取 Logo)+ 只带主页 id', () => {
  const def = loadDefinition('pages/club/detail/index.js');
  assert.deepEqual(plain(def.onShareTimeline.call({ data: { clubId: 9, club: { name: '夜行社', cover: 'https://img.example/c.jpg', logo: 'https://img.example/l.jpg' } } })),
    { title: '夜行社', query: 'id=9', imageUrl: 'https://img.example/c.jpg' });
  assert.deepEqual(def.onShareTimeline.call({ data: { clubId: 9, club: { name: '夜行社', logo: 'https://img.example/l.jpg' } } }).imageUrl, 'https://img.example/l.jpg');
});

test('商家主页朋友圈:只对可公开商家开放,query 为 canonical userId + tab=about', () => {
  const page = loadDefinition('pages/userinfo/userinfo.js');
  const comp = loadDefinition('subpackageMember/components/cy/profile/index.js', 'Component');
  const getTimelinePayload = comp.methods.getTimelinePayload;
  const merchant = { data: { userId: '88', subjectIsMerchant: true, subjectMerchant: { name: '巷口咖啡', coverImage: 'https://img.example/m.jpg' } } };
  assert.deepEqual(plain(getTimelinePayload.call(merchant)), { title: '巷口咖啡', query: 'userId=88&tab=about', imageUrl: 'https://img.example/m.jpg' });
  assert.equal(getTimelinePayload.call({ data: { userId: '88', subjectIsMerchant: false } }), null);
  const host = { data: { userId: '88' }, selectComponent: () => ({ getTimelinePayload: () => getTimelinePayload.call(merchant) }) };
  assert.equal(page.onShareTimeline.call(host).query, 'userId=88&tab=about');
  // 默认关朋友圈菜单,资料组件确认商家后打开
  assert.match(read('pages/userinfo/userinfo.js'), /wx\.hideShareMenu\(\{ menus: \['shareTimeline'\]/);
  assert.match(read('pages/userinfo/userinfo.wxml'), /bind:subjectmerchant="onSubjectMerchant"/);
  assert.match(read('subpackageMember/components/cy/profile/index.js'), /this\.triggerEvent\('subjectmerchant'/);
});

test('单页模式下打不开的页面不提供朋友圈:游玩页、成长中心', () => {
  ['pages/play/index.js', 'subpackageP3/pages/growthcenter/index/index.js'].forEach((file) => {
    assert.doesNotMatch(read(file).replace(/\/\/[^\n]*/g, ''), /onShareTimeline\s*\(/, file);
  });
});

test('第九轮 m4:据点详情恢复朋友圈,路径仍是本页,只带 poiId / merchantId', () => {
  const def = loadDefinition('subpackageRoam/poi-detail/index.js');
  const ctx = { data: { poiId: '77' }, _merchantId: '', selectComponent: () => null };
  ctx._shareSnapshot = def._shareSnapshot;
  const tl = plain(def.onShareTimeline.call(ctx));
  assert.equal(tl.query, 'poiId=77');
  assert.equal(tl.title, '这个节点等你到店打卡');
  assert.ok(tl.imageUrl);
  assert.equal(tl.path, undefined, '朋友圈不能自定义路径');
  const m = { data: { poiId: '' }, _merchantId: '9', selectComponent: () => null, _shareSnapshot: def._shareSnapshot };
  assert.equal(def.onShareTimeline.call(m).query, 'merchantId=9');
});

test('第九轮 m4:官方路线详情有好友与朋友圈分享,落到同一详情页同一 id', () => {
  const def = loadDefinition('subpackageRoam/official-route/index.js');
  const ctx = { _id: '12', data: { state: 'ready', route: { name: '西湖一圈', cover: 'https://img.example/r.jpg' } } };
  ctx._shareCard = def._shareCard;
  assert.deepEqual(plain(def.onShareAppMessage.call(ctx)),
    { title: '西湖一圈', path: '/subpackageRoam/official-route/index?id=12', imageUrl: 'https://img.example/r.jpg' });
  assert.deepEqual(plain(def.onShareTimeline.call(ctx)), { title: '西湖一圈', query: 'id=12', imageUrl: 'https://img.example/r.jpg' });
  const loading = { _id: '12', data: { state: 'loading', route: null }, _shareCard: def._shareCard };
  const card = def.onShareAppMessage.call(loading);
  assert.equal(card.title, '城瘾官方线路');
  assert.ok(card.imageUrl, '没加载到时用固定封面,不让微信截屏');
});
