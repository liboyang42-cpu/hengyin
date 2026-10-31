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
var owners = Object.create(null);   // sessionKey -> 领取身份
var started = false;
var starting = false;
var stopping = false;
var stopFailure = null;
var stopBlocksRestart = false;
var stopPromise = null;
var usingBackground = false;
var generation = 0;
var waiters = [];
var revokedThroughGeneration = -1;
var revisions = Object.create(null);
var revokeListeners = Object.create(null);
var notifying = []; // 回调中的批次：owners 已清空时仍可按身份取消

function cancelNotifications(key, force) {
  notifying.forEach(function (batch) {
    batch.forEach(function (item) { if (force || item.key === key) item.cancelled = true; });
  });
}

function notify(batch, callback, value) {
  var errs = []; // 一个消费者抛错不能挡住其余持有者，收尾后抛出首个
  notifying.push(batch);
  try {
    batch.forEach(function (item) {
      if (item.cancelled || (callback === 'success' && owners[item.key] !== item)) return;
      try { if (item.opts[callback]) item.opts[callback](value); } catch (e) { errs.push(e); }
    });
  } finally {
    notifying.splice(notifying.indexOf(batch), 1);
  }
  if (errs.length) throw errs[0];
}

function count() { return Object.keys(owners).length; }
function stopLocation(requireConfirmation) {
  // 普通行程释放沿用已获同意持有者的恢复；用途撤回把同一在途停止升级为必须确认。
  if (requireConfirmation) stopBlocksRestart = true;
  if (stopping) return stopPromise;
  stopping = true;
  var completed = false, resolveStop;
  var result = stopPromise = new Promise(function (resolve) { resolveStop = resolve; });
  // 系统没有回调也不能宣称已停；只保留可显式重试的停止失败，不重新开启采集。
  var timer = setTimeout(function () { finish({ errMsg: 'stopLocationUpdate:fail timeout' }); }, 10000);
  var finish = function (error) {
    if (completed) return;
    completed = true;
    clearTimeout(timer);
    stopping = false;
    stopFailure = error || null;
    if (!error) stopBlocksRestart = false;
    stopPromise = null;
    resolveStop({ ok: !error, error: error || null });
    if (error && stopBlocksRestart) {
      if (waiters.length) finishFailure(generation, error);
    } else beginStart();
  };
  try {
    wx.stopLocationUpdate({
      success: function () { finish(null); },
      fail: function (error) { finish(error || { errMsg: 'stopLocationUpdate:fail' }); },
      complete: function (res) {
        if (res && /:fail/.test(res.errMsg || '')) finish(res);
        else if (res && /:ok/.test(res.errMsg || '')) finish(null);
      }
    });
  } catch (e) {
    if (completed) throw e;
    finish({ errMsg: 'stopLocationUpdate:fail exception' });
  }
  return result;
}

function stopState() { return stopping ? 'stopping' : stopFailure ? 'failed' : 'confirmed'; }
function retryStop() {
  // 撤回一个用途不越权关闭其它独立获准的持有者。
  return count() > 0 ? Promise.resolve({ ok: true, otherOwners: true }) : stopLocation(true);
}

function finishSuccess(gen, background) {
  // 撤回前的系统 start 不能代替之后的新授权；补停完成后再为当前持有者真启动。
  if (gen <= revokedThroughGeneration) {
    started = false;
    usingBackground = false;
    if (starting) { starting = false; generation += 1; }
    stopLocation(true);
    return;
  }
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
  if (!stopBlocksRestart) stopFailure = null;
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
  var called = false;
  function wrap(cb) { return function (v) { called = true; return cb(v); }; }
  try {
    start({ success: wrap(success), fail: wrap(fail) });
  } catch (e) {
    if (called) throw e; // 消费者回调的同步异常不是启动失败
    fail(e);
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
    callStart(function (o) { wx.startLocationUpdate(o); }, function () { finishSuccess(gen, false); },
      function (e) { finishFailure(gen, e || bgErr); });
  };

  if (!wx.startLocationUpdateBackground) { toForeground(null); return; }
  callStart(function (o) { wx.startLocationUpdateBackground(o); }, function () { finishSuccess(gen, true); }, toForeground);
}

/**
 * @param {string} key      行程标识,如 'play:<topicId>' / 'roam'
 * @param {object} opts     { onFail(err) }
 * 成功回调里 usingBackground 表示实际拿到的是后台还是前台能力。
 */
function acquire(key, opts) {
  opts = opts || {};
  if (stopFailure && stopBlocksRestart) { if (opts.fail) opts.fail(stopFailure); return; }
  if (owners[key]) return; // owner key 即幂等键，重复 acquire 不能重复回调/注册监听。
  cancelNotifications(key, false); // 新领取取代该 key 在途的旧失败通知
  var owner = owners[key] = { key: key, opts: opts };
  if (started) { opts.success && opts.success(usingBackground); return; }
  waiters.push(owner);
  beginStart();
}

/** 释放一个持有者;归零才真停。传 force=true 用于「无论如何都收干净」的兜底。 */
function release(key, force) {
  cancelNotifications(key, force);
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
function hasOwner(key) { return !!owners[key]; }
function revision(key) { return revisions[key] || 0; }

function observeRevocation(key, listener) {
  var list = revokeListeners[key] || (revokeListeners[key] = []);
  list.push(listener);
  return function () {
    revokeListeners[key] = (revokeListeners[key] || []).filter(function (item) { return item !== listener; });
    if (revokeListeners[key].length === 0) delete revokeListeners[key];
  };
}

/** 撤回的是用途授权；其他独立获准的持有者继续运行，最后一个才停系统定位。 */
function revoke(key) {
  var needsStop = started || starting || stopping || !!stopFailure;
  var result = Promise.resolve({ ok: true, otherOwners: count() > 1 });
  revisions[key] = revision(key) + 1;
  cancelNotifications(key, false);
  delete owners[key];
  waiters = waiters.filter(function (item) { return item.key !== key; });
  if (count() === 0) {
    revokedThroughGeneration = generation;
    generation += 1;
    starting = false;
    started = false;
    usingBackground = false;
    if (needsStop) result = stopLocation(true);
  }
  (revokeListeners[key] || []).slice().forEach(function (listener) { listener(); });
  return result;
}

module.exports = { stopState: stopState, retryStop: retryStop, acquire: acquire, release: release, revoke: revoke, revision: revision,
  observeRevocation: observeRevocation, hasOwner: hasOwner, isBackground: isBackground, isRunning: isRunning };
