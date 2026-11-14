// 旧提现入口保留给历史路由；2026-09-15 起不再进银行卡表单，改弹平台客服微信线下处理。
const merchantTheme = require('../../../utils/merchant-theme.js');
const withdrawCs = require('../../../utils/withdraw-cs.js');

const PERK_ORGANIZER_NOTE = '票款由主办方收取；店铺如需结算，请联系主办方线下处理';

// 只有全权益核销(服务端 noCashReason 即该句)且没有其它到账记录的店铺,才提示找主办方。
function onlyPerkRedemptions(res) {
  const rows = res && res.data && Array.isArray(res.data.rows) ? res.data.rows : null;
  return !!rows && rows.length > 0 && rows.every((row) => row
    && row.settlementState === 'NOT_APPLICABLE'
    && typeof row.noCashReason === 'string' && row.noCashReason.indexOf(PERK_ORGANIZER_NOTE) === 0);
}

Page({
  data: {
    statusBarHeight: getApp().globalData.statusBarHeight,
    navBarHeight: getApp().globalData.navBarHeight,
    perkOnlyNote: '',
  },

  onShow() {
    merchantTheme.merchantPageShow();
    this._loadPerkOnlyNote();
  },

  _loadPerkOnlyNote() {
    const seq = (this._noteSeq || 0) + 1;
    this._noteSeq = seq;
    getApp().sendRequest({
      hideLoading: true,
      url: '/api/merchant/finance/redemptions',
      method: 'POST',
      data: JSON.stringify({ pageNum: 1, pageSize: 20, filter: 'all' }),
      header: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (seq !== this._noteSeq) return;
        this.setData({ perkOnlyNote: onlyPerkRedemptions(res) ? PERK_ORGANIZER_NOTE : '' });
      },
      fail: () => {},
    });
  },

  onHide() { this._noteSeq = (this._noteSeq || 0) + 1; merchantTheme.merchantPageRestore(); },
  onUnload() { merchantTheme.merchantPageRestore(); },

  goBankWithdraw() { withdrawCs.showWithdrawCsPopup(); },
  goBack() { wx.navigateBack({ delta: 1 }); },
});
