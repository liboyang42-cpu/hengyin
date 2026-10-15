'use strict';
// Authored only; NOT_RUN until centralized verification is authorized.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const ll = require('../../utils/location/last-location.js');
const invalidCoordinates = [null, undefined, '', ' \t\n', false, true, [], [1], {},
  { valueOf: () => 1 }, Symbol('latitude'), 1n];
function memory(value) {
  const writes = [];
  return { writes, getStorageSync: () => value,
    setStorageSync(key, next) { writes.push([key, next]); value = next; } };
}

test('valid rejects coercible missing and nonnumeric types in either coordinate', () => {
  for (const value of invalidCoordinates) {
    assert.equal(ll.valid({ latitude: value, longitude: 120 }), false);
    assert.equal(ll.valid({ latitude: 30, longitude: value }), false);
  }
  assert.equal(ll.valid(null), false); assert.equal(ll.valid([]), false);
  assert.equal(ll.valid({ latitude: 0, longitude: 0 }), false);
});

test('invalid save cannot replace previously stored real coordinates', () => {
  const env = memory({ latitude: 30, longitude: 120 });
  for (const value of invalidCoordinates) {
    assert.equal(ll.save({ latitude: value, longitude: 120 }, env), false);
    assert.equal(ll.save({ latitude: 30, longitude: value }, env), false);
  }
  assert.deepEqual(env.writes, []);
  assert.deepEqual(ll.load(env), { latitude: 30, longitude: 120 });
});

test('invalid cached coordinate types are rejected and resolve falls back to existing fixed location', () => {
  for (const value of invalidCoordinates) {
    for (const point of [{ latitude: value, longitude: 120 }, { latitude: 30, longitude: value }]) {
      const env = memory(point);
      assert.equal(ll.load(env), null);
      const resolved = ll.resolve(env);
      assert.equal(resolved.source, 'fixed');
      assert.equal(resolved.latitude, ll.FIXED.latitude); assert.equal(resolved.longitude, ll.FIXED.longitude);
      assert.deepEqual(env.writes, [], 'fallback must not overwrite storage');
    }
  }
});

test('single-axis zero, numeric strings and boundary coordinates remain valid and normalized', () => {
  for (const point of [
    { latitude: 0, longitude: 120 }, { latitude: 30, longitude: 0 },
    { latitude: '0', longitude: '120' }, { latitude: '30', longitude: '0' },
    { latitude: ' 30.5 ', longitude: ' 120.5 ' },
    { latitude: -90, longitude: -180 }, { latitude: '90', longitude: '180' },
  ]) {
    const env = memory();
    assert.equal(ll.valid(point), true); assert.equal(ll.save(point, env), true);
    const expected = { latitude: Number(point.latitude), longitude: Number(point.longitude) };
    assert.deepEqual(env.writes, [[ll.KEY, expected]]); assert.deepEqual(ll.load(env), expected);
    assert.equal(ll.resolve(env).source, 'last');
  }
  assert.equal(ll.valid({ latitude: '0', longitude: '0' }), false);
});
