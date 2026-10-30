'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function harness() {
  const listeners = new Set();
  let stops = 0;
  const wx = {
    onLocationChange(fn) { listeners.add(fn); },
    offLocationChange(fn) { assert.equal(typeof fn, 'function'); listeners.delete(fn); },
    startLocationUpdateBackground(opts) { opts.success(); },
    stopLocationUpdate(opts) { stops++; opts.success(); }
  };
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../utils/location/bg-tracker.js'), 'utf8'), { wx, module, setTimeout, clearTimeout });
  const bgTracker = module.exports;
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  const context = { wx, bgTracker, playLocationOwnerSequence: 0, app: { getUserID: () => 9 } };
  const page = { data: { topicId: 31, nav: { active: false }, nodes: [], nextNode: null },
    setData(patch) { Object.assign(this.data, patch); }, _persistRunSession() {},
    _pauseSessionClock() {}, _clearRunSessionEverywhere() {}, _syncRunState() {} };
  for (const name of ['_playMember', '_capturePlayLoginContext', '_boundPlayOwner', '_isPlayAccountCurrent', '_sessionParams', '_sessionKey', '_trackKey', '_stopTracking', 'watchLocation', 'onPlayHudToggle', '_endRunSession']) {
    const start = source.indexOf('  ' + name + '(');
    const end = source.indexOf('\n  },', start);
    assert.ok(start >= 0 && end > start);
    page[name] = vm.runInNewContext('({' + source.slice(start, end + 4).trim() + '})', context)[name];
  }
  return { page, wx, bgTracker, listeners, stops: () => stops };
}

test('play pause and completion release only its tracker and listener while roam continues', () => {
  const h = harness();
  const roamListener = () => {};
  h.wx.onLocationChange(roamListener);
  h.bgTracker.acquire('roam', { success() {} });
  h.page.watchLocation();
  const oldPlayListener = h.page._playLocationHandler;
  assert.equal(h.listeners.size, 2);
  let paused = false;
  h.page._sessionClock = { isPaused: () => paused, pause() { paused = true; }, resume() { paused = false; } };
  h.page.onPlayHudToggle();
  assert.equal(h.listeners.size, 1);
  assert.ok(h.listeners.has(roamListener));
  assert.equal(h.stops(), 0);
  oldPlayListener({ latitude: 31, longitude: 121 });
  assert.equal(h.page._loc, undefined, 'queued old callback cannot update a paused play');
  h.page.data.gpsOk = true;
  h.page.onPlayHudToggle();
  assert.equal(h.listeners.size, 2);
  h.page._endRunSession();
  assert.equal(h.listeners.size, 1);
  assert.equal(h.stops(), 0);
  h.bgTracker.release('roam');
  assert.equal(h.stops(), 1);
});

test('completing the only active play stops location immediately and repeatedly finishing is harmless', () => {
  const h = harness();
  h.page.watchLocation();
  h.page._endRunSession();
  assert.equal(h.stops(), 1);
  assert.equal(h.listeners.size, 0);
  h.page._endRunSession();
  assert.equal(h.stops(), 1);
});
