const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load(file, app = {}, wx = {}, pages = []) {
  let config;
  const sandbox = {
    Component(value) { config = value; },
    require(name) {
      if (name.includes('toast')) return () => {};
      if (name.includes('exit-motion') || name.includes('morph-entrance')) return () => ({});
      // 2026-10-27 L02:共享弹层套了返回守卫(G-2),沙箱里原样返回组件定义
      if (name.includes('native-back-guard')) return { withNativeBackGuard: (definition) => definition };
      return {};
    },
    getApp: () => app, wx, console, getCurrentPages: () => pages
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../..', file), 'utf8'), sandbox);
  const instance = { ...config.methods, data: JSON.parse(JSON.stringify(config.data)), properties: {} };
  for (const [key, property] of Object.entries(config.properties)) instance.data[key] = property.value;
  instance.setData = function (patch, done) { Object.assign(this.data, patch); if (done) done(); };
  instance.triggerEvent = () => {};
  return { instance, config };
}
// 模拟 wx.navigateTo 成功:同步回调 success/complete(菜单在跳成功后才收起)。
const navigateOk = urls => ({ url, success, complete }) => { urls.push(url); if (success) success(); if (complete) complete(); };
const tap = index => ({ currentTarget: { dataset: { index } } });

test('悬浮发布原地展开；玩家与商家中间导航仍进入模板广场', () => {
  const navigation = [];
  const wx = { switchTab: ({ url }) => navigation.push(url), reLaunch: ({ url }) => navigation.push(url) };
  const { instance: bar } = load('components/tabBar/index.js', { sendRequest() {} }, wx);
  bar.openPublishSheet();
  assert.equal(bar.data.publishSheetShow, true);
  assert.equal(navigation.length, 0);
  bar.onChange(tap(2));
  assert.equal(navigation.pop(), '/pages/template/index');
  bar.onChange(tap(1));
  assert.equal(navigation.pop(), '/pages/roam/index');
  bar.data.mode = 'merchant'; bar.applyMode(); bar.onChange(tap(2));
  assert.equal(navigation.pop(), '/pages/template/index?mode=merchant');
});

test('发布悬浮钮只在 C 端模板页出现；首页与其他页不出', () => {
  const home = load('components/tabBar/index.js', {}, {}, [{ route: 'pages/index/index' }]).instance;
  home.applyMode(); home.updateActive();
  assert.equal(home.data.showPublish, false);
  const tpl = load('components/tabBar/index.js', {}, {}, [{ route: 'pages/template/index' }]).instance;
  tpl.applyMode(); tpl.updateActive();
  assert.equal(tpl.data.showPublish, true);
  const merchant = load('components/tabBar/index.js', {}, {}, [{ route: 'pages/template/index' }]).instance;
  merchant.data.mode = 'merchant'; merchant.applyMode(); merchant.updateActive();
  assert.equal(merchant.data.showPublish, false);
});

test('关闭后到达的旧能力请求不能覆盖新一轮菜单', () => {
  const requests = [];
  const { instance: bar } = load('components/tabBar/index.js', { sendRequest: options => requests.push(options) });
  bar.openPublishSheet(); bar.closePublishSheet(); bar.openPublishSheet();
  requests[0].success({ code: 200, data: { role: 'merchant' } });
  assert.equal(bar.data.publishSnapshot, null);
  requests[1].success({ code: 200, data: { role: 'player' } });
  assert.equal(bar.data.publishSnapshot.role, 'player');
});

for (const mode of [1, 2]) {
  test(`类型 ${mode} 在快速配置和专业手动中均保留 mode`, () => {
    const urls = [];
    const { instance: sheet } = load('components/cy/publish-sheet/index.js', {}, { navigateTo: navigateOk(urls) });
    sheet.data.variant = 'quick'; sheet.data.pub = { permission: {} };
    sheet.chooseTopicMode({ currentTarget: { dataset: { mode } } });
    assert.equal(sheet.data.level, 'topicAbility');
    sheet.chooseSimple(); sheet.choosePro();
    assert.deepEqual(urls, [`/pages/publish/simple/index?mode=${mode}`, `/pages/publish/fabu/index?mode=${mode}`]);
    sheet.backToCards(); assert.equal(sheet.data.level, 'cards');
  });
}

test('模版保留现有介绍入口；信息未就绪时不会进入发布流程', () => {
  const urls = [];
  const { instance: sheet } = load('components/cy/publish-sheet/index.js', {}, { navigateTo: navigateOk(urls) });
  sheet.data.variant = 'quick';
  sheet.goTemplate(); sheet.chooseTopicMode({ currentTarget: { dataset: { mode: 2 } } });
  assert.equal(urls.length, 0); assert.equal(sheet.data.level, 'cards');
  sheet.data.pub = { permission: {} }; sheet.goTemplate();
  assert.deepEqual(urls, ['/pages/publish/template-intro/index']);
});

test('现有主题发布禁用权限仍会阻止跳转', () => {
  const urls = [];
  const { instance: sheet } = load('components/cy/publish-sheet/index.js', {}, { navigateTo: navigateOk(urls) });
  sheet.data.pub = { permission: { canSimplePublish: false, canProPublish: false } };
  sheet.chooseSimple(); sheet.choosePro(); assert.equal(urls.length, 0);
});

test('L09 先跳转后收菜单:成功后才 close,失败时菜单保持打开', () => {
  const order = [];
  let pending = null;
  const { instance: sheet } = load('components/cy/publish-sheet/index.js', {}, {
    navigateTo: (options) => { order.push('navigate:' + options.url); pending = options; }
  });
  sheet.triggerEvent = name => order.push(name);
  sheet.data.pub = { permission: {} };
  sheet.goTemplate();
  assert.deepEqual(order, ['navigate:/pages/publish/template-intro/index'], '新页上屏前不能先收起菜单');
  sheet.goTemplate();
  assert.equal(order.length, 1, '跳转进行中重复点击不应再次 navigateTo');
  pending.success(); pending.complete();
  assert.deepEqual(order, ['navigate:/pages/publish/template-intro/index', 'close']);

  order.length = 0;
  sheet.goTemplate();
  pending.fail(); pending.complete();
  assert.deepEqual(order, ['navigate:/pages/publish/template-intro/index'], '跳转失败时菜单不应关闭');
});
