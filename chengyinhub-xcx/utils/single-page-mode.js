'use strict';
// 朋友圈单页模式(第七轮 C 第 1 项)。
//
// 从朋友圈点开小程序页,微信用「单页模式」打开,场景值 1154:没有登录态(wx.login 不可用),
// 不能跳转到别的页面,不能支付;页面底部由微信自己挂「前往小程序」。我们这边要做到两件事:
//   1. 页面在游客态把公开内容渲染出来(主题/官方活动/俱乐部/商家主页的读接口都已匿名放行);
//   2. 用户点了需要登录、跳页或支付的按钮时,不能「点了没反应」——统一给一句提示,
//      让他用微信底栏的「前往小程序」进完整版。
//
// 判定用 getEnterOptionsSync(本次进入),退化到 getLaunchOptionsSync。每次调用现查,不缓存:
// 用户点「前往小程序」后进入的是完整模式,不能沿用单页时的结论。
const SINGLE_PAGE_SCENE = 1154;
const SINGLE_PAGE_NOTICE = '请前往小程序使用完整服务';
// 单页模式下不可用、点了就会无声失败的接口:登录、跳页、支付。
const GUARDED_APIS = ['login', 'navigateTo', 'redirectTo', 'reLaunch', 'switchTab', 'navigateBack',
  'navigateToMiniProgram', 'requestPayment', 'requestOrderPayment'];
const NOTICE_INTERVAL_MS = 1500;
// 已安装过的 wx 对象。不在 wx 上挂标记属性:某些宿主(含走查 rig 的 wx 代理)对未知键返回函数,标记永远为真。
const installedApis = new WeakSet();

function entryScene(wxApi) {
  const api = wxApi || (typeof wx !== 'undefined' ? wx : null);
  if (!api) return 0;
  const read = (name) => {
    try { return typeof api[name] === 'function' ? api[name]() : null; } catch (e) { return null; }
  };
  const options = read('getEnterOptionsSync') || read('getLaunchOptionsSync') || {};
  return Number(options && options.scene) || 0;
}

function isSinglePageMode(wxApi) {
  return entryScene(wxApi) === SINGLE_PAGE_SCENE;
}

function createNotifier(show, now) {
  const clock = now || Date.now;
  let last = -Infinity;
  return function notify() {
    const t = clock();
    if (t - last < NOTICE_INTERVAL_MS) return false;
    last = t;
    try { show(SINGLE_PAGE_NOTICE); } catch (e) { /* 提示失败不影响页面 */ }
    return true;
  };
}

// 只在单页模式下拦截:每次调用现查场景,完整模式原样透传。返回实际包住的接口名。
function installSinglePageGuard(wxApi, notify) {
  const api = wxApi;
  if (!api || (typeof api !== 'object' && typeof api !== 'function') || installedApis.has(api)) return [];
  installedApis.add(api);
  const wrapped = [];
  GUARDED_APIS.forEach(function (name) {
    const original = api[name];
    if (typeof original !== 'function') return;
    const guarded = function (options) {
      if (!isSinglePageMode(api)) return original.apply(api, arguments);
      notify();
      const res = { errMsg: name + ':fail single page mode' };
      const opts = options || {};
      try { if (typeof opts.fail === 'function') opts.fail(res); } catch (e) {}
      try { if (typeof opts.complete === 'function') opts.complete(res); } catch (e) {}
      return undefined;
    };
    try {
      api[name] = guarded;
      if (api[name] !== guarded) Object.defineProperty(api, name, { value: guarded, configurable: true, writable: true });
      if (api[name] === guarded) wrapped.push(name);
    } catch (e) { /* 不可写就保持原样;登录闸那条路仍有提示 */ }
  });
  return wrapped;
}

module.exports = { SINGLE_PAGE_SCENE, SINGLE_PAGE_NOTICE, GUARDED_APIS, entryScene, isSinglePageMode,
  createNotifier, installSinglePageGuard };
