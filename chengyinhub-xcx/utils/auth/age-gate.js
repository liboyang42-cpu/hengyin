'use strict';
// P1b SEC-14 小程序侧(2026-11-15,owner:上线只面向 18 周岁及以上):
//   新登录(游客 → 有会话)成功后问一次年龄,文案与 App 一致;回答写到 POST /api/compliance/age/confirm。
//   - 已确认过的账号(POST /api/compliance/age/status → confirmed=true)不再问。
//   - 读状态失败不拦人(与 App 相同:读不到就不打断);老用户冷启动恢复缓存会话不算新登录,今天的行为不变。
//   - 选「我未满 18 周岁」(或关掉弹窗)= 没有确认:给出朴素的未满 18 岁说明,退出登录。服务端不保存任何内容。
//   - 「我已年满 18 周岁」保存失败:提示再试一次,重新问;连续失败三次先放行,下次登录再问。
const modal = require('../modal.js');
const toast = require('../toast.js');

const STATUS_URL = '/api/compliance/age/status';
const CONFIRM_URL = '/api/compliance/age/confirm';
const UNDER_AGE_CODE = 'AGE_UNDER_18';
const SOURCE = 'sign_in';

// 与 App(Resources/Localizable.xcstrings storeSafety.age.*)逐字一致
const COPY = {
  title: '确认年龄',
  body: '城瘾只面向 18 周岁及以上的玩家。请确认你的年龄。',
  confirm: '我已年满 18 周岁',
  under: '我未满 18 周岁',
  refused: '城瘾目前只面向 18 周岁及以上的玩家，暂时无法为你提供服务。',
  saveFailed: '没有保存成功，请再试一次。',
  ok: '知道了',
  // 页面没挂 cy-modal-host、回落到原生弹窗时,按钮最多 4 个字
  nativeConfirm: '已满18',
  nativeUnder: '未满18',
};
const MAX_SAVE_ATTEMPTS = 3;

function isOk(body) { return !!(body && (body.code === 200 || body.code === '200')); }

// 只认服务端明确的 confirmed=true / false;其它一律 null(= 读不到)。
function parseConfirmed(body) {
  if (!isOk(body) || !body.data || typeof body.data !== 'object') return null;
  if (body.data.confirmed === true) return true;
  if (body.data.confirmed === false) return false;
  return null;
}

function refusalMessage(body) {
  if (body && body.errorCode === UNDER_AGE_CODE && typeof body.msg === 'string' && body.msg.trim()
      && body.msg.trim().length <= 60) return body.msg.trim();
  return COPY.refused;
}

function createAgeGate(deps) {
  deps = deps || {};
  const show = deps.show || function (opts) { return modal.show(opts); };
  const notify = deps.toast || function (text) { toast(text); };
  let inflight = null;

  function post(app, url, data) {
    return new Promise(function (resolve) {
      let done = false;
      const finish = function (body) { if (!done) { done = true; resolve(body || null); } };
      try {
        app.sendRequest({
          url: url, method: 'POST', hideLoading: true, silentError: true, autoErrorToast: false, timeout: 10000,
          header: { 'content-type': 'application/json' }, data: data || {},
          success: finish, fail: function () { finish(null); },
          successStatusAbnormal: function () { finish(null); },
        });
      } catch (e) { finish(null); }
    });
  }

  function ask(native) {
    return new Promise(function (resolve) {
      try {
        show({
          title: COPY.title, content: COPY.body, showCancel: true,
          confirmText: native ? COPY.nativeConfirm : COPY.confirm,
          cancelText: native ? COPY.nativeUnder : COPY.under,
          success: function (res) { resolve(res && res.confirm ? 'adult' : 'under'); },
          // 长按钮文案在原生弹窗里会失败:换短文案再问一次;再失败按没有回答处理
          fail: function () { resolve(native ? 'under' : 'retryNative'); },
        });
      } catch (e) { resolve(native ? 'under' : 'retryNative'); }
    }).then(function (answer) { return answer === 'retryNative' ? ask(true) : answer; });
  }

  function tell(text) {
    return new Promise(function (resolve) {
      try {
        show({ title: COPY.title, content: text, showCancel: false, confirmText: COPY.ok,
          success: function () { resolve(); }, fail: function () { resolve(); } });
      } catch (e) { resolve(); }
    });
  }

  function signOut(app) {
    try {
      const session = typeof app.getSession === 'function' ? app.getSession() : null;
      if (session && typeof session.clearSession === 'function') session.clearSession();
    } catch (e) { /* 退出失败也不放行:结果仍按未确认返回 */ }
  }

  function refuse(app) {
    return post(app, CONFIRM_URL, { adult: false, source: SOURCE }).then(function (body) {
      signOut(app);
      return tell(refusalMessage(body));
    }).then(function () { return false; });
  }

  function confirmLoop(app, attempt) {
    return ask(false).then(function (answer) {
      if (answer !== 'adult') return refuse(app);
      return post(app, CONFIRM_URL, { adult: true, source: SOURCE }).then(function (body) {
        if (parseConfirmed(body) === true) return true;
        if (body && body.errorCode === UNDER_AGE_CODE) return refuse(app);
        notify(COPY.saveFailed);
        return attempt + 1 >= MAX_SAVE_ATTEMPTS ? true : confirmLoop(app, attempt + 1);
      });
    });
  }

  // check(app) → Promise<boolean>:true = 可以继续(已确认/刚确认/读不到状态);false = 未确认,已退出登录。
  function check(app) {
    if (!app || typeof app.sendRequest !== 'function') return Promise.resolve(true);
    if (inflight) return inflight;
    const run = post(app, STATUS_URL, {}).then(function (body) {
      return parseConfirmed(body) === false ? confirmLoop(app, 0) : true;
    }).catch(function () { return true; });
    inflight = run.then(function (r) { inflight = null; return r; }, function () { inflight = null; return true; });
    return inflight;
  }

  return { check: check };
}

let shared = null;
function instance() { if (!shared) shared = createAgeGate(); return shared; }

module.exports = {
  createAgeGate: createAgeGate,
  check: function (app) { return instance().check(app); },
  parseConfirmed: parseConfirmed,
  refusalMessage: refusalMessage,
  COPY: COPY,
  STATUS_URL: STATUS_URL,
  CONFIRM_URL: CONFIRM_URL,
};
