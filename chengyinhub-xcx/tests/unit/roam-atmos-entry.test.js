'use strict';

// 第九轮 m1 第 8 项:首次进漫游的大气层转场。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const MOD = path.join(ROOT, 'subpackageRoam/components/roam-atmos-entry/index.js');
const URL = 'https://www.chengyinhub.com/prod-api/profile/atmos.mp4';

function make(props, env) {
  let def;
  const store = Object.assign({}, env.store || {});
  const prev = { Component: global.Component, wx: global.wx };
  global.Component = (d) => { def = d; };
  global.wx = {
    getStorageSync: (k) => store[k],
    setStorageSync: (k, v) => { store[k] = v; },
    removeStorageSync: (k) => { delete store[k]; },
    getNetworkType: (o) => o.success({ networkType: env.net || 'wifi' }),
  };
  delete require.cache[require.resolve(MOD)];
  const mod = require(MOD);
  const events = [];
  const c = Object.assign({}, def.methods, {
    data: Object.assign({}, def.data, { src: '', reducedMotion: false, force: false }, props),
    setData(p) { Object.assign(this.data, p); },
    triggerEvent(n, d) { events.push([n, d]); },
  });
  const restore = () => Object.assign(global, prev);
  return { c, def, events, store, mod, restore };
}

test('默认地址为空:不播', () => {
  const t = make({}, {});
  try { t.def.lifetimes.attached.call(t.c); assert.equal(t.c.data.phase, 'off'); assert.deepEqual(t.events, [['done', { reason: 'skip' }]]); } finally { t.restore(); }
});

test('减弱动态 / 慢网 / 已看过 / 非受信地址:都不播,也不记已看过', () => {
  for (const [props, env] of [
    [{ src: URL, reducedMotion: true }, {}],
    [{ src: URL }, { net: '3g' }],
    [{ src: URL }, { store: { cy_roam_atmos_seen_v1: 1 } }],
    [{ src: 'http://evil.example/x.mp4' }, {}],
  ]) {
    const t = make(props, env);
    try {
      t.def.lifetimes.attached.call(t.c);
      assert.equal(t.c.data.phase, 'off');
      assert.equal(t.events.length, 1);
      if (!(env.store)) assert.equal(t.store.cy_roam_atmos_seen_v1, undefined);
    } finally { t.restore(); }
  }
});

test('开播才记已看过;播完淡出;超时没开播就撤掉、下次还能再试', async () => {
  const t = make({ src: URL }, {});
  try {
    t.def.lifetimes.attached.call(t.c);
    assert.equal(t.c.data.phase, 'load', '加载中整层透明,不挡地图');
    t.c.onPlay();
    assert.equal(t.c.data.phase, 'play');
    assert.equal(t.store.cy_roam_atmos_seen_v1, 1);
    t.c.onEnded();
    assert.equal(t.c.data.phase, 'dim', '第九轮 m2 补充第 5 项:片尾先压暗,不直接把近白的云淡到深色地图上');
    t.def.lifetimes.detached.call(t.c);
  } finally { t.restore(); }

  const u = make({ src: URL }, {});
  try {
    u.def.lifetimes.attached.call(u.c);
    await new Promise((r) => setTimeout(r, u.mod.START_TIMEOUT_MS + 50));
    assert.equal(u.c.data.phase, 'off');
    assert.deepEqual(u.events, [['done', { reason: 'fail' }]]);
    assert.equal(u.store.cy_roam_atmos_seen_v1, undefined);
  } finally { u.restore(); }
});

test('点一下跳过 = 记已看过;加载失败直接撤', () => {
  const t = make({ src: URL }, {});
  try {
    t.def.lifetimes.attached.call(t.c);
    t.c.onPlay(); t.c.skip();
    assert.equal(t.c.data.phase, 'dim');
    t.def.lifetimes.detached.call(t.c);
  } finally { t.restore(); }
  const u = make({ src: URL }, {});
  try {
    u.def.lifetimes.attached.call(u.c);
    u.c.onError();
    assert.equal(u.c.data.phase, 'off');
    u.def.lifetimes.detached.call(u.c);
  } finally { u.restore(); }
});

test('漫游页:地址为空时组件不挂;地图之后才挂;组件在分包异步加载', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/roam/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-roam-atmos-entry wx:if="\{\{atmosOn && atmosSrc\}\}"/);
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/roam/index.json'), 'utf8'));
  assert.equal(json.componentPlaceholder['cy-roam-atmos-entry'], 'view');
  assert.equal(require('../../utils/config.js').roamAtmosVideoUrl, '');
});

// 第九轮 m1 第 3 项:地点卡评分沿用据点详情读接口;读不到时这一格显示「—」,不弹错。
test('地点卡:评分来自 /api/city/nodes/{id};读失败显示「—」;导航调 wx.openLocation', () => {
  const PC = path.join(ROOT, 'subpackageRoam/components/roam-place-card/index.js');
  let def; const calls = []; const opened = [];
  const prev = { Component: global.Component, getApp: global.getApp, wx: global.wx };
  global.Component = (d) => { def = d; };
  global.getApp = () => ({ sendRequest: (o) => calls.push(o) });
  global.wx = { openLocation: (o) => opened.push(o) };
  try {
    delete require.cache[require.resolve(PC)];
    const mod = require(PC);
    assert.equal(mod.ratingText(4.83, 12), '4.8');
    assert.equal(mod.ratingText(4.8, 0), '', '没有评价就不显示分数');
    const c = Object.assign({}, def.methods, { data: Object.assign({}, def.data, { reducedMotion: true }),
      setData(p) { Object.assign(this.data, p); }, triggerEvent() {} });
    const place = { id: 1, name: '咖啡', shareKind: 'poi', shareId: '5', lat: 31.2, lng: 121.4, address: '山阴路' };
    c.data.place = place; c._onPlace(place);
    assert.equal(calls[0].url, '/api/city/nodes/5');
    calls[0].success({ code: 200, data: { averageRating: 4.75, ratingCount: 3 } });
    assert.equal(c.data.rating, '4.8');
    c._onPlace(place);
    calls[1].fail();
    assert.equal(c.data.ratingError, true);
    assert.equal(c.data.rating, '');
    c.onNavigate();
    assert.deepEqual(opened[0], { latitude: 31.2, longitude: 121.4, name: '咖啡', address: '山阴路', scale: 17 });
  } finally { Object.assign(global, prev); }
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageRoam/components/roam-place-card/index.wxml'), 'utf8');
  assert.match(wxml, /\{\{rating \|\| '—'\}\}/);
  assert.match(wxml, /open-type="share" data-share-type="place"/);
});

test('第九轮 m2 补充第 5 项:压暗 → 淡出 → 撤,顺序与时长固定', async () => {
  const t = make({ src: URL }, {});
  try {
    t.def.lifetimes.attached.call(t.c);
    t.c.onPlay(); t.c.onEnded();
    assert.equal(t.c.data.phase, 'dim');
    t.c.skip();
    assert.equal(t.c.data.phase, 'dim', '压暗中再点不打断');
    await new Promise((r) => setTimeout(r, t.mod.DIM_MS + 30));
    assert.equal(t.c.data.phase, 'fade');
    await new Promise((r) => setTimeout(r, t.mod.FADE_MS + 30));
    assert.equal(t.c.data.phase, 'off');
    assert.deepEqual(t.events[t.events.length - 1], ['done', { reason: 'played' }]);
  } finally { t.restore(); }
  const wxss = fs.readFileSync(path.join(__dirname, '../../subpackageRoam/components/roam-atmos-entry/index.wxss'), 'utf8');
  assert.match(wxss, /\.atm__dim \{[^}]*transition: opacity \.36s/);
});
