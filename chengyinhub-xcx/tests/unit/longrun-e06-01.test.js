'use strict';
// Authored only for centralized verification; NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createAudioSession } = require('../../utils/audio-session.js');
function harness() {
  const begin = [], end = [], calls = [];
  const session = createAudioSession({ onAudioInterruptionBegin: f => begin.push(f), onAudioInterruptionEnd: f => end.push(f) });
  const ctx = { paused: false,
    pause() { this.paused = true; calls.push('pause'); },
    play() { this.paused = false; calls.push('play'); },
  };
  return { session, ctx, calls, begin, end };
}

test('duplicate live registration during interruption retains one automatic resume', () => {
  const { session: s, ctx, calls, begin, end } = harness();
  assert.equal(s.register(ctx, { kind: 'guide' }), ctx);
  begin[0]();
  assert.equal(s.register(ctx, { kind: 'chapter' }), ctx);
  s.register(ctx);
  assert.equal(s._size(), 1); assert.equal(begin.length, 1); assert.equal(end.length, 1);
  end[0](); end[0](); s.resumeAll();
  assert.deepEqual(calls, ['pause', 'play']);
});

test('duplicate live registration retains explicit pauseAll resume bookkeeping', () => {
  const { session: s, ctx, calls, end } = harness();
  s.register(ctx); s.pauseAll(); s.register(ctx);
  end[0](); assert.deepEqual(calls, ['pause']);
  s.resumeAll(); s.resumeAll();
  assert.deepEqual(calls, ['pause', 'play']);
});

test('unregister followed by same-context registration starts with no old resume eligibility', () => {
  const { session: s, ctx, calls, begin, end } = harness();
  s.register(ctx); begin[0](); s.unregister(ctx);
  assert.equal(s._size(), 0);
  s.register(ctx); end[0](); s.resumeAll();
  assert.deepEqual(calls, ['pause']); assert.equal(s._size(), 1);
  assert.equal(begin.length, 1); assert.equal(end.length, 1);
  ctx.paused = false; begin[0](); end[0]();
  assert.deepEqual(calls, ['pause', 'pause', 'play'], 'fresh registration still handles a new interruption');
});

test('duplicate registration does not restore automatic resume cancelled by page pause', () => {
  const { session: s, ctx, calls, begin, end } = harness();
  s.register(ctx); begin[0](); s.pauseAll(); s.register(ctx); end[0]();
  assert.deepEqual(calls, ['pause']);
  s.resumeAll(); assert.deepEqual(calls, ['pause', 'play']);
});
