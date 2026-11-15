const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const geo = require('../../utils/roam-route-geo.js').routeGeo([{ lat: 30, lng: 120 }, { lat: 30.01, lng: 120.01 }], []);
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
async function advance(t, ms) { for (let i = 0; i < ms; i += 80) { t.mock.timers.tick(80); await flush(); } }
function patch(data, values) {
  for (const [key, value] of Object.entries(values)) {
    const parts = key.split('.'); let at = data;
    for (const part of parts.slice(0, -1)) at = at[part] || (at[part] = {});
    at[parts.at(-1)] = value;
  }
}
function nativePoi(tag, definition, nativeTemplate) {
  const property = definition.properties.enablePoi;
  assert.equal(property?.type.name, 'Boolean');
  const value = tag.match(/\benable-poi="\{\{(true|false)\}\}"/)?.[1];
  const data = { enablePoi: value == null ? property.value : value === 'true' };
  const binding = nativeTemplate.match(/\benable-poi="\{\{(\w+)\}\}"/)?.[1];
  assert.equal(binding, 'enablePoi');
  return data[binding];
}
function harness(kind, style = { subkey: 'fixture', layerStyle: 3, tone: 'paper' }) {
  const relative = kind === 'roam' ? 'pages/roam/index' : 'subpackageRoam/session/index';
  const mapId = kind === 'roam' ? 'rshMap' : 'ssMap';
  let owner = '11', definition, active, pendingImage;
  const snapshots = [], fits = [], images = [];
  const app = { globalData: {}, getUserID: () => owner };
  const wx = { nextTick: fn => fn(), getStorageSync() {}, showLoading() {}, hideLoading() {},
    createMapContext: () => ({ includePoints: o => { fits.push(o); o.success(); }, takeSnapshot: o => snapshots.push(o) }) };
  global.wx = wx; global.getApp = () => app; global.Behavior = v => v;
  function load(file) {
    const absolute = path.join(root, file + '.js');
    vm.runInNewContext(fs.readFileSync(absolute, 'utf8'), { require: createRequire(absolute), getApp: () => app,
      wx, Page: v => { definition = v; }, Component: v => { definition = v; }, Behavior: v => v,
      getCurrentPages: () => [], console, Date, Promise, setTimeout, clearTimeout, setInterval, clearInterval });
    return definition;
  }
  const config = load(relative);
  const page = { ...config, data: JSON.parse(JSON.stringify(config.data)),
    _shareEpoch: 1, _sessionOwner: owner, _shareMapOwner: owner,
    setData(values, done) { patch(this.data, values); if (done) done(); }, selectComponent: () => active?.map };
  Object.assign(page.data, { geo, mapStyle: { ...style }, shareArch: { archGeo: geo },
    fmSubkey: style.subkey, fmLayerStyle: style.layerStyle, fmTone: style.tone,
    shareMapInstances: [{ id: 1 }], share: { show: true, drawing: true, mapImage: '' } });
  const template = fs.readFileSync(path.join(root, relative + '.wxml'), 'utf8');
  const tag = (template.match(/<(?:cy-)?free-map\b[^>]*>/g) || []).find(t => t.includes('id="' + mapId + '"'));
  assert.ok(tag, 'share snapshot must use the existing strict free-map');
  assert.match(tag, /wx:key="id"/);
  assert.match(tag, /data-request-id="\{\{item.id\}\}"/);
  const readyHandler = tag.match(/bind(?::)?ready="([^"]+)"/)?.[1];
  assert.equal(typeof page[readyHandler], 'function', 'actual parent WXML must bind ready');
  const registration = JSON.parse(fs.readFileSync(path.join(root, relative + '.json'), 'utf8'));
  assert.equal(registration.usingComponents[tag.match(/^<([^\s]+)/)[1]], '/components/cy/free-map/index');
  const mapConfig = load('components/cy/free-map/index');
  const nativeTemplate = fs.readFileSync(path.join(root, 'components/cy/free-map/index.wxml'), 'utf8').match(/<map\b[^>]*>/)[0];
  const bindings = Object.fromEntries([...nativeTemplate.matchAll(/\bbind(?::)?([a-z]+)="([^"]+)"/g)].map(m => [m[1], m[2]]));
  function mount() {
    const requestId = page._shareEpoch;
    const defaults = Object.fromEntries(Object.entries(mapConfig.properties).map(([k, p]) => [k, p.value]));
    const map = { ...mapConfig.methods, data: { ...defaults, ...JSON.parse(JSON.stringify(mapConfig.data)),
      ...style, enablePoi: nativePoi(tag, mapConfig, nativeTemplate), scene: 'explore', fit: true, fitPoints: page.data.geo.include },
      setData(p, done) { Object.assign(this.data, p); if (done) done(); },
      triggerEvent(name, detail) { if (name === 'ready') page[readyHandler]({ currentTarget: { dataset: { requestId } }, detail }); } };
    mapConfig.lifetimes.attached.call(map); map._fit([]);
    const instance = { map, requestId, native(type, detail = {}) {
      const handler = bindings[type]; assert.equal(typeof map[handler], 'function');
      map[handler]({ currentTarget: { dataset: { generation: map._mapGeneration || 0 } }, detail });
    }, ready() { this.native('updated'); this.native('updated'); if (style.subkey) this.native('abilitysuccess', { ability: 'layer-style', errCode: 0 }); } };
    active = instance; return instance;
  }
  mount();
  const canvas = { createImage() { const image = { width: 400, height: 300, set src(value) {
    images.push(value); if (pendingImage) pendingImage.resolve = () => this.onload(); else queueMicrotask(() => this.onload());
  } }; return image; } };
  return { page, snapshots, images, fits, mount, mapConfig, nativeTemplate, current: () => active,
    start() { return kind === 'roam' ? page._shareMapImage(canvas, 0, page.data.shareArch) : page._shareMapImage(canvas); },
    changeOwner() { owner = '12'; }, holdImage() { pendingImage = {}; return pendingImage; },
    reopen() {
      page._shareEpoch++;
      if (kind === 'roam') { page._archGeo = geo; page.openScene = () => {}; page._openShareSheet(); }
      return mount();
    },
    cancel() { if (kind === 'roam') page._cancelShareWork(); else page.closeShare(); } };
}
for (const kind of ['roam', 'session']) {
  test('POI兼容：' + kind + ' 分享宿主从实际JSON/WXML派发false到原生map', () => {
    const h = harness(kind);
    assert.equal(h.current().map.data.enablePoi, false);
  });
}
test('POI兼容：既有9处调用者沿实际注册链保持默认true', () => {
  const h = harness('roam'), callers = [];
  function visit(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (['node_modules', 'tests', 'scripts'].includes(entry.name)) continue;
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) { visit(file); continue; }
      if (!file.endsWith('.wxml')) continue;
      const json = file.replace(/\.wxml$/, '.json');
      if (!fs.existsSync(json)) continue;
      const components = JSON.parse(fs.readFileSync(json, 'utf8')).usingComponents || {};
      const source = fs.readFileSync(file, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
      for (const match of source.matchAll(/<([\w-]+)\b[^>]*>/g)) {
        const tag = match[0];
        if (components[match[1]] !== '/components/cy/free-map/index' || /id="(?:rshMap|ssMap)"/.test(tag)) continue;
        assert.doesNotMatch(tag, /\benable-poi=/);
        assert.equal(nativePoi(tag, h.mapConfig, h.nativeTemplate), true);
        callers.push(path.relative(root, file));
      }
    }
  }
  visit(root);
  assert.equal(callers.length, 9);
});
for (const kind of ['roam', 'session']) {
  for (const [tone, layerStyle] of [['', 1], ['white', 1], ['black', 2], ['paper', 3], ['2077', 4]]) {
    test(kind + ' ' + (tone || 'default') + '：仅当前实例真实ready后截图，普通updated不授权', async t => {
      t.mock.timers.enable({ apis: ['setTimeout'] });
      const h = harness(kind, { tone, layerStyle, subkey: tone ? 'fixture' : '' });
      const original = JSON.stringify(h.page.data.geo);
      const pending = h.start(); h.current().native('updated'); await advance(t, 80);
      assert.equal(h.snapshots.length, 0);
      h.current().ready(); await advance(t, 80); assert.equal(h.snapshots.length, 1);
      h.snapshots[0].success({ tempImagePath: '/tmp/current.png' });
      assert.ok(await pending); assert.equal(JSON.stringify(h.page.data.geo), original);
    });
  }
  for (const type of ['abilityfail', 'error']) {
    test(kind + ' ' + type + '：真实WXML失败入口阻止截图并保留线稿回退', async t => {
      t.mock.timers.enable({ apis: ['setTimeout'] });
      const h = harness(kind), pending = h.start();
      h.current().native(type, type === 'abilityfail' ? { ability: 'layer-style', errCode: 1000 } : {});
      await advance(t, 80); assert.equal(await pending, null); assert.equal(h.snapshots.length, 0);
    });
  }
  test(kind + '：无后续ready超时不截图，允许真实轨迹回退', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] }); const h = harness(kind), pending = h.start();
    await advance(t, 4000); assert.equal(await pending, null); assert.equal(h.snapshots.length, 0);
  });
  test(kind + '：关闭后旧ready不得解锁新请求', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] }); const h = harness(kind), old = h.current(), pending = h.start();
    h.cancel(); h.reopen(); old.ready();
    await advance(t, 80); assert.equal(await pending, null); assert.equal(h.snapshots.length, 0);
    const next = h.start(); h.current().ready(); await advance(t, 80); assert.equal(h.snapshots.length, 1);
    h.snapshots[0].success({ tempImagePath: '/tmp/new.png' }); assert.ok(await next);
  });
  for (const retire of ['owner', 'style', 'view', 'failure']) {
    test(kind + '：截图在途改变 ' + retire + '，旧图不得加载或回填', async t => {
      t.mock.timers.enable({ apis: ['setTimeout'] }); const h = harness(kind), pending = h.start();
      h.current().ready(); await advance(t, 80); assert.equal(h.snapshots.length, 1);
      if (retire === 'owner') h.changeOwner();
      if (retire === 'style') { h.page.data.fmLayerStyle = 4; h.page.data.mapStyle = { subkey: 'fixture', layerStyle: 4, tone: '2077' }; }
      if (retire === 'view') { h.page.data.geo = { ...geo }; h.page.data.shareArch = { archGeo: { ...geo } }; }
      if (retire === 'failure') h.current().native('error');
      h.snapshots[0].success({ tempImagePath: '/tmp/stale.png' });
      assert.equal(await pending, null); assert.deepEqual(h.images, []); assert.equal(h.page.data.share.mapImage, '');
    });
  }
  test(kind + '：图像解码期间关闭，旧路径和图像不回填', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] }); const h = harness(kind), held = h.holdImage(), pending = h.start();
    h.current().ready(); await advance(t, 80); h.snapshots[0].success({ tempImagePath: '/tmp/decode.png' }); await flush();
    h.cancel(); held.resolve(); assert.equal(await pending, null); assert.equal(h.page.data.share.mapImage, '');
  });
}
