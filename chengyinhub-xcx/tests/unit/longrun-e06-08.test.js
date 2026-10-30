'use strict';
// E06-08: authored only; NOT_RUN under the source-only delivery protocol.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function harness() {
  const bg = [], fg = [], stops = [];
  const module = { exports: {} };
  const filename = path.resolve(__dirname, '../../utils/location/bg-tracker.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, setTimeout, clearTimeout, wx: {
    startLocationUpdateBackground: options => bg.push(options),
    startLocationUpdate: options => fg.push(options),
    stopLocationUpdate: options => stops.push(options),
  } }, { filename });
  return { tracker: module.exports, bg, fg, stops };
}

test('success force-release cancels old batch despite same-key replacement waiting for stop', () => {
  const { tracker: t, bg, stops } = harness(); const seen = [];
  t.acquire('first', { success() {
    seen.push('first'); t.release('first', true);
    t.acquire('second', { success: () => seen.push('new-second') });
  } });
  t.acquire('second', { success: () => seen.push('old-second') });
  bg[0].success();
  assert.deepEqual(seen, ['first']);
  assert.equal(t.isRunning(), false);
  assert.equal(stops.length, 1); assert.equal(bg.length, 1);
  stops[0].complete({ errMsg: 'stopLocationUpdate:ok' }); assert.equal(bg.length, 2);
  bg[1].success();
  assert.deepEqual(seen, ['first', 'new-second']);
  assert.equal(t.isRunning(), true);
});

test('success release/reacquire cancels old identity while preserving shared running owner', () => {
  const { tracker: t, bg, stops } = harness(); const seen = [];
  t.acquire('first', { success() {
    seen.push('first'); t.release('second');
    t.acquire('second', { success: () => seen.push('new-second') });
  } });
  t.acquire('second', { success: () => seen.push('old-second') });
  bg[0].success();
  assert.deepEqual(seen, ['first', 'new-second']);
  assert.equal(stops.length, 0); assert.equal(bg.length, 1);
  t.release('second'); assert.equal(stops.length, 0);
  t.release('first'); assert.equal(stops.length, 1);
});

test('foreground failure still notifies every retained consumer after owners are cleared', () => {
  const { tracker: t, bg, fg } = harness(); const seen = [];
  const error = { errMsg: 'foreground denied' };
  t.acquire('first', { fail: err => seen.push(['first', err]) });
  t.acquire('second', { fail: err => seen.push(['second', err]) });
  bg[0].fail({ errMsg: 'background denied' });
  assert.equal(fg.length, 1); fg[0].fail(error);
  assert.deepEqual(seen, [['first', error], ['second', error]]);
  assert.equal(t.isRunning(), false);
  t.acquire('second', {}); assert.equal(bg.length, 2, 'failed owner must be retryable');
});

test('failure consumer can cancel another old waiter and reacquire its key independently', () => {
  const { tracker: t, bg, fg, stops } = harness(); const seen = [];
  t.acquire('first', { fail() {
    seen.push('first'); t.release('second');
    t.acquire('second', { success: () => seen.push('new-second') });
  } });
  t.acquire('second', { fail: () => seen.push('old-second') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['first']);
  assert.equal(bg.length, 2); assert.equal(stops.length, 0);
  bg[1].success();
  assert.deepEqual(seen, ['first', 'new-second']);
});

test('failure force-release cancels all remaining notifications even without live owners', () => {
  const { tracker: t, bg, fg } = harness(); const seen = [];
  t.acquire('first', { fail() { seen.push('first'); t.release('first', true); } });
  t.acquire('second', { fail: () => seen.push('second') });
  t.acquire('third', { fail: () => seen.push('third') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['first']);
  assert.equal(t.isRunning(), false);
});

test('revoke during failure notification cancels that key like release (master revoke design)', () => {
  const { tracker: t, bg, fg } = harness(); const seen = [];
  t.acquire('first', { fail() { seen.push('first'); t.revoke('second'); } });
  t.acquire('second', { fail: () => seen.push('second') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['first']);
  assert.equal(t.revision('second'), 1);
});
