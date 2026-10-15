'use strict';
/**
 * 后台定位的唯一开关。play / roam 都走这里,不各写一套。
 *
 * 为什么要收敛到一处:后台定位的风险不在「开」,在「忘了关」。
 * 页面各自 start/stop 时,只要有一条退出路径漏掉 stop,就变成对用户的长期位置采集 ——
 * 那是真违规,而且没有任何报错会提醒你。所以「谁在用」由本模块记账,
 * 引用计数归零才真正 stop。
 *
 * ⚠️ 与前台版的本质差别:前台版可以在 onHide 里停(页面看不见就不需要位置);
 *    后台版**不能**在 onHide 停 —— 玩家锁屏塞兜里走路正是它存在的理由。
 *    停的时机只有一个:这次行程结束(通关/退出/离开页面)。
 */
var owners = Object.create(null);   // sessionKey -> acquisition identity
var started = false;
var starting = false;
var stopping = false;
var usingBackground = false;
var generation = 0;
var waiters = [];
// A batch stays cancellable while consumer callbacks run, even after failure
// has cleared owners. Reusing a key must never revive its previous callback.
var notifying = [];

function notify(batch, callback, value) {
  notifying.push(batch);
  try {
    batch.forEach(function (item) {
      if (item.cancelled) return;
      if (callback === 'success' && owners[item.key] !== item) return;
      if (item.opts[callback]) item.opts[callback](value);
    });
  } finally {
    notifying.splice(notifying.indexOf(batch), 1);
  }
}

function count() { return Object.keys(owners).length; }
function stopLocation() {
  if (stopping) return;
  stopping = true;
  var completed = false;
  var finish = function () {
    if (completed) return;
    completed = true;
    stopping = false;
    beginStart();
  };
  try {
    wx.stopLocationUpdate({ success: finish, fail: finish, complete: finish });
  } catch (e) {
    finish();
  }
}

function finishSuccess(gen, background) {
  // release 后旧启动才成功：没有新持有者就必须补偿 stop；若已有新一代启动在等，
  // 全局定位能力已经可用，可直接满足新一代，避免旧成功反过来停掉新行程。
  if (gen !== generation) {
    if (count() === 0) stopLocation();
    else if (starting) finishSuccess(generation, background);
    return;
  }
  starting = false;
  if (count() === 0) {
    waiters = [];
    started = false;
    usingBackground = false;
    stopLocation();
    return;
  }
  started = true;
  usingBackground = background;
  var ready = waiters;
  waiters = [];
  notify(ready, 'success', background);
}

function finishFailure(gen, err) {
  if (gen !== generation || started) return;
  starting = false;
  started = false;
  usingBackground = false;
  var failed = waiters.filter(function (item) { return owners[item.key] === item; });
  waiters = [];
  owners = Object.create(null);
  notify(failed, 'fail', err);
}

function callStart(start, success, fail) {
  var callbackStarted = false;
  function wrap(callback) {
    return function (value) {
      callbackStarted = true;
      return callback(value);
    };
  }
  try {
    start.call(wx, { success: wrap(success), fail: wrap(fail) });
  } catch (err) {
    // A synchronous consumer callback may throw through the platform call.
    // It is not a startup failure and must not trigger fallback/second delivery.
    if (callbackStarted) throw err;
    fail(err);
  }
}

function beginStart() {
  if (started || starting || stopping || count() === 0) return;
  starting = true;
  generation += 1;
  var gen = generation;

  var toForeground = function (bgErr) {
    if (gen !== generation || count() === 0 || started) return;
    // 后台权限没开通/用户拒绝 → 退回前台版,功能降级但不中断行程。
    callStart(wx.startLocationUpdate,
      function () { finishSuccess(gen, false); },
      function (e) { finishFailure(gen, e || bgErr); });
  };

  if (!wx.startLocationUpdateBackground) { toForeground(null); return; }
  callStart(wx.startLocationUpdateBackground,
    function () { finishSuccess(gen, true); }, toForeground);
}

/**
 * @param {string} key      行程标识,如 'play:<topicId>' / 'roam'
 * @param {object} opts     { onFail(err) }
 * 成功回调里 usingBackground 表示实际拿到的是后台还是前台能力。
 */
function acquire(key, opts) {
  opts = opts || {};
  if (owners[key]) return; // owner key 即幂等键，重复 acquire 不能重复回调/注册监听。
  var owner = { key: key, opts: opts, cancelled: false };
  owners[key] = owner;
  if (started) { opts.success && opts.success(usingBackground); return; }
  waiters.push(owner);
  beginStart();
}

/** 释放一个持有者;归零才真停。传 force=true 用于「无论如何都收干净」的兜底。 */
function release(key, force) {
  notifying.forEach(function (batch) {
    batch.forEach(function (item) {
      if (force || item.key === key) item.cancelled = true;
    });
  });
  delete owners[key];
  waiters = waiters.filter(function (item) { return item.key !== key; });
  if (!force && count() > 0) return;
  owners = Object.create(null);
  waiters = []; // force 时还要清掉其他 owner；旧 waiter 不能污染下一会话。
  if (starting) {
    starting = false;
    generation += 1; // 让所有在途回调失效；迟到 success 会走补偿 stop。
  }
  if (!started) return;
  started = false;
  usingBackground = false;
  stopLocation();
}

function isBackground() { return started && usingBackground; }
function isRunning() { return started; }

module.exports = { acquire: acquire, release: release, isBackground: isBackground, isRunning: isRunning };
