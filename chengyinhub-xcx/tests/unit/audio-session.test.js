'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createAudioSession } = require('../../utils/audio-session.js');

function fakeWx(withKeepOn = true) {
  const w = { begin: [], end: [], keep: [] };
  w.onAudioInterruptionBegin = (f) => w.begin.push(f);
  w.onAudioInterruptionEnd = (f) => w.end.push(f);
  if (withKeepOn) w.setKeepScreenOn = (o) => w.keep.push(o.keepScreenOn);
  return w;
}
function fakeCtx(playing) {
  const c = { paused: !playing, calls: [] };
  c.play = () => { c.paused = false; c.calls.push('play'); };
  c.pause = () => { c.paused = true; c.calls.push('pause'); };
  return c;
}
const fire = (list) => list.forEach((f) => f());

test('打断监听只注册一次,与 ctx 个数无关', () => {
  const w = fakeWx(); const s = createAudioSession(w);
  s.register(fakeCtx(false), { kind: 'a' }); s.register(fakeCtx(false), { kind: 'b' });
  assert.equal(w.begin.length, 1); assert.equal(w.end.length, 1);
});

test('打断开始只暂停在播的;结束只恢复被打断前在播的', () => {
  const w = fakeWx(); const s = createAudioSession(w);
  const playing = s.register(fakeCtx(true), { kind: 'guide' });
  const idle = s.register(fakeCtx(false), { kind: 'kit' });
  fire(w.begin);
  assert.deepEqual(playing.calls, ['pause']); assert.deepEqual(idle.calls, []);
  fire(w.end);
  assert.deepEqual(playing.calls, ['pause', 'play']); assert.deepEqual(idle.calls, []);
  fire(w.end);   // 再来一次结束不重复恢复
  assert.deepEqual(playing.calls, ['pause', 'play']);
});

test('用户在打断期间已手动暂停的不被恢复:打断中途 unregister 的也不恢复', () => {
  const w = fakeWx(); const s = createAudioSession(w);
  const a = s.register(fakeCtx(true)); const b = s.register(fakeCtx(true));
  fire(w.begin); s.unregister(b); fire(w.end);
  assert.deepEqual(a.calls, ['pause', 'play']); assert.deepEqual(b.calls, ['pause']);
});

test('pauseAll 暂停并记账;onHide 的暂停不会被打断结束顶回来;resumeAll 只恢复在播过的', () => {
  const w = fakeWx(); const s = createAudioSession(w);
  const a = s.register(fakeCtx(true)); const b = s.register(fakeCtx(false));
  s.pauseAll();
  assert.deepEqual(a.calls, ['pause']);
  fire(w.end);
  assert.deepEqual(a.calls, ['pause']);
  s.resumeAll();
  assert.deepEqual(a.calls, ['pause', 'play']); assert.deepEqual(b.calls, []);
});

test('keepAwake 按 owner 引用计数,最后一个释放才关;重复开关不重复调用', () => {
  const w = fakeWx(); const s = createAudioSession(w);
  s.keepAwake('story', true); s.keepAwake('audio', true); s.keepAwake('audio', true);
  assert.deepEqual(w.keep, [true]);
  s.keepAwake('story', false); assert.deepEqual(w.keep, [true]);
  s.keepAwake('audio', false); assert.deepEqual(w.keep, [true, false]);
  s.keepAwake('x', false); assert.deepEqual(w.keep, [true, false]);
  s.keepAwake('a', true); s.keepAwake('b', true); s.releaseAwake();
  assert.deepEqual(w.keep, [true, false, true, false]);
});

test('API 缺失时静默降级,不抛', () => {
  const s = createAudioSession({});
  const c = s.register(fakeCtx(true));
  assert.doesNotThrow(() => { s.keepAwake('s', true); s.keepAwake('s', false); s.pauseAll(); s.resumeAll(); });
  assert.deepEqual(c.calls, ['pause', 'play']);
  const s2 = createAudioSession(fakeWx(false));
  assert.doesNotThrow(() => s2.keepAwake('s', true));
});
