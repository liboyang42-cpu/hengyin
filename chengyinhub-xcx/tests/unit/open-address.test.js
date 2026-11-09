'use strict';

// 2026-11-03 第九轮 m1 第 9 项:地址一点即开地图的唯一入口 utils/open-address.js
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');

function load(responder) {
  const opened = [];
  const requests = [];
  const toasts = [];
  const storage = {};
  const sandbox = {
    module: { exports: {} },
    wx: {
      openLocation: (o) => opened.push(o),
      showToast: (o) => toasts.push(o),
      getStorageSync: (k) => storage[k],
      setStorageSync: (k, v) => { storage[k] = JSON.parse(JSON.stringify(v)); },
    },
    getApp: () => ({ sendRequest: (o) => { requests.push(o); (responder || (() => {}))(o); } }),
    require: (p) => { if (p === './toast.js') return (msg) => toasts.push(msg); throw new Error('unexpected require ' + p); },
    Promise,
  };
  sandbox.exports = sandbox.module.exports;
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'utils/open-address.js'), 'utf8'), sandbox);
  return { api: sandbox.module.exports, opened, requests, toasts, storage };
}

test('有坐标:直接 wx.openLocation,GCJ-02 原样传,scale 17,不发请求', async () => {
  const h = load();
  const ok = await h.api.openAddress({ lat: '31.11', lng: 121.38, name: '海星市场', address: '都市路 1 号' });
  assert.equal(ok, true);
  assert.equal(h.requests.length, 0);
  assert.deepEqual({ ...h.opened[0] }, { latitude: 31.11, longitude: 121.38, name: '海星市场', address: '都市路 1 号', scale: 17 });
});

test('latitude/longitude 字段名也认;0,0 与非法值当没有坐标', () => {
  const h = load();
  assert.deepEqual({ ...h.api.coords({ latitude: 30.2, longitude: 120.1 }) }, { lat: 30.2, lng: 120.1 });
  assert.equal(h.api.coords({ lat: 0, lng: 0 }), null);
  assert.equal(h.api.coords({ lat: '', lng: '' }), null);
  assert.equal(h.api.coords({ lat: 91, lng: 10 }), null);
  assert.equal(h.api.coords(null), null);
});

test('只有文字地址且允许 geocode:走 /api/map/geocode,结果存本机,第二次不再请求', async () => {
  const h = load((o) => o.success({ code: 200, data: { lat: 31.2, lng: 121.4 } }));
  const src = { name: '店', address: '闵行区都市路 1 号', city: '上海' };
  assert.equal(h.api.canOpen(src, { geocode: true }), true);
  assert.equal(await h.api.openAddress(src, { geocode: true }), true);
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].url, '/api/map/geocode');
  assert.deepEqual({ ...h.requests[0].data }, { address: '闵行区都市路 1 号', region: '上海' });
  assert.equal(h.opened[0].latitude, 31.2);
  assert.ok(h.storage.cy_addr_geo_v1['闵行区都市路 1 号']);
  h.api._reset();
  assert.equal(await h.api.openAddress(src, { geocode: true }), true);
  assert.equal(h.requests.length, 1, '第二次读本机缓存');
  assert.equal(h.opened.length, 2);
});

test('geocode 拿不到:不开地图,只给一句轻提示', async () => {
  const h = load((o) => o.success({ code: 200, data: {} }));
  assert.equal(await h.api.openAddress({ address: '某处' }, { geocode: true }), false);
  assert.equal(h.opened.length, 0);
  assert.equal(h.toasts.length, 1);
});

test('没坐标又不允许 geocode(或连地址都没有):不给点,也不发请求', async () => {
  const h = load();
  assert.equal(h.api.canOpen({ address: '某处' }), false);
  assert.equal(h.api.canOpen({}, { geocode: true }), false);
  assert.equal(await h.api.openAddress({ address: '某处' }), false);
  assert.equal(await h.api.openAddress({}, { geocode: true }), false);
  assert.equal(h.requests.length, 0);
  assert.equal(h.opened.length, 0);
});

test('openFromEvent 读 data-lat/lng/name/address,data-geo="1" 打开 geocode', async () => {
  const h = load((o) => o.success({ code: 200, data: { lat: 30, lng: 120 } }));
  await h.api.openFromEvent({ currentTarget: { dataset: { lat: 31, lng: 121, name: 'A', address: 'B' } } });
  assert.equal(h.opened[0].latitude, 31);
  await h.api.openFromEvent({ currentTarget: { dataset: { address: 'C', geo: '1' } } });
  assert.equal(h.requests.length, 1);
  assert.equal(h.opened[1].latitude, 30);
});
