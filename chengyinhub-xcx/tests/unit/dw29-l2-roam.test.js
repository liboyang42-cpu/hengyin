'use strict';

// 2026-10-29 真机走查 l2-roam:V-06 / V-07 / V-08 / V-09。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8');
const ROAM_JS = 'pages/roam/index.js';
const ROAM_WXML = 'pages/roam/index.wxml';
const ROAM_WXSS = 'pages/roam/index.wxss';

/* ---------------- V-06 搜索兜底 ---------------- */

function mountSearch(responder) {
  let def;
  const requests = [];
  const timers = [];
  const sandbox = {
    require: (p) => require(path.resolve(ROOT, 'components/cy/map-address-search', p)),
    Component: (d) => { def = d; },
    getApp: () => ({ sendRequest: (opts) => { requests.push(opts); responder(opts); } }),
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout() {},
    wx: {},
  };
  vm.runInNewContext(read('components/cy/map-address-search/index.js'), sandbox);
  const comp = Object.assign({}, def.methods, {
    data: Object.assign({}, def.data, { places: [], lat: 31.1, lng: 121.37 }),
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent() {},
  });
  return { comp, requests, flush: () => { while (timers.length) timers.shift()(); } };
}

test('V-06 腾讯地点搜索回空(未配 key)时用商家节点库兜底出结果', () => {
  const h = mountSearch((opts) => {
    if (opts.url === '/api/map/place-search') opts.success({ code: 200, data: [] });
    else opts.success({ code: 200, data: [{ poiId: 7, name: '海星市场', merchantAddress: '闵行区都市路 1 号', lat: 31.11, lng: 121.38 }] });
  });
  h.comp.onInput({ detail: { value: '海星' } });
  h.flush();
  assert.deepEqual(h.requests.map((r) => r.url), ['/api/map/place-search', '/api/city/nodes']);
  const nodeReq = h.requests[1];
  assert.equal(nodeReq.method, 'GET');
  assert.deepEqual({ ...nodeReq.data }, { lat: 31.1, lng: 121.37, radius: 20000, keyword: '海星' });
  assert.equal(h.comp.data.searching, false);
  assert.equal(h.comp.data.remoteError, false);
  assert.equal(h.comp.data.results.length, 1);
  assert.equal(h.comp.data.results[0].name, '海星市场');
  assert.equal(h.comp.data.results[0].sub, '闵行区都市路 1 号');
});

test('V-06 联网搜索失败、节点库也没有时才如实提示', () => {
  const h = mountSearch((opts) => {
    if (opts.url === '/api/map/place-search') opts.fail({ errMsg: 'network' });
    else opts.success({ code: 200, data: [] });
  });
  h.comp.onInput({ detail: { value: '图书馆' } });
  h.flush();
  assert.equal(h.requests.length, 2);
  assert.equal(h.comp.data.remoteError, true);
  assert.equal(h.comp.data.results.length, 0);
});

test('V-06 腾讯地点搜索有结果时不再查节点库', () => {
  const h = mountSearch((opts) => opts.success({ code: 200, data: [{ name: '闵行图书馆', address: 'x', lat: 31.1, lng: 121.3 }] }));
  h.comp.onInput({ detail: { value: '图书馆' } });
  h.flush();
  assert.deepEqual(h.requests.map((r) => r.url), ['/api/map/place-search']);
  assert.equal(h.comp.data.results[0].name, '闵行图书馆');
});

test('V-06 cityNodesToPlaces 去重、丢掉无坐标节点', () => {
  const { cityNodesToPlaces } = require('../../utils/map-address-search.js');
  const out = cityNodesToPlaces([
    { poiId: 1, name: 'A', lat: 31, lng: 121 },
    { poiId: 2, name: 'A', lat: 31, lng: 121 },
    { poiId: 3, merchantName: 'B 店', lat: '31.2', lng: '121.4' },
    { poiId: 4, name: 'C' },
    { poiId: 5, name: '本地已有', lat: 31, lng: 121 },
  ], [{ name: '本地已有' }]);
  assert.deepEqual(out.map((p) => [p.id, p.name, p.lat]), [['node-1', 'A', 31], ['node-3', 'B 店', 31.2]]);
});

test('V-06 地图上不再有「仅前台记录，锁屏后可能中断」', () => {
  assert.doesNotMatch(read(ROAM_WXML), /仅前台记录/);
});

/* ---------------- V-07 黑雾与长按进度环 ---------------- */

function loadRoamPage() {
  let config;
  global.getApp = () => ({ globalData: { user_id: 9, features: {} }, isDevEnv: () => false, sendRequest() {} });
  global.wx = { getStorageSync: () => '', setStorageSync() {}, getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }) };
  global.Page = (c) => { config = c; };
  delete require.cache[require.resolve('../../' + ROAM_JS)];
  require('../../' + ROAM_JS);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  return page;
}

test('V-07 走路时屏幕雾重绘合并,不被每次定位重置', () => {
  const page = loadRoamPage();
  page._fogMode = 'screen';
  let renders = 0;
  page._renderScreenFog = () => { renders++; };
  page._scheduleScreenFogRender();
  const first = page._fogScreenRenderTimer;
  page._scheduleScreenFogRender();
  page._scheduleScreenFogRender();
  assert.equal(page._fogScreenRenderTimer, first, '已在排的重绘不能被后来的定位回调推迟');
  page._scheduleScreenFogRender(0);
  assert.notEqual(page._fogScreenRenderTimer, first, '手势松手要插队立即重画');
  clearTimeout(page._fogScreenRenderTimer);
  page._fogScreenRenderTimer = null;
  assert.equal(renders, 0);
});

test('V-07 手势按下不拉黑,只作废在途重绘,并停止跟随回中', () => {
  const page = loadRoamPage();
  page._fogMode = 'screen';
  page.data.fogScreenGuard = false;
  page._fogScreenRenderTimer = setTimeout(() => { throw new Error('旧视口重绘不该落地'); }, 5);
  page.onRegionChange({ detail: { phase: 'begin', causedBy: 'gesture' } });
  assert.equal(page.data.fogScreenGuard, false);
  assert.equal(page._fogScreenRenderTimer, null);
  assert.ok(page._hold > Date.now() + 30000);
});

test('V-07 屏幕雾离屏画布按 1 倍像素', () => {
  const js = read(ROAM_JS);
  assert.match(js, /const FOG_SCREEN_DPR = 1;/);
  assert.match(js, /_trySetupScreenFog[\s\S]*?const dpr = FOG_SCREEN_DPR;/);
});

test('V-07 长按结束是以主键中心为圆心的标准进度环', () => {
  const wxml = read(ROAM_WXML);
  const wxss = read(ROAM_WXSS);
  const r = /hold__h--r"><view class="hold__f" style="transform:rotate\(\{\{([^}]+)\}\}deg\)"/.exec(wxml);
  const l = /hold__h--l"><view class="hold__f" style="transform:rotate\(\{\{([^}]+)\}\}deg\)"/.exec(wxml);
  assert.ok(r && l);
  const at = (expr, endHoldPct) => vm.runInNewContext(expr, { endHoldPct });
  // 右半边:0% 藏在左侧(-135°),50% 扫满右半圈(45°);左半边 50% 起从 -135° 扫到 45°
  assert.deepEqual([0, 25, 50, 100].map((p) => at(r[1], p)), [-135, -45, 45, 45]);
  assert.deepEqual([0, 50, 75, 100].map((p) => at(l[1], p)), [-135, -135, -45, 45]);
  // 环心对齐:右遮罩里的环向左偏半个环宽,左遮罩里的环贴左;每边只描半圈
  assert.match(wxss, /\.hold__h--r \.hold__f\{ left:-71rpx; border-top-color:#FFFFFF; border-right-color:#FFFFFF; \}/);
  assert.match(wxss, /\.hold__h--l \.hold__f\{ left:0; border-bottom-color:#FFFFFF; border-left-color:#FFFFFF; \}/);
  assert.match(wxss, /\.hold__f\{[^}]*border:8rpx solid transparent;[^}]*transform-origin:50% 50%;/);
});

/* ---------------- V-08 动态二级页 ---------------- */

test('V-08 头像关注角标用两级选择器,不被内置 button 184px 宽顶成白色月牙', () => {
  const wxss = read('components/cy/post-card/index.wxss').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.match(wxss, /\.post-card__avatar \.post-card__follow \{[^}]*width: 32rpx;/);
  assert.doesNotMatch(wxss, /(^|\})\s*\.post-card__follow \{/, '单类选择器 (0,1,0) 压不过 wx-button:not([size=mini])');
});

test('V-08 结算页次级动作去漫游记录,不再跳到动态二级页', () => {
  const wxml = read(ROAM_WXML);
  const js = read(ROAM_JS);
  assert.match(wxml, /class="settle__minor-act" bindtap="openSettleHistory" aria-role="button" aria-label="漫游记录"[^>]*>漫游记录<\/text>/);
  assert.doesNotMatch(wxml, /返回广场/);
  assert.doesNotMatch(js, /url: '\/pages\/square\/list/);
  const page = loadRoamPage();
  const calls = [];
  page.data.screen = 'arrive';
  page.closeSettle = () => { calls.push('close'); page.data.screen = 'entry-map'; };
  page.openRoamHistory = () => calls.push('history');
  page.openSettleHistory();
  assert.deepEqual(calls, ['close', 'history']);
  page.openSettleHistory();
  assert.deepEqual(calls, ['close', 'history'], '不在结算页时不响应');
});

/* ---------------- V-09 漫游护照 ---------------- */

test('V-09 删掉统计说明句,区块间距放开', () => {
  const wxml = read(ROAM_WXML);
  const wxss = read(ROAM_WXSS);
  assert.doesNotMatch(wxml, /以下统计来自/);
  assert.match(wxss, /\.pp-stats\{ flex:none; display:flex; padding:24rpx 46rpx 40rpx; \}/);
  assert.match(wxss, /\.pp-trips\{[^}]*padding:72rpx 0 31rpx 31rpx;/);
});
