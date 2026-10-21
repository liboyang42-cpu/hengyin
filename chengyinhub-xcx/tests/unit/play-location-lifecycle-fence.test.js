'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const PAGE = path.resolve(__dirname, '../../pages/play/index.js');
const TRACKER = path.resolve(__dirname, '../../utils/location/bg-tracker.js');

function harness() {
  let member = 7, definition;
  const dialogs = [], positions = [], starts = [], stops = [], listeners = new Set();
  const app = { globalData: {}, getUserID: () => member, sendRequest() {} };
  global.getApp = () => app; global.getCurrentPages = () => [];
  global.wx = {
    getStorageSync: () => '', setStorageSync() {}, showToast() {},
    getSystemInfoSync: () => ({}), getWindowInfo: () => ({}), setKeepScreenOn() {},
    showModal: options => dialogs.push(options), getLocation: options => positions.push(options),
    startLocationUpdateBackground: options => starts.push(options),
    startLocationUpdate: options => starts.push(options),
    stopLocationUpdate: options => { stops.push(options); if (options.success) options.success(); },
    onLocationChange: listener => listeners.add(listener), offLocationChange: listener => listeners.delete(listener),
  };
  global.Page = value => { definition = value; };
  delete require.cache[TRACKER]; delete require.cache[PAGE];
  if (process.env.PLAY_LOCATION_BASELINE_SOURCE) {
    const loaded = new Module(PAGE, module);
    loaded.filename = PAGE; loaded.paths = Module._nodeModulePaths(path.dirname(PAGE));
    loaded._compile(fs.readFileSync(process.env.PLAY_LOCATION_BASELINE_SOURCE, 'utf8'), PAGE);
  } else require(PAGE);
  const tracker = require(TRACKER);
  function makePage() {
    const callbacks = [];
    const page = Object.assign({}, definition, { data: structuredClone(definition.data) });
    page.data.topicId = 31; page._playOwner = String(member); page._playDisposed = false;
    page.setData = function (patch, callback) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let target = this.data;
        for (let i = 0; i < parts.length - 1; i++) target = target[parts[i]];
        target[parts.at(-1)] = value;
      }
      if (callback) { if (this.deferData) callbacks.push(callback); else callback(); }
    };
    page.rebuild = () => {}; // Map rendering is separate; ownership, lifecycle and SDK callers remain real.
    return { page, callbacks };
  }
  return { ...makePage(), makePage, tracker, dialogs, positions, starts, stops, listeners,
    account: value => { member = value; }, confirm: () => dialogs.at(-1).success({ confirm: true }),
    position: () => positions.at(-1).success({ latitude: 31, longitude: 121, accuracy: 10 }) };
}

for (const stage of ['dialog', 'gps', 'setData']) test('unload fences late location ' + stage + ' callback before any tracker reacquire', () => {
  const h = harness(); h.page.requestPlayLocation();
  if (stage !== 'dialog') h.confirm();
  if (stage === 'setData') { h.page.deferData = true; h.position(); }
  h.page.onUnload(); assert.equal(h.tracker.hasOwner('play:31'), false);
  if (stage === 'dialog') h.confirm();
  if (stage === 'gps') h.position();
  if (stage === 'setData') h.callbacks.shift()();
  assert.equal(h.starts.length, 0); assert.equal(h.tracker.hasOwner('play:31'), false);
  assert.equal(h.listeners.size, 0);
  if (stage === 'dialog') assert.equal(h.positions.length, 0);
});

for (const stage of ['dialog', 'gps']) for (const change of ['account', 'session', 'pause']) {
  test(change + ' fences late location ' + stage + ' callback', () => {
    const h = harness(); h.page.requestPlayLocation(); if (stage === 'gps') h.confirm();
    if (change === 'account') h.account(8);
    if (change === 'session') h.page.data.topicId = 32;
    if (change === 'pause') h.page._stopTracking();
    if (stage === 'dialog') h.confirm(); else h.position();
    assert.equal(h.starts.length, 0); assert.equal(h.tracker.hasOwner('play:31'), false);
    assert.equal(h.page._loc, undefined);
    if (stage === 'dialog') assert.equal(h.positions.length, 0);
  });
}

test('normal explicit consent starts tracking and hide keeps approved game location alive', () => {
  const h = harness(); h.page.requestPlayLocation(); h.confirm(); h.position();
  assert.equal(h.starts.length, 1); h.starts[0].success();
  assert.equal(h.tracker.isRunning(), true); assert.equal(h.listeners.size, 1);
  h.page.onHide();
  assert.equal(h.tracker.isRunning(), true); assert.equal(h.stops.length, 0);
  h.page._playLocationHandler({ latitude: 31.001, longitude: 121.002, accuracy: 10 });
  assert.equal(h.page._loc.latitude, 31.001);
  h.page.onUnload(); assert.equal(h.stops.length, 1); assert.equal(h.listeners.size, 0);
  assert.equal(h.tracker.hasOwner('play:31'), false);
});

test('hide during explicitly approved GPS request does not revoke game location permission', () => {
  const h = harness(); h.page.requestPlayLocation(); h.confirm(); h.page.onHide(); h.position();
  assert.equal(h.starts.length, 1); h.starts[0].success();
  assert.equal(h.tracker.isRunning(), true); assert.equal(h.listeners.size, 1); h.page.onUnload();
});

test('old start success cannot release or strand the newer same-key game owner', () => {
  const h = harness(); h.page.watchLocation(); assert.equal(h.starts.length, 1);
  h.page._stopTracking();
  const next = h.makePage().page; next.watchLocation(); assert.equal(h.starts.length, 2);
  const nextKey = next._playTrackingKey;
  h.starts[0].success();
  assert.equal(h.tracker.hasOwner(nextKey), true); assert.equal(h.tracker.isRunning(), true);
  assert.equal(h.listeners.size, 1); assert.equal(h.page._playLocationHandler == null, true);
  assert.equal(typeof next._playLocationHandler, 'function'); assert.equal(h.stops.length, 0);
  h.page.onUnload(); // Repeated old cleanup must not release a key now held only by the new page.
  assert.equal(h.tracker.hasOwner(nextKey), true); assert.equal(h.listeners.size, 1);
  h.starts[1].success(); assert.equal(h.listeners.size, 1);
  next.onUnload(); assert.equal(h.tracker.hasOwner(nextKey), false); assert.equal(h.listeners.size, 0);
});

test('a superseded GPS callback cannot clear a newer request or restart an ended generation', () => {
  const h = harness(); h.page.requestPlayLocation(); h.confirm(); const old = h.positions[0];
  h.page._stopTracking(); h.page.requestPlayLocation(); h.confirm();
  old.success({ latitude: 1, longitude: 2, accuracy: 10 });
  assert.equal(h.page._locationRequesting, true); assert.equal(h.starts.length, 0);
  h.position(); assert.equal(h.starts.length, 1); h.starts[0].success(); h.page.onUnload();
});

test('disposed or changed-account pages cannot directly acquire a fresh tracker owner', () => {
  for (const change of ['account', 'unload']) {
    const h = harness();
    if (change === 'account') h.account(8); else h.page.onUnload();
    h.page.watchLocation(); h.page.requestPlayLocation();
    assert.equal(h.starts.length, 0); assert.equal(h.dialogs.length, 0);
    assert.equal(h.tracker.hasOwner('play:31'), false);
  }
});

for (const change of ['account', 'session']) test('pending SDK start retires its owner after ' + change + ' changes', () => {
  const h = harness(); h.page.watchLocation(); const key = h.page._playTrackingKey;
  if (change === 'account') h.account(8); else h.page.data.topicId = 32;
  h.starts[0].success();
  assert.equal(h.tracker.hasOwner(key), false);
  assert.equal(h.tracker.isRunning(), false); assert.equal(h.listeners.size, 0);
});

for (const change of ['account', 'session']) test('running GPS callback retires its owner after ' + change + ' changes', () => {
  const h = harness(); h.page.watchLocation(); const key = h.page._playTrackingKey; h.starts[0].success(); const callback = h.page._playLocationHandler;
  if (change === 'account') h.account(8); else h.page.data.topicId = 32;
  callback({ latitude: 99, longitude: 88, accuracy: 10 });
  assert.equal(h.tracker.hasOwner(key), false);
  assert.equal(h.tracker.isRunning(), false); assert.equal(h.listeners.size, 0);
  assert.equal(h.page._loc, undefined);
});

test('two live pages for the same topic have separate owners and either can stop without clearing the other', () => {
  const h = harness(); h.page.watchLocation();
  const next = h.makePage().page; next.watchLocation();
  const firstKey = h.page._playTrackingKey, nextKey = next._playTrackingKey;
  assert.notEqual(firstKey, nextKey); assert.equal(h.starts.length, 1, 'SDK start remains shared');
  h.starts[0].success(); assert.equal(h.listeners.size, 2);
  h.page.onUnload();
  assert.equal(h.tracker.hasOwner(firstKey), false); assert.equal(h.tracker.hasOwner(nextKey), true);
  assert.equal(h.tracker.isRunning(), true); assert.equal(h.listeners.size, 1); assert.equal(h.stops.length, 0);
  next._playLocationHandler({ latitude: 31, longitude: 121, accuracy: 10 });
  assert.equal(next._loc.latitude, 31);
  next.onUnload(); assert.equal(h.tracker.hasOwner(nextKey), false); assert.equal(h.listeners.size, 0); assert.equal(h.stops.length, 1);
});

test('old-account pending SDK start retires only its lease while the new account same-topic page stays live', () => {
  const h = harness(); h.page.watchLocation(); const firstKey = h.page._playTrackingKey;
  h.account(8); const next = h.makePage().page; next.watchLocation(); const nextKey = next._playTrackingKey;
  h.starts[0].success();
  assert.equal(h.tracker.hasOwner(firstKey), false); assert.equal(h.tracker.hasOwner(nextKey), true);
  assert.equal(h.tracker.isRunning(), true); assert.equal(h.listeners.size, 1);
  assert.equal(typeof next._playLocationHandler, 'function'); assert.equal(h.stops.length, 0);
  h.page.onUnload(); assert.equal(h.tracker.hasOwner(nextKey), true);
  next.onUnload(); assert.equal(h.stops.length, 1);
});

test('a retained old GPS callback cannot release a reused same-key lease after a fresh start', () => {
  const h = harness(); h.page.watchLocation(); h.starts[0].success(); const oldCallback = h.page._playLocationHandler;
  h.page._stopTracking(); h.page.watchLocation(); h.starts[1].success();
  const newKey = h.page._playTrackingKey;
  oldCallback({ latitude: 99, longitude: 88, accuracy: 10 });
  assert.equal(h.tracker.hasOwner(newKey), true); assert.equal(h.tracker.isRunning(), true); assert.equal(h.listeners.size, 1);
  assert.equal(h.page._loc, undefined); h.page.onUnload();
});
