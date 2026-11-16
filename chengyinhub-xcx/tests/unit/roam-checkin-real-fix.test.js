'use strict';
// GAME-4 (W7 x-rewards-evidence):点亮地点只用设备真实定位,不再把地图中心或地点自己的坐标当成玩家位置。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const src = fs.readFileSync(path.join(__dirname, '..', '..', 'pages', 'roam', 'index.js'), 'utf8');
const body = (name) => {
  const start = src.indexOf('\n  ' + name + '(');
  assert.ok(start > 0, name + ' exists');
  const next = src.indexOf('\n  },\n', start);
  return src.slice(start, next);
};

test('onPoiTap never sends the map centre or the place itself as the player position', () => {
  const tap = body('onPoiTap');
  assert.doesNotMatch(tap, /this\.data\.center/);
  assert.doesNotMatch(tap, /curLat/);
  assert.match(tap, /_realCheckinFix\(\)/);
  assert.match(tap, /需要打开定位才能点亮这里/);
});

test('the check-in body carries the real fix with accuracy and fix time', () => {
  const post = body('_postPoiCheckin');
  assert.match(post, /curLat: String\(fix\.lat\)/);
  assert.match(post, /curLng: String\(fix\.lng\)/);
  assert.match(post, /locTimestamp: String\(fix\.at\)/);
  assert.match(post, /body\.locAccuracy = String\(fix\.accuracy\)/);
  assert.match(post, /r\.uncredited/);
});

test('a real fix is reused for 30 seconds, otherwise a fresh high-accuracy GCJ-02 fix is taken', () => {
  const fix = body('_realCheckinFix');
  assert.match(fix, /Date\.now\(\) - last\.at < 30000/);
  assert.match(fix, /type: 'gcj02'/);
  assert.match(fix, /isHighAccuracy: true/);
  assert.match(fix, /fail: \(\) => resolve\(null\)/);
  assert.match(src, /this\._lastRealFix = \{ lat: loc\.latitude, lng: loc\.longitude, accuracy: loc\.accuracy, at: now \}/);
});

test('_realCheckinFix returns null when location fails, and the last fix when fresh', async () => {
  const fn = new Function('wx', 'return function() {' + fix0() + '}');
  function fix0() {
    const b = body('_realCheckinFix');
    return b.slice(b.indexOf('{') + 1);
  }
  const failing = { getLocation: (o) => o.fail() };
  assert.equal(await fn(failing).call({}), null);
  const fresh = { lat: 1, lng: 2, accuracy: 5, at: Date.now() };
  assert.deepEqual(await fn(failing).call({ _lastRealFix: fresh }), fresh);
  const ok = { getLocation: (o) => o.success({ latitude: 31.2, longitude: 121.4, accuracy: 12 }) };
  const got = await fn(ok).call({});
  assert.equal(got.lat, 31.2);
  assert.equal(got.accuracy, 12);
});
