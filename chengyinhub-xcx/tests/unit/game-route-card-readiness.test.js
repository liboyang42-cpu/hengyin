const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function patch(target, values) {
  for (const [key, value] of Object.entries(values)) {
    const keys = key.split('.'); let at = target;
    for (const part of keys.slice(0, -1)) at = at[part];
    at[keys.at(-1)] = value;
  }
}
function load(relative, wx, app = {}) {
  let definition;
  const file = path.join(root, relative);
  global.wx = wx; global.getApp = () => app; global.Behavior = value => value;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), wx, getApp: () => app,
    Component: value => { definition = value; }, Page: value => { definition = value; },
    Date, Promise, console, setTimeout, clearTimeout, setInterval, clearInterval });
  return definition;
}
function mapHarness(properties = {}, event = () => {}, snapshot = options => options.success({ tempImagePath: '/tmp/native-map.jpg' }), autoFit = true) {
  const events = [], fits = []; let snapshots = 0;
  const context = { includePoints(options) { fits.push(options); if (autoFit && options.success) options.success(); },
    takeSnapshot(options) { snapshots++; snapshot(options); } };
  const wx = { nextTick: fn => fn(), getStorageSync() {}, createMapContext: () => context };
  const definition = load('components/cy/free-map/index.js', wx);
  const defaults = Object.fromEntries(Object.entries(definition.properties).map(([key, value]) => [key, value.value]));
  const map = { ...definition.methods, data: { ...defaults, ...JSON.parse(JSON.stringify(definition.data)), scene: 'explore', ...properties },
    setData(values, done) { patch(this.data, values); if (done) done(); },
    triggerEvent(name, detail) { events.push({ name, detail }); event(name, detail); } };
  definition.lifetimes.attached.call(map);
  map._fit([]);
  const native = (generation = map._mapGeneration || 0, detail = {}) => ({ currentTarget: { dataset: { generation } }, detail });
  const template = fs.readFileSync(path.join(root, 'components/cy/free-map/index.wxml'), 'utf8').match(/<map\b[\s\S]*?>/)[0];
  const bindings = Object.fromEntries([...template.matchAll(/\bbind(?::)?([a-z]+)="([^"]+)"/g)].map(match => [match[1], match[2]]));
  const dispatch = (type, detail, generation) => {
    const handler = bindings[type];
    assert.equal(typeof map[handler], 'function', '真实 map WXML 必须绑定 ' + type);
    map[handler]({ ...native(generation, detail), type });
  };
  return { map, definition, events, fits, dispatch, snapshots: () => snapshots,
    update(generation) { dispatch('updated', { native: true }, generation); },
    style(errCode = 0, generation) { dispatch(errCode === 0 ? 'abilitysuccess' : 'abilityfail', { ability: 'layer-style', errCode }, generation); },
    change(key, value) { const previous = map.data[key]; map.data[key] = value; definition.properties[key].observer.call(map, value, previous); },
    ready() { this.update(); this.update(); if (map.data.subkey || map.data.tone === '2077') this.style(); },
    detach() { definition.lifetimes.detached.call(map); } };
}
const points = [{ latitude: 31.2, longitude: 121.4 }, { latitude: 31.21, longitude: 121.41 }];

test('2077 updated 语义保持；3.5秒展示回退不能升级为截图就绪', t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = mapHarness({ tone: '2077', subkey: 'fixture', layerStyle: 4 });
  h.update();
  assert.equal(h.events.filter(e => e.name === 'updated').length, 1);
  assert.equal(h.map.data.mapLoaded, false);
  t.mock.timers.tick(3500);
  assert.equal(h.map.data.mapLoaded, true, '保留原展示层回退');
  assert.equal(h.map.isSnapshotReady(), false, '固定时间不能证明样式生效');
  assert.equal(h.events.filter(e => e.name === 'ready' && e.detail.ready).length, 0);
});

for (const [tone, layerStyle] of [['white', 1], ['black', 2], ['paper', 3], ['2077', 4]]) {
  test('当前 ' + tone + ' 样式和原生视野更新都完成才发截图 ready', () => {
    const h = mapHarness({ tone, subkey: 'fixture', layerStyle, fit: true, fitPoints: points });
    h.style();
    assert.equal(h.map.isSnapshotReady(), false);
    h.update();
    assert.equal(h.map.isSnapshotReady(), false, '首帧后仍需等待 includePoints 对应的更新');
    h.update();
    assert.equal(h.map.isSnapshotReady({ tone, subkey: 'fixture', layerStyle }), true);
    assert.equal(h.map.isSnapshotReady({ tone, subkey: 'fixture', layerStyle: 99 }), false);
    assert.equal(h.events.filter(e => e.name === 'ready' && e.detail.ready).length, 1);
    h.update(); h.style();
    assert.equal(h.events.filter(e => e.name === 'ready' && e.detail.ready).length, 1);
  });
}

test('默认底图无需个性化能力，但仍等待真实视野更新；fit失败不就绪', () => {
  const h = mapHarness({ fit: true, fitPoints: points });
  h.update(); assert.equal(h.map.isSnapshotReady(), false);
  h.update(); assert.equal(h.map.isSnapshotReady(), true);
  h.map.data.fitPoints = points.map(p => ({ ...p, latitude: p.latitude + 0.01 }));
  h.map._fit([]);
  assert.equal(h.map.isSnapshotReady(), false);
  h.fits.at(-1).fail({ errMsg: 'includePoints failed' });
  h.update();
  assert.equal(h.map.isSnapshotReady(), false);
});

test('样式/同tone编号切换隔离旧代 updated、style 和卸载回调', () => {
  const h = mapHarness({ tone: 'paper', subkey: 'fixture', layerStyle: 3 });
  h.ready(); assert.equal(h.map.isSnapshotReady(), true);
  const old = h.map._mapGeneration || 0;
  h.change('layerStyle', 4);
  assert.equal(h.map.isSnapshotReady(), false);
  h.update(old); h.style(0, old);
  assert.equal(h.map.isSnapshotReady(), false);
  h.style(); h.map.onUpdated({ detail: {} });
  assert.equal(h.map.isSnapshotReady(), false, '缺代次的更新也不能证明新样式就绪');
  h.ready(); assert.equal(h.map.isSnapshotReady(), true);
  h.change('subkey', 'other-fixture');
  assert.equal(h.map.isSnapshotReady(), false);
  h.ready();
  h.detach();
  const count = h.events.length;
  h.update(); h.style();
  assert.equal(h.map.isSnapshotReady(), false);
  assert.equal(h.events.length, count);
});

function pageHarness(snapshot) {
  let owner = 1, currentMap; const maps = [], requests = [];
  const g = new Proxy({}, { get: (_, key) => key === 'measureText' ? () => ({ width: 10 })
    : key === 'createLinearGradient' ? () => ({ addColorStop() {} }) : () => {}, set: () => true });
  const canvas = { getContext: () => g, createImage: () => ({ set src(value) { queueMicrotask(() => this.onload()); } }) };
  const wx = { getStorageSync() {}, getSystemInfoSync: () => ({}), getMenuButtonBoundingClientRect: () => ({}),
    createSelectorQuery: () => ({ select() { return this; }, fields() { return this; }, exec(done) { done([{ node: canvas }]); } }) };
  const app = { _loginEpoch: 1, globalData: {}, getUserID: () => owner,
    sendRequest(request) { requests.push(request); request.success({ code: 200, data: {} }); } };
  const definition = load('pages/play/index.js', wx, app);
  const wxml = fs.readFileSync(path.join(root, 'pages/play/index.wxml'), 'utf8');
  const page = { ...definition, data: { ...JSON.parse(JSON.stringify(definition.data)), showFinish: true,
    topicId: 12, mode: 1, nodes: points.map((p, i) => ({ nodeId: i + 1, lat: p.latitude, lng: p.longitude, done: true })), finishSnap: 'line.png' },
    _routeState: { sessionId: 'run-A' }, selectComponent: () => currentMap?.map,
    setData(values, done) {
      patch(this.data, values);
      if (values.routeMap) {
        const route = values.routeMap;
        const requestId = route.instances?.[0]?.id;
        currentMap = mapHarness({ subkey: route.subkey, tone: route.tone, layerStyle: route.layerStyle, fit: true, fitPoints: route.points },
          (name, detail) => {
            if ((name === 'ready' && /bindready="onRouteMapUpdated"/.test(wxml)) || (name === 'updated' && /bindupdated="onRouteMapUpdated"/.test(wxml))) {
              page.onRouteMapUpdated({ currentTarget: { dataset: { requestId } }, detail });
            }
          }, snapshot);
        maps.push(currentMap);
      }
      if (done) done();
    } };
  return { page, maps, requests, map: () => currentMap, switchOwner() { owner = 2; app._loginEpoch++; } };
}

test('路线卡没有 ready 时6秒只失败回退，不再固定600ms或超时放行截图', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = pageHarness(); h.page._startRouteCard();
  h.map().update();
  t.mock.timers.tick(600); await flush();
  assert.equal(h.map().snapshots(), 0);
  t.mock.timers.tick(5400); await flush();
  t.mock.timers.tick(600); await flush();
  assert.equal(h.map().snapshots(), 0);
  assert.equal(h.page.data.routeCard.error, true);
  assert.equal(h.page.data.finishSnap, 'line.png');
  assert.equal(h.requests.length, 0);
});

test('真实组件 ready 立即接通原生截图和原路线卡绘制，不等固定延时', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = pageHarness(); h.page._startRouteCard();
  h.map().ready(); await flush();
  assert.equal(h.map().snapshots(), 1);
  assert.equal(h.page.data.routeCard.ready, true);
});

test('样式失败明确回退，错误底图不截图、不进路线卡附加请求', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = pageHarness(); h.page._startRouteCard();
  h.map().style(1000); await flush();
  assert.equal(h.page.data.routeCard.error, true);
  assert.equal(h.map().snapshots(), 0);
  assert.equal(h.requests.length, 0);
});

test('旧路线卡代次ready不能放行新卡，当前style检查不能靠事件名称绕过', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = pageHarness(); h.page._startRouteCard();
  const oldRequest = h.page.data.routeMap.instances?.[0]?.id;
  const wxml = fs.readFileSync(path.join(root, 'pages/play/index.wxml'), 'utf8');
  assert.match(wxml, /id="routemap"[^>]*wx:for="\{\{routeMap\.instances\}\}"[^>]*wx:key="id"[^>]*data-request-id="\{\{item\.id\}\}"/);
  h.page._startRouteCard();
  h.page.onRouteMapUpdated({ currentTarget: { dataset: { requestId: oldRequest } }, detail: { ready: true, generation: 0 } });
  await flush(); assert.equal(h.map().snapshots(), 0);
  h.maps[0].ready(); await flush(); assert.equal(h.map().snapshots(), 0);
  h.map().ready(); await flush(); assert.equal(h.map().snapshots(), 1);
});

for (const retired of ['style', 'account', 'session', 'closed', 'detached']) {
  test('截图在途发生 ' + retired + '，迟回路径不绘制/上传旧地图', async t => {
    t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
    let pending;
    const h = pageHarness(options => { pending = options; });
    h.page._startRouteCard(); h.map().ready(); await flush();
    assert.equal(h.map().snapshots(), 1);
    if (retired === 'style') h.map().change('tone', 'paper');
    if (retired === 'account') h.switchOwner();
    if (retired === 'session') h.page._routeState.sessionId = 'run-B';
    if (retired === 'closed') h.page._hideFinish();
    if (retired === 'detached') h.map().detach();
    pending.success({ tempImagePath: '/tmp/late.jpg' }); await flush();
    assert.equal(h.page.data.routeCard.ready, false);
    assert.equal(h.requests.length, 0);
  });
}

test('完成页关闭会取消就绪等待，后来的原生事件和超时都不截图', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = pageHarness(); h.page._startRouteCard(); h.page._hideFinish();
  assert.equal(h.page._routeMapWait, null);
  h.map().ready(); t.mock.timers.tick(7000); await flush();
  assert.equal(h.map().snapshots(), 0);
  assert.equal(h.requests.length, 0);
});


test('R1-stale-fit-success：新fit失败后，旧fit成功不能恢复ready', () => {
  const h = mapHarness({ fit: true, fitPoints: points }, undefined, undefined, false);
  h.update(); h.update();
  assert.equal(h.fits.length, 2);
  h.fits[1].fail();
  assert.equal(h.map.isSnapshotReady(), false);
  h.fits[0].success(); h.fits[1].success(); h.update();
  assert.equal(h.map.isSnapshotReady(), false);
  assert.equal(h.events.filter(e => e.name === 'ready' && e.detail.ready).length, 0);
});

test('R1-pre-success-update：当前fit成功前的updated不可补作成功后的render', () => {
  const h = mapHarness({ fit: true, fitPoints: points }, undefined, undefined, false);
  h.update(); h.update();
  assert.equal(h.map.isSnapshotReady(), false);
  h.fits[1].success();
  assert.equal(h.map.isSnapshotReady(), false, '没有success之后的原生updated');
  h.update();
  assert.equal(h.map.isSnapshotReady(), true);
});

test('R1-viewport-ABA：显式视野A→B→A隔离旧fit与旧原生更新', () => {
  const h = mapHarness({ fit: true, fitPoints: points }, undefined, undefined, false);
  h.update(); const oldFit = h.fits[1], oldGeneration = h.map._mapGeneration || 0;
  h.change('fitPoints', points.map(p => ({ ...p, latitude: p.latitude + 1 })));
  h.change('fitPoints', points);
  assert.ok(h.map._mapGeneration > oldGeneration, '显式视野换代必须落到真实keyed map实例');
  const template = fs.readFileSync(path.join(root, 'components/cy/free-map/index.wxml'), 'utf8');
  assert.match(template, /<map[^>]*wx:for="\{\{mapInstances\}\}"[^>]*wx:key="generation"[^>]*data-generation="\{\{item\.generation\}\}"/);
  h.update(); h.update();
  oldFit.success(); h.update(oldGeneration);
  assert.equal(h.map.isSnapshotReady(), false);
  h.fits.at(-1).success();
  assert.equal(h.map.isSnapshotReady(), false);
  h.update(); assert.equal(h.map.isSnapshotReady(), true);
});

for (const kind of ['abilityfail', 'error']) {
  for (const alreadyReady of [false, true]) {
    test('R2真实WXML ' + kind + '，' + (alreadyReady ? '已有ready也立即失效' : '未就绪即明确失败'), () => {
      const h = mapHarness({ tone: 'paper', subkey: 'fixture', layerStyle: 3 });
      if (alreadyReady) { h.ready(); assert.equal(h.map.isSnapshotReady(), true); }
      const before = h.events.filter(e => e.name === 'ready' && e.detail.ready === false).length;
      h.dispatch(kind, kind === 'abilityfail' ? { ability: 'layer-style', errCode: 1000, errMsg: 'fixture failure' } : {});
      assert.equal(h.map.isSnapshotReady(), false);
      assert.equal(h.events.filter(e => e.name === 'ready' && e.detail.ready === false).length, before + 1);
      h.ready();
      assert.equal(h.map.isSnapshotReady(), false, '同一失败实例的迟到success/update不能恢复ready');
    });
  }
}

test('R2旧代失败不能影响新实例，非layer-style能力失败不能误伤当前样式', () => {
  const h = mapHarness({ tone: 'paper', subkey: 'fixture', layerStyle: 3 });
  h.ready(); const old = h.map._mapGeneration || 0;
  h.change('layerStyle', 4); h.ready();
  h.dispatch('abilityfail', { ability: 'layer-style', errCode: 1000 }, old);
  h.dispatch('error', {}, old);
  h.dispatch('abilityfail', { ability: 'unrelated', errCode: 1000 });
  assert.equal(h.map.isSnapshotReady(), true);
  h.detach();
  const count = h.events.length;
  h.dispatch('error', {}); h.dispatch('abilityfail', { ability: 'layer-style', errCode: 1000 });
  assert.equal(h.events.length, count);
});

for (const kind of ['abilityfail', 'error']) {
  test('R2真实WXML ' + kind + ' 在截图中到达，迟图不绘制/上传', async t => {
    t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
    let pending;
    const h = pageHarness(options => { pending = options; });
    h.page._startRouteCard(); h.map().ready(); await flush();
    assert.equal(h.map().snapshots(), 1);
    h.map().dispatch(kind, kind === 'abilityfail' ? { ability: 'layer-style', errCode: 1000 } : {});
    pending.success({ tempImagePath: '/tmp/late-after-native-failure.jpg' }); await flush();
    assert.equal(h.page.data.routeCard.ready, false);
    assert.equal(h.page.data.routeCard.error, true);
    assert.equal(h.requests.length, 0);
  });
}
