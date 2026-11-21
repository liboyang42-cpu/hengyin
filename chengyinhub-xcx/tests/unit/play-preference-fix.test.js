'use strict';
// x-life-games R11:偏好题提交和拍照同一规则 —— 带坐标的城市站随提交带最近一次定位,没有定位照常提交。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const start = src.indexOf('  submitPreference(payload) {');
const body = src.slice(start, src.indexOf('\n  },', start));

test('preference submit merges the stop fix into the write payload', () => {
  assert.ok(start > 0, 'submitPreference exists');
  assert.match(body, /photoFixOf\(this\._loc, prefNode, this\.data\.mode\)/);
  assert.match(body, /_routeWritePayload\('preference', nodeId, original\)/);
});

test('the fix never gates the submit', () => {
  const before = body.slice(0, body.indexOf('photoFixOf('));
  assert.doesNotMatch(before, /return;\s*\/\/.*定位/);
  assert.doesNotMatch(body, /if \(!fix|fix\s*===\s*null/);
});
