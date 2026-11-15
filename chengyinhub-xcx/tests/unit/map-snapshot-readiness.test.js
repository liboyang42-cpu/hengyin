const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

function harness() {
  const requests = [], snapshots = [], uploads = [], selections = [];
  let revision = 1, contextError = false;
  const session = { getRevision: () => revision };
  const app = {
    getSession: () => session,
    sendRequest: request => requests.push(request),
    getUploadClient: () => ({ uploadAll: paths => { uploads.push(Array.from(paths)); return { destroy() {} }; } }),
  };
  function load(file) {
    const filename = path.resolve(__dirname, '../..', file);
    let definition;
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      Component: value => { definition = value; }, getApp: () => app,
      require: createRequire(filename), clearTimeout, setTimeout,
      wx: { createMapContext: () => {
        if (contextError) throw new Error('map unavailable');
        return { takeSnapshot: options => snapshots.push(options) };
      } },
    });
    const host = { ...definition.methods, data: JSON.parse(JSON.stringify(definition.data)),
      setData(patch, done) { Object.assign(this.data, patch); if (done) done(); },
      triggerEvent(name, detail) { if (name === 'select') selections.push(detail); } };
    return { host, definition };
  }
  const { host: map, definition } = load('pages/square/components/route-map/index.js');
  map.data.preview = true;
  const { host: picker } = load('pages/square/components/activity-picker/index.js');
  picker.selectComponent = () => map;
  picker._generation = 1;
  picker._pending = { id: 1, topicId: 7 };
  const chapters = [{ nodes: [{ latitude: 31, longitude: 121 }, { latitude: 31.1, longitude: 121.1 }] }];
  function ready(id = 7) {
    map.loadRoute(id);
    requests.at(-1).success({ code: 200, data: { id, chaptersList: chapters } });
    return map._routeReadEpoch;
  }
  const updated = epoch => map.onMapUpdated({ currentTarget: { dataset: { epoch } } });
  return { map, picker, definition, ready, updated, snapshots, uploads, selections,
    switchSession() { revision++; }, failContext() { contextError = true; } };
}

async function settle() { for (let i = 0; i < 8; i++) await Promise.resolve(); }
async function advance(t, ms) {
  for (let elapsed = 0; elapsed < ms; elapsed += 80) { t.mock.timers.tick(80); await settle(); }
}

test('数据 ready 不截图；收到本代次原生 updated 后才可截，默认 white 与几何不变', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness(), epoch = h.ready();
  const style = require('../../utils/map-style.js');
  assert.equal(h.map.data.mapLayerStyle, style.lightMapStyle().layerStyle);
  assert.equal(h.map.data.mapSubkey, style.lightMapStyle().subkey);
  const geometry = JSON.stringify(h.map.data.routePolyline);
  const pending = h.map.snapshotRoute();
  t.mock.timers.tick(160); await settle();
  assert.equal(h.snapshots.length, 0);
  h.updated(epoch); t.mock.timers.tick(80); await settle();
  assert.equal(h.snapshots.length, 1);
  h.snapshots[0].success({ tempImagePath: '/tmp/route.png' });
  assert.equal(await pending, '/tmp/route.png');
  assert.equal(JSON.stringify(h.map.data.routePolyline), geometry);
  const template = fs.readFileSync(path.resolve(__dirname, '../../pages/square/components/route-map/index.wxml'), 'utf8');
  assert.match(template, /wx:for="\{\{mapInstances\}\}" wx:key="epoch" data-epoch="\{\{item.epoch\}\}" bindupdated="onMapUpdated"/);
});

test('旧代次事件不能解锁新图；新加载必须重新等待；旧等待不得上传', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness(), oldEpoch = h.ready();
  const old = assert.rejects(h.map.snapshotRoute(), e => e.mapSnapshot === true);
  const epoch = h.ready(8);
  assert.equal(h.map.data.mapInstances[0].epoch, epoch);
  h.picker.onRouteReady();
  h.updated(oldEpoch); t.mock.timers.tick(80); await settle();
  assert.equal(h.snapshots.length, 0);
  h.updated(epoch); t.mock.timers.tick(80); await settle();
  assert.equal(h.snapshots.length, 1);
  h.snapshots[0].success({ tempImagePath: '/tmp/current.png' }); await settle();
  assert.deepEqual(h.uploads, [['/tmp/current.png']]);
  await advance(t, 4000); await old;
  assert.equal(h.snapshots.length, 1);
});

test('已截图后换主题，迟到成功不得交给活动选择器上传', async () => {
  const h = harness(); h.updated(h.ready());
  h.picker.onRouteReady(); await settle();
  assert.equal(h.snapshots.length, 1);
  h.ready(8);
  h.snapshots[0].success({ tempImagePath: '/tmp/late.png' }); await settle();
  assert.equal(h.uploads.length, 0);
  assert.equal(h.selections[0].routePreviewImg, '');
});

test('上一张图已 updated，新加载也不能沿用旧的就绪状态', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness(), oldEpoch = h.ready(); h.updated(oldEpoch);
  const epoch = h.ready(8), pending = h.map.snapshotRoute();
  h.updated(oldEpoch); t.mock.timers.tick(80); await settle();
  assert.equal(h.snapshots.length, 0);
  h.updated(epoch); t.mock.timers.tick(80); await settle();
  assert.equal(h.snapshots.length, 1);
  h.snapshots[0].success({ tempImagePath: '/tmp/new-route.png' });
  assert.equal(await pending, '/tmp/new-route.png');
});

test('卸载和会话切换后的事件不能解锁，迟到快照成功也必须丢弃', async () => {
  for (const invalidate of [h => h.definition.lifetimes.detached.call(h.map), h => h.switchSession()]) {
    const h = harness(), epoch = h.ready(); h.updated(epoch);
    const pending = assert.rejects(h.map.snapshotRoute(), e => e.mapSnapshot === true);
    await settle(); invalidate(h);
    h.map._routeMapUpdated = null; h.updated(epoch);
    assert.equal(h.map._routeMapUpdated, null);
    h.snapshots[0].success({ tempImagePath: '/tmp/stale-session.png' });
    await pending;
  }
});

test('未渲染超时不截图、不上传，活动选择器回退封面', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness(); h.ready(); h.picker.onRouteReady();
  await advance(t, 4000);
  assert.equal(h.snapshots.length, 0);
  assert.equal(h.uploads.length, 0);
  assert.equal(h.selections[0].routePreviewImg, '');
});

test('快照失败、空路径和空白路径都不得上传空图，保留封面回退', async () => {
  for (const result of [{ fail: true }, {}, { tempImagePath: '' }, { tempImagePath: '   ' }]) {
    const h = harness(); h.updated(h.ready()); h.picker.onRouteReady(); await settle();
    if (result.fail) h.snapshots[0].fail({ errMsg: 'snapshot failed' });
    else h.snapshots[0].success(result);
    await settle();
    assert.equal(h.uploads.length, 0);
    assert.equal(h.selections[0].routePreviewImg, '');
  }
});

test('原生 MapContext 构造异常也走拒绝与封面回退', async () => {
  const h = harness(); h.updated(h.ready()); h.failContext();
  assert.doesNotThrow(() => h.picker.onRouteReady()); await settle();
  assert.equal(h.snapshots.length, 0);
  assert.equal(h.uploads.length, 0);
  assert.equal(h.selections[0].routePreviewImg, '');
});
