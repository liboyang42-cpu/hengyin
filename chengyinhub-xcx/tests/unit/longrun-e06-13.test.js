'use strict';
// Authored only for later centralized verification, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { hasCoords, pickLocation, getCurrentLocation } = require('../../utils/location/location-manager.js');
const invalid = [true, false, [1], [], { valueOf: () => 1 }, { toString: () => '1' },
  null, undefined, '', ' \t\n', Symbol('coordinate'), 1n];
function harness() {
  const choose = [], locate = [], toasts = [], modals = [];
  return { choose, locate, toasts, modals, env: {
    wx: { chooseLocation: options => choose.push(options), getLocation: options => locate.push(options) },
    toast: title => toasts.push(title), modal: options => modals.push(options),
  } };
}

test('hasCoords rejects nonnumeric types in each axis without invoking coercion hooks', () => {
  let coercions = 0;
  const hostile = { valueOf() { coercions++; throw new Error('must not coerce'); } };
  for (const value of invalid.concat([hostile])) {
    assert.equal(hasCoords({ latitude: value, longitude: 120 }), false);
    assert.equal(hasCoords({ latitude: 30, longitude: value }), false);
  }
  assert.equal(coercions, 0);
});

test('pickLocation invalid platform coordinate cannot enter caller form update', () => {
  for (const value of invalid) {
    const h = harness(); const form = { latitude: 30, longitude: 120 }; const failures = [];
    pickLocation({ onPick: poi => Object.assign(form, poi), onFail: error => failures.push(error) }, h.env);
    h.choose[0].success({ name: 'invalid', latitude: value, longitude: 120 });
    assert.deepEqual(form, { latitude: 30, longitude: 120 });
    assert.equal(failures.length, 1); assert.equal(failures[0].code, 'POI_COORDS_MISSING');
    assert.deepEqual(h.toasts, ['没找到该地点坐标，请换个词重选']); assert.deepEqual(h.modals, []);
  }
});

test('getCurrentLocation invalid coordinate follows existing failure path without success or permission prompt', () => {
  for (const value of invalid) {
    const h = harness(); let successes = 0; const failures = [];
    getCurrentLocation({ onSuccess() { successes++; }, onFail: error => failures.push(error) }, h.env);
    const result = { latitude: 30, longitude: value };
    h.locate[0].success(result);
    assert.equal(successes, 0); assert.deepEqual(failures, [result]);
    assert.deepEqual(h.toasts, ['未获取到有效位置，请重试']); assert.deepEqual(h.modals, []);
  }
});

test('numeric strings remain accepted and onPick preserves original scalar types and raw result', () => {
  const h = harness(); let picked, raw;
  const result = { name: 'point', address: 'address', latitude: ' 30.5 ', longitude: '120.5', extra: 'raw' };
  assert.equal(hasCoords(result), true);
  pickLocation({ onPick(poi, source) { picked = poi; raw = source; } }, h.env);
  h.choose[0].success(result);
  assert.deepEqual(picked, { name: 'point', address: 'address', latitude: ' 30.5 ', longitude: '120.5' });
  assert.equal(raw, result); assert.deepEqual(h.toasts, []);
});

test('location-manager keeps its existing single-axis zero rejection and range boundaries', () => {
  for (const point of [
    { latitude: 0, longitude: 120 }, { latitude: 30, longitude: 0 },
    { latitude: '0', longitude: '120' }, { latitude: '30', longitude: '0' },
    { latitude: Infinity, longitude: 120 }, { latitude: 30, longitude: NaN },
    { latitude: 90.1, longitude: 120 }, { latitude: 30, longitude: 180.1 },
  ]) assert.equal(hasCoords(point), false);
  assert.equal(hasCoords({ latitude: 90, longitude: 180 }), true);
  assert.equal(hasCoords({ latitude: '-90', longitude: '-180' }), true);
});
