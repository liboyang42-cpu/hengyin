'use strict';
// Source-only handoff: these cases are authored, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function tracker(wx) {
  const module = { exports: {} };
  const filename = path.resolve(__dirname, '../../utils/location/bg-tracker.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, wx, setTimeout, clearTimeout }, { filename });
  return module.exports;
}

test('background startup throw uses existing foreground fallback with platform receiver', () => {
  const ready = [], stops = []; let bgCalls = 0, fgCalls = 0;
  const wx = {
    startLocationUpdateBackground() { assert.equal(this, wx); bgCalls++; throw new Error('background unavailable'); },
    startLocationUpdate(options) { assert.equal(this, wx); fgCalls++; options.success(); },
    stopLocationUpdate: options => stops.push(options),
  };
  const t = tracker(wx);
  assert.doesNotThrow(() => t.acquire('a', { success: background => ready.push(background) }));
  assert.deepEqual(ready, [false]); assert.equal(bgCalls, 1); assert.equal(fgCalls, 1);
  assert.equal(t.isRunning(), true); assert.equal(t.isBackground(), false);
  t.release('a'); assert.equal(stops.length, 1);
});

test('foreground startup throw fails all waiting consumers and permits same-key retry', () => {
  const bg = [], seen = []; const error = new Error('foreground unavailable'); let fgCalls = 0;
  const t = tracker({
    startLocationUpdateBackground: options => bg.push(options),
    startLocationUpdate() { fgCalls++; throw error; }, stopLocationUpdate() {},
  });
  t.acquire('a', { fail: err => seen.push(['a', err]) });
  t.acquire('b', { fail: err => seen.push(['b', err]) });
  assert.doesNotThrow(() => bg[0].fail({ errMsg: 'denied' }));
  assert.deepEqual(seen, [['a', error], ['b', error]]); assert.equal(fgCalls, 1);
  assert.equal(t.isRunning(), false);
  t.acquire('a', { success: value => seen.push(['retry', value]) });
  assert.equal(bg.length, 2); bg[1].success();
  assert.deepEqual(seen, [['a', error], ['b', error], ['retry', true]]);
});

test('both startup APIs throwing clear the owner instead of leaving starting stuck', () => {
  const failures = []; let attempts = 0; const error = new Error('foreground throw');
  const t = tracker({ startLocationUpdateBackground() { attempts++; throw new Error('background throw'); },
    startLocationUpdate() { throw error; }, stopLocationUpdate() {} });
  assert.doesNotThrow(() => t.acquire('a', { fail: e => failures.push(e) }));
  assert.doesNotThrow(() => t.acquire('a', { fail: e => failures.push(e) }));
  assert.equal(attempts, 2); assert.deepEqual(failures, [error, error]);
  assert.equal(t.isRunning(), false);
});

test('missing background API and throwing foreground still release acquisition identity', () => {
  const failures = []; let attempts = 0;
  const t = tracker({ startLocationUpdate() { attempts++; throw new Error('foreground throw'); }, stopLocationUpdate() {} });
  t.acquire('a', { fail: e => failures.push(e) });
  t.acquire('a', { fail: e => failures.push(e) });
  assert.equal(attempts, 2); assert.equal(failures.length, 2); assert.equal(t.isRunning(), false);
});

for (const background of [true, false]) test('synchronous ' + (background ? 'background' : 'foreground') + ' consumer success throw is not a platform failure', () => {
  const error = new Error('consumer success'); let foregroundCalls = 0, failures = 0;
  const t = tracker({
    startLocationUpdateBackground(options) { if (background) options.success(); else options.fail({ errMsg: 'denied' }); },
    startLocationUpdate(options) { foregroundCalls++; options.success(); }, stopLocationUpdate() {},
  });
  assert.throws(() => t.acquire('a', { success() { throw error; }, fail() { failures++; } }), e => e === error);
  assert.equal(foregroundCalls, background ? 0 : 1);
  assert.equal(failures, 0); assert.equal(t.isRunning(), true);
});

test('synchronous consumer failure throw is delivered once and never reinterpreted as startup failure', () => {
  const error = new Error('consumer failure'); let foregroundCalls = 0, failures = 0;
  const t = tracker({
    startLocationUpdateBackground: options => options.fail({ errMsg: 'background denied' }),
    startLocationUpdate(options) { foregroundCalls++; options.fail({ errMsg: 'foreground denied' }); }, stopLocationUpdate() {},
  });
  assert.throws(() => t.acquire('a', { fail() { failures++; throw error; } }), e => e === error);
  assert.equal(failures, 1); assert.equal(foregroundCalls, 1); assert.equal(t.isRunning(), false);
});
