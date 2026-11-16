'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { photoFix, MAX_AGE_MS } = require('../../pages/play/utils/photo-fix.js');

const node = { nodeId: 1, lat: '31.2304', lng: '121.4737' };
const now = 1_700_000_000_000;
const loc = { longitude: 121.4738, latitude: 31.2305, accuracy: 20, capturedAt: now - 1000 };

test('located city stop sends the recent fix with the photo', () => {
  assert.deepStrictEqual(photoFix(loc, node, 1, now), { longitude: 121.4738, latitude: 31.2305, accuracy: 20 });
});

test('stale, missing, online or free-explore cases send nothing and never block', () => {
  assert.deepStrictEqual(photoFix(Object.assign({}, loc, { capturedAt: now - MAX_AGE_MS - 1 }), node, 1, now), {});
  assert.deepStrictEqual(photoFix(null, node, 1, now), {});
  assert.deepStrictEqual(photoFix(loc, { nodeId: 2 }, 1, now), {});
  assert.deepStrictEqual(photoFix(loc, node, 2, now), {});
  assert.deepStrictEqual(photoFix(Object.assign({}, loc, { capturedAt: undefined }), node, 1, now), {});
});
