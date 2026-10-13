/** Transport for the existing content/comment report entry points. */
function requestId() { return 'mini-community-' + Date.now() + '-' + Math.random().toString(36).slice(2); }
function createClient(app, wxApi) {
  let reporting = false;
  function currentOwner() { return String(typeof app.getUserID === 'function' ? app.getUserID() || '' : ''); }
  function call(task, dispatch) {
    return new Promise((resolve, reject) => {
      if (task.aborted) return reject({ code: 499, msg: '操作已取消' });
      if (currentOwner() !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' });
      task.cancel = () => reject({ code: 499, msg: '操作已取消' });
      task.current = dispatch({
        hideLoading: true, silentError: true,
        header: { 'content-type': 'application/json' },
        success: res => {
          if (currentOwner() !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' });
          if (Number(res && res.code) === 200) resolve(res.data);
          else reject(res || { code: 500, msg: '社区请求失败' });
        },
        successStatusAbnormal: (res, status) => reject(Object.assign({}, res, { code: status, transportFailure: true })),
        fail: res => reject(Object.assign({}, res, { transportFailure: true }))
      });
    });
  }
  function pipeline(options, operation) {
    const task = { owner: currentOwner(), aborted: false, current: null, abort() {
      this.aborted = true;
      if (this.cancel) this.cancel();
      if (this.current && this.current.abort) this.current.abort();
    } };
    Promise.resolve().then(() => operation(task)).then(data => {
      if (!task.aborted && options.success) options.success({ code: 200, data });
    }).catch(error => {
      if (task.aborted) return;
      if (error.transportFailure && options.fail) options.fail(error);
      else {
        const res = { code: error.code || 500, msg: error.msg || error.message || '社区操作失败' };
        if (!options.silentError && options.autoErrorToast !== false && res.code !== 499 && typeof app.tips === 'function')
          app.tips(typeof app.getRequestErrorMessage === 'function' ? app.getRequestErrorMessage(res, '社区操作失败') : res.msg);
        if (options.success) options.success(res);
      }
    }).then(() => { if (options.complete) options.complete(); });
    return task;
  }
  async function report(options, task) {
    if (reporting) throw { code: 409, msg: '举报正在处理中' };
    reporting = true;
    try {
      const capabilities = await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/capabilities', method: 'GET', data: {},
        ...callbacks
      }));
      const taxonomy = capabilities && capabilities.reporting;
      if (!taxonomy || typeof taxonomy.policyVersion !== 'string' || !taxonomy.policyVersion.trim()
        || !Array.isArray(taxonomy.reasons) || !taxonomy.reasons.length
        || taxonomy.reasons.some(reason => !reason || typeof reason.label !== 'string' || !reason.label.trim()
          || typeof reason.code !== 'string' || !reason.code.trim()))
        throw { code: 503, msg: '举报原因暂不可用' };
      if (typeof options.selectReportReason !== 'function')
        throw { code: 503, msg: '举报原因选择暂不可用，请重试' };
      const index = await options.selectReportReason(taxonomy.reasons.map(r => r.label), task);
      if (!Number.isInteger(index) || !taxonomy.reasons[index])
        throw { code: 500, msg: '举报原因选择无效，请重试' };
      const data = options.data || {};
      return await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/reports', method: 'POST',
        data: { targetType: options.url === '/api/comment/report' ? 'COMMENT' : 'CONTENT',
          targetId: data.id, reasonCode: taxonomy.reasons[index].code,
          policyVersion: taxonomy.policyVersion, requestId: data.request_id || requestId() },
        ...callbacks
      }));
    } finally {
      reporting = false;
    }
  }
  function send(options) {
    return pipeline(options, task => {
      if (options.url !== '/api/creativesquare/report' && options.url !== '/api/comment/report')
        throw { code: 409, msg: '此社区功能尚未接通' };
      return report(options, task);
    });
  }
  return { request: send };
}
module.exports = { createClient };
