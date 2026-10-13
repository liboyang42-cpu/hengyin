// 限时打字的终端样式(原型 hello world 开机,2026-09-24):刚打对先「关机」再出判定;重进 / 普通台面直接出判定。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function typein(props) {
  let spec;
  vm.runInNewContext(fs.readFileSync(require.resolve('../../pages/play/components/playkit-typein/index.js'), 'utf8'), {
    Component: value => { spec = value; }, require: () => ({ haptic() {} }),
    setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: id => clearTimeout(id),
  });
  const wins = [];
  const stage = { _stopAll() {}, win(t) { wins.push(t); }, fail() {}, nudge() {} };
  const c = { data: JSON.parse(JSON.stringify(spec.data)), ...spec.methods,
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb(); },
    selectComponent: () => stage, triggerEvent() {} };
  for (const [key, prop] of Object.entries(spec.properties)) c.data[key] = prop.value;
  const update = patch => {
    Object.assign(c.data, patch);
    for (const [fields, observer] of Object.entries(spec.observers)) {
      if (fields.includes(',')) observer.apply(c, fields.split(',').map(key => c.data[key.trim()]));
    }
  };
  Object.assign(c.data, props);
  update({ show: true });
  return { c, update, wins, spec };
}

test('开始回包保留正在输入的这一轮，尝试次数变化才重置', () => {
  const { c, update } = typein({ target: 'hello world' });
  c.onRun();
  c.onInput({ detail: { value: 'hello' } });
  const startedAt = c._t0;
  update({ attempts: 0, passed: false });
  assert.equal(c.data.phase, 'run');
  assert.equal(c.data.typed, 'hello');
  assert.equal(c._t0, startedAt);
  update({ attempts: 1, passed: false });
  assert.equal(c.data.phase, 'intro');
  assert.equal(c.data.typed, '');
});

test('终端样式刚打对:先压成一条线收掉,再出「一字不差」', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { c, update, wins } = typein({ terminalSkin: true, target: 'hello world' });
  update({ attempts: 1, passed: true });
  assert.equal(c.data.off, 'off');
  assert.deepEqual(wins, [], '收屏放完之前不出判定');
  t.mock.timers.tick(500);
  assert.equal(c.data.off, 'gone');
  t.mock.timers.tick(400);
  assert.deepEqual(wins, ['一字不差']);
});

test('重进时已经打对 / 没开终端样式 / 减弱动态:直接出判定,不收屏', () => {
  const again = typein({ terminalSkin: true, passed: true, attempts: 1 });
  assert.deepEqual(again.wins, ['一字不差']);
  assert.equal(again.c.data.off, '');
  const plain = typein({ terminalSkin: false });
  plain.update({ attempts: 1, passed: true });
  assert.deepEqual(plain.wins, ['一字不差']);
  const reduced = typein({ terminalSkin: true, reducedMotion: true });
  reduced.update({ attempts: 1, passed: true });
  assert.deepEqual(reduced.wins, ['一字不差']);
  assert.equal(reduced.c.data.off, '');
});

// 第一键开服务器计时，完整输入才提交；按键不能自行宣布通过。
test('终端键盘首键开表、退格与完整输入提交', () => {
  const { c, spec } = typein({ terminalSkin: true, target: 'hi' });
  const events = [];
  c.triggerEvent = (name, detail) => events.push([name, detail]);
  c.selectComponent = () => ({ begin(options) { assert.equal(options.immediate, true); c.onRun(); }, _stopAll() {} });
  const press = key => c.onKey({ currentTarget: { dataset: { key } } });
  press('h');
  assert.equal(c.data.phase, 'arming');
  assert.equal(c.data.typed, '');
  assert.equal(c._t0, 0);
  spec.observers.startConfirmed.call(c, true);
  assert.equal(c.data.typed, 'h');
  assert.equal(events[0][0], 'start');
  press('back');
  assert.equal(c.data.typed, '');
  press('h'); press('i'); press('i');
  assert.equal(events.length, 2);
  assert.equal(events[1][0], 'submit');
  assert.equal(events[1][1].text, 'hi');
  assert.equal(c.data.passed, false);
});


test('开始被拒后清首键并允许重试；相同轮次回读不再永远停在连接中', () => {
  const { c, spec } = typein({ target: 'hi', terminalSkin: true });
  const events = [];
  c.triggerEvent = name => events.push(name);
  c.onKey({ currentTarget: { dataset: { key: 'h' } } });
  assert.equal(c.data.phase, 'arming');
  spec.observers.startFailureSeq.call(c, 1);
  assert.equal(c.data.phase, 'intro');
  assert.equal(c._firstKey, null);
  c.onStart();
  assert.equal(c.data.phase, 'arming');
  assert.deepEqual(events, ['start', 'start']);
});

test('等待服务器开始事实期间不能输入或重复开表；回读确认后再开本地计时', () => {
  const { c, spec } = typein({ target: 'hi' });
  let starts = 0;
  c.triggerEvent = name => { if (name === 'start') starts += 1; };
  c.selectComponent = () => ({ begin() { c.onRun(); } });
  c.onStart(); c.onStart(); c.onKey({ currentTarget: { dataset: { key: 'h' } } });
  assert.equal(starts, 1);
  assert.equal(c.data.phase, 'arming');
  assert.equal(c._t0, 0);
  spec.observers.startConfirmed.call(c, false);
  assert.equal(c.data.phase, 'arming', 'unknown 不能谎报失败并放行再次开表');
  spec.observers.startConfirmed.call(c, true);
  assert.equal(c.data.phase, 'run');
  assert.ok(c._t0 > 0);
});
