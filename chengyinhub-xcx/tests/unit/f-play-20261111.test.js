// f-play(2026-11-11):游戏能玩 —— 三条进入路径走查里修掉的缺陷,每条一组断言。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const shake = require('../../pages/play/utils/play-shake.js');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const STRONG = { x: 1.6, y: -1.1, z: .9 };

function fakeWx() {
  const listeners = new Set();
  const calls = { starts: 0, stops: 0 };
  return { listeners, calls,
    startAccelerometer() { calls.starts++; }, stopAccelerometer() { calls.stops++; },
    onAccelerometerChange(f) { listeners.add(f); }, offAccelerometerChange(f) { listeners.delete(f); },
    emit(r) { [...listeners].forEach((f) => f(r)); } };
}

function mountKit(kind, wx, props) {
  let config;
  const events = [];
  const file = path.join(ROOT, 'pages/play/components/playkit-' + kind + '/index.js');
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
  const data = JSON.parse(JSON.stringify(config.data));
  for (const [k, v] of Object.entries(config.properties)) data[k] = v.value;
  Object.assign(data, props || {});
  const inst = { data, ...config.methods, setData(p) { Object.assign(this.data, p); }, triggerEvent(n) { events.push(n); } };
  config.lifetimes.attached.call(inst);
  const change = (patch) => {
    Object.assign(data, patch);
    for (const [names, fn] of Object.entries(config.observers)) {
      const keys = names.split(',').map((x) => x.trim());
      if (keys.some((k) => Object.hasOwn(patch, k))) fn.apply(inst, keys.map((k) => data[k]));
    }
  };
  return { events, change, detach() { config.lifetimes.detached.call(inst); } };
}

test('V3-04 共用传感器:第一个持有者开、最后一个放手才关;读数只给最上面那一个', () => {
  const wx = fakeWx();
  const got = [];
  const a = shake.holdAccelerometer(wx, () => got.push('a'));
  const b = shake.holdAccelerometer(wx, () => got.push('b'));
  assert.equal(wx.calls.starts, 1);
  assert.equal(wx.listeners.size, 1);
  wx.emit(STRONG);
  assert.deepEqual(got, ['b']);
  b(); b();
  assert.equal(wx.calls.stops, 0, '还有人持有时不关传感器');
  wx.emit(STRONG);
  assert.deepEqual(got, ['b', 'a']);
  const stale = [...wx.listeners][0];
  a();
  assert.equal(wx.calls.stops, 1);
  assert.equal(wx.listeners.size, 0);
  stale(STRONG);
  assert.deepEqual(got, ['b', 'a'], '放手后排队的旧回调不再送读数');
  assert.equal(shake.holdAccelerometer({}, () => {}), null, '没有传感器接口就不持有(按钮是退路)');
});

for (const kind of ['coinflip', 'diceroll']) {
  test('V3-04 ' + kind + ':流里一份 + 玩法开始弹窗选「全屏」一份,摇一下只动整屏那份;整屏收回后流里那份还能摇', () => {
    const wx = fakeWx();
    const inline = mountKit(kind, wx, { inline: true });
    inline.change({ show: true });
    const full = mountKit(kind, wx);
    full.change({ show: true });
    wx.emit(STRONG);
    const ev = kind === 'coinflip' ? 'flip' : 'roll';
    assert.deepEqual(full.events, [ev]);
    assert.deepEqual(inline.events, [], '盖在下面的那份不跟着抛 / 掷');
    full.change({ show: false });
    full.detach();
    assert.equal(wx.calls.stops, 0, '整屏关掉不能把流里那份的传感器一起关死');
    wx.emit(STRONG);
    assert.deepEqual(inline.events, [ev]);
    inline.detach();
    assert.equal(wx.calls.stops, 1);
    assert.equal(wx.listeners.size, 0);
  });
}

test('顶部缺图:主题开场标题卡后面压主题封面(原型 gp-start),封面链与本页其他底图同一口径', () => {
  const wxml = read('pages/play/index.wxml');
  const intro = wxml.slice(wxml.indexOf('<view class="st-intro '), wxml.indexOf('<view class="st-intro__go"'));
  assert.match(intro, /<view class="st-intro__bg" wx:if="\{\{topicCover \|\| chapter\.cover\}\}" aria-hidden="true"><image class="st-intro__im" src="\{\{topicCover \|\| chapter\.cover\}\}" mode="aspectFill" \/><\/view>/);
  assert.ok(intro.indexOf('st-intro__bg') < intro.indexOf('st-intro__body'), '封面在标题下面一层');
  const wxss = read('pages/play/index.wxss');
  const block = wxss.slice(wxss.indexOf('.st-intro__bg{'), wxss.indexOf('.st-intro__body{'));
  assert.match(block, /\.st-intro__im\{[^}]*opacity:\.55;[^}]*saturate\(\.5\)/);
  assert.match(block, /\.st-intro__body, \.st-intro__go\{ position:relative; \}/, '标题与按钮压在封面之上');
  assert.doesNotMatch(block, /@keyframes|animation:/);
});
