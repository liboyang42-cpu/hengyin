const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createSessionStore } = require('../../utils/session/session-store.js');
const { createSessionManager } = require('../../utils/session/session-manager.js');
const { MAP_AVATAR_COLORS } = require('../../utils/play-visual-tokens.js');

function mount(uid = '7') {
  const root = path.resolve(__dirname, '../..'); let app, definition;
  const requests = []; const wx = { request: request => { requests.push(request); return { abort() {} }; } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App: value => { app = value; }, wx, console,
    require: file => file === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(root, file)),
  }, { filename: 'app.js' });
  const storage = new Map();
  const session = createSessionStore({ target: app.globalData, wx: {
    setStorageSync: (key, value) => storage.set(key, value), getStorageSync: key => storage.get(key),
    removeStorageSync: key => storage.delete(key),
  } });
  app.getSession = () => session; app.isDevEnv = () => false; app.loadFeatureFlags = () => {};
  app.globalData.siteBaseUrl = 'http://127.0.0.1:8129';
  session.setUserID(uid); session.setAuthorization(uid ? 'fixture-' + uid : '');
  app.getSessionManager = () => createSessionManager({ session, isDevEnv: () => false,
    wxLogin: async () => ({ code: 'fixture-code' }), exchangeCode: async () => ({ code: 401, msg: 'fixture-denied' }),
  });
  const file = path.join(root, 'subpackageRoam/nearby/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: value => { definition = value; },
    getApp: () => app, wx, require: createRequire(file), clearTimeout, setTimeout,
  }, { filename: file });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(data) { Object.assign(this.data, data); }, _queueIcons: () => Promise.resolve(false), _radius: 1000,
  });
  return { app, page, session, requests, actor(id, token = 'fixture-' + id) { session.setUserID(id); session.setAuthorization(token); } };
}
function reply(request, status, data) { request.success({ statusCode: status, data }); }
async function settled() { for (let i = 0; i < 30; i++) await Promise.resolve(); }
const team = { teamId: 42, title: 'fixture team', latitude: 31, longitude: 121, maxMembers: 4, joinedCount: 2 };
const topic = { id: 5, kind: 'topic', productType: 2, name: 'fixture topic', latitude: 31, longitude: 121, distance: 20 };

test('city orientation is absent from map markers, list, counts and category options', () => {
  const h = mount(); h.page.setData({ center: { lat: 31, lng: 121 } }); h.page.refresh(h.page.data.center);
  reply(h.requests[0], 200, { code: 200, data: [] });
  reply(h.requests[1], 200, { code: 200, data: { items: [
    { ...topic, productType: 1 }, { ...topic, id: 6, productType: '1' },
    { ...topic, id: 7, productType: null }, { ...topic, id: 8, productType: 2 },
  ] } });
  assert.deepEqual(Array.from(h.page._items, x => x.id), [8]);
  assert.deepEqual(Array.from(h.page.data.listRows, x => x.id), [8]);
  assert.deepEqual(Array.from(h.page.data.markers, x => x.id), [83]);
  assert.equal(h.page.data.headerText, '附近 · 0 支队伍 · 1 处地点');
  reply(h.requests[1], 200, { code: 200, data: { items: [{ ...topic, productType: 1 }] } });
  assert.equal(h.page.data.listRows.length, 0);
  assert.deepEqual(Array.from(h.page._layerOptions.place), ['全部地点']);
});

test('guest keeps the map and title, with no protected layer requests or fabricated zero', () => {
  const h = mount(''); h.page.refresh(h.page.data.center);
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.headerText, '附近');
  assert.equal(h.page.data.nearbyReadAccess, 'login'); assert.ok(h.page.data.center);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../subpackageRoam/nearby/index.wxml'), 'utf8');
  assert.match(wxml, /<free-map/); assert.match(wxml, /nearbyReadAccess === 'login' \? '去登录'/);
  assert.match(wxml, /load-failure="\{\{!nearbyReadAccess\}\}"/, 'authentication actions must not be hidden by the ordinary-failure veil');
});

for (const layer of ['teams', 'topics']) for (const status of [401, 403]) {
  test('actual request-client HTTP ' + status + ' on ' + layer + ' clears both snapshots and preserves the access outcome', async () => {
    const h = mount(); h.page.refresh(h.page.data.center);
    h.page._teams = [team]; h.page._items = [topic];
    h.page.setData({ current: { teamId: 42 }, topic, sheet: 'team', headerText: 'stale count' });
    reply(h.requests[layer === 'teams' ? 0 : 1], status, { code: 500 });
    await settled();
    assert.equal(h.page.data.nearbyReadAccess, status === 401 ? 'login' : 'denied');
    assert.equal(h.page.data.headerText, '附近'); assert.equal(h.page._teams.length, 0); assert.equal(h.page._items.length, 0);
    assert.equal(h.page.data.current, null); assert.equal(h.page.data.topic, null); assert.equal(h.page.data.sheet, '');
    reply(h.requests[layer === 'teams' ? 1 : 0], 200, layer === 'teams' ? { code: 200, data: { items: [topic] } } : { code: 200, data: [team] });
    assert.equal(h.page._teams.length, 0); assert.equal(h.page._items.length, 0);
  });
}

for (const failed of ['teams', 'topics']) for (const order of ['error-first', 'success-first']) {
  test('partial HTTP500 ' + failed + '/' + order + ' is not erased by the other successful layer', () => {
    const h = mount(); h.page.refresh(h.page.data.center);
    const error = () => reply(h.requests[failed === 'teams' ? 0 : 1], 500, { code: 500 });
    const success = () => reply(h.requests[failed === 'teams' ? 1 : 0], 200,
      failed === 'teams' ? { code: 200, data: { items: [topic] } } : { code: 200, data: [team] });
    if (order === 'error-first') { error(); success(); } else { success(); error(); }
    assert.equal(h.page.data.loadError, true); assert.equal(h.page.data.nearbyReadAccess, '');
    assert.equal(h.page.data.headerText, failed === 'teams' ? '附近 · 1 处地点' : '附近 · 1 支队伍'); // v13 计数条:读到的那层照实写
  });
}

test('legitimate empty results show zero only after HTTP200 and valid arrays; network is an ordinary failure', () => {
  const h = mount(); h.page.refresh(h.page.data.center);
  assert.equal(h.page.data.headerText, '附近');
  reply(h.requests[0], 200, { code: 200, data: [] }); reply(h.requests[1], 200, { code: 200, data: { items: [] } });
  assert.equal(h.page.data.headerText, '附近 · 0 支队伍'); assert.equal(h.page.data.loadError, false);
  // 2026-10-22:每次 refresh 依次发 队伍 / 主题活动 / 地标商家 三个读取,第二轮从下标 3 开始
  h.page.refresh(h.page.data.center); h.requests[3].fail({ errMsg: 'fixture network' });
  reply(h.requests[4], 200, { code: 200, data: { items: [] } });
  assert.equal(h.page.data.headerText, '附近'); assert.equal(h.page.data.loadError, true); assert.equal(h.page.data.nearbyReadAccess, '');
});

test('account and token changes clear old cards/markers and reject out-of-order responses', () => {
  const h = mount(); h.page.refresh(h.page.data.center); const old = h.requests.slice();
  reply(old[0], 200, { code: 200, data: [team] }); reply(old[1], 200, { code: 200, data: { items: [topic] } });
  h.page.setData({ sheet: 'team', current: { teamId: 42 }, topic });
  h.actor('8'); h.page._shownOnce = true; h.page.onShow();
  assert.equal(h.page.data.current, null); assert.equal(h.page.data.topic, null); assert.equal(h.page.data.sheet, '');
  assert.equal(h.page.data.markers.length, 0); assert.equal(h.page.data.headerText, '附近');
  reply(old[0], 200, { code: 200, data: [team] }); reply(old[1], 403, { code: 403 });
  assert.equal(h.page._teams.length, 0); assert.equal(h.page.data.nearbyReadAccess, '');
  const actor8 = h.requests.slice(3); // 旧账号那轮占 0–2(队伍 / 主题活动 / 地标商家) reply(actor8[0], 200, { code: 200, data: [] }); reply(actor8[1], 200, { code: 200, data: { items: [] } });
  h.actor('8', 'fixture-new-token'); h.page.onShow();
  reply(actor8[0], 200, { code: 200, data: [team] });
  assert.equal(h.page.data.headerText, '附近'); assert.equal(h.page._teams.length, 0);
});

test('successful automatic refresh uses a fresh read owner and does not adopt the old response', async () => {
  const h = mount(); h.app.getSessionManager = () => createSessionManager({ session: h.session,
    wxLogin: async () => ({ code: 'fixture-code' }), exchangeCode: async () => ({ token: 'fixture-refreshed', data: { id: '7', userType: 1, role: 'player' } }),
  });
  h.page.refresh(h.page.data.center); reply(h.requests[0], 401, { code: 401 }); await settled();
  // 2026-10-22:首轮 0–2 = 队伍 / 主题活动 / 地标商家;3 = 换票后的自动重发;之后新一轮 4–6
  assert.equal(h.requests.length, 4); reply(h.requests[3], 200, { code: 200, data: [team] });
  assert.equal(h.page._teams.length, 0); assert.equal(h.requests.length, 7);
  reply(h.requests[4], 200, { code: 200, data: [] }); reply(h.requests[5], 200, { code: 200, data: { items: [] } });
  assert.equal(h.page.data.headerText, '附近 · 0 支队伍'); assert.equal(h.page.data.nearbyReadAccess, '');
});

for (const stopped of ['_hidden', '_unloaded']) {
  test('real login finishing after ' + stopped + ' cannot reload nearby layers', async () => {
    const h = mount(''); h.page.refresh(h.page.data.center); let finish;
    h.app.getSessionManager = () => createSessionManager({ session: h.session,
      wxLogin: async () => ({ code: 'fixture-code' }), exchangeCode: () => new Promise(resolve => { finish = resolve; }),
    });
    const pending = h.page.retryNearby(); await settled(); h.page[stopped] = true;
    finish({ token: 'fixture-login', data: { id: '7', userType: 1, role: 'player' } }); await pending;
    assert.equal(h.requests.length, 0);
  });
}

for (const generator of ['team', 'self']) {
  test('late ' + generator + ' image callback cannot restore previous account icon caches', async () => {
    const h = mount(); h.page.refresh(h.page.data.center); const owner = h.page._myIdentity();
    const seq = generator === 'team' ? h.page._teamSeq : h.page._topicSeq;
    const current = () => h.page._nearbyCurrent(generator === 'team' ? 'teams' : 'topics', owner, seq);
    h.page._canvas = async () => ({ getContext: () => ({}) });
    let image; h.page._loadImage = () => new Promise(resolve => { image = resolve; });
    h.page._far[MAP_AVATAR_COLORS.cream] = 'fixture-far';
    if (generator === 'team') h.page._teams = [{ ...team, leaderAvatar: '/fixture-avatar.jpg' }];
    else { h.page._me = { lat: 31, lng: 121 }; h.app.globalData.avatar = '/fixture-avatar.jpg'; }
    const pending = generator === 'team' ? h.page._genTeamIcons(current) : h.page._genIcons([], current);
    await settled(); assert.equal(typeof image, 'function');
    h.actor('8'); h.page._resetNearbyRead(h.page._myIdentity()); image({ width: 20, height: 20 });
    assert.equal(await pending, false); assert.equal(Object.keys(h.page._icons).length, 0); assert.equal(h.page._selfIcon, '');
  });
}

test('P-38「全部」= 所有距离:三类附近读取都用放开的半径,再点范围档位回到具体公里数', () => {
  const T = require('../../subpackageRoam/utils/map-team.js');
  const h = mount(); h.page.setData({ center: { lat: 31, lng: 121 } });
  h.page.onLayerTap({ currentTarget: { dataset: { layer: 'all' }, offsetLeft: 0 } });
  const radii = h.requests.map(r => r.data && r.data.radius);
  assert.ok(radii.length >= 3, '队伍 / 主题 / 地点三路都重新读取');
  assert.ok(radii.every(r => r === T.ALL_RADIUS), '全部不再停在 20 公里档');
  assert.equal(h.page.data.rangeAll, true);
  assert.equal(h.page.data.rangeText, '全部距离');
  assert.equal(h.page.data.layer, 'all');
  const before = h.requests.length;
  h.page.onLayerTap({ currentTarget: { dataset: { layer: 'all' }, offsetLeft: 0 } });
  assert.equal(h.page.data.rangeAll, true);
  h.page.onRangePick({ detail: { index: 1 } });
  assert.equal(h.page.data.rangeAll, false);
  assert.ok(h.requests.slice(before).every(r => r.data.radius === 3000));
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../subpackageRoam/nearby/index.wxml'), 'utf8');
  assert.match(wxml, /\{\{rangeAll \? rangeText : rangeText \+ '内'\}\} · \{\{listRows\.length \|\| 0\}\} 个/);
});
