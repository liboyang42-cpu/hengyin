const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function component(file, timers) {
  let spec;
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8'), {
    Component: value => { spec = value; },
    require: () => ({ haptic() {} }), wx: {}, Date, Math,
    setTimeout: fn => { const id = timers.size + 1; timers.set(id, fn); return id; },
    clearTimeout: id => timers.delete(id), setInterval: () => 99, clearInterval() {},
  });
  const instance = { data: JSON.parse(JSON.stringify(spec.data)), ...spec.methods,
    setData(patch) { Object.assign(this.data, patch); }, triggerEvent() {} };
  for (const [key, prop] of Object.entries(spec.properties || {})) instance.data[key] = prop.value;
  return { instance, spec };
}

test('输入挑战收到完成/次数耗尽结果后取消截止计时且禁止再开始', () => {
  for (const passed of [true, false]) {
    const timers = new Map();
    const stage = component('pages/play/components/play-stage/index.js', timers).instance;
    stage.data.limitSeconds = 10; stage._run();
    const { instance: input, spec } = component('pages/play/components/playkit-typein/index.js', timers);
    input.selectComponent = () => stage;
    input.data.tries = 1; input.data.attempts = 1; input.data.passed = passed;
    const lifecycle = Object.keys(spec.observers).find(key => key.replace(/\s/g, '').startsWith('show,attempts,passed'));
    assert.ok(lifecycle, '输入终态必须被observer消费');
    spec.observers[lifecycle].call(input, true, 1, passed, false, false);
    assert.equal(timers.size, 0, '已结束的输入挑战不能继续触发超时');
    assert.equal(input.data.locked, true);
    assert.equal(stage.data.verdict, passed ? 'win' : 'fail');
    input.onStart();
    assert.equal(stage.data.countingIn, false, '次数耗尽不能重新开始');
  }
});

test('开场点击继续后切后台，取消退出回调，回到前台仍能继续', () => {
  const timers = new Map();
  const { instance: gate, spec } = component('pages/publish/components/chapter-gate/index.js', timers);
  let events = 0; gate.triggerEvent = () => { events += 1; };
  gate.continueStory(); spec.pageLifetimes.hide.call(gate);
  for (const fn of timers.values()) fn();
  assert.equal(events, 0);
  assert.equal(gate.data.leaving, false);
  gate.continueStory();
  for (const fn of [...timers.values()]) fn();
  assert.equal(events, 1);
});
