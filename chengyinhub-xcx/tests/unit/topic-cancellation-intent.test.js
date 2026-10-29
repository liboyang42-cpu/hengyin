'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const file = '../../utils/topic-cancellation-intent.js';
let rows;
beforeEach(() => {
  rows = new Map();
  global.wx = {
    getStorageSync: key => rows.get(key),
    setStorageSync: (key, value) => rows.set(key, value),
    removeStorageSync: key => rows.delete(key),
  };
});

test('same logical cancellation survives process/module recreation without adopting the new listing epoch', () => {
  let intents = require(file);
  const original = intents.remember('19', 'MERCHANT', '42', '3', 5);
  delete require.cache[require.resolve(file)]; intents = require(file);
  assert.deepEqual(intents.peek('19', 'MERCHANT', '42'), original);
  assert.throws(() => intents.remember('19', 'MERCHANT', '42', '4', 8));
  assert.deepEqual(intents.peek('19', 'MERCHANT', '42'), original);
  assert.equal(intents.peek('20', 'MERCHANT', '42'), null);
  assert.equal(intents.peek('19', 'CLUB:7', '42'), null);
  assert.equal(intents.peek('19', 'MERCHANT', '43'), null);
});

test('only matching original DONE acknowledgement can release the stored epoch for a deliberate new cancellation', () => {
  const intents = require(file);
  intents.remember('19', '', '42', '0', 2);
  assert.throws(() => intents.acknowledge('19', '', '42', '1'));
  assert.equal(intents.peek('19', '', '42').epoch, '0');
  intents.acknowledge('19', '', '42', '0');
  assert.equal(intents.peek('19', '', '42'), null);
  assert.equal(intents.remember('19', '', '42', '1', 3).epoch, '1');
});

test('missing malformed foreign storage and failed durable writes never create a replacement identity', () => {
  const intents = require(file);
  for (const invalid of [null, undefined, '', '-1', '01', '1.5', '9223372036854775808', 1.5, Number.MAX_SAFE_INTEGER + 1])
    assert.throws(() => intents.epoch(invalid));
  const original = intents.remember('19', 'CLUB:7', '42', '0', 0);
  const key = [...rows.keys()][0]; rows.set(key, Object.assign({}, original, { actor: '20' }));
  assert.throws(() => intents.peek('19', 'CLUB:7', '42'));
  assert.throws(() => intents.remember('19', 'CLUB:7', '42', '1', 0));
  assert.equal(rows.get(key).actor, '20');
  rows.clear(); global.wx.setStorageSync = () => {};
  assert.throws(() => intents.remember('19', 'CLUB:7', '42', '0', 0));
  assert.equal(rows.size, 0);
});

test('unknown native storage removal is not treated as an acknowledged absence', () => {
  const intents = require(file);
  intents.remember('19', '', '42', '0', 1);
  global.wx.removeStorageSync = () => {};
  assert.throws(() => intents.acknowledge('19', '', '42', '0'));
  assert.equal(intents.peek('19', '', '42').epoch, '0');
});
