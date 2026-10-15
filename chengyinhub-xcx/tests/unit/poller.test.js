'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createPoller } = require('../../utils/poller.js');

function fakeTimers() {
  let now = 0; let id = 0; const q = new Map();
  return {
    setTimeout: (f, ms) => { q.set(++id, { f, at: now + ms }); return id; },
    clearTimeout: (i) => q.delete(i),
    pending: () => q.size,
    nextDelay: () => { const t = [...q.values()]; return t.length ? Math.min(...t.map((x) => x.at)) - now : null; },
    async advance(ms) {
      const end = now + ms;
      for (;;) {
        const e = [...q.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!e) break;
        q.delete(e[0]); now = e[1].at; e[1].f();
        for (let i = 0; i < 5; i++) await Promise.resolve();
      }
      now = end;
    },
  };
}
const mk = (T, run) => createPoller({ run, interval: 4000, maxInterval: 10000, setTimeout: T.setTimeout, clearTimeout: T.clearTimeout });

test('成功时按 4s 节拍;start/stop 幂等', async () => {
  const T = fakeTimers(); let n = 0;
  const p = mk(T, () => { n++; return Promise.resolve(true); });
  p.start(); p.start();
  assert.equal(T.pending(), 1);
  await T.advance(3999); assert.equal(n, 0);
  await T.advance(1); assert.equal(n, 1);
  await T.advance(4000); assert.equal(n, 2);
  p.stop(); p.stop();
  assert.equal(T.pending(), 0);
  await T.advance(20000); assert.equal(n, 2);
});

test('连续失败 4s→8s→10s 封顶,成功后复位', async () => {
  const T = fakeTimers(); const results = [false, false, false, true, true]; let n = 0;
  const p = mk(T, () => Promise.resolve(results[n++]));
  p.start();
  const seen = [T.nextDelay()];
  for (let i = 0; i < 5; i++) { await T.advance(T.nextDelay()); seen.push(T.nextDelay()); }
  assert.deepEqual(seen.slice(0, 6), [4000, 8000, 10000, 10000, 4000, 4000]);
});

test('reject 也算失败;不抛未处理异常', async () => {
  const T = fakeTimers(); let n = 0;
  const p = mk(T, () => { n++; return Promise.reject(new Error('x')); });
  p.start(); await T.advance(4000);
  assert.equal(T.nextDelay(), 8000);
  const p2 = mk(T, () => { throw new Error('sync'); });
  p2.start(); await T.advance(4000);
  p.stop(); p2.stop();
});

test('在途时不叠发:run 未回来,到点顺延而不是再发一次', async () => {
  const T = fakeTimers(); let n = 0; let done;
  const p = mk(T, () => { n++; return new Promise((r) => { done = r; }); });
  p.start(); await T.advance(4000); assert.equal(n, 1);
  await T.advance(30000); assert.equal(n, 1);
  done(true); for (let i = 0; i < 5; i++) await Promise.resolve();
  await T.advance(4000); assert.equal(n, 2);
});

test('在途时 stop:回来后不再排下一次;stop 后立刻 start 不被旧响应打乱', async () => {
  const T = fakeTimers(); let n = 0; const dones = [];
  const p = mk(T, () => { n++; return new Promise((r) => dones.push(r)); });
  p.start(); await T.advance(4000);
  p.stop(); dones[0](false); for (let i = 0; i < 5; i++) await Promise.resolve();
  assert.equal(T.pending(), 0);
  p.start(); assert.equal(T.nextDelay(), 4000);
  p.stop();
});
