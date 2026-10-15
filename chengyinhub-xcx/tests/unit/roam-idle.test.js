const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const { createIdleDetector } = require('../../utils/roam-idle.js');
const { m2lat } = require('../../utils/roam-geo.js');

const P = { lat: 31.23, lng: 121.47 };

test('3m 内停留满 60s 才进入静止态', () => {
  const d = createIdleDetector();
  assert.equal(d.update(P, 0), false);
  assert.equal(d.update({ lat: P.lat + m2lat(1), lng: P.lng }, 30000), false);
  assert.equal(d.update({ lat: P.lat + m2lat(2), lng: P.lng }, 59999), false);
  assert.equal(d.update({ lat: P.lat + m2lat(2), lng: P.lng }, 60000), true);
});

test('位移达到 3m 立即退出静止并重新计时', () => {
  const d = createIdleDetector();
  d.update(P, 0);
  assert.equal(d.update(P, 61000), true);
  const moved = { lat: P.lat + m2lat(5), lng: P.lng };
  assert.equal(d.update(moved, 62000), false);
  assert.equal(d.update(moved, 121000), false);
  assert.equal(d.update(moved, 122000), true);
});

test('非法点不改变状态，reset 清空锚点', () => {
  const d = createIdleDetector();
  d.update(P, 0);
  assert.equal(d.update(null, 70000), true);
  assert.equal(d.update({ lat: NaN, lng: 1 }, 70000), true);
  d.reset();
  assert.equal(d.update(P, 80000), false);
});

test('漫游页只把静止态用于 HUD 展示，不改计时', () => {
  const root = path.join(__dirname, '../..');
  const js = fs.readFileSync(path.join(root, 'pages/roam/index.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(root, 'pages/roam/index.wxml'), 'utf8');
  assert.match(js, /require\('\.\.\/\.\.\/utils\/roam-idle\.js'\)/);
  assert.match(wxml, /原地停留中/);
  assert.doesNotMatch(js, /roamIdle[^\n]*_sessionClock/);
});
