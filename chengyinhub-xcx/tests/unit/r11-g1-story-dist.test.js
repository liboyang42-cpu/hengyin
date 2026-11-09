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


/* R11 g1(j4 F7):「下一站 · Xm」与罗盘距离必须跟着定位走,不能停在 rebuild 那一刻的数 */
test('下一站距离随定位更新;文字没变不重复 setData', () => {
  const h = harness(); h.page.requestPlayLocation(); h.confirm(); h.position();
  h.starts[0].success();
  h.page.data.nextNode = { nodeId: 9, lat: 31.0, lng: 121.0 };
  h.page._playLocationHandler({ latitude: 31.0014, longitude: 121.0, accuracy: 10 });
  assert.equal(h.page.data.storyDistText, '156m');
  let writes = 0; const set = h.page.setData;
  h.page.setData = function (patch, cb) { if ('storyDistText' in patch) writes++; return set.call(this, patch, cb); };
  h.page._playLocationHandler({ latitude: 31.0014, longitude: 121.0, accuracy: 10 });
  assert.equal(writes, 0, '距离没变不写');
  h.page._playLocationHandler({ latitude: 31.0001, longitude: 121.0, accuracy: 10 });
  assert.equal(h.page.data.storyDistText, '11m');
  assert.equal(writes, 1);
  h.page.onUnload();
});
