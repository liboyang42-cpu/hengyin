'use strict';

function memberId(app) {
  const id = String(app && typeof app.getUserID === 'function' && app.getUserID() || '');
  return id === '0' ? '' : id;
}
function failureStatus(res, statusCode) {
  return Number(statusCode || res && (res.statusCode || res.code));
}
function retryLogin(host, app, reload) {
  if (host._loginPending) return;
  host._loginPending = true;
  let pending;
  try {
    pending = memberId(app) && typeof app.reLogin === 'function' ? app.reLogin()
      : typeof app.firstLogin === 'function' ? app.firstLogin() : null;
  } catch (error) { host._loginPending = false; return; }
  return Promise.resolve(pending).then(result => {
    host._loginPending = false;
    if (!host._detached && !host._unloaded && !host._hidden && memberId(app) && !(result && result.ok === false)) reload.call(host);
  }, () => { host._loginPending = false; });
}
function waitForInitialSession(host, app, reload) {
  if (host._initialSessionWait || !app || app.isAppReady !== false || typeof app.waitForAppReady !== 'function') return;
  let pending;
  try { pending = app.waitForAppReady(); } catch (error) { return; }
  if (!pending || typeof pending.then !== 'function') return;
  host._initialSessionWait = true;
  Promise.resolve(pending).then(() => {
    if (!host._detached && !host._unloaded && !host._hidden && memberId(app)) reload.call(host);
  }, () => {});
}
// 调用方先验证自身请求epoch；此处只接传输层会话变更与自有续期失败清空身份。
function recoverReadSession(host, app, res, reset, reload, statusCode) {
  if (!(res && res.reason === 'sessionChanged') && !(failureStatus(res, statusCode) === 401 && !memberId(app))) return false;
  if (host._detached || host._unloaded) return true;
  reset.call(host, !memberId(app));
  host._readSessionChanged = true;
  if (!host._hidden && memberId(app)) {
    host._readSessionChanged = false;
    reload.call(host);
  }
  return true;
}
module.exports = { memberId, failureStatus, retryLogin, waitForInitialSession, recoverReadSession };
