'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm');
const app = require('../../app.json');
const file = path.resolve(__dirname, '../../utils/registered-navigation.js');
const routes = app.pages.map(route => '/' + route).concat(app.subPackages.flatMap(pkg => pkg.pages.map(route => '/' + pkg.root + '/' + route)));
function load() {
  const calls = [], toasts = [], sandbox = {
    wx: { showToast(options) { toasts.push(options.title); } }, getCurrentPages() { return []; },
    module: { exports: {} },
  };
  for (const action of ['navigateTo', 'redirectTo', 'reLaunch']) sandbox.wx[action] = options => { calls.push({ action, options }); return options; };
  const context = vm.createContext(sandbox);
  sandbox.module.exports = vm.loadUiModule(file, context);
  return { navigate: sandbox.module.exports.navigateRegistered, calls, toasts };
}
test('registered paths retain exact query, fragment, action and callbacks', () => {
  const { navigate, calls, toasts } = load();
  for (const route of routes) {
    const url = route + '?id=7&name=%E5%9F%8E%E7%98%BE#detail';
    for (const action of ['navigateTo', 'redirectTo', 'reLaunch']) {
      const fail = () => {}, complete = () => {}, success = () => {};
      navigate({ url, fail, complete, success }, action);
      const sent = calls.at(-1);
      assert.equal(sent.action, action);
      assert.equal(sent.options.url, url);
      assert.equal(sent.options.fail, fail);
      assert.equal(sent.options.complete, complete);
      assert.equal(sent.options.success, success);
    }
  }
  assert.equal(calls.length, routes.length * 3);
  assert.deepEqual(toasts, []);
});
test('invalid, external and nonstring URLs fail visibly and always release callbacks', () => {
  const { navigate, calls, toasts } = load();
  for (const url of [undefined, null, {}, '', '/missing/index', '//example.com/pages/play/index', 'https://example.com', ' /pages/play/index', '/pages/play/index/extra', '/pages/play/index\n']) {
    const result = [];
    assert.equal(navigate({ url, fail(error) { result.push(error.errMsg); }, complete(error) { result.push(error.errMsg); } }), false);
    assert.equal(result.length, 2);
    assert.ok(result.every(message => message.includes('fail invalid registered route')));
  }
  assert.equal(calls.length, 0);
  assert.equal(toasts.length, 10);
  let complete = false;
  assert.throws(() => navigate({ url: '/missing/index', fail() { throw Error('callback'); }, complete() { complete = true; } }), /callback/);
  assert.equal(complete, true);
  assert.equal(navigate({ url: routes[0] }, 'request'), false);
  assert.equal(calls.length, 0);
});

test('real chat handlers reject raw backend routes and preserve valid deep links', () => {
  const calls = [], toasts = [];
  let definition;
  const chat = path.resolve(__dirname, '../../subpackageB/pages/im/chat/index.js');
  vm.navigationVm.runInNewContext(fs.readFileSync(chat, 'utf8'), {
    Page(value) { definition = value; }, getApp() { return { globalData: {}, tips(value) { toasts.push(value); } }; },
    require(id) { return require(path.resolve(path.dirname(chat), id)); },
    wx: { navigateTo(options) { calls.push(options.url); }, redirectTo(options) { calls.push(options.url); },
      showToast(options) { toasts.push(options.title); } },
  }, { filename: chat });
  const page = Object.assign({}, definition, { data: { msgs: [{ id: 9, senderId: 0, card: { action: '/missing/index' } }] } });
  page.onCardTap({ currentTarget: { dataset: { current: true, msg: 9 } } });
  page.onCardBtn({ currentTarget: { dataset: { action: '/missing/index' } } });
  page._closedReturnPath = '/missing/index'; page.returnToMessageList();
  assert.deepEqual(calls, []);
  assert.equal(toasts.length, 3);
  const target = '/pages/merchant/profile/index?id=7&from=card';
  page.data.msgs[0].card.action = target;
  page.onCardTap({ currentTarget: { dataset: { current: true, msg: 9 } } });
  page._closedReturnPath = '/subpackageB/pages/im/list/index?type=2'; page.returnToMessageList();
  assert.deepEqual(calls, [target, '/subpackageB/pages/im/list/index?type=2']);
});
