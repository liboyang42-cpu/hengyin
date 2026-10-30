'use strict';
// Source-only handoff, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function load(wx) {
  const module = { exports: {} };
  const filename = path.resolve(__dirname, '../../utils/location/bg-tracker.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, wx, setTimeout, clearTimeout }, { filename });
  return module.exports;
}

for (const callback of ['success', 'fail', 'complete']) test('stop ' + callback + ' preserves restarted batch error after notifying other consumers', () => {
  let t, stopOptions, starts = 0; const seen = []; const error = new Error('consumer after stop');
  t = load({
    startLocationUpdateBackground(options) { starts++; options.success(); },
    startLocationUpdate() { assert.fail('consumer error must not trigger foreground fallback'); },
    stopLocationUpdate(options) {
      stopOptions = options;
      t.acquire('new-a', { success() { seen.push('a'); throw error; } });
      t.acquire('new-b', { success: () => seen.push('b') });
      assert.equal(starts, 1, 'new owners wait for stop completion');
      options[callback]({ errMsg: callback === 'fail' ? 'stopLocationUpdate:fail unavailable' : 'stopLocationUpdate:ok' });
    },
  });
  t.acquire('old', {});
  assert.throws(() => t.release('old'), value => value === error);
  assert.deepEqual(seen, ['a', 'b']); assert.equal(starts, 2); assert.equal(t.isRunning(), true);
  stopOptions.success(); stopOptions.fail(); stopOptions.complete();
  assert.equal(starts, 2); assert.deepEqual(seen, ['a', 'b']);
});

test('stop completion preserves restarted failure consumer error without repeating delivery', () => {
  let t, starts = 0, foreground = 0, stopOptions; const seen = []; const error = new Error('consumer fail');
  t = load({
    startLocationUpdateBackground(options) {
      starts++; if (starts === 1) options.success(); else options.fail({ errMsg: 'denied' });
    },
    startLocationUpdate(options) { foreground++; options.fail({ errMsg: 'foreground denied' }); },
    stopLocationUpdate(options) {
      stopOptions = options;
      t.acquire('new-a', { fail() { seen.push('a'); throw error; } });
      t.acquire('new-b', { fail: () => seen.push('b') });
      options.complete({ errMsg: 'stopLocationUpdate:ok' });
    },
  });
  t.acquire('old', {});
  assert.throws(() => t.release('old'), value => value === error);
  assert.deepEqual(seen, ['a', 'b']); assert.equal(starts, 2); assert.equal(foreground, 1);
  assert.equal(t.isRunning(), false); stopOptions.complete(); assert.equal(starts, 2);
});

test('genuine stop API throw still clears stopping and permits later acquisition', () => {
  let starts = 0, stops = 0;
  const t = load({ startLocationUpdateBackground(options) { starts++; options.success(); },
    startLocationUpdate() { assert.fail('unexpected fallback'); },
    stopLocationUpdate() { stops++; throw new Error('platform stop unavailable'); } });
  t.acquire('old', {}); assert.doesNotThrow(() => t.release('old'));
  assert.equal(stops, 1); assert.equal(t.isRunning(), false);
  t.acquire('new', {}); assert.equal(starts, 2); assert.equal(t.isRunning(), true);
});

test('stop callback rethrows even a falsy value from the restarted consumer', () => {
  let t, starts = 0; let caught = false, thrown = 'unset';
  t = load({ startLocationUpdateBackground(options) { starts++; options.success(); },
    startLocationUpdate() { assert.fail('unexpected fallback'); },
    stopLocationUpdate(options) { t.acquire('new', { success() { throw null; } }); options.complete({ errMsg: 'stopLocationUpdate:ok' }); } });
  t.acquire('old', {});
  try { t.release('old'); } catch (value) { caught = true; thrown = value; }
  assert.equal(caught, true); assert.equal(thrown, null); assert.equal(starts, 2);
});
