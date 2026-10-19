// Phase 3.1 发布提交状态机。
//
// 抽取 publish/activity 与 publish/fabu 共享的提交时序与不变量。依赖注入,页面保留各自
// UI(submitting 标志/analytics/toast/跳转、url 差异)。状态:idle → submitting → 成功 done / 失败回 idle。
//
// 依赖:
//  - request(payload, cb):执行发布网络请求;cb({ ok, data?, msg?, networkError? })。
//      ok=true → 业务成功;ok=false → 业务失败(带 msg)或网络失败(networkError=true)。
//
// submit(payload, handlers) 的 handlers(均可选):
//  onSuccess(data) / onFail(res)。

function createPublishWorkflow(deps) {
  deps = deps || {};
  var request = deps.request;
  if (!request) {
    throw new Error('publish-workflow: 缺少 request 依赖');
  }

  var state = 'idle'; // idle | submitting | done
  var destroyed = false;
  var active = null;

  function call(fn, arg) { if (typeof fn === 'function') fn(arg); }

  function submit(payload, handlers) {
    handlers = handlers || {};
    // 防重:仅 idle 可发起(submitting 在途、done 已完成均忽略)。
    if (destroyed || state !== 'idle') return false;
    state = 'submitting';
    var operation = {};
    active = operation;

    function receive(res) {
      if (destroyed || active !== operation) return;
      active = null;
      if (res && res.ok) {
        state = 'done';
        call(handlers.onSuccess, res.data);
      } else {
        state = 'idle';
        call(handlers.onFail, res);
      }
    }
    try {
      request(payload, receive);
    } catch (error) {
      if (active !== operation) throw error;
      receive({ ok: false, networkError: true });
    }
    return true;
  }

  return {
    submit: submit,
    destroy: function () { destroyed = true; active = null; },
    getState: function () { return state; },
    isBusy: function () { return state === 'submitting'; },
  };
}

module.exports = { createPublishWorkflow: createPublishWorkflow };
