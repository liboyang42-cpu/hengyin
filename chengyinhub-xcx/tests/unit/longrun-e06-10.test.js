'use strict';
// Authored only; NOT_RUN under source-only handoff rules.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function harness(foreground) {
  const bg = [], fg = [], stops = [];
  const module = { exports: {} };
  const filename = path.resolve(__dirname, '../../utils/location/bg-tracker.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, setTimeout, clearTimeout, wx: {
    startLocationUpdateBackground: options => bg.push(options),
    startLocationUpdate: foreground || (options => fg.push(options)),
    stopLocationUpdate: options => stops.push(options),
  } }, { filename });
  return { tracker: module.exports, bg, fg, stops };
}

test('success batch notifies all consumers before propagating first error unchanged', () => {
  const { tracker: t, bg } = harness(); const seen = [];
  const first = new Error('first success'), second = new Error('second success');
  t.acquire('a', { success() { seen.push('a'); throw first; } });
  t.acquire('b', { success() { seen.push('b'); throw second; } });
  t.acquire('c', { success: value => seen.push(['c', value]) });
  assert.throws(() => bg[0].success(), error => error === first);
  assert.deepEqual(seen, ['a', 'b', ['c', true]]); assert.equal(t.isRunning(), true);
});

test('failure batch notifies all consumers before propagating first error and permits retry', () => {
  const { tracker: t, bg, fg } = harness(); const seen = [];
  const first = new Error('first failure'), platform = { errMsg: 'denied' };
  t.acquire('a', { fail(error) { seen.push(['a', error]); throw first; } });
  t.acquire('b', { fail: error => seen.push(['b', error]) });
  bg[0].fail(platform);
  assert.throws(() => fg[0].fail(platform), error => error === first);
  assert.deepEqual(seen, [['a', platform], ['b', platform]]);
  assert.equal(t.isRunning(), false);
  t.acquire('a', {}); assert.equal(bg.length, 2);
});

test('throwing consumer can still cancel an old same-key success waiter during dispatch', () => {
  const { tracker: t, bg, stops } = harness(); const seen = []; const error = new Error('after release');
  t.acquire('a', { success() {
    seen.push('a'); t.release('a', true);
    t.acquire('b', { success: () => seen.push('new-b') }); throw error;
  } });
  t.acquire('b', { success: () => seen.push('old-b') });
  assert.throws(() => bg[0].success(), value => value === error);
  assert.deepEqual(seen, ['a']); assert.equal(stops.length, 1);
  stops[0].complete({ errMsg: 'stopLocationUpdate:ok' }); bg[1].success();
  assert.deepEqual(seen, ['a', 'new-b']);
});

test('throwing failure consumer can cancel another waiter without cancelling remaining live callbacks', () => {
  const { tracker: t, bg, fg } = harness(); const seen = []; const error = new Error('after cancellation');
  t.acquire('a', { fail() { seen.push('a'); t.release('b'); throw error; } });
  t.acquire('b', { fail: () => seen.push('old-b') });
  t.acquire('c', { fail: () => seen.push('c') });
  bg[0].fail({ errMsg: 'background denied' });
  assert.throws(() => fg[0].fail({ errMsg: 'foreground denied' }), value => value === error);
  assert.deepEqual(seen, ['a', 'c']);
});

test('falsy thrown value is rethrown after the rest of the batch', () => {
  const { tracker: t, bg } = harness(); const seen = []; let caught = false, thrown = 'unset';
  t.acquire('a', { success() { seen.push('a'); throw undefined; } });
  t.acquire('b', { success: () => seen.push('b') });
  try { bg[0].success(); } catch (error) { caught = true; thrown = error; }
  assert.equal(caught, true); assert.equal(thrown, undefined);
  assert.deepEqual(seen, ['a', 'b']);
});

test('synchronous foreground callback batch error remains consumer error without a second fallback', () => {
  const error = new Error('foreground consumer'); const seen = []; let fgCalls = 0, fails = 0;
  const { tracker: t, bg } = harness(options => { fgCalls++; options.success(); });
  t.acquire('a', { success() { seen.push('a'); throw error; }, fail() { fails++; } });
  t.acquire('b', { success: () => seen.push('b'), fail() { fails++; } });
  assert.throws(() => bg[0].fail({ errMsg: 'background denied' }), value => value === error);
  assert.deepEqual(seen, ['a', 'b']); assert.equal(fgCalls, 1); assert.equal(fails, 0);
  assert.equal(t.isRunning(), true); assert.equal(t.isBackground(), false);
});
