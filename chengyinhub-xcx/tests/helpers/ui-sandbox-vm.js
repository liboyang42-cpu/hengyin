'use strict';
// tests/helpers/ui-sandbox-vm.js —— node:vm 的同款出口,只多做一件事:
// 页面在沙箱里 require('<rel>/utils/toast.js' | '<rel>/utils/loading.js') 时,把真模块装进去。
//
// 这两个模块运行时读全局 wx / getCurrentPages。页面跑在 vm 上下文里、wx 是该上下文的 mock,
// 若用 Node 自己的 require 装模块,它读到的是 Node 全局(没有 wx → ReferenceError),
// 所以模块源码必须在页面所在的同一个上下文里求值,回落到 wx.showToast / wx.showLoading
// 时才打得到测试的桩。沙箱没有 require 的,补一个只认这两个模块的(其它一律 unexpected require)。
//
// 用法:把 `const vm = require('node:vm')` 换成 `const vm = require('../helpers/ui-sandbox-vm.js')`。
const fs = require('node:fs');
const path = require('node:path');
const nodeVm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
// PR10 F-W1-12:session/login-reload 是纯函数(只读 app 入参,不碰 wx),页面靠它区分「未登录」与加载失败。
// PR10 末轮:display-text 是纯函数(只解码用户文字里的 &lt; 等实体,不碰 wx),页面在映射时用它。
// 2026-10-23:page-motion 只调 setData / setTimeout(页面自己的),依赖的 motion-preference 在 Node 里读不到 wx 时按「不减少动效」处理。
// 2026-10-29:ticket-expiry 是纯函数(只按日期算「N 天内过期」,不碰 wx),票夹映射票卡时用它。
const UI_MODULE = /utils\/(toast|loading|modal|datetime|coop-publisher-access|coop-invite-return|roleGuard|session\/login-reload|display-text|page-motion|ticket-expiry)\.js$/;
const INSTALLED = Symbol('ui-sandbox-vm');

function isUiModule(request) {
  return UI_MODULE.test(String(request));
}

// 在 context 里求值真模块;模块自己的 require(safe-user-message 等纯函数)走 Node 真 require。
function loadUiModule(request, context) {
  const name = UI_MODULE.exec(String(request))[1];
  const directory = /^(coop-publisher-access|coop-invite-return)$/.test(name) ? 'subpackageMember/utils' : 'utils';
  const file = path.join(ROOT, directory, name + '.js');
  const source = '(function (module, exports, require) {' + fs.readFileSync(file, 'utf8') + '\n})';
  const wrapper = nodeVm.runInContext(source, context, { filename: file });
  const mod = { exports: {} };
  wrapper(mod, mod.exports, (id) => require(path.resolve(path.dirname(file), id)));
  return mod.exports;
}

function install(sandbox) {
  if (!sandbox || typeof sandbox !== 'object' || sandbox[INSTALLED]) return sandbox;
  sandbox[INSTALLED] = true;
  const inner = sandbox.require;
  const shim = function (request) {
    if (isUiModule(request)) return loadUiModule(request, sandbox);
    if (typeof inner !== 'function') throw new Error('unexpected require: ' + request);
    return inner.apply(this, arguments);
  };
  if (typeof inner === 'function') Object.assign(shim, inner);
  sandbox.require = shim;
  return sandbox;
}

module.exports = Object.assign({}, nodeVm, {
  isUiModule,
  loadUiModule,
  createContext(sandbox, options) {
    return nodeVm.createContext(install(sandbox), options);
  },
  runInNewContext(code, sandbox, options) {
    return nodeVm.runInNewContext(code, install(sandbox), options);
  },
  runInContext(code, context, options) {
    return nodeVm.runInContext(code, install(context), options);
  },
});
