'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
function harness(initial = false) {
  const values = { play_reduced_motion: initial };
  let failPrimary = false, failLegacy = false;
  const wx = {
    getStorageSync(key) { return values[key]; },
    setStorageSync(key, value) {
      if (key === 'play_reduced_motion' && failPrimary || key === 'roam_reduced_motion' && failLegacy) throw Error('storage');
      values[key] = value;
    },
  };
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/motion-preference.js'), 'utf8'), { module, wx });
  const preference = module.exports;
  const behaviorModule = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'behaviors/reduced-motion.js'), 'utf8'), {
    module: behaviorModule, Behavior(value) { return value; }, require() { return preference; },
  });
  const behavior = behaviorModule.exports;
  function mount(explicit = false, observeBeforeAttach = false) {
    const instance = {
      data: { reducedMotion: explicit }, updates: 0, ...behavior.methods,
      setData(patch) {
        this.updates++;
        assert.ok(this.updates < 20, 'observer 不得自激');
        Object.assign(this.data, patch);
        observe(this, patch.reducedMotion);
      },
    };
    if (observeBeforeAttach) observe(instance, explicit);
    behavior.lifetimes.attached.call(instance);
    return instance;
  }
  function observe(instance, value) {
    if (behavior.observers && behavior.observers.reducedMotion) behavior.observers.reducedMotion.call(instance, value);
    else if (behavior.properties.reducedMotion.observer) behavior.properties.reducedMotion.observer.call(instance, value);
  }
  return { wx, preference, behavior, mount, observe,
    primaryFails(value) { failPrimary = value; }, legacyFails(value) { failLegacy = value; } };
}
test('共享偏好实时开关同步所有已挂载组件，无需返回页面', () => {
  const h = harness(), a = h.mount(), b = h.mount();
  assert.equal(h.preference.writeReducedMotion(h.wx, true), true);
  assert.equal(a.data.reducedMotion, true);
  assert.equal(b.data.reducedMotion, true);
  assert.equal(h.preference.writeReducedMotion(h.wx, false), false);
  assert.equal(a.data.reducedMotion, false);
  assert.equal(b.data.reducedMotion, false);
  h.behavior.lifetimes.detached.call(a); h.behavior.lifetimes.detached.call(b);
});
test('attached 前的属性监听不会把存储偏好误记为页面显式值', () => {
  const h = harness(true), implicit = h.mount(false, true), explicit = h.mount(true, true);
  h.preference.writeReducedMotion(h.wx, false);
  assert.equal(implicit.data.reducedMotion, false);
  assert.equal(implicit._pageReducedMotion, false);
  assert.equal(explicit.data.reducedMotion, true);
  h.behavior.lifetimes.detached.call(implicit); h.behavior.lifetimes.detached.call(explicit);
});
test('detached 退订后不再写组件，其他订阅继续生效', () => {
  const h = harness(), a = h.mount(), b = h.mount();
  h.behavior.lifetimes.detached.call(a);
  h.preference.writeReducedMotion(h.wx, true);
  assert.equal(a.updates, 0);
  assert.equal(a.data.reducedMotion, false);
  assert.equal(b.data.reducedMotion, true);
  h.behavior.lifetimes.detached.call(b);
});
test('页面显式 true 优先，共享偏好不能覆盖；正常 observer 可以取消显式值', () => {
  const h = harness(), a = h.mount(true);
  assert.equal(a.data.reducedMotion, true);
  h.preference.writeReducedMotion(h.wx, false);
  assert.equal(a.data.reducedMotion, true);
  a.data.reducedMotion = false; h.observe(a, false);
  assert.equal(a.data.reducedMotion, false);
  h.preference.writeReducedMotion(h.wx, true);
  assert.equal(a.data.reducedMotion, true);
  h.preference.writeReducedMotion(h.wx, false);
  assert.equal(a.data.reducedMotion, false);
  h.behavior.lifetimes.detached.call(a);
});
test('共享值引起的 observer 不污染页面显式值、不自激，page show 仍同步', () => {
  const h = harness(true), a = h.mount();
  assert.equal(a.data.reducedMotion, true);
  assert.equal(a._pageReducedMotion, false);
  h.preference.writeReducedMotion(h.wx, false);
  assert.equal(a.data.reducedMotion, false);
  assert.equal(a.updates, 2);
  h.wx.setStorageSync('play_reduced_motion', true);
  h.behavior.pageLifetimes.show.call(a);
  assert.equal(a.data.reducedMotion, true);
  assert.equal(a._pageReducedMotion, false);
  h.behavior.lifetimes.detached.call(a);
});
test('主存储失败返回 null 且不广播；legacy 失败仍广播主键已保存结果', () => {
  const h = harness(), a = h.mount();
  let calls = 0;
  const stop = h.preference.subscribeReducedMotion(() => { calls++; });
  h.primaryFails(true);
  assert.equal(h.preference.writeReducedMotion(h.wx, true), null);
  assert.equal(calls, 0); assert.equal(a.data.reducedMotion, false);
  h.primaryFails(false); h.legacyFails(true);
  assert.equal(h.preference.writeReducedMotion(h.wx, true), true);
  assert.equal(calls, 1); assert.equal(a.data.reducedMotion, true);
  stop(); h.behavior.lifetimes.detached.call(a);
});
test('单个 listener 抛错不阻止其他 listener，退订幂等', () => {
  const h = harness(); let calls = 0;
  const fail = h.preference.subscribeReducedMotion(() => { throw Error('listener'); });
  const stop = h.preference.subscribeReducedMotion(() => { calls++; });
  h.preference.writeReducedMotion(h.wx, true); assert.equal(calls, 1);
  stop(); stop();
  h.preference.writeReducedMotion(h.wx, false); assert.equal(calls, 1);
  fail();
});
