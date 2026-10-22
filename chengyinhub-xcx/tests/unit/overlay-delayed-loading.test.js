const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function load(rel, props) {
  let def;
  global.Component = d => { def = d; };
  global.Behavior = d => d;
  const full = path.join(ROOT, rel);
  delete require.cache[require.resolve(full)];
  require(full);
  const vm = Object.assign({}, def.methods);
  vm.data = Object.assign({}, JSON.parse(JSON.stringify(def.data)), props);
  vm.setData = function (patch) { Object.assign(this.data, patch); };
  // observers/lifetimes 由测试手动触发
  vm.def = def;
  return vm;
}

test('cy-loading-mask：show 立刻挂上挡触摸,400ms 后才显出', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const vm = load('components/cy/loading-mask/index.js', { delay: 400 });
  vm.show({ title: '支付中' });
  assert.equal(vm.data.visible, true);
  assert.equal(vm.data.entered, false);
  t.mock.timers.tick(399);
  assert.equal(vm.data.entered, false);
  t.mock.timers.tick(1);
  assert.equal(vm.data.entered, true);
});

test('cy-loading-mask：延时前 hide 什么都不显示,直接撤掉', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const vm = load('components/cy/loading-mask/index.js', { delay: 400 });
  vm.show();
  t.mock.timers.tick(100);
  vm.hide();
  assert.equal(vm.data.visible, false);
  assert.equal(vm.data.entered, false);
  t.mock.timers.tick(1000);
  assert.equal(vm.data.entered, false);
});

test('cy-loading-mask：计数式 show/hide 保持,嵌套不提前关', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const vm = load('components/cy/loading-mask/index.js', { delay: 400 });
  vm.show();
  vm.show();
  t.mock.timers.tick(400);
  vm.hide();
  assert.equal(vm.data.visible, true);
  assert.equal(vm.data.entered, true);
  vm.hide();
  t.mock.timers.tick(140);
  assert.equal(vm.data.visible, false);
});

test('cy-loading-mask：delay=0 立即显出;退场期间再 show 会恢复', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const vm = load('components/cy/loading-mask/index.js', { delay: 0 });
  vm.show();
  assert.equal(vm.data.entered, true);
  vm.hide();
  assert.equal(vm.data.entered, false);
  vm.show();
  assert.equal(vm.data.entered, true);
  t.mock.timers.tick(200);
  assert.equal(vm.data.visible, true);
});

test('cy-skeleton：挂载后 400ms 才渲染,提前卸载不留定时器', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const vm = load('components/cy/skeleton/index.js', { delay: 400, count: 4 });
  vm.def.lifetimes.attached.call(vm);
  assert.equal(vm.data.ready, false);
  t.mock.timers.tick(399);
  assert.equal(vm.data.ready, false);
  t.mock.timers.tick(1);
  assert.equal(vm.data.ready, true);

  const early = load('components/cy/skeleton/index.js', { delay: 400, count: 4 });
  early.def.lifetimes.attached.call(early);
  early.def.lifetimes.detached.call(early);
  t.mock.timers.tick(1000);
  assert.equal(early.data.ready, false);
});

test('cy-skeleton：delay=0 立即渲染;wxml 以 ready 门控', () => {
  const vm = load('components/cy/skeleton/index.js', { delay: 0, count: 2 });
  vm.def.lifetimes.attached.call(vm);
  assert.equal(vm.data.ready, true);
  assert.match(read('components/cy/skeleton/index.wxml'), /^<block wx:if="\{\{ready\}\}">/);
});

test('弹层契约：遮罩/面板拦截 touchmove,sheet 正文是 scroll-view', () => {
  const sheet = read('components/cy/sheet/index.wxml');
  assert.match(sheet, /sh__mask"[^>]*catchtouchmove="noop"/);
  assert.match(sheet, /<scroll-view class="sh__body" scroll-y/);
  assert.match(read('components/cy/modal/index.wxml'), /class="mo [^"]*"[^>]*catchtouchmove="noop"/s);
  assert.match(read('components/cy/dropdown/index.wxml'), /class="cdd__layer[^"]*"\s+catchtouchmove="noop"/);
  assert.match(read('components/cy/modal/index.js'), /noop\(\)/);
});
