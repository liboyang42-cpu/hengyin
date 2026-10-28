'use strict';

/* 找东西:台面按原图比例收框(不裁)。2026-10-27 生产实测:16:9 的场景图塞进竖台面按 aspectFill 裁掉一半多,
   标在两侧的三个点玩家一个都看不见、点不中。坐标改成量这个框 = 量原图,和编辑页同一把尺。 */

const assert = require('assert');
const test = require('node:test');

let config;
global.Behavior = (b) => b;
global.Component = (c) => { config = c; };
global.wx = { createSelectorQuery: () => ({}) };
require('../../pages/play/components/playkit-hidden/index.js');
const { _containFrame: containFrame, _toPercent: toPercent } = config.methods;

function parse(style) {
  const out = {};
  style.split(';').filter(Boolean).forEach((kv) => { const [k, v] = kv.split(':'); out[k] = parseFloat(v); });
  return out;
}

test('横图进竖台面:框占满宽、上下留边居中,不裁', () => {
  const f = parse(containFrame({ width: 350, height: 439 }, { width: 1280, height: 720 }));
  assert.equal(f.left, 0);
  assert.equal(f.width, 100);
  assert.ok(Math.abs(f.height - (350 * 720 / 1280) / 439 * 100) < 0.01);
  assert.ok(Math.abs(f.top * 2 + f.height - 100) < 0.01);
});

test('竖图进横台面:框占满高、左右留边居中', () => {
  const f = parse(containFrame({ width: 686, height: 360 }, { width: 900, height: 1200 }));
  assert.equal(f.top, 0);
  assert.equal(f.height, 100);
  assert.ok(Math.abs(f.left * 2 + f.width - 100) < 0.01);
});

test('量不到台面或原图尺寸时不收框(沿用整块台面)', () => {
  assert.equal(containFrame(null, { width: 10, height: 10 }), '');
  assert.equal(containFrame({ width: 0, height: 10 }, { width: 10, height: 10 }), '');
  assert.equal(containFrame({ width: 10, height: 10 }, { width: 0, height: 10 }), '');
});

test('点在框里报相对原图的百分比,点在留边上不算', () => {
  const frame = { left: 20, top: 210, width: 350, height: 197 };
  assert.deepEqual(toPercent(20 + 0.762 * 350, 210 + 0.29 * 197, frame), { x: 76.2, y: 29 });
  assert.equal(toPercent(100, 200, frame), null);
});
