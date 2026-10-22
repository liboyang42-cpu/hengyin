// 2026-10-22 owner 定稿的漂浮圆圈运动规则(pages/publish/template-intro/orbit.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const orbit = require('../../pages/publish/template-intro/orbit.js');

function run(o, seconds, dt = 1 / 60) { for (let t = 0; t < seconds; t += dt) orbit.step(o, dt, false); }

test('沿半圆从下往上走(从左下进、左上出)', () => {
  const o = orbit.createOrbit();
  const c = orbit.center(390, 421);
  const y0 = orbit.place(o, 3, c.cx, c.cy).y;
  run(o, 0.5);
  assert.ok(orbit.place(o, 3, c.cx, c.cy).y < y0, '同一颗的 y 要变小(往上)');
});

test('永不停:慢速漂移之外约每 2.65s 快转一次,正好多走一格', () => {
  const o = orbit.createOrbit();
  run(o, 1.0);
  assert.ok(o.vel > 0, '漂移时也在动');
  const p0 = o.P;
  run(o, orbit.EVERY);   // 恰好经过一次快转
  const travelled = o.P - p0;
  const driftOnly = orbit.DRIFT * orbit.EVERY;
  assert.ok(Math.abs(travelled - driftOnly - 1) < 0.08, `快转多走一格,实际多走 ${(travelled - driftOnly).toFixed(3)}`);
});

test('大小只在快转时变,慢速漂移时不变', () => {
  const o = orbit.createOrbit();
  const c = orbit.center(390, 421);
  run(o, 2.0);   // 首次快转前(sinceKick 从 1.4 起,约 1.25s 时快转)
  run(o, 1.2);   // 快转已经收住
  const r1 = orbit.place(o, 0, c.cx, c.cy).r;
  run(o, 0.6);   // 还在下一次快转之前
  assert.ok(o.sinceKick < orbit.EVERY);
  assert.equal(orbit.place(o, 0, c.cx, c.cy).r.toFixed(3), r1.toFixed(3), '漂移期间半径不变');
});

test('快转时两颗两颗地变,最下面一对先变', () => {
  const o = orbit.createOrbit();
  orbit.startSpin(o, orbit.JUMP);
  o.kickDist = 0.3;   // 快转进行到一部分
  const c = orbit.center(390, 421);
  const byPair = {};
  for (let i = 0; i < orbit.N; i += 1) {
    const p = orbit.place(o, i, c.cx, c.cy);
    const pair = o.pairs[i];
    (byPair[pair] = byPair[pair] || []).push(p.r);
  }
  const pairs = Object.keys(byPair).map(Number).sort((a, b) => a - b);
  pairs.forEach((k) => assert.equal(byPair[k].length, 2, '每一对正好两颗'));
  pairs.forEach((k) => assert.equal(byPair[k][0].toFixed(3), byPair[k][1].toFixed(3), '同一对一起变'));
  const target = orbit.RMAX * orbit.LEVELS[o.lvl];
  const progress = pairs.map((k) => Math.abs(byPair[k][0] - orbit.RMAX * o.scFrom) / Math.abs(target - orbit.RMAX * o.scFrom));
  for (let j = 1; j < progress.length; j += 1) assert.ok(progress[j] <= progress[j - 1], '越往上的一对变得越晚');
});

test('最大不超过 48(owner:最大那档不能太大)', () => {
  assert.equal(Math.max(...orbit.LEVELS) * orbit.RMAX, 48);
});
