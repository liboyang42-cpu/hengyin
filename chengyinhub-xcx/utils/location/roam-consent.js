'use strict';
/**
 * 漫游定位同意的本机真源 + 撤回编排(F0385)。
 *
 * 为什么需要本机标记:撤回只写服务端 REVOKE 时,微信系统授权仍是「已允许」,
 * 漫游 GO 链/雾层回调里的 _startReal(true) 会直接复用旧授权重启定位 —— 旧授权缓存放行。
 * 所以撤回后在本机落一个 revoked 标记,所有重启/重入入口都先读它;只有用户再次
 * 明示同意(AGREE 留痕成功)才清掉。
 *
 * 撤回顺序:先本机停(标记 + 强制释放所有定位持有者 + 通知页面摘监听),再写服务端记录。
 * 服务端失败只影响留痕,不能让定位继续跑 —— 用户意图优先于网络。
 */
var STORAGE_KEY = 'cy_roam_location_consent_revoked_v1';
var listeners = [];
var STOP_TIMEOUT_MS = 3000;

function storage() {
  return (typeof wx !== 'undefined' && wx) ? wx : null;
}

function isRevoked() {
  var w = storage();
  if (!w || typeof w.getStorageSync !== 'function') return false;
  try { return !!w.getStorageSync(STORAGE_KEY); } catch (e) { return false; }
}

function markRevoked(at) {
  var w = storage();
  if (!w || typeof w.setStorageSync !== 'function') return;
  try { w.setStorageSync(STORAGE_KEY, { at: at || Date.now() }); } catch (e) {}
}

function clearRevoked() {
  var w = storage();
  if (!w) return;
  try {
    if (typeof w.removeStorageSync === 'function') w.removeStorageSync(STORAGE_KEY);
    else if (typeof w.setStorageSync === 'function') w.setStorageSync(STORAGE_KEY, '');
  } catch (e) {}
}

/** 页面订阅「同意被撤回」:在这里摘掉自己的 onLocationChange 并刷新 UI。返回退订函数。 */
function subscribe(fn) {
  if (typeof fn !== 'function') return function () {};
  listeners.push(fn);
  return function () { listeners = listeners.filter(function (item) { return item !== fn; }); };
}

function notify(event) {
  listeners.slice().forEach(function (fn) {
    try { fn(event); } catch (e) { /* 一个页面出错不能挡住其他持有者停止 */ }
  });
}

/**
 * 本机停止所有定位持有者。返回 Promise<{ ok, errMsg }>,ok=false 表示系统 stop 报错,
 * 调用方必须把失败告诉用户(F0385「停止失败有反馈」)。
 */
function stopAllHolders(tracker, stopLocation) {
  var w = storage();
  // 调用方显式传入 stopLocation(页面里的 wx.stopLocationUpdate),让动作台账能在页面闭包里看到这个设备能力。
  var stop = typeof stopLocation === 'function' ? stopLocation
    : (w && typeof w.stopLocationUpdate === 'function' ? function (o) { w.stopLocationUpdate(o); } : null);
  try { if (tracker && typeof tracker.release === 'function') tracker.release('roam', true); } catch (e) {}
  notify({ type: 'revoked' });
  return new Promise(function (resolve) {
    if (!stop) { resolve({ ok: true, errMsg: '' }); return; }
    var done = false;
    var timer = null;
    var finish = function (ok, errMsg) {
      if (done) return;
      done = true;
      if (timer) clearTimeout(timer);
      resolve({ ok: ok, errMsg: errMsg || '' });
    };
    // 系统回调丢失时不能把撤回流程挂死;按「停止结果未知」报给用户。
    timer = setTimeout(function () { finish(false, 'stopLocationUpdate:timeout'); }, STOP_TIMEOUT_MS);
    try {
      stop({
        success: function () { finish(true); },
        fail: function (err) {
          var msg = (err && err.errMsg) || '';
          // 本来就没在采集时系统也会报 fail:那不是「停止失败」。
          if (/not\s*start|未开启|not running/i.test(msg)) finish(true);
          else finish(false, msg || 'stopLocationUpdate:fail');
        },
        complete: function () { finish(true); },
      });
    } catch (e) {
      finish(false, (e && e.message) || 'stopLocationUpdate:throw');
    }
  });
}

/**
 * 撤回编排。deps: { recordConsent(payload)→Promise, tracker, stopLocation?(options) }
 * resolve { stop: {ok,errMsg} };reject 时 err.stop 仍带本机停止结果(留痕失败,本机已停)。
 */
function revoke(deps) {
  deps = deps || {};
  markRevoked();
  var stopping = stopAllHolders(deps.tracker, deps.stopLocation); // 同步部分(标记/释放/通知)已在这里完成
  var record;
  try {
    record = typeof deps.recordConsent === 'function'
      ? deps.recordConsent({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'REVOKE' })
      : null;
  } catch (e) {
    record = Promise.reject(e);
  }
  return Promise.all([stopping, Promise.resolve(record).then(function () { return null; }, function (err) {
    return err instanceof Error ? err : new Error('撤回记录失败');
  })]).then(function (pair) {
    var stop = pair[0];
    if (pair[1]) { pair[1].stop = stop; throw pair[1]; }
    return { stop: stop };
  });
}

/** 当前采集状态的人类可读文案(隐私页展示)。 */
function describeStatus(tracker) {
  var running = false;
  var background = false;
  try {
    running = !!(tracker && tracker.isRunning && tracker.isRunning());
    background = !!(tracker && tracker.isBackground && tracker.isBackground());
  } catch (e) {}
  if (isRevoked()) {
    return running
      ? { key: 'revoked-running', text: '已撤回同意，但定位仍在运行，请重试停止' }
      : { key: 'revoked', text: '已撤回同意 · 当前未采集位置；再次开启漫游定位前会重新征求同意' };
  }
  if (running) {
    return { key: background ? 'running-background' : 'running', text: background ? '漫游定位采集中（含锁屏后台）' : '漫游定位采集中（仅前台）' };
  }
  return { key: 'idle', text: '当前未采集位置；仅在你主动开启漫游定位时使用' };
}

module.exports = {
  STORAGE_KEY: STORAGE_KEY,
  isRevoked: isRevoked,
  markRevoked: markRevoked,
  clearRevoked: clearRevoked,
  subscribe: subscribe,
  stopAllHolders: stopAllHolders,
  revoke: revoke,
  describeStatus: describeStatus,
};
