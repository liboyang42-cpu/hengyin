// a1-v3-21(2026-11-10 业主「开启定位最好进入游戏就提示 后面就不需要提示了 而且最好有提示怎么打开」):
//   · 定位说明只在进入这一局时弹一次(index.js 进页那一处 requestPlayLocation);之后任何入口再调都不再弹,
//     只在系统里已经授权时静默取一次位置(例如玩家去设置里打开后回来)。
//   · 系统拒绝(微信不会再弹授权框)时给一张「怎么打开」:去设置 → 位置信息;打开后回来静默开始定位。
//   · 问过且没开定位时,站点卡的主按钮是「扫码到达」(直接绑原有 onScanArrive);别的入口点到达只给一句轻提示,不再回到定位说明。
// index.js 的 UI 门禁基线按行号登记,这里放方法,index.js 只在原行上挂钩(不增删行)。
const cyModal = require('../../../utils/modal.js');
const cyToast = require('../../../utils/toast.js');

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
      if (typeof wx.getSetting === 'function') {
        wx.getSetting({
          success: (r) => {
            const granted = !!(r && r.authSetting && r.authSetting['scope.userLocation'] === true);
            if (granted && !this.data.gpsOk) this._relocateSilently();
          },
          fail() {},
        });
      }
      return true;
    },
    /** 系统里已授权时静默取一次:requestPlayLocation 同步调 cyModal.show,这一次换成「已同意」直接往下走,调完立刻还原 */
    _relocateSilently() {
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

module.exports = { data, methods, confirmNow, deniedBySystem, GUIDE };
