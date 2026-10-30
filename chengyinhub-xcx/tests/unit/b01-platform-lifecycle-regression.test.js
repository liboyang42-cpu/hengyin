'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createPoller } = require('../../pages/play/utils/poller.js');
const { createAudioSession } = require('../../pages/play/utils/audio-session.js');

// Execute the unchanged singleton in a separate realm per case; no global.wx/cache leaks.
function trackerHarness() {
  const bg = [], fg = [], stops = [];
  const wx = {
    startLocationUpdateBackground: o => bg.push(o),
    startLocationUpdate: o => fg.push(o),
    stopLocationUpdate: o => stops.push(o),
  };
  const filename = path.resolve(__dirname, '../../utils/location/bg-tracker.js');
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, wx, setTimeout, clearTimeout }, { filename });
  return { tracker: module.exports, bg, fg, stops };
}
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

for (const rejected of [false, true]) test('poller restart before old ' + (rejected ? 'rejection' : 'false result') + ' preserves new cadence', async () => {
  const queue = new Map(); let id = 0, calls = 0, resolveOld, rejectOld;
  const old = new Promise((resolve, reject) => { resolveOld = resolve; rejectOld = reject; });
  const p = createPoller({ interval: 10, maxInterval: 40,
    run: () => { calls++; return calls === 1 ? old : true; },
    setTimeout: (fn, ms) => { queue.set(++id, { fn, ms }); return id; },
    clearTimeout: key => queue.delete(key),
  });
  const tick = async () => {
    assert.equal(queue.size, 1);
    const [key, task] = queue.entries().next().value;
    queue.delete(key); task.fn(); await flush();
  };
  p.start(); await tick(); p.stop(); p.start();
  await tick(); // New generation timer fires while old request is still pending.
  assert.equal(calls, 1, 'restart must preserve single-flight');
  if (rejected) rejectOld(new Error('late')); else resolveOld(false);
  await flush();
  assert.equal(queue.size, 1);
  assert.equal([...queue.values()][0].ms, 10, 'old failure must not back off the new generation');
  await tick(); assert.equal(calls, 2);
  assert.equal([...queue.values()][0].ms, 10);
  p.stop(); assert.equal(queue.size, 0);
});

test('audio unregister then re-register same context cannot inherit interruption resume', () => {
  let begin, end; const calls = [];
  const session = createAudioSession({ onAudioInterruptionBegin: f => { begin = f; }, onAudioInterruptionEnd: f => { end = f; } });
  const ctx = { paused: false, pause() { this.paused = true; calls.push('pause'); }, play() { this.paused = false; calls.push('play'); } };
  session.register(ctx); begin(); session.unregister(ctx); session.register(ctx);
  end(); session.resumeAll();
  assert.deepEqual(calls, ['pause']);
  assert.equal(session._size(), 1);
});

test('GPS old start success can satisfy replacement owner without reviving released waiter', () => {
  const { tracker: t, bg, stops } = trackerHarness(); const seen = [];
  t.acquire('same', { success: () => seen.push('old') }); t.release('same');
  t.acquire('same', { success: () => seen.push('new') });
  assert.equal(bg.length, 2);
  bg[0].success(); bg[1].fail({ errMsg: 'late obsolete failure' });
  assert.deepEqual(seen, ['new']); assert.equal(t.isRunning(), true);
  assert.equal(stops.length, 0);
  t.release('same'); assert.equal(stops.length, 1);
});

test('GPS success batch cannot notify released waiter after same-key reacquire in another consumer', () => {
  const { tracker: t, bg, stops } = trackerHarness(); const seen = [];
  t.acquire('first', { success: () => {
    seen.push('first'); t.release('first', true);
    t.acquire('second', { success: () => seen.push('new-second') });
  } });
  t.acquire('second', { success: () => seen.push('released-second') });
  bg[0].success();
  assert.deepEqual(seen, ['first'], 'released batch callback must not borrow replacement owner identity');
  assert.equal(stops.length, 1); stops[0].complete({ errMsg: 'stopLocationUpdate:ok' }); bg[1].success();
  assert.deepEqual(seen, ['first', 'new-second']);
  t.release('second');
});

test('GPS failure batch respects consumer release performed by earlier failure callback', () => {
  const { tracker: t, bg, fg } = trackerHarness(); const seen = [];
  t.acquire('first', { fail: () => { seen.push('first'); t.release('second', true); } });
  t.acquire('second', { fail: () => seen.push('released-second') });
  bg[0].fail({ errMsg: 'background denied' }); fg[0].fail({ errMsg: 'foreground denied' });
  assert.deepEqual(seen, ['first'], 'released consumer must not receive the remainder of an old failure batch');
  assert.equal(t.isRunning(), false);
});
