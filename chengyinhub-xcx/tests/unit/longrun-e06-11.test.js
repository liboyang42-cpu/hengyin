'use strict';
// Authored only, NOT_RUN under the source-only protocol.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function harness(newStart) {
  const bg = [], fg = [], stops = [];
  const module = { exports: {} };
  const filename = path.resolve(__dirname, '../../utils/location/bg-tracker.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, wx: {
    startLocationUpdateBackground(options) { bg.push(options); if (bg.length > 1 && newStart) newStart(options); },
    startLocationUpdate: options => fg.push(options), stopLocationUpdate: options => stops.push(options),
  } }, { filename });
  return { tracker: module.exports, bg, fg, stops };
}

test('direct same-key reacquire during failure replaces old callback before asynchronous success', () => {
  const { tracker: t, bg, fg, stops } = harness(); const seen = [];
  t.acquire('a', { fail() { seen.push('old-a'); t.acquire('b', { success: () => seen.push('new-b') }); } });
  t.acquire('b', { fail: () => seen.push('old-b') });
  t.acquire('c', { fail: () => seen.push('old-c') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['old-a', 'old-c']); assert.equal(bg.length, 2);
  bg[1].success(); assert.deepEqual(seen, ['old-a', 'old-c', 'new-b']);
  assert.equal(stops.length, 0); assert.equal(t.isRunning(), true);
});

test('direct replacement with synchronous success cannot be followed by the old failure callback', () => {
  const { tracker: t, bg, fg } = harness(options => options.success()); const seen = [];
  t.acquire('a', { fail() { seen.push('old-a'); t.acquire('b', { success: () => seen.push('new-b') }); } });
  t.acquire('b', { fail: () => seen.push('old-b') });
  t.acquire('c', { fail: () => seen.push('old-c') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['old-a', 'new-b', 'old-c']); assert.equal(t.isRunning(), true);
});

test('replacement acquisition owns its own failure and old batch still notifies unrelated consumer', () => {
  const { tracker: t, bg, fg } = harness(); const seen = [];
  const oldError = { errMsg: 'old failure' }, newError = { errMsg: 'new failure' };
  t.acquire('a', { fail() { seen.push('old-a'); t.acquire('b', { fail: error => seen.push(['new-b', error]) }); } });
  t.acquire('b', { fail: () => seen.push('old-b') });
  t.acquire('c', { fail: error => seen.push(['old-c', error]) });
  bg[0].fail(oldError); fg[0].fail(oldError);
  assert.deepEqual(seen, ['old-a', ['old-c', oldError]]);
  bg[1].fail(newError); fg[1].fail(newError);
  assert.deepEqual(seen, ['old-a', ['old-c', oldError], ['new-b', newError]]);
  assert.equal(t.isRunning(), false);
});

test('acquiring a different key during failure does not cancel the original remaining failures', () => {
  const { tracker: t, bg, fg } = harness(); const seen = [];
  t.acquire('a', { fail() { seen.push('old-a'); t.acquire('new', { success: () => seen.push('new') }); } });
  t.acquire('b', { fail: () => seen.push('old-b') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['old-a', 'old-b']);
  bg[1].success(); assert.deepEqual(seen, ['old-a', 'old-b', 'new']);
});
