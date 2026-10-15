'use strict';

// 原生选择器/裁剪与后续上传绑定发起页面和会员，离页或换号后取消旧操作。
function createPageBoundOperation(options) {
  options = options || {};
  var owner = options.owner;
  var getPages = options.getPages || function () { return []; };
  var getUserID = options.getUserID || function () { return ''; };
  var member = String(getUserID() || '');
  var setPoll = options.setInterval || setInterval;
  var clearPoll = options.clearInterval || clearInterval;
  var pollMs = Math.max(100, Number(options.pollMs) || 250);
  var task = null;
  var finished = false;
  var aborted = false;
  var timer = null;
  var abortListeners = [];

  function stopTimer() {
    if (timer !== null) clearPoll(timer);
    timer = null;
  }

  function stopTask(control) {
    if (!control) return;
    try {
      if (typeof control.abort === 'function') control.abort();
      else if (typeof control.destroy === 'function') control.destroy();
    } catch (e) {}
  }

  function checkOwner() {
    if (finished || aborted) return;
    var pages;
    try { pages = getPages() || []; } catch (e) { pages = []; }
    if (!owner || pages.indexOf(owner) < 0 || String(getUserID() || '') !== member) operation.abort();
  }

  var operation = {
    attach: function (control) {
      checkOwner();
      if (finished || aborted) {
        stopTask(control);
        return operation;
      }
      if (task && task !== control) stopTask(task);
      task = control || null;
      return operation;
    },
    finish: function () {
      if (finished || aborted) return;
      finished = true;
      task = null;
      abortListeners = [];
      stopTimer();
    },
    abort: function () {
      if (finished || aborted) return;
      aborted = true;
      var activeTask = task;
      task = null;
      stopTimer();
      stopTask(activeTask);
      var listeners = abortListeners;
      abortListeners = [];
      listeners.forEach(function (listener) { try { listener(); } catch (e) {} });
    },
    onAbort: function (listener) {
      if (typeof listener !== 'function' || finished) return operation;
      if (aborted) {
        try { listener(); } catch (e) {}
      } else abortListeners.push(listener);
      return operation;
    },
    destroy: function () { operation.abort(); },
    // 回调到达时同步读一次页面栈，补掉轮询间隔内“页面已移除但尚未 tick”的竞态。
    isAborted: function () { checkOwner(); return aborted; },
    isFinished: function () { return finished; },
  };

  timer = setPoll(checkOwner, pollMs);

  return operation;
}

module.exports = { createPageBoundOperation: createPageBoundOperation };
