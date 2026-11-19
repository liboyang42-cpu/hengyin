const app = getApp();
const { recoverReadSession } = require('../../../utils/session/login-reload.js');
const { decideMerchantGate, decideClubGate } = require('../../../utils/access-gate.js');
const { denyCtaText, followDenyCta } = require('../../../utils/merchant-no-permission.js');

// 页面级守卫:读 access/me,没权限就盖住页面内容;state 事件报给页面。
// merchant:原地无权限态(锁 + 一行「当前身份不能…」+ 一个动作:非成员「申请入驻」/ 在营成员「返回」),不自动退出。
// club:保持 fail 半屏讲原因,2s 自动出去。
Component({
  properties: {
    kind: { type: String, value: 'merchant' },   // merchant | club | login
    need: { type: String, value: '' },           // merchant: canXxx 字段名;club: club:xxx 权限串
    clubId: { type: null, value: '' },
    activityId: { type: null, value: '' },
    // merchant deny「返回」在栈底时 reLaunch 的去处;不传 = 商家工作台
    backUrl: { type: String, value: '' },
  },
  data: { state: 'checking', reason: '', top: 0, _sheet: false, denyCta: '' },
  observers: {
    'clubId, activityId'() { if (this.data.kind === 'club') this.check(); },
  },
  lifetimes: {
    detached() { this._detached = true; this._epoch = (this._epoch || 0) + 1; },
    attached() {
      const pages = getCurrentPages();
      this._page = pages[pages.length - 1];
      const g = app.globalData || {};
      this.setData({ top: (g.statusBarHeight || 0) + (g.navBarHeight || 0) });
      if (this.data.kind !== 'club') this.check();
    },
  },
  pageLifetimes: {
    hide() { this._hidden = true; },
    show() { this._hidden = false; if (this._readSessionChanged) { this._readSessionChanged = false; this.check(); } },
  },
  methods: {
    check() {
      const kind = this.data.kind;
      const owner = String(app.getUserID && app.getUserID() || '');
      const epoch = (this._epoch || 0) + 1;
      this._epoch = epoch;
      if (!owner || owner === '0') { this.setData({ state: 'login', reason: '', _sheet: false }); return; }
      if (kind === 'login') { this.setData({ state: 'allow' }); return; }
      if (kind === 'club' && !this.data.clubId && !this.data.activityId) return;   // 还没拿到范围,等 observer
      const that = this;
      const settle = (raw) => {
        if (that._detached || epoch !== that._epoch || owner !== String(app.getUserID && app.getUserID() || '')) return;
        // 商家会话已失效(access/me 回「请先登录」)走登录门,同样盖住内容,但给「登录」而不是「申请入驻」
        const decision = kind === 'merchant' && raw.state === 'deny' && raw.expired ? { state: 'login', reason: raw.reason } : raw;
        const denied = decision.state === 'deny';
        const sheet = denied && kind !== 'merchant';   // 商家 deny 原地讲清,不弹 2s 自动退出的半屏
        // 面板先随盖层渲染出来,再把 show 翻成 true —— 不赌初值能不能触发 result-sheet 的 2s 计时
        that._denyMember = denied && decision.member === true;
        // 商家 deny 原地停住:同页 cy-error 的 auto-back(页面自己的 403 加载失败)不许再把人带走
        if (kind === 'merchant' && that._page) that._page.__cyDenyInPlace = denied;
        that.setData({
          state: denied ? 'deny' : (decision.state === 'login' ? 'login' : decision.state === 'allow' ? 'allow' : 'unknown'),
          reason: denied || decision.state === 'login' ? decision.reason : '',
          denyCta: denied && kind === 'merchant' ? denyCtaText(that._denyMember) : '',
          _sheet: false,
        }, () => { if (sheet) that.setData({ _sheet: true }); });
        that.triggerEvent('state', { state: that.data.state, access: decision.state === 'allow' && decision.access ? decision.access : null }); // access:允许时附上已归一的岗位权限,页面按它显隐入口,不再另拉一次
      };
      // 两条分支各自写死 /api 字面量:UI-GATE-0 的 U1 要能枚举出请求路径,变量拼的核不了。
      // 不传 silentError:request-client 的静默分支都是「没挂 fail 才弹」,本组件 fail 与
      // successStatusAbnormal 都挂了;autoErrorToast:false 关掉业务失败自动 toast,
      // 可见反馈由本组件的 deny 屏承担(unknown 时刻意不拦,后端仍然拦)。
      const responseOf = (res, statusCode) => statusCode ? Object.assign({}, res || {}, { code: Number(statusCode) }) : res;
      const loginFailure = res => Number(res && (res.code || res.statusCode)) === 401;
      const onMerchant = (res, statusCode) => { const reply = responseOf(res, statusCode); settle(loginFailure(reply) ? { state: 'login', reason: '请登录后查看经营信息' } : decideMerchantGate(reply, that.data.need)); };
      const onClub = (res, statusCode) => { const reply = responseOf(res, statusCode); settle(loginFailure(reply) ? { state: 'login', reason: '请登录后查看俱乐部信息' } : decideClubGate(reply, that.data.clubId, that.data.need)); };
      // request-client 静默重登失败时以 fail(data, 401) 交回;那不是「响应坏了」,是真的没身份,
      // 必须拦并给去处(审核清单 §6.6「401 后给去处」)。其余失败仍判 unknown,不误锁人。
      const onFail = (data, statusCode) => {
        if (that._detached || epoch !== that._epoch) return;
        if (recoverReadSession(that, app, data, function (anonymous) {
          this._epoch++; this.setData({ state: anonymous ? 'login' : 'checking', reason: anonymous ? '请登录后查看' : '', _sheet: false });
        }, that.check, statusCode)) return;
        const code = Number(statusCode || data && (data.statusCode || data.code));
        settle(code === 401 ? { state: 'login', reason: '请登录后查看' }
          : code === 403 ? { state: 'deny', reason: '当前身份不能查看这项内容', member: false } : { state: 'unknown' });
      };
      if (kind === 'club') {
        app.sendRequest({
          url: '/api/club/access/me',
          method: 'POST',
          header: { 'Content-Type': 'application/json' },
          data: JSON.stringify({ clubId: this.data.clubId || undefined, activityId: this.data.activityId || undefined }),
          hideLoading: true,
          autoErrorToast: false,
          success: onClub,
          successStatusAbnormal: onClub,
          fail: onFail,
        });
        return;
      }
      app.sendRequest({
        url: '/api/merchant/access/me',
        method: 'POST',
        hideLoading: true,
        autoErrorToast: false,
        success: onMerchant,
        successStatusAbnormal: onMerchant,
        fail: onFail,
      });
    },
    onLogin() {
      if (this._loginPending) return;
      this._loginPending = true;
      const login = app.getUserID && app.getUserID() && typeof app.reLogin === 'function' ? app.reLogin()
        : typeof app.firstLogin === 'function' ? app.firstLogin() : null;
      Promise.resolve(login).then(result => {
        this._loginPending = false;
        if (this._detached || !(app.getUserID && app.getUserID()) || (result && result.ok === false)) return;
        const page = this._page || {};
        const opts = page.options || {};
        const query = Object.keys(opts).map(k => k + '=' + encodeURIComponent(opts[k])).join('&');
        if (page.route) wx.redirectTo({ url: '/' + page.route + (query ? '?' + query : ''), fail: () => this.check() });
        else this.check();
      }).catch(() => { this._loginPending = false; });
    },
    // 商家原地无权限态的唯一动作:非成员去申请入驻,在营成员返回(栈底 reLaunch 商家工作台)
    onDenyCta() {
      if (this.data.state !== 'deny' || this.data.kind !== 'merchant') return;
      followDenyCta(this, this._denyMember === true, this.data.backUrl || '/pages/merchant/index/index');
    },
    onExit() {
      // 到点收起与点遮罩可能先后到;同页 cy-error auto-back 也可能同时在弹 —— 按页只走一次
      const page = this._page || {};
      if (page.__cyAutoBacked) return;
      page.__cyAutoBacked = true;
      const pages = getCurrentPages();
      if (pages.length > 1) { wx.navigateBack(); return; }
      wx.reLaunch({ url: this.data.kind === 'club' ? '/pages/index/index' : '/pages/merchant/index/index' });
    },
  },
});
