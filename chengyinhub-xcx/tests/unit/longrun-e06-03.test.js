'use strict';
// Authored for deferred centralized verification. NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createAudioSession } = require('../../utils/audio-session.js');
function harness() {
  const requests = [];
  return { requests, session: createAudioSession({ setKeepScreenOn: options => requests.push(options) }) };
}
const values = requests => requests.map(request => request.keepScreenOn);

test('failed enable retries only on a later owner operation and deduplicates pending calls', () => {
  const { session: s, requests: q } = harness();
  s.keepAwake('a', true); s.keepAwake('a', true); s.keepAwake('b', true);
  assert.deepEqual(values(q), [true]);
  q[0].fail(); assert.equal(q.length, 1, 'no automatic failure retry');
  s.keepAwake('b', true); assert.deepEqual(values(q), [true, true]);
  q[1].success(); s.keepAwake('a', false);
  assert.deepEqual(values(q), [true, true], 'remaining owner keeps screen on');
  s.keepAwake('b', false); assert.deepEqual(values(q), [true, true, false]);
});

test('failed disable stays retryable when releaseAwake is called again', () => {
  const { session: s, requests: q } = harness();
  s.keepAwake('a', true); q[0].success(); s.releaseAwake();
  q[1].fail(); assert.equal(q.length, 2);
  s.releaseAwake(); assert.deepEqual(values(q), [true, false, false]);
  q[2].success(); s.releaseAwake(); assert.equal(q.length, 3);
});

test('synchronous platform throw does not permanently suppress a repeated owner request', () => {
  let attempts = 0; const q = [];
  const s = createAudioSession({ setKeepScreenOn(options) {
    attempts++; if (attempts === 1) throw new Error('platform unavailable'); q.push(options);
  } });
  assert.doesNotThrow(() => s.keepAwake('a', true));
  assert.equal(attempts, 1);
  s.keepAwake('a', true); assert.equal(attempts, 2);
  q[0].success(); s.keepAwake('a', true); assert.equal(attempts, 2);
});

test('late off success cannot overwrite a newer confirmed on state', () => {
  const { session: s, requests: q } = harness();
  s.keepAwake('a', true); q[0].success();
  s.keepAwake('a', false); s.keepAwake('b', true);
  assert.deepEqual(values(q), [true, false, true]);
  q[2].success(); q[1].success(); q[1].fail();
  s.keepAwake('b', true); assert.equal(q.length, 3, 'obsolete close must not change confirmed state');
  s.releaseAwake(); assert.deepEqual(values(q), [true, false, true, false]);
});

test('last owner release issues off before pending on settles and ignores stale on callbacks', () => {
  const { session: s, requests: q } = harness();
  s.keepAwake('a', true); s.keepAwake('b', true);
  s.keepAwake('a', false); assert.deepEqual(values(q), [true]);
  s.keepAwake('b', false); assert.deepEqual(values(q), [true, false]);
  q[0].fail(); s.releaseAwake(); assert.equal(q.length, 2, 'stale failure must not clear current inflight off');
  q[1].success(); q[0].success(); s.releaseAwake();
  assert.equal(q.length, 2, 'stale success must not turn confirmed off back on');
});

test('a settled failed request cannot settle its replacement via a duplicate success', () => {
  const { session: s, requests: q } = harness();
  s.keepAwake('a', true); q[0].fail(); s.keepAwake('a', true);
  q[0].success(); s.keepAwake('a', true); assert.equal(q.length, 2);
  q[1].fail(); s.keepAwake('a', true); assert.equal(q.length, 3);
});
