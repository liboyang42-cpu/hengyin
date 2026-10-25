'use strict';
// 写前登录闸(owner 2026-10-24 拍板):「没登录也能浏览;报名、入会、发帖这类写库动作才弹窗要求登录」。
// 也是微信审核的要求:游客必须先能看,再谈登录。全仓所有写入口统一走这一处,别在页面里各写一套。
//
// requireLogin({ reason }) → Promise<boolean>
//   - 已有真实会话:直接 true,不弹窗、不发网络登录。
//   - 游客:用现有 cy-modal(utils/modal.js → cy-modal-host)弹「登录后继续」;
//     「微信登录」= app.firstLogin()(单航班 wx.login → /api/login/code,账号在这一刻才创建),
//     成功落地会话才 true;「暂不登录」/失败 = false,调用方原样不动页面。
//   - 并发:同一时刻只弹一个窗,其余调用共享同一结果。
// withLogin(options, action):登录成功后把原动作**恢复执行一次**;取消则什么都不做。
const modal = require('../modal.js');

const TITLE = '登录后继续';
const CANCEL_TEXT = '暂不登录';
const CONFIRM_TEXT = '微信登录';
const DEFAULT_REASON = '登录后即可报名、加入俱乐部和发帖';
// 动作名 → 正文一句话。传 reason 原文也行(以「登录后」开头的中文短句)。
const REASONS = {
  signup: '登录后即可报名参加',
  checkout: '登录后即可购票和支付',
  club: '登录后即可加入俱乐部',
  post: '登录后即可发帖和评论',
  like: '登录后即可点赞、收藏和关注',
  team: '登录后即可创建或加入队伍',
  roam: '登录后即可开始漫游并保存足迹',
  play: '登录后即可开始游玩并保存进度',
  publish: '登录后即可发布作品',
  report: '登录后即可举报',
};

function resolveReason(options) {
  const reason = options && options.reason;
  if (typeof reason === 'string' && REASONS[reason]) return REASONS[reason];
  if (typeof reason === 'string' && /^登录后/.test(reason) && reason.length <= 30) return reason;
  return DEFAULT_REASON;
}

function hasSession(app) {
  if (!app || typeof app.getUserID !== 'function' || typeof app.getAuthorization !== 'function') return false;
  const id = String(app.getUserID() || '');
  const token = app.getAuthorization();
  return Number(id) > 0 && !!token && token !== app.DEV_TOKEN;
}

function createRequireLogin(deps) {
  deps = deps || {};
  const getAppRef = deps.getApp || function () { try { return getApp(); } catch (e) { return null; } };
  const show = deps.show || function (opts) { return modal.show(opts); };
  const getPages = deps.getPages || function () { return typeof getCurrentPages === 'function' ? getCurrentPages() : null; };
  let pending = null;

  function sessionStamp(app) {
    try {
      const session = typeof app.getSession === 'function' ? app.getSession() : null;
      return { owner: String(app.getUserID() || ''), token: app.getAuthorization(),
        revision: session && typeof session.getRevision === 'function' ? session.getRevision() : null };
    } catch (e) { return null; }
  }
  function sameSession(app, original) {
    const now = sessionStamp(app);
    return !!original && !!now && original.owner === now.owner && original.token === now.token
      && original.revision === now.revision;
  }
  function resultFor(app) {
    const original = sessionStamp(app);
    return { ok: !!original && hasSession(app), current: () => sameSession(app, original) };
  }
  function continuation(options) {
    options = options || {};
    try {
      const pages = getPages(), page = pages && pages[pages.length - 1];
      const owner = options.owner || page;
      const epoch = owner && (owner._loginContinuationEpoch || 0);
      const pageEpoch = page && (page._loginContinuationEpoch || 0);
      const target = () => {
        const values = options.target ? options.target() : [];
        if (!Array.isArray(values) || values.some(value => value != null
          && !['string', 'number', 'boolean'].includes(typeof value))) throw Error('登录续接目标无法确认');
        return JSON.stringify(values.map(value => value == null ? '' : String(value)));
      };
      const original = target();
      return () => {
        try {
          const currentPages = getPages();
          return (!pages || !!page && !!currentPages && currentPages[currentPages.length - 1] === page)
            && (!owner || !(owner._unloaded || owner._destroyed || owner._disposed || owner._attached === false)
              && (owner._loginContinuationEpoch || 0) === epoch)
            && (!page || (page._loginContinuationEpoch || 0) === pageEpoch)
            && target() === original;
        } catch (e) { return false; }
      };
    } catch (e) { return () => false; }
  }

  function ask(content) {
    return new Promise(function (resolve) {
      try {
        show({
          title: TITLE, content: content, showCancel: true,
          cancelText: CANCEL_TEXT, confirmText: CONFIRM_TEXT,
          success: function (res) { resolve(!!(res && res.confirm)); },
          fail: function () { resolve(false); },
        });
      } catch (e) { resolve(false); }
    });
  }

  function loginResult(options) {
    const app = getAppRef();
    if (hasSession(app)) return Promise.resolve(resultFor(app));
    if (!app || typeof app.firstLogin !== 'function') return Promise.resolve({ ok: false });
    if (pending) return pending.promise;
    const original = sessionStamp(app), attempt = {};
    pending = attempt;
    attempt.promise = ask(resolveReason(options)).then(function (confirmed) {
      if (!confirmed || !sameSession(app, original)) return { ok: false };
      // firstLogin 自带失败 toast(微信登录失败/网络异常),这里不再重复提示。
      return Promise.resolve(app.firstLogin()).then(function (r) {
        if (!r || !r.ok || typeof r.isCurrentSession === 'function' && !r.isCurrentSession()) return { ok: false };
        return resultFor(app);
      }, function () { return { ok: false }; });
    }).then(function (result) { if (pending === attempt) pending = null; return result; },
      function () { if (pending === attempt) pending = null; return { ok: false }; });
    return attempt.promise;
  }

  function requireLogin(options) {
    return loginResult(options).then(result => !!result.ok && result.current());
  }

  function withLogin(options, action) {
    // Capture before either the modal or login Promise; never recapture a replacement page/target/account.
    const current = continuation(options);
    if (!current()) return Promise.resolve(false);
    return loginResult(options).then(function (result) {
      const ok = !!result.ok && result.current() && current();
      if (ok && typeof action === 'function') action();
      return ok;
    });
  }

  return { requireLogin: requireLogin, withLogin: withLogin, isPending: function () { return !!pending; } };
}

let shared = null;
function instance() { if (!shared) shared = createRequireLogin(); return shared; }

module.exports = {
  createRequireLogin: createRequireLogin,
  requireLogin: function (options) { return instance().requireLogin(options); },
  withLogin: function (options, action) { return instance().withLogin(options, action); },
  hasSession: hasSession,
  resolveReason: resolveReason,
  REASONS: REASONS,
  TITLE: TITLE, CANCEL_TEXT: CANCEL_TEXT, CONFIRM_TEXT: CONFIRM_TEXT, DEFAULT_REASON: DEFAULT_REASON,
};
