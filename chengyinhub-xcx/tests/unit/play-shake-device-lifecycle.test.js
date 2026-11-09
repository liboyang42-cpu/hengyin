const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const shake = require('../../pages/play/utils/play-shake.js');

test('wx g readings retain the prototype physical threshold without triggering at rest or walking', () => {
  let count = 0;
  const feed = shake.createShakeDetector(() => count++);
  for (const reading of [{ x: 0, y: 0, z: 1 }, { x: 1, y: 0, z: 0 },
    { x: .58, y: -.58, z: .58 }, { x: .6, y: -.4, z: 1.3 }]) {
    assert.equal(feed(reading), false);
  }
  assert.equal(count, 0);
  assert.equal(feed({ x: 1.4, y: -.8, z: .7 }), true);
  assert.equal(count, 1);
});

test('a deliberate g-scale shake keeps the 900ms cooldown and axis sign symmetry', () => {
  let time = 0;
  let count = 0;
  const feed = shake.createShakeDetector(() => count++, { now: () => time });
  assert.equal(feed({ x: -1.5, y: .9, z: -.5 }), true);
  time = 900;
  assert.equal(feed({ x: 1.5, y: -.9, z: .5 }), false);
  time = 901;
  assert.equal(feed({ x: 1.5, y: -.9, z: .5 }), true);
  assert.equal(count, 2);
});

test('an explicit detector threshold remains in the same raw input units', () => {
  const feed = shake.createShakeDetector(() => {}, { magnitude: 3 });
  assert.equal(feed({ x: 1, y: 1, z: 1 }), false);
  assert.equal(feed({ x: -1.1, y: 1, z: 1 }), true);
  assert.equal(shake.magnitude({ x: -1, y: 2, z: -3 }), 6);
});

function mount(kind, props = {}) {
  let config;
  const listeners = new Set();
  const starts = [];
  const events = [];
  const wx = {
    startAccelerometer(options) { starts.push(options.interval); },
    stopAccelerometer() {},
    onAccelerometerChange(listener) { listeners.add(listener); },
    offAccelerometerChange(listener) { listeners.delete(listener); },
  };
  const file = path.join(__dirname, '../../pages/play/components/playkit-' + kind + '/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component(value) { config = value; }, wx, Date, Math, setTimeout, clearTimeout,
    require(name) {
      if (name.endsWith('/play-shake.js')) return shake;
      if (name.endsWith('/reduced-motion.js')) return {};
      if (name.endsWith('/motion.js')) return { haptic() {} };
      if (name === './faces.js') return { faceMark() { return ''; }, faceCode() { return ''; }, toDataUri(x) { return x; } };
      if (name.endsWith('/d20-renderer.js')) return { createD20Renderer() { throw new Error('unexpected canvas'); } };
      throw new Error('unexpected dependency: ' + name);
    },
  }, { filename: file });
  const data = { ...JSON.parse(JSON.stringify(config.data)) };
  for (const [key, value] of Object.entries(config.properties)) data[key] = value.value;
  Object.assign(data, props);
  const instance = { data, ...config.methods,
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name) { events.push(name); },
  };
  config.lifetimes.attached.call(instance);
  function change(patch) {
    Object.assign(data, patch);
    for (const [names, observer] of Object.entries(config.observers)) {
      const keys = names.split(',').map(x => x.trim());
      if (keys.some(key => Object.hasOwn(patch, key))) observer.apply(instance, keys.map(key => data[key]));
    }
  }
  return { data, starts, events, listeners, change,
    hide() { config.pageLifetimes.hide.call(instance); },
    show() { config.pageLifetimes.show.call(instance); },
    detach() { config.lifetimes.detached.call(instance); },
  };
}

for (const kind of ['coinflip', 'diceroll']) {
  test(kind + ': foreground resumes visible sensing and invalidates callbacks from the previous visibility interval', () => {
    const kit = mount(kind);
    kit.change({ show: true });
    assert.deepEqual(kit.starts, ['game']);
    assert.equal(kit.listeners.size, 1);
    const old = [...kit.listeners][0];
    kit.hide();
    assert.equal(kit.listeners.size, 0);
    old({ x: 1.4, y: -.8, z: .7 });
    assert.equal(kit.events.length, 0);
    kit.show();
    assert.equal(kit.listeners.size, 1);
    old({ x: 1.4, y: -.8, z: .7 });
    assert.equal(kit.events.length, 0, 'a queued callback must not become live again after foregrounding');
    [...kit.listeners][0]({ x: 1.4, y: -.8, z: .7 });
    assert.deepEqual(kit.events, [kind === 'coinflip' ? 'flip' : 'roll']);
    kit.detach();
    assert.equal(kit.listeners.size, 0);
  });

  test(kind + ': property notifications while backgrounded cannot restart sensing', () => {
    const kit = mount(kind);
    kit.change({ show: true });
    kit.hide();
    const before = kit.starts.length;
    kit.change(kind === 'coinflip' ? { headsLabel: 'A' } : { diceCount: 2 });
    assert.equal(kit.starts.length, before);
    assert.equal(kit.listeners.size, 0);
    kit.show();
    assert.equal(kit.listeners.size, 1);
    kit.detach();
  });

  test(kind + ': foregrounding a closed kit keeps the sensor stopped', () => {
    const kit = mount(kind);
    kit.change({ show: true });
    kit.hide();
    kit.change({ show: false });
    const before = kit.starts.length;
    kit.show();
    assert.equal(kit.starts.length, before);
    assert.equal(kit.listeners.size, 0);
    kit.detach();
  });
}

test('dice inline and D20 modes never acquire the shake sensor, including mode changes', () => {
  const kit = mount('diceroll', { inline: true });
  kit.change({ show: true });
  kit.hide(); kit.show();
  assert.equal(kit.starts.length, 0);
  kit.change({ inline: false, mode: 'd20' });
  assert.equal(kit.listeners.size, 0);
  kit.change({ mode: 'd6' });
  assert.equal(kit.listeners.size, 1);
  const old = [...kit.listeners][0];
  kit.change({ mode: 'd20' });
  assert.equal(kit.listeners.size, 0);
  old({ x: 1.4, y: -.8, z: .7 });
  assert.equal(kit.events.length, 0);
  kit.hide(); kit.show();
  assert.equal(kit.listeners.size, 0);
  kit.detach();
});
