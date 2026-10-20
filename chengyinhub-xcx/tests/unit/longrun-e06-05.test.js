'use strict';
// Authored only for later centralized verification. NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createPoller } = require('../../pages/play/utils/poller.js');
function harness(options) {
  const queue = new Map(); let id = 0;
  const poller = createPoller(Object.assign({ run: () => false }, options, {
    setTimeout(fn, ms) { queue.set(++id, { fn, ms }); return id; },
    clearTimeout: key => queue.delete(key),
  }));
  return { poller, delay() {
    assert.equal(queue.size, 1); return queue.values().next().value.ms;
  }, async tick() {
    assert.equal(queue.size, 1);
    const [key, entry] = queue.entries().next().value;
    queue.delete(key); entry.fn();
    for (let i = 0; i < 5; i++) await Promise.resolve();
  } };
}

test('nonpositive and nonfinite interval values use the established default cadence', async () => {
  for (const interval of [-10, 0, Infinity, -Infinity, NaN, 'bad', '', ' ', null, true]) {
    const h = harness({ interval }); h.poller.start();
    assert.equal(h.delay(), 4000, 'invalid interval must not reach the timer');
    await h.tick(); assert.equal(h.delay(), 8000);
    h.poller.stop();
  }
});

test('invalid maxInterval uses the existing interval times 2.5 cap', async () => {
  for (const maxInterval of [-10, 0, Infinity, -Infinity, NaN, 'bad', ' ']) {
    const h = harness({ interval: 100, maxInterval }); h.poller.start();
    await h.tick(); assert.equal(h.delay(), 200);
    await h.tick(); assert.equal(h.delay(), 250);
    h.poller.stop();
  }
});

test('numeric string configuration remains compatible with existing arithmetic', async () => {
  const h = harness({ interval: '100', maxInterval: '350' }); h.poller.start();
  assert.equal(h.delay(), 100); await h.tick(); assert.equal(h.delay(), 200);
  await h.tick(); assert.equal(h.delay(), 350); h.poller.stop();
});

test('finite values above the timer range never overflow to short-delay scheduling', async () => {
  const h = harness({ interval: Number.MAX_VALUE, maxInterval: Number.MAX_VALUE });
  h.poller.start(); assert.equal(h.delay(), 2147483647);
  await h.tick(); assert.equal(h.delay(), 2147483647); h.poller.stop();
});

test('default and explicit caps stay representable when backoff approaches the timer limit', async () => {
  for (const maxInterval of [undefined, Number.MAX_VALUE]) {
    const h = harness({ interval: 1500000000, maxInterval }); h.poller.start();
    assert.equal(h.delay(), 1500000000);
    await h.tick(); assert.equal(h.delay(), 2147483647);
    await h.tick(); assert.equal(h.delay(), 2147483647); h.poller.stop();
  }
});

test('sub-millisecond positive configuration cannot produce a zero-millisecond timer', async () => {
  const h = harness({ interval: 0.1, maxInterval: 0.5 }); h.poller.start();
  assert.equal(h.delay(), 1); await h.tick(); assert.equal(h.delay(), 1); h.poller.stop();
});

test('finite positive cap below interval never shortens the initial cadence', async () => {
  const h = harness({ interval: 100, maxInterval: 50 }); h.poller.start();
  assert.equal(h.delay(), 100); await h.tick(); assert.equal(h.delay(), 100); h.poller.stop();
});
