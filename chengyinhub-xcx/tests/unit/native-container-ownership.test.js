'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8');
const hosts = [
  ...['sheet', 'scene-sheet', 'modal', 'date-sheet', 'publish-sheet', 'back-guard'].map(name => 'components/cy/' + name + '/index.wxml'),
  'pages/merchant/decor/perks/index.wxml', 'pages/merchant/decor/index.wxml',
  'subpackageMember/couponInfo/couponInfo.wxml',
  'subpackageMember/components/cy/scene-roam-poi-detail/index.wxml',
  'pages/club/detail/index.wxml', 'pages/merchant/components/merchant-claim-sheet/index.wxml',
  'pages/club/edit/index.wxml', 'pages/publish/fabu/index.wxml',
  'pages/publish/temp/index.wxml', 'pages/play/index.wxml',
];
const tags = file => read(file).match(/<page-container\b[\s\S]*?\/>/g) || [];
const attribute = (tag, name) => (tag.match(new RegExp('(?:^|\\s)' + name + '="([^"]*)"')) || [])[1];
const owners = file => file.startsWith('components/cy/') ? tags(file)
  : (read(file).match(/<cy-back-guard\b[\s\S]*?\/>/g) || []).filter(tag => attribute(tag, 'wx:if'));
function evaluate(binding, data) {
  return Function('data', 'with(data){return !!(' + binding.slice(2, -2) + ')}')(
    new Proxy(data, { has: () => true, get: (target, key) => key === Symbol.unscopables ? undefined : target[key] }));
}
function mounted(tag, data, parentRendered = true) {
  const condition = attribute(tag, 'wx:if');
  return parentRendered && (!condition || evaluate(condition, data)) ? 1 : 0;
}
function closed() {
  const state = { show: false, _backGuard: false, mode: 2, backGuardOff: false,
    imagePicking: false, guardLeaving: false, sceneBackGuardOff: false, sceneCurrent: null };
  for (const tag of hosts.flatMap(owners)) {
    for (const key of (attribute(tag, 'show').match(/[A-Za-z_]\w*/g) || [])) {
      if (!(key in state) && !['true', 'false', 'club', 'edit'].includes(key)) state[key] = false;
    }
    for (const [, name, field] of attribute(tag, 'show').matchAll(/([A-Za-z_]\w*)\.([A-Za-z_]\w*)/g)) {
      state[name] = Object.assign({}, state[name], { [field]: false });
    }
  }
  state.sceneCurrent = null;
  return state;
}

test('所有18处native容器都以owner谓词实际挂载，关闭不是仅show=false', () => {
  assert.equal(hosts.length, 16);
  assert.equal(hosts.flatMap(owners).length, 18, '原18处都接入既有stack，没有遗漏raw owner');
  assert.equal(hosts.flatMap(tags).length, 6, '只有共享组件保留真实native tag');
  for (const file of hosts) for (const tag of owners(file)) {
    assert.equal(mounted(tag, closed()), 0, file + ': closed native component must not exist');
    if (tag.startsWith('<page-container')) {
      assert.ok(attribute(tag, 'bind:afterleave'), file + ': preserve native leave callback');
      assert.equal(attribute(tag, 'duration'), '{{0}}');
      assert.equal(attribute(tag, 'overlay'), '{{false}}');
    } else {
      assert.ok(attribute(tag, 'bind:back'), file + ': preserve original owner return callback');
      assert.equal(JSON.parse(read(file.replace('.wxml', '.json'))).usingComponents['cy-back-guard'], '/components/cy/back-guard/index');
    }
  }
});

test('单个分类sheet的backGuard=false确实不挂native组件，父Page仅一个实例', () => {
  const parent = owners('pages/publish/temp/index.wxml')[0];
  const child = tags('components/cy/sheet/index.wxml')[0];
  const page = Object.assign(closed(), { categorySheetVisible: true });
  assert.ok(read('pages/publish/temp/index.wxml').includes('back-guard="{{false}}"'));
  assert.ok(read('components/cy/category-sheet/index.wxml').includes('back-guard="{{backGuard}}"'));
  const g = freshGuard(), parentOwner = guardInstance(g);
  parentOwner.show(evaluate(attribute(parent, 'show'), page));
  const sheet = { show: true, _render: true, backGuard: false, _backGuard: false };
  assert.equal(mounted(tags('components/cy/back-guard/index.wxml')[0], parentOwner.data) + mounted(child, sheet, sheet._render), 1);
  parentOwner.detach(); g._reset();
});

function freshGuard() {
  global.getCurrentPages = () => [{}];
  const file = path.join(ROOT, 'utils/native-back-guard.js');
  delete require.cache[file];
  const guard = require(file); guard._reset(); return guard;
}
function guardInstance(guard, onBack, deferred = false) {
  const def = guard.withNativeBackGuard({ properties: { show: { value: false } } }, onBack);
  const vm = Object.assign({}, def.methods, { data: Object.assign({}, def.data, { show: false, backGuard: true }), rendered: false, queue: [] });
  vm.setData = (patch, done) => {
    Object.assign(vm.data, patch);
    const render = () => { vm.rendered = !vm._nbgDead && !!patch._backGuard; if (done) done(); };
    if (deferred) vm.queue.push(render); else render();
  };
  vm.flush = () => { while (vm.queue.length) vm.queue.shift()(); };
  vm.flushOne = () => vm.queue.shift()?.();
  vm.show = value => { vm.data.show = value; def.observers['show, backGuard'].call(vm, value, vm.data.backGuard); };
  vm.hidePage = () => def.pageLifetimes.hide.call(vm);
  vm.showPage = () => def.pageLifetimes.show.call(vm);
  vm.detach = () => def.lifetimes.detached.call(vm);
  def.lifetimes.attached.call(vm);
  return vm;
}

test('叠层/关闭退场只保留一个实际native挂载，不按show=true数量冒充', () => {
  const g = freshGuard(), a = guardInstance(g), b = guardInstance(g);
  a.show(true); b.show(true);
  const tag = tags('components/cy/sheet/index.wxml')[0];
  assert.equal(mounted(tag, a.data) + mounted(tag, b.data), 1);
  b.show(false);
  assert.equal(mounted(tag, a.data) + mounted(tag, b.data), 1, '退场中的上层_render仍可为true，但native须让给下层');
  a.show(false);
  assert.equal(mounted(tag, a.data) + mounted(tag, b.data), 0);
  a.detach(); b.detach(); g._reset();
});

test('旧afterleave延迟期间新B成为top，不能关闭非top旧A', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let closedA = 0, closedB = 0;
  const a = guardInstance(g, () => closedA++), b = guardInstance(g, () => closedB++);
  a.show(true); a._onBackGuardLeave(); b.show(true);
  t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1);
  assert.equal(closedA, 0); assert.equal(closedB, 0);
  assert.equal(b.data._backGuard, true);
  a.detach(); b.detach(); g._reset();
});

test('native实例交接须等旧owner卸载渲染回调，不能先挂新owner', () => {
  const g = freshGuard(), a = guardInstance(g, null, true), b = guardInstance(g);
  a.show(true); a.flush(); assert.equal(a.rendered, true);
  b.show(true);
  assert.equal(a.rendered, true);
  assert.equal(b.rendered, false, '旧native实例还在时不可创建新实例');
  a.flush();
  assert.equal(a.rendered, false); assert.equal(b.rendered, true);
  a.detach(); b.detach(); g._reset();
});

test('卸载ack前页栈变深不消费旧Page返回，也不重新挂载旧owner', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let backs = 0;
  const a = guardInstance(g, () => backs++, true);
  a.show(true); a.flush(); a._onBackGuardLeave();
  t.mock.timers.tick(g.LEAVE_SETTLE_MS);
  global.getCurrentPages = () => [{}, {}];
  a.flush(); t.mock.timers.tick(0);
  assert.equal(backs, 0, 'false渲染ack也是异步边界，必须复查真实页栈');
  assert.equal(a.rendered, false, 'hide回调尚未到达也不能恢复旧Page的native实例');
  a.hidePage(); global.getCurrentPages = () => [{}]; a.showPage(); a.flush();
  assert.equal(a.rendered, true, '正常回页仍恢复原编辑owner');
  a._onBackGuardLeave(); t.mock.timers.tick(g.LEAVE_SETTLE_MS); a.flush();
  assert.equal(backs, 1, '回页后的合法返回仍然有效');
  a.detach(); g._reset();
});

test('page hide主动撤下实例，show沿用原owner恢复', () => {
  const g = freshGuard(), a = guardInstance(g);
  a.show(true); a.hidePage();
  assert.equal(a.data._backGuard, false);
  assert.equal(a.rendered, false);
  a.showPage(); assert.equal(a.data._backGuard, true);
  a.detach(); g._reset();
});

test('关闭重开相同实例也使原60ms返回失效，不关闭重新打开的面板', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let closed = 0;
  const a = guardInstance(g, () => closed++);
  a.show(true); a._onBackGuardLeave(); a.show(false); a.show(true);
  t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1);
  assert.equal(closed, 0); assert.equal(a.data._backGuard, true);
  a.detach(); g._reset();
});

test('A首次挂载ack未到时B打开再关闭，迟ack只恢复当前A', () => {
  const g = freshGuard(), a = guardInstance(g, null, true), b = guardInstance(g);
  a.show(true); b.show(true); b.show(false);
  assert.equal(b.rendered, false);
  a.flush();
  assert.equal(a.rendered, true); assert.equal(a.data._backGuard, true);
  assert.equal(b.rendered, false);
  a.detach(); b.detach(); g._reset();
});

test('A旧卸载ack晚于B关闭和C打开，只挂当前C、不复活B', () => {
  const g = freshGuard(), a = guardInstance(g, null, true), b = guardInstance(g), c = guardInstance(g);
  a.show(true); a.flush(); b.show(true); b.show(false); c.show(true);
  assert.equal(a.rendered, true); assert.equal(b.rendered, false); assert.equal(c.rendered, false);
  a.flush();
  assert.equal(a.rendered, false); assert.equal(b.rendered, false); assert.equal(c.rendered, true);
  a.detach(); b.detach(); c.detach(); g._reset();
});

test('已有卸载在途的release也等ack，800ms内不重挂，之后只挂当前top', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(), a = guardInstance(g, null, true), b = guardInstance(g);
  a.show(true); a.flush(); a.show(false);
  let navigated = 0;
  g.releaseNativeBackGuards(() => { assert.equal(a.rendered, false); navigated++; });
  b.show(true); assert.equal(navigated, 0); assert.equal(b.rendered, false);
  a.flush(); assert.equal(navigated, 1);
  t.mock.timers.tick(799); assert.equal(b.rendered, false);
  t.mock.timers.tick(1); assert.equal(b.rendered, true); assert.equal(a.rendered, false);
  a.detach(); b.detach(); g._reset();
});

test('当前top手势只消费一次，业务保留脏面板时沿原合同重挂', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let requested = 0;
  const a = guardInstance(g, () => requested++);
  a.show(true); a._onBackGuardLeave(); a._onBackGuardLeave();
  t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1); t.mock.timers.tick(0);
  assert.equal(requested, 1); assert.equal(a.data._backGuard, true);
  a.detach(); g._reset();
});

test('hide/show跨过原afterleave等待，不消费旧手势或丢掉恢复的owner', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let requested = 0;
  const a = guardInstance(g, () => requested++);
  a.show(true); a._onBackGuardLeave(); a.hidePage(); a.showPage();
  t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1);
  assert.equal(requested, 0); assert.equal(a.data._backGuard, true);
  a.detach(); g._reset();
});

test('卸载后迟到setData ack不复活旧owner，不阻挡新面板', () => {
  const g = freshGuard(), a = guardInstance(g, null, true), b = guardInstance(g);
  a.show(true); b.show(true); a.detach();
  assert.equal(b.rendered, true);
  a.flush(); assert.equal(a.rendered, false); assert.equal(b.rendered, true);
  b.detach(); g._reset();
});

test('真实cy-sheet脏数据请求确认；真实cy-modal返回只取消确认并恢复原sheet', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard();
  global.Behavior = value => value;
  global.getApp = () => ({ globalData: {} });
  global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ windowWidth: 375 }) };
  function mountComponent(file, props) {
    let definition; global.Component = value => { definition = value; };
    const absolute = path.join(ROOT, file); delete require.cache[absolute]; require(absolute);
    const vm = Object.assign({}, definition.methods, { data: Object.assign({}, definition.data), events: [] });
    for (const [key, property] of Object.entries(definition.properties)) vm.data[key] = property.value;
    Object.assign(vm.data, props);
    vm.setData = (patch, done) => { Object.assign(vm.data, patch); if (done) done(); };
    vm.triggerEvent = (name, detail) => vm.events.push({ name, detail });
    definition.observers['show, backGuard'].call(vm, vm.data.show, vm.data.backGuard);
    vm.setShow = show => { vm.data.show = show; definition.observers['show, backGuard'].call(vm, show, vm.data.backGuard); };
    vm.detach = () => definition.lifetimes.detached.call(vm);
    return vm;
  }
  const sheet = mountComponent('components/cy/sheet/index.js', { show: true, dirty: true });
  sheet._onBackGuardLeave(); t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1); t.mock.timers.tick(0);
  assert.deepEqual(sheet.events, [{ name: 'requestclose', detail: { reason: 'back' } }]);
  assert.equal(sheet.data.show, true, '脏数据不自动放弃');
  const modal = mountComponent('components/cy/modal/index.js', { show: true, showCancel: true });
  assert.equal(sheet.data._backGuard, false); assert.equal(modal.data._backGuard, true);
  modal._onBackGuardLeave(); t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1);
  assert.deepEqual(modal.events, [{ name: 'cancel', detail: undefined }]);
  modal.setShow(false); t.mock.timers.tick(0);
  assert.equal(sheet.data._backGuard, true);
  sheet.detach(); modal.detach(); g._reset();
});

test('真实fabu删除节点确认进入同一stack：仅modal持native，取消后恢复原故事编辑owner', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let definition, page, modalDefinition;
  global.Behavior = value => value;
  global.getApp = () => ({ globalData: {}, getUserID: () => 42, getAuthorization: () => '', sendRequest: () => assert.fail('不发业务请求') });
  global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ windowWidth: 375 }), showToast() {} };
  global.Page = value => { definition = value; };
  const pageFile = path.join(ROOT, 'pages/publish/fabu/index.js'); delete require.cache[pageFile]; require(pageFile);
  const patch = function (values, done) { Object.assign(this.data, values); if (done) done(); };
  page = Object.assign({}, definition, { data: structuredClone(definition.data), setData: patch });
  page.data.storyEditor.show = true;
  global.getCurrentPages = () => [page];
  function component(file, properties) {
    let def; global.Component = value => { def = value; };
    const absolute = path.join(ROOT, file); delete require.cache[absolute]; require(absolute);
    const vm = Object.assign({}, def.methods, { data: structuredClone(def.data || {}), setData: patch });
    for (const [key, property] of Object.entries(def.properties || {})) vm.data[key] = property.value;
    Object.assign(vm.data, properties);
    return { vm, def };
  }
  const raw = component('components/cy/back-guard/index.js', { show: true });
  let rawBackCalls = 0;
  raw.vm.triggerEvent = name => { assert.equal(name, 'back'); rawBackCalls++; page.onNativeBackGuard(); };
  raw.def.observers['show, backGuard'].call(raw.vm, true, true);
  const modal = component('components/cy/modal/index.js', { show: false }); modalDefinition = modal.def;
  const host = component('components/cy/modal-host/index.js', {}).vm;
  host.setData = function (values, done) {
    patch.call(this, values, done);
    if (Object.hasOwn(values, 'show')) {
      modal.vm.data.show = values.show;
      modalDefinition.observers['show, backGuard'].call(modal.vm, values.show, true);
    }
  };
  modal.vm.triggerEvent = name => { assert.equal(name, 'cancel'); host.onCancel(); };
  page.selectComponent = selector => selector === '#cy-modal-host' ? host : null;
  const owner = owners('pages/publish/fabu/index.wxml')[0];
  assert.equal(evaluate(attribute(owner, 'show'), page.data), true);
  assert.equal(tags('pages/publish/fabu/index.wxml').length, 0, 'Page不再另占raw native实例');
  page.removeStoryNode({ currentTarget: { dataset: { blockkey: 'existing-node-block' } } });
  assert.equal(host.data.show, true);
  const nativeTag = tags('components/cy/back-guard/index.wxml')[0];
  const modalTag = tags('components/cy/modal/index.wxml')[0];
  assert.equal(mounted(nativeTag, raw.vm.data) + mounted(modalTag, modal.vm.data), 1);
  assert.equal(raw.vm.data._backGuard, false); assert.equal(modal.vm.data._backGuard, true);
  modal.vm._onBackGuardLeave(); t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1); t.mock.timers.tick(0);
  assert.equal(host.data.show, false); assert.equal(rawBackCalls, 0);
  assert.equal(page.data.storyEditor.show, true, '取消确认不能关闭底层故事编辑器');
  assert.equal(mounted(nativeTag, raw.vm.data) + mounted(modalTag, modal.vm.data), 1);
  assert.equal(raw.vm.data._backGuard, true);
  raw.def.lifetimes.detached.call(raw.vm); modal.def.lifetimes.detached.call(modal.vm); g._reset();
});

for (const editor of ['fabu', 'temp']) test(editor + '真实Page消费子层返回后，新modal不能被旧父owner重挂抢顶', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const g = freshGuard(); let definition, page, raw;
  global.Behavior = value => value;
  global.getApp = () => ({ globalData: {}, getUserID: () => 42, getAuthorization: () => '', sendRequest: () => assert.fail('不发业务请求') });
  global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ windowWidth: 375 }), showToast() {} };
  global.Page = value => { definition = value; };
  const file = path.join(ROOT, 'pages/publish/' + editor + '/index.js'); delete require.cache[file]; require(file);
  const patch = function (values, done) {
    for (const [key, value] of Object.entries(values)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data;
      for (const part of parts.slice(0, -1)) target = target[part];
      target[parts.at(-1)] = value;
    }
    if (done) done();
  };
  function component(relative, props) {
    let def; global.Component = value => { def = value; };
    const absolute = path.join(ROOT, relative); delete require.cache[absolute]; require(absolute);
    const vm = { ...def.methods, data: structuredClone(def.data || {}), setData: patch };
    for (const [key, property] of Object.entries(def.properties || {})) vm.data[key] = property.value;
    Object.assign(vm.data, props); return { vm, def };
  }
  const owner = owners('pages/publish/' + editor + '/index.wxml')[0];
  function wire() {
    const on = evaluate(attribute(owner, 'wx:if'), page.data);
    if (on && !raw) {
      raw = component('components/cy/back-guard/index.js', { show: true });
      raw.vm.triggerEvent = name => { assert.equal(name, 'back'); page.onNativeBackGuard(); };
      raw.def.observers['show, backGuard'].call(raw.vm, true, true);
    } else if (!on && raw) { raw.def.lifetimes.detached.call(raw.vm); raw = null; }
  }
  page = { ...definition, data: structuredClone(definition.data), setData(values, done) { patch.call(this, values); wire(); if (done) done(); } };
  global.getCurrentPages = () => [page];
  if (editor === 'fabu') { page.data.storyEditor.show = true; page.data.storyVarSheet.show = true; }
  else { page.data.previewVisible = true; page.data.pickerSheet.open = true; }
  wire();
  const modal = component('components/cy/modal/index.js', { show: false });
  const host = component('components/cy/modal-host/index.js', {}).vm;
  host.setData = function (values, done) {
    patch.call(this, values, done);
    if (Object.hasOwn(values, 'show')) {
      modal.vm.data.show = values.show;
      modal.def.observers['show, backGuard'].call(modal.vm, values.show, true);
    }
  };
  page.selectComponent = id => id === '#cy-modal-host' ? host : null;
  raw.vm._onBackGuardLeave(); t.mock.timers.tick(g.LEAVE_SETTLE_MS + 1);
  assert.equal(editor === 'fabu' ? page.data.storyVarSheet.show : page.data.pickerSheet.open, false);
  assert.equal(editor === 'fabu' ? page.data.storyEditor.show : page.data.previewVisible, true, '保留原关层优先级');
  if (editor === 'fabu') page.removeStoryNode({ currentTarget: { dataset: { blockkey: 'existing-node-block' } } });
  else {
    page._toolSelected = {}; page._toolSummaries = {};
    page.previewToolFromSheet({ currentTarget: { dataset: { key: 'reward' } } });
  }
  assert.equal(host.data.show, true, '真实Page调用经modal.show进入实际modal-host');
  assert.equal(modal.vm.data._backGuard, true);
  t.mock.timers.tick(50);
  assert.equal(modal.vm.data._backGuard, true, '可见modal必须保持top，旧Page不能抢回');
  assert.equal(g._stack.at(-1), modal.vm);
  assert.equal(raw.vm.data._backGuard, false);
  host.onCancel(); t.mock.timers.tick(0);
  assert.equal(host.data.show, false); assert.equal(raw.vm.data._backGuard, true);
  assert.equal(editor === 'fabu' ? page.data.storyEditor.show : page.data.previewVisible, true, '取消后保留原编辑态');
  raw.def.lifetimes.detached.call(raw.vm); modal.def.lifetimes.detached.call(modal.vm); g._reset();
});

test('temp程序退页使用native卸载ack，不再等待已被共享helper拒绝的程序afterleave', () => {
  for (const unload of [false, true]) {
    const g = freshGuard(); let definition, navigated = 0;
    global.Behavior = value => value;
    global.getApp = () => ({ globalData: {} });
    global.wx = { getStorageSync() {}, navigateBack() { navigated++; assert.equal(owner.rendered, false); } };
    global.Page = value => { definition = value; };
    const file = path.join(ROOT, 'pages/publish/temp/index.js'); delete require.cache[file]; require(file);
    const page = { ...definition, data: structuredClone(definition.data), setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } };
    page.data.previewVisible = true; global.getCurrentPages = () => [page];
    const owner = guardInstance(g, () => page.onNativeBackGuard(), true);
    owner.show(true); owner.flush(); page.exitPage(); page.exitPage();
    assert.equal(navigated, 0, '未卸载ack前不退页，重复请求不另起导航');
    if (unload) page.onUnload();
    owner.flush();
    assert.equal(navigated, unload ? 0 : 1, '当前Page只在卸载ack后退一次，卸载后的迟ack不导航');
    if (!unload) assert.equal(page.data.previewVisible, false);
    owner.detach(); g._reset();
  }
});
