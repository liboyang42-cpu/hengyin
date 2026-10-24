'use strict';

// C:U-180:枚举 → 接口字符串的单一适配层。
const test = require('node:test');
const assert = require('node:assert/strict');
const { enumCode, enumLabel } = require('../../utils/enum-code.js');
const { normalizeAtmosphere } = require('../../utils/chapter-atmosphere.js');

test('enumCode 归一为去空白的大写字符串,空值给空串', () => {
  assert.equal(enumCode('public'), 'PUBLIC');
  assert.equal(enumCode('  Paused '), 'PAUSED');
  assert.equal(enumCode('CONFIRMED'), 'CONFIRMED');
  assert.equal(enumCode(null), '');
  assert.equal(enumCode(undefined), '');
  assert.equal(enumCode(''), '');
  assert.equal(enumCode(0), '0');
  assert.equal(enumCode(false), 'FALSE');
});

test('enumLabel 只认自有键,查不到给 fallback', () => {
  const labels = { READY: '可开局', DONE: '已结束' };
  assert.equal(enumLabel(labels, 'ready', '状态待确认'), '可开局');
  assert.equal(enumLabel(labels, 'unknown', '状态待确认'), '状态待确认');
  assert.equal(enumLabel(labels, 'toString', '状态待确认'), '状态待确认');
  assert.equal(enumLabel(labels, 'constructor', 'x'), 'x');
  assert.equal(enumLabel(null, 'READY', 'x'), 'x');
});

test('已迁移的 chapter-atmosphere 行为不变', () => {
  assert.equal(normalizeAtmosphere(' blue '), 'BLUE');
  assert.equal(normalizeAtmosphere('night'), 'BLUE');
  assert.equal(normalizeAtmosphere(''), 'DEFAULT');
  assert.equal(normalizeAtmosphere(null), 'DEFAULT');
  assert.equal(normalizeAtmosphere(0), 'DEFAULT');
  assert.equal(normalizeAtmosphere('green'), 'DEFAULT');
});
