// a1-v3-21(2026-11-10 业主「开启定位最好进入游戏就提示 后面就不需要提示了 而且最好有提示怎么打开」):
//   · 定位说明只在进入这一局时弹一次(index.js 进页那一处 requestPlayLocation);之后任何入口再调都不再弹,
//     拒绝后只走扫码，点「开启定位」后才可恢复读取。
//   · 系统拒绝(微信不会再弹授权框)时给一张「怎么打开」:去设置 → 位置信息;打开后回来静默开始定位。
//   · 问过且没开定位时,站点卡的主按钮是「扫码到达」(直接绑原有 onScanArrive);别的入口点到达只给一句轻提示,不再回到定位说明。
// index.js 的 UI 门禁基线按行号登记,这里放方法,index.js 只在原行上挂钩(不增删行)。
const cyModal = require('../../../utils/modal.js');
const cyToast = require('../../../utils/toast.js');
const arrivalReading = require('./arrival-reading.js');

/** 开局定位读数超过这个年纪就先重取一次再判 300 米(站着不动的玩家,最近一次定位事件可能是几分钟前) */
const STALE_FIX_MS = 45000;
const NEED_FIX_CODES = ['ARRIVE_LOCATION_MISSING', 'ARRIVE_ACCURACY_MISSING', 'ARRIVE_LOCATION_INVALID', 'ARRIVE_LOCATION_STALE'];
const START_REFUSAL_CODES = ['ARRIVE_TOO_FAR', 'ARRIVE_LOCATION_STALE'];

function codesOf(r) {
  return r ? [r.reasonCode, r.reason, r.errorCode].filter((c) => typeof c === 'string') : [];
}
/** 服务端因为「没带定位 / 定位过期 / 定位无效」拒绝:重新定位后原样重试即可 */
function needsFreshFix(r) { return codesOf(r).some((c) => NEED_FIX_CODES.includes(c)); }
/** 开局拒绝(太远 / 定位过期):同一个「重新定位」弹窗 */
function isStartRefusal(r) { return codesOf(r).some((c) => START_REFUSAL_CODES.includes(c)); }
/** 读数是否过期;重取失败后给 failedAt,短时间内不再重复重取 */
function isStaleFix(loc, now, failedAt) {
  if (!loc) return false;
  const at = Number(loc.capturedAt);
  if (!Number.isFinite(at) || at <= 0) return false;
  const t = now == null ? Date.now() : now;
  if (failedAt && t - failedAt < STALE_FIX_MS) return false;
  return t - at > STALE_FIX_MS;
}

/** 跳过说明弹窗、按「已同意」继续:只给静默重取位置用 */
function confirmNow(opts) { if (opts && typeof opts.success === 'function') opts.success({ confirm: true, cancel: false }); }

/** 系统层拒绝(而不是玩家点了「先用扫码」)的 errMsg */
function deniedBySystem(err) {
  const msg = String((err && err.errMsg) || '');
  return /auth|deny|denied|authorize|permission/i.test(msg);
}

const GUIDE = {
  title: '打开定位',
  content: '点右上角「···」→「设置」→「位置信息」，选「仅在使用小程序期间」。',
  confirmText: '去设置',
  cancelText: '先用扫码',
};

function methods() {
  const api = {
    /** requestPlayLocation 的前置:返回 true = 这一局已经问过,不再弹说明 */
    _locationAskedOnce() {
      if (this._locSilent) return false;
      if (!this._locAsked) { this._locAsked = true; this.setData({ locAsked: true }); return false; }
      this._recheckLocationSetting();
      return true;
    },
    /** 系统里已经授权(例如玩家从「···→设置」回来)而本页还没定位:静默取一次;没授权就放掉等着的开局动作 */
    _recheckLocationSetting(toastIfOff) {
      if (this._playLocationDeclined) { this._pendingStart = null; if (toastIfOff) this._locationDeclinedToast(); return; }
      if (this._locationRequesting) return;
      if (typeof wx.getSetting !== 'function') { if (toastIfOff) this._locationDeclinedToast(); return; }
      wx.getSetting({
        success: (r) => {
          const granted = !!(r && r.authSetting && r.authSetting['scope.userLocation'] === true);
          if (granted && !this.data.gpsOk) this._relocateSilently();
          else if (!granted) { this._pendingStart = null; if (toastIfOff) this._locationDeclinedToast(); }
        },
        fail() { if (toastIfOff) this._locationDeclinedToast(); },
      });
    },
    /** 开局动作(开游戏 / 到达 / 提交)缺定位时先挂起,定位拿到后自动接着做;换局就作废 */
    _setPendingStart(resume) {
      const key = this._sessionKey();
      this._pendingStart = () => { if (key === this._sessionKey()) resume(); };
    },
    _runPendingStart() {
      const run = this._pendingStart;
      this._pendingStart = null;
      if (typeof run === 'function') run();
    },
    /** 站点卡「开启定位」:系统已拒绝 → 去设置;从没问过系统 → 重新给一次用途说明 */
    onEnableLocation() {
      this._playLocationDeclined = false;
      if (typeof wx.getSetting !== 'function') return;
      wx.getSetting({
        success: (r) => {
          const auth = (r && r.authSetting) || {};
          if (auth['scope.userLocation'] === true) { this._relocateSilently(); return; }
          if (auth['scope.userLocation'] === false && typeof wx.openSetting === 'function') {
            wx.openSetting({
              success: (s) => { if (s && s.authSetting && s.authSetting['scope.userLocation']) this._relocateSilently(); },
              fail() {},
            });
            return;
          }
          this._locAsked = false;
          this.requestPlayLocation();
        },
        fail() {},
      });
    },
    /** 开局动作缺定位:没问过就问;问过没开就查一次系统设置,已授权接着做,否则轻提示 */
    _locateThen(resume) {
      this._setPendingStart(resume);
      if (!this._locAsked) { this.requestPlayLocation(); return; }
      this._recheckLocationSetting(true);
    },
    /** 开局读数过期就先重取一次再接着做;重取失败照旧用旧读数(服务端再判) */
    _freshStartFix(done) {
      if (this._playLocationDeclined) { done(); return; }
      if (!isStaleFix(this._loc, Date.now(), this._staleFixFailedAt) || typeof wx.getLocation !== 'function') { done(); return; }
      const key = this._sessionKey();
      wx.getLocation({
        type: 'gcj02', isHighAccuracy: true,
        success: (loc) => {
          if (key !== this._sessionKey()) return;
          this._loc = arrivalReading.stamp({ longitude: loc.longitude, latitude: loc.latitude, accuracy: loc.accuracy });
          done();
        },
        fail: () => { if (key !== this._sessionKey()) return; this._staleFixFailedAt = Date.now(); done(); },
      });
    },
    /** 开局闸:在打开首站玩法之前就判定位 / 距离,不让玩家答完才被拒。返回 true = 已拦下(resume 会在条件满足后接着做) */
    _startGate(node, resume) {
      if (!node || node.done || !node.lat || !node.lng || Number(this.data.mode) !== 1 || this.data.isPreview || this.data.isMock
        || !this._startLocationNeeded() || this._isStoryGame(node) || this._advancedReadyNodeId === node.nodeId) return false;
      if (!this.data.gpsOk) {
        if (this._locAsked) return false;   // 问过没开:走扫码到达
        this._locateThen(resume); return true;
      }
      if (isStaleFix(this._loc, Date.now(), this._staleFixFailedAt)) { this._freshStartFix(resume); return true; }
      const tooFar = this._startTooFar(node);
      if (tooFar) { this._startRefusal(tooFar, resume); return true; }
      return false;
    },
    /** 系统里已授权时静默取一次:requestPlayLocation 同步调 cyModal.show,这一次换成「已同意」直接往下走,调完立刻还原 */
    _relocateSilently() {
      if (this._playLocationDeclined) return;
      const show = cyModal.show;
      this._locSilent = true;
      cyModal.show = confirmNow;
      try { this.requestPlayLocation(); } finally { cyModal.show = show; this._locSilent = false; }
    },
    /** 问过、没开定位时点「到达」:不再弹说明,只给一句轻提示;站点卡的主按钮此时已是「扫码到达」 */
    _locationDeclinedToast() {
      cyToast('定位未开启，可扫码到达');
    },
    /** 取位置失败:系统拒绝时给「怎么打开」,去设置打开后回来静默开始定位 */
    _locationGuide(err) {
      if (!deniedBySystem(err) || this._locGuideShown) return false;
      this._locGuideShown = true;
      const that = this;
      cyModal.show(Object.assign({}, GUIDE, {
        success(r) {
          if (!r || !r.confirm || typeof wx.openSetting !== 'function') return;
          wx.openSetting({
            success(s) { if (s && s.authSetting && s.authSetting['scope.userLocation']) that._relocateSilently(); },
            fail() {},
          });
        },
      }));
      return true;
    },
  };
  return api;
}

const data = { locAsked: false };

module.exports = { data, methods, confirmNow, deniedBySystem, GUIDE, STALE_FIX_MS, needsFreshFix, isStartRefusal, isStaleFix };
