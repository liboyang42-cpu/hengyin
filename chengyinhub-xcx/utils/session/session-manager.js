// Phase 1.2 单航班登录与续期。
//
// 把原 app.js 中 firstLogin 与 reLogin 两套重复的「wx.login → POST /api/login/code → 落七字段」
// 收敛为一处,并加单航班锁(single-flight):同一时刻最多一个登录在途,并发的首登/静默重登
// 共享同一个 loginPromise —— 解决「N 个并发 401 各自重登、互相覆盖 token」。
//
// 依赖注入(便于单测,生产由 app.js 用 wx/真实请求装配):
//   - session:     session-store 实例(setAuthorization/setUserID/.../getAuthorization/getUserID)
//   - wxLogin():   Promise<{code}>,promisified wx.login(失败 reject)
//   - exchangeCode(code): Promise<body>,POST /api/login/code,resolve 为响应 body({data,token,msg});网络失败 reject
//
// 返回结果结构:{ ok, authed, cached?, reason?, msg?, isCurrentSession? }
//   - isCurrentSession:成功落地的会话是否仍有效，仅供延迟回调核验。
//   - ok=false 表示登录未成功;authed 表示是否拿到真实会话。
//   - 任何环境(含开发版)失败都返回显式失败,绝不写会话、绝不伪装已认证(配合 app.js 的假 ready 修复)。
//     F-W1-01:开发版曾在失败时写入假用户 id=1 / dev-token(useDevUser),401 静默重登失败后会话被
//     整体换成假身份;项目规则禁止假用户降级,该兜底已删除,开发版与体验版/正式版走同一失败路径。

function createSessionManager(deps) {
  deps = deps || {};
  var session = deps.session;
  var wxLogin = deps.wxLogin;
  var exchangeCode = deps.exchangeCode;
  var loginTimeoutMs = Number(deps.loginTimeoutMs) > 0 ? Number(deps.loginTimeoutMs) : 15000;

  if (!session || !wxLogin || !exchangeCode) {
    throw new Error('session-manager: 缺少 session/wxLogin/exchangeCode 依赖');
  }

  function withTimeout(promise) {
    return new Promise(function (resolve, reject) {
      var settled = false;
      var timer = setTimeout(function () {
        if (settled) return;
        settled = true;
        reject(new Error('login timeout'));
      }, loginTimeoutMs);
      Promise.resolve(promise).then(function (value) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      }, function (error) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error);
      });
    });
  }

  // 一次性清掉旧版本已落地的 OpenID；写空值会同步覆盖内存与 Storage，后续不再恢复身份标识。
  if (typeof session.setOpenID === 'function') {
    session.setOpenID('');
  }

  // 把安全响应体落地(token 取顶层、其余取 data.*),不接收或持久化 OpenID。
  function isValidPayload(body) {
    if (!body || !body.data) return false;
    var d = body.data;
    var token = typeof body.token === 'string' ? body.token.trim() : '';
    var id = String(d.id === undefined || d.id === null ? '' : d.id);
    var userType = Number(d.userType);
    var role = typeof d.role === 'string' ? d.role.trim() : '';
    return !!token && /^[1-9][0-9]*$/.test(id)
      && [1, 2, 3].indexOf(userType) >= 0
      && ['player', 'merchant', 'club'].indexOf(role) >= 0;
  }

  function applySession(body) {
    var d = body.data;
    if (typeof session.applySnapshot === 'function') {
      session.applySnapshot({
        authorization: body.token,
        user_id: d.id,
        user_type: d.userType,
        open_id: '',
        avatar: d.avatar || '',
        nickname: d.nickname || '',
        role: d.role,
      });
      return;
    }
    session.setAuthorization(body.token);
    session.setUserID(d.id);
    session.setUserType(d.userType);
    session.setAvatar(d.avatar);
    session.setNickname(d.nickname);
    session.setUserRole(d.role);
  }

  function loginSucceeded(result) {
    var owner = String(session.getUserID() || '');
    var authorization = session.getAuthorization();
    var revision = typeof session.getRevision === 'function' ? session.getRevision() : null;
    // 回调前仍可能换号；用闭包核验，避免把 token 复制到登录结果。
    result.isCurrentSession = function () {
      return String(session.getUserID() || '') === owner && session.getAuthorization() === authorization
        && (revision === null || session.getRevision() === revision);
    };
    return result;
  }

  function doLogin() {
    var owner = String(session.getUserID() || '');
    var authorization = session.getAuthorization();
    var revision = typeof session.getRevision === 'function' ? session.getRevision() : null;
    function isCurrent() {
      return String(session.getUserID() || '') === owner && session.getAuthorization() === authorization
        && (revision === null || session.getRevision() === revision);
    }
    function sessionChanged() {
      return { ok: false, authed: false, reason: 'sessionChanged' };
    }
    return Promise.resolve()
      .then(function () { return isCurrent() ? withTimeout(wxLogin()) : sessionChanged(); })
      .then(
        function (loginRes) {
          if (loginRes && loginRes.reason === 'sessionChanged') return loginRes;
          if (!isCurrent()) return sessionChanged();
          return withTimeout(exchangeCode(loginRes.code)).then(
            function (body) {
              if (!isCurrent()) return sessionChanged();
              if (isValidPayload(body)) {
                try {
                  applySession(body);
                } catch (error) {
                  return { ok: false, authed: false, reason: 'sessionPersistFail' };
                }
                return loginSucceeded({ ok: true, authed: true });
              }
              if (body && body.data) {
                return { ok: false, authed: false, reason: 'invalidPayload' };
              }
              // 换 token 成功但无业务数据
              return { ok: false, authed: false, reason: 'noData', msg: body && body.msg };
            },
            function () {
              if (!isCurrent()) return sessionChanged();
              // /api/login/code 网络失败
              return { ok: false, authed: false, reason: 'networkFail' };
            }
          );
        },
        function () {
          if (!isCurrent()) return sessionChanged();
          // wx.login 本身失败
          return { ok: false, authed: false, reason: 'wxLoginFail' };
        }
      );
  }

  var inFlight = null;

  // 单航班登录:并发调用复用同一个在途 Promise。
  function login() {
    if (inFlight) return inFlight;
    inFlight = doLogin().then(
      function (r) { inFlight = null; return r; },
      function (e) { inFlight = null; throw e; }
    );
    return inFlight;
  }

  // 首登:已有缓存会话(user_id+authorization)直接成功,不发网络登录;否则单航班登录。
  function ensureSession() {
    if (session.getUserID() && session.getAuthorization()) {
      return Promise.resolve({ ok: true, authed: true, cached: true });
    }
    return login();
  }

  return {
    login: login,
    ensureSession: ensureSession,
    refresh: login, // 静默重登复用同一单航班通道
    isLoggingIn: function () { return !!inFlight; },
  };
}

module.exports = { createSessionManager: createSessionManager };
