'use strict';

// 商家页的会话续期:只给已有账号续(单航班管理器会短路),失败只回 false、不 toast,
// 由调用页用页内错误给重试。
// owner 2026-10-24(app.js:142-144):游客的账号只在写入动作的登录弹窗里创建。本机没有会员(游客)
// 时这里一律回 false、不发 wx.login;调用页对游客落「登录后查看」态,用户点「登录」才走 retryLogin。
// renew=true 只给「已登录会员读到 401、会话刚被清掉」的续期用,那是同一个微信号的老账号。
function memberOf(app) {
  const id = typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '';
  return id === '0' ? '' : id;
}

function isGuest(app) {
  const instance = app || (typeof getApp === 'function' ? getApp() : null);
  return !instance || !memberOf(instance);
}

function ensureSession(app, renew) {
  const instance = app || (typeof getApp === 'function' ? getApp() : null);
  if (!instance) return Promise.resolve(false);
  if (!memberOf(instance) && renew !== true) return Promise.resolve(false);
  const manager = typeof instance.getSessionManager === 'function' ? instance.getSessionManager() : null;
  if (manager && typeof manager.ensureSession === 'function') {
    return Promise.resolve(manager.ensureSession()).then(
      function (result) { return !!(result && result.ok); },
      function () { return false; },
    );
  }
  return Promise.resolve(!!memberOf(instance));
}

module.exports = { ensureSession, isGuest };
