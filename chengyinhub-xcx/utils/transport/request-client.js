// Phase 1.3 统一请求通道。
//
// 把原 app.js sendRequest 的全部行为收口到纯模块,行为零变;唯一增强:认证头自动注入,
// 使页面不再手拼 'Authorization': app.getAuthorization()。认证模型采用安全默认值(见单测):
//   - GET/POST 默认自动 Authorization;公共请求必须显式 param.auth===false。
//   - POST 且无 param.header → urlencoded;其余方法默认 application/json。
//   - 提供 param.header → 自动补 Authorization(除非已显式带或 param.auth===false),content-type 保留。
//
// 依赖注入(便于单测,生产由 app.js 用 wx/会话装配):
//   - wxRequest(opts):      执行请求(success/fail/complete 回调),生产 = wx.request。
//   - getBaseUrl():          默认基础 URL(globalData.siteBaseUrl)。
//   - getAuthorization():    当前 token。
//   - getUserID():           当前会员，重试沿用发起身份。
//   - reLogin(cb):           单航班静默重登,cb(ok)。
//   - showToast(msg)/hideToast()
//   - getErrorMessage(res, fallback) / shouldAutoToast(param, body):沿用 app.js 既有纯函数。
//   - newRequestId():         同意接口 requestId 冲突时换的新号(默认 consent-时间-随机)。
//   - isPlayerView():         当前是否玩家视角(G-4,生产 = !roleGuard.isMerchantView());缺省 false = 不过滤。

var sanitizeResponseMessage = require('./safe-user-message.js').sanitizeResponseMessage;
var normalizeClientVersion = require('../client-version.js').normalizeClientVersion;
var roleViewFilter = require('./role-view-filter.js');

// #472-2(owner 2026-10-23 批准改动本 C00 文件):同意留痕接口回 409 + CONSENT_REQUEST_CONFLICT
// = 这个 requestId 已属于另一条同意事件(例如协议重新发布后客户端重放了旧号)。这时换一个新号重发
// **恰好一次**;第二次仍冲突就如实交给调用方,不循环。只认这一个端点 + 服务端显式错误码:
// 别的 409(如定位同意的状态已变)换号也解决不了,照旧原样返回。
// 按路径比对(不是发起调用):写成正则,query 不影响判定。
var CONSENT_ENDPOINT = /^\/api\/agreement\/consent(?:\?|$)/;
var CONSENT_REQUEST_CONFLICT = 'CONSENT_REQUEST_CONFLICT';

function defaultConsentRequestId() {
  return 'consent-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

function isConsentRequestConflict(param, res, bodyCode) {
  var body = res && res.data;
  return CONSENT_ENDPOINT.test(String(param.url || '')) && !!body && typeof body === 'object'
    && body.errorCode === CONSENT_REQUEST_CONFLICT && (res.statusCode == 409 || bodyCode == 409);
}

/** 换上新 requestId 的请求参数;原请求没带 requestId(或读不出来)就返回 null,不重发。 */
function withFreshConsentRequestId(param, newRequestId) {
  var raw = param.data;
  var asString = typeof raw === 'string';
  var data = raw;
  if (asString) {
    try { data = JSON.parse(raw); } catch (notJson) { return null; }
  }
  if (!data || typeof data !== 'object' || !data.requestId) return null;
  var fresh = String(newRequestId() || '');
  if (!fresh || fresh === String(data.requestId) || fresh.length > 64) return null;
  var next = Object.assign({}, data, { requestId: fresh });
  return Object.assign({}, param, { data: asString ? JSON.stringify(next) : next });
}

// owner 2026-10-24(本 C00 文件为该任务获授权改动,见 PR CY-HANDOFF):「游客可浏览,写入时弹窗登录」。
// 游客(发起时无会员、无 token)的请求被 401 拦下时**不再静默 wx.login 建号**:
//   - 写请求 → deps.promptLogin(param)(同一个「登录后继续」弹窗);登录成功用新身份重发恰好一次,取消/失败照常 fail。
//   - 读请求 → 不弹窗、不建号、不 toast,按 401 交给调用方的 fail(页面显示游客态/未登录态)。
// 写/读判定:param.write 显式优先;GET 一律读;PUT/PATCH/DELETE 一律写;POST 看路径末段的动词
// (含 list/info/detail 等读名词即读,含 create/join/like/report 等写动词即写,其余按读处理 —— 宁可少弹也不在浏览时弹)。
var READ_TOKENS = ['list', 'info', 'detail', 'details', 'home', 'data', 'page', 'search', 'query', 'count',
  'summary', 'stats', 'feed', 'feeds', 'latest', 'status', 'config', 'features', 'capabilities', 'taxonomy',
  'nearby', 'leaderboard', 'members', 'history', 'records', 'preview', 'check', 'mine', 'my', 'get', 'view'];
var WRITE_TOKENS = ['add', 'create', 'save', 'update', 'edit', 'delete', 'remove', 'join', 'apply', 'quit',
  'signup', 'enroll', 'pay', 'checkout', 'order', 'submit', 'publish', 'like', 'unlike', 'favorite', 'bookmark',
  'follow', 'unfollow', 'action', 'report', 'comment', 'reply', 'use', 'start', 'finish', 'arrive', 'checkin',
  'reveal', 'discover', 'visit', 'claim', 'redeem', 'invite', 'accept', 'reject', 'respond', 'cancel',
  'withdraw', 'transfer', 'register', 'set', 'toggle', 'send', 'draft', 'vote', 'rate', 'posts', 'comments',
  'replies', 'reports', 'team', 'teams', 'remix', 'copy', 'appeal'];

function pathTokens(url) {
  var path = String(url || '').split('?')[0].replace(/\/+$/, '');
  var last = path.slice(path.lastIndexOf('/') + 1);
  return last.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase().split(/[-_.]+/).filter(Boolean);
}

function isWriteRequest(param, method) {
  if (param && typeof param.write === 'boolean') return param.write;
  if (method === 'GET') return false;
  if (method !== 'POST') return true;
  var tokens = pathTokens(param && param.url);
  if (tokens.some(function (t) { return READ_TOKENS.indexOf(t) >= 0; })) return false;
  return tokens.some(function (t) { return WRITE_TOKENS.indexOf(t) >= 0; });
}

function createRequestClient(deps) {
  deps = deps || {};
  var wxRequest = deps.wxRequest;
  var getBaseUrl = deps.getBaseUrl;
  var getAuthorization = deps.getAuthorization || function () { return ''; };
  var getClientVersion = deps.getClientVersion || function () { return ''; };
  var upgradePrompted = false;
  var getUserID = deps.getUserID || function () { return ''; };
  var reLogin = deps.reLogin || function (cb) { cb(false); };
  var promptLogin = deps.promptLogin || function () { return Promise.resolve(false); };
  var showToast = deps.showToast || function () {};
  var hideToast = deps.hideToast || function () {};
  var getErrorMessage = deps.getErrorMessage || function (res, fb) { return fb; };
  var shouldAutoToast = deps.shouldAutoToast || function () { return false; };
  var scheduleRetry = deps.scheduleRetry || function (fn, ms) { setTimeout(fn, ms); };
  var newRequestId = deps.newRequestId || defaultConsentRequestId;
  var defaultTimeoutMs = Math.max(1000, Number(deps.defaultTimeoutMs) || 12000);
  var isPlayerView = deps.isPlayerView || function () { return false; };
  function playerView() {
    try { return !!isPlayerView(); } catch (unavailable) { return false; }
  }

  if (!wxRequest || !getBaseUrl) {
    throw new Error('request-client: 缺少 wxRequest/getBaseUrl 依赖');
  }

  // 解析最终请求头:复刻旧行为 + 认证自动注入。
  function buildHeader(param, method) {
    var header = param.header;
    if (!header) {
      header = method === 'POST'
        ? { 'content-type': 'application/x-www-form-urlencoded;' }
        : { 'content-type': 'application/json' };
    }
    // 所有方法默认补认证(不覆盖已显式带的,param.auth===false 才可关闭)。
    if (param.auth !== false && header.Authorization === undefined) {
      header = Object.assign({}, header, { 'Authorization': getAuthorization() });
    }
    var version = '';
    try { version = normalizeClientVersion(getClientVersion()); } catch (unavailable) {}
    if (version) {
      header = Object.assign({}, header, { 'X-Mini-Program-Version': version });
    }
    return header;
  }

  function requestControl(state) {
    if (state.control) return state.control;
    state.control = {
      isAuthenticatedContinuationCurrent: function () {
        if (state.aborted || state.sessionChanged || typeof state.renewalCurrent !== 'function'
            || state.owner !== String(getUserID() || '') || state.authorization !== getAuthorization()) return false;
        try { return state.renewalCurrent() === true; } catch (error) { return false; }
      },
      abort: function () {
        if (state.aborted || state.sessionChanged) return;
        state.aborted = true;
        if (state.task && typeof state.task.abort === 'function') state.task.abort();
      }
    };
    return state.control;
  }

  function send(param, customSiteUrl, _state) {
    var method = (param.method || 'GET').toUpperCase();
    var requestUrl = (customSiteUrl || getBaseUrl()) + param.url;
    var header = buildHeader(param, method);
    // 重试守卫存于内部 state(不污染调用方 param;复用同一 param 的多次请求各自独立重登)。
    // task/aborted 让调用方能取消当前尝试和静默重登后的下一跳；同一个 state 只暴露一个 control。
    var state = _state || { retried: false, aborted: false, task: null, attempt: 0, transportRetryAttempt: 0, owner: String(getUserID() || ''), authorization: getAuthorization() };
    var control = requestControl(state);
    if (state.aborted || state.sessionChanged) return control;
    function ownerChanged() { return state.owner !== String(getUserID() || ''); }
    function sessionChanged() { return ownerChanged() || (param.auth !== false && state.authorization !== getAuthorization()); }
    function stopForSessionChange() {
      state.sessionChanged = true;
      var failure = { reason: 'sessionChanged', msg: '登录状态已变更，请重试' };
      typeof param.fail == 'function' && param.fail(failure);
      typeof param.complete == 'function' && param.complete(failure);
    }
    if (sessionChanged()) { stopForSessionChange(); return control; }
    // G-4:玩家视角不发商家工作台接口,异步按 403 静默失败。
    if (!state.attempt && playerView() && roleViewFilter.isMerchantOnlyUrl(param.url)) {
      Promise.resolve().then(function () {
        if (state.aborted || state.sessionChanged) return;
        var blocked = roleViewFilter.blockedBody();
        if (typeof param.successStatusAbnormal == 'function') param.successStatusAbnormal(blocked, 403);
        else if (typeof param.fail == 'function') param.fail(blocked, 403);
        typeof param.complete == 'function' && param.complete(blocked);
      });
      return control;
    }
    var attempt = (state.attempt || 0) + 1;
    state.attempt = attempt;
    // 静默重登窗口内抑制本次 wxRequest 的 complete;complete 改由最终一跳(重试成功)或重登失败分支触发一次。
    var suppressComplete = false;
    var responseObserved = false;

    var requestOptions = {
      url: requestUrl,
      data: param.data || {},
      method: method,
      header: header,
      success: function (res) {
        if (state.aborted || state.sessionChanged) return;
        if (sessionChanged()) { stopForSessionChange(); return; }
        responseObserved = true;
        if (res && res.data && typeof res.data === 'object') sanitizeResponseMessage(res.data, '操作失败');
        // G-4:玩家视角收到商家身份报错 ⇒ 清文案、打标记、不 toast。
        var roleViewSuppressed = playerView() && roleViewFilter.isMerchantDenial(res && res.data, res && res.statusCode);
        if (roleViewSuppressed) roleViewFilter.suppress(res.data);
        // 登录态失效(HTTP 401、RuoYi 业务 code=401 或历史 code=2)→ 静默重登并重试一次(内部 state.retried 防死循环)。
        var bodyCode = res.data && (res.data.code == null ? null : Number(res.data.code));
        if (res.statusCode == 426 || (res.statusCode == 200 && bodyCode == 426)) {
          hideToast();
          if (!upgradePrompted) {
            upgradePrompted = true;
            showToast('版本需更新，请保存后重开');
          }
          if (typeof param.successStatusAbnormal == 'function') param.successStatusAbnormal(res.data, 426);
          else if (typeof param.fail == 'function') param.fail(res.data, 426);
          return;
        }
        var needReLogin = res.statusCode == 401 || bodyCode == 401 || bodyCode == 2;
        if (needReLogin && !state.retried) {
          state.retried = true;
          suppressComplete = true; // 本次被重试/重登失败接管,complete 不在此处双触发
          if (ownerChanged()) { stopForSessionChange(); return; }
          // 游客:不静默建号。写 → 弹登录窗,登录成功用新身份重发一次;读 → 安静地按 401 失败。
          if (!state.owner && !state.authorization && !getUserID() && !getAuthorization()) {
            var failAsGuest = function () {
              hideToast();
              if (typeof param.fail == 'function') param.fail(res.data, res && res.statusCode == 401 ? 401 : bodyCode);
              typeof param.complete == 'function' && param.complete(res && res.data ? res.data : res);
            };
            if (!isWriteRequest(param, method)) { failAsGuest(); return; }
            Promise.resolve().then(function () { return promptLogin(param); }).then(function (ok) {
              if (state.aborted || state.sessionChanged) return;
              var owner = String(getUserID() || '');
              if (ok && owner && getAuthorization()) {
                state.owner = owner;
                state.authorization = getAuthorization();
                send(param, customSiteUrl, state);
                return;
              }
              failAsGuest();
            }, function () { if (!state.aborted && !state.sessionChanged) failAsGuest(); });
            return;
          }
          reLogin(function (ok, result) {
            if (state.aborted || state.sessionChanged) return;
            // 只有自己完成的首登可把匿名请求绑定到新身份，外部换号不能冒充首登。
            if (!state.owner && ok && result && typeof result.isCurrentSession === 'function' && result.isCurrentSession()) {
              state.owner = String(getUserID() || '');
            }
            var ownSessionCleared = !ok && result && result.sessionCleared && !getUserID() && !getAuthorization();
            if ((ownerChanged() && !ownSessionCleared) || (result && result.reason === 'sessionChanged')) { stopForSessionChange(); return; }
            if (ok) {
              if (result && typeof result.isCurrentSession === 'function' && !result.isCurrentSession()) { stopForSessionChange(); return; }
              state.authorization = getAuthorization();
              state.renewalCurrent = result && typeof result.isCurrentSession === 'function'
                ? function () { return result.isCurrentSession(); } : null;
              send(param, customSiteUrl, state); // 用新 token 重试原请求(共享 state 防二次重登)
            } else {
              hideToast();
              if (typeof param.fail == 'function') {
                // HTTP 401 保留 HTTP status；HTTP 200 包裹业务 code=401/2 时保留业务鉴权码。
                // 若一律传外层 200，权限页会把重登失败误判为普通业务失败并保留旧敏感快照。
                var authFailureStatus = res && res.statusCode == 401
                  ? 401
                  : bodyCode;
                param.fail(res.data, authFailureStatus);
              } else if (!param.silentError) {
                showToast('登录已过期，请重新进入');
              }
              // 无重试:在此触发本次被抑制的 complete(一次),保证 fail 先于 complete。
              typeof param.complete == 'function' && param.complete(res && res.data ? res.data : res);
            }
          });
          return;
        }
        // #472-2:同意接口 requestId 冲突 → 换新号重发恰好一次(共享 state.consentRetried,第二次冲突不再重发)。
        if (!state.consentRetried && isConsentRequestConflict(param, res, bodyCode)) {
          var freshParam = withFreshConsentRequestId(param, newRequestId);
          if (freshParam) {
            state.consentRetried = true;
            suppressComplete = true; // 本次交给重发那一跳收尾,complete 只触发一次
            send(freshParam, customSiteUrl, state);
            return;
          }
        }
        // HTTP 状态码异常(非可重试 401)。
        if (res.statusCode && res.statusCode != 200) {
          hideToast();
          if (typeof param.successStatusAbnormal == 'function') {
            // 第二个参数是 HTTP 状态码(纯追加,既有 137 处调用点忽略它即可)。
            // 没有它,调用方分不清「4xx 明确失败」与「5xx/408 结果未知」——
            // 写操作把网关超时当成业务失败告诉用户,是 P0-2 明令禁止的那类假结论。
            param.successStatusAbnormal(res.data, res.statusCode);
          } else if (typeof param.fail == 'function') {
            // 没挂 successStatusAbnormal 时回落到 fail。原先这里只 toast 就 return,success 与
            // fail 一个都不触发 —— 调用方挂在这两个回调里的「解锁提交态」永远不执行。
            // 全仓 349 处 sendRequest 里有 137 处正是这种形态(挂了 fail/complete、没挂
            // successStatusAbnormal),其中 baoming.js 补报价那处会把 isPaying 永久锁死:
            // 已 setData({isPaying:true}) + showLoading,然后 handlePayment() 再也不会被调用,
            // 用户既看不到结果也无法重试(731 行 isPaying 守卫挡住二次点击)。
            // 回落到 fail 而不是 success:HTTP 非 200 对调用方而言就是「这次没成功」。
            param.fail(res.data, res.statusCode);
          } else if (!param.silentError && !roleViewSuppressed) {
            // HTTP 异常也必须经过统一错误映射，禁止把后端内部 API 路径透传到用户界面。
            showToast(getErrorMessage(res.data, '请求失败（' + res.statusCode + '）'));
          }
          return;
        }
        var body = res.data || {};
        if (!roleViewSuppressed && shouldAutoToast(param, body)) {
          showToast(getErrorMessage(body, '操作失败'));
        }
        typeof param.success == 'function' && param.success(body);
      },
      fail: function (res) {
        if (state.aborted || state.sessionChanged) return;
        if (sessionChanged()) { stopForSessionChange(); return; }
        responseObserved = true;
        // 传输层失败(断网/超时)可以原样重发:调用方显式声明 retry 才重试,
        // 而且只在这一支重试 —— code!=200 是业务结论,重试只会把同一个错误再问一遍。
        // ⚠️ 仅供只读请求声明。写请求重发 = 可能重复下单/重复核销。
        var left = Number(param.retry);
        if (Number.isFinite(left) && left > 0) {
          var next = Object.assign({}, param, { retry: left - 1 });
          state.transportRetryAttempt += 1;
          var attemptIndex = state.transportRetryAttempt;
          suppressComplete = true; // 这次失败交给重试那一跳收尾,complete 只在最后一跳触发一次
          scheduleRetry(function () {
            if (state.aborted || state.sessionChanged) return;
            send(next, customSiteUrl, state);
          }, 400 * attemptIndex);
          return;
        }
        hideToast();
        var failData = res && res.data ? res.data : res;
        if (failData && typeof failData === 'object') sanitizeResponseMessage(failData, '网络异常，请稍后重试');
        if (typeof param.fail == 'function') {
          param.fail(failData);
        } else {
          showToast(getErrorMessage(failData, '网络异常，请稍后重试'));
        }
      },
      complete: function (res) {
        if (state.aborted || state.sessionChanged) return;
        if (suppressComplete) return; // 被重登重试/失败分支接管,避免 complete 双触发或早于 fail
        if (!responseObserved && sessionChanged()) { stopForSessionChange(); return; }
        typeof param.complete == 'function' && param.complete(res && res.data ? res.data : res);
      }
    };
    requestOptions.timeout = param.timeout != null ? param.timeout : defaultTimeoutMs;
    var task = wxRequest(requestOptions);
    // reLogin 在测试或某些实现里可能同步重入 send()；只让当前尝试登记自己的 task，
    // 避免外层同步返回后把重试那一跳的 RequestTask 覆盖掉。
    if (!state.aborted && state.attempt === attempt) state.task = task;
    return control;
  }

  return { send: send };
}

module.exports = { createRequestClient: createRequestClient, isWriteRequest: isWriteRequest };
