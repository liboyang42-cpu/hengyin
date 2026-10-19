const app = getApp();
const merchantTheme = require('../../utils/merchant-theme.js');
const datetime = require('../../utils/datetime.js');
const modal = require('../../utils/modal.js');
const cyToast = require('../../utils/toast.js');
const { COUPON_TYPE_LABELS } = require('../../utils/coupon-form.js');
const { shapeCouponSummary } = require('./coupon-summary.js');

function scalarText(value) {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  return '—';
}

// 展示实际有效时间；时区与后端券有效期统一为中国时间。
function dayText(value) {
  const parts = datetime.chinaParts(value);
  if (!parts) return '—';
  return datetime.formatDayDots(value) + ' ' + String(parts.hours).padStart(2, '0')
    + ':' + String(parts.minutes).padStart(2, '0');
}

// 状态徽标:与后端 sms_coupon.status 对齐(3=平台手动失效 / 4=商家已停发)。
const COUPON_STATUS_TEXTS = { 0: '未开始', 1: '进行中', 2: '已结束', 3: '已失效', 4: '已停发' };
// 失效/停发是「这张券为什么不可用」的强提示,用 danger;其余状态保持中性。
const COUPON_STATUS_TONES = { 3: 'danger', 4: 'danger' };

function statusText(status) {
  return COUPON_STATUS_TEXTS[Number(status)] || '';
}

function statusTone(status) {
  return COUPON_STATUS_TONES[Number(status)] || '';
}

// 仅进行中(以及历史遗留的未开始)的券可停发 —— 与后端 CAS 的 status in (0,1) 同一口径。
function canStop(status) {
  const value = Number(status);
  return value === 0 || value === 1;
}

function finiteCount(value) {
  if (value === null || value === undefined || value === '') return null;
  const count = Number(value);
  return Number.isFinite(count) && count >= 0 ? count : null;
}

function normalizeCoupon(item) {
  if (!item || item.id === null || item.id === undefined) return null;
  const name = scalarText(item.name);
  if (name === '—') return null;
  const publishCount = finiteCount(item.publishCount);
  const receiveCount = finiteCount(item.receiveCount);
  return Object.assign({}, item, {
    name,
    description: scalarText(item.description),
    startTime: scalarText(item.startTime),
    endTime: scalarText(item.endTime),
    startDay: dayText(item.startTime),
    endDay: dayText(item.endTime),
    code: scalarText(item.code),
    // couponType=-1(存量无类型)不加 1 取下标,否则落到 [0]='请选择' 冒充表单占位。
    couponTypeText: Number(item.couponType) < 0
      ? '优惠券'
      : (COUPON_TYPE_LABELS[Number(item.couponType) + 1] || '优惠券'),
    statusText: statusText(item.status),
    statusTone: statusTone(item.status),
    canStop: canStop(item.status),
    // UI-04(2026-09-18):券张数没取到显示 0,不再显示横杠
    publishCount: publishCount === null ? 0 : publishCount,
    receiveCount: receiveCount === null ? 0 : receiveCount,
    remainCount: publishCount === null || receiveCount === null
      ? 0
      : Math.max(publishCount - receiveCount, 0),
  });
}

Page({
  /**
   * 页面的初始数据
   */
  data: {
    list: [],//列表数组
    loadState: 'loading',
    errorMsg: '',
    operationScope: '',
    summaryItems: [], // BL-77 商家券领取/核销统计,无数据为空数组即不展示
    tpShow: false, // 弹窗显示状态
    currentCoupon: {} // 当前选中的优惠券
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    this.setData({ operationScope: options && options.scope === 'MERCHANT' ? 'MERCHANT' : '' });
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    merchantTheme.merchantPageShow(); this._navigating = false;
    var that = this
    that.getList()
    that.loadSummary()
  },

  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { merchantTheme.merchantPageRestore(); },

  /**
   * 用户点击右上角分享
   */

  // 获取列表
  getList: function () {
    var that = this;
    // 过期回包丢弃:onShow/重试/停发后回读并发时只认最后一次
    const seq = that._listSeq = (that._listSeq || 0) + 1;
    that.setData({ loadState: 'loading', errorMsg: '' });
    app.sendRequest({
      hideLoading: true,
      url: '/api/coupon/mypublishlist',
      autoErrorToast: false, // 失败由整页 auto-back fail 半屏讲原因,不再叠 toast
      method: "POST",
      data: {
        keyword: '',
        is_select: 0,
        scope: that.data.operationScope
      },
      success: function (res) {
        if (seq !== that._listSeq) return;
        if ((res.code == "200" || res.code === 200) && Array.isArray(res.data)) {
          const list = res.data.map(normalizeCoupon).filter(Boolean);
          that.setData({
            list: list,
            loadState: 'ready',
          })
        } else {
          that.setData({ loadState: 'error', errorMsg: '服务返回异常，请稍后再试' });
        }
      },
      fail: function () {
        if (seq !== that._listSeq) return;
        that.setData({ loadState: 'error', errorMsg: '网络可能不稳定，请稍后再试' });
      },
      complete: function () {

      }
    })
  },

  retryLoad() { this.getList(); this.loadSummary(); },

  // BL-77:只在商家口径下读统计;失败或没有券就不展示,不阻塞列表。
  loadSummary() {
    if (this.data.operationScope !== 'MERCHANT') return;
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      autoErrorToast: false,
      url: '/api/merchant/coupon-summary',
      method: 'POST',
      success: (res) => this.setData({ summaryItems: shapeCouponSummary(res) || [] }),
      fail: () => this.setData({ summaryItems: [] }),
    });
  },

  // 点击优惠券
  tpClick: function (e) {
    const item = e.currentTarget.dataset.item;
    this.setData({
      tpShow: true,
      currentCoupon: item
    });
  },

  // 关闭弹窗
  tpClose: function () {
    this.setData({
      tpShow: false,
      currentCoupon: {}
    });
  },

  // 停发自己的券:二次确认(不可撤销)→ POST /api/coupon/stop → 成功回读列表。
  // 只停新增发放/领取,已领的照常可用可核销;结果未知(网络)也回读,不让人对着旧状态再点。
  stopCoupon: function (e) {
    const fromRow = e && e.currentTarget && e.currentTarget.dataset ? e.currentTarget.dataset.item : null;
    const item = fromRow || this.data.currentCoupon;
    if (!item || !item.id || !item.canStop) return;
    // 在途闸:确认后到回包前不再打开确认、不再发停发
    if (this._stopping) return;
    const that = this;
    modal.show({
      dangerKey: 'merchant.coupon.stop',
      dangerParams: { name: item.name },
      success(r) {
        if (!r.confirm || that._stopping) return;
        that._stopping = true;
        app.sendRequest({
          url: '/api/coupon/stop',
          method: 'POST',
          data: that.data.operationScope
            ? { couponId: item.id, scope: that.data.operationScope }
            : { couponId: item.id },
          success(res) {
            that._stopping = false;
            if (res.code == '200' || res.code === 200) {
              cyToast.success('已停发「' + item.name + '」');
              that.setData({ tpShow: false, currentCoupon: {} });
              that.getList();
            } else {
              cyToast.error((res && res.msg) || '停发失败，请稍后重试');
            }
          },
          fail(err) {
            that._stopping = false;
            cyToast.error((err && err.msg) || '停发结果待确认，已刷新列表');
            that.getList();
          },
        });
      },
    });
  },
  GoAdd: function () {
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ complete: () => { this._navigating = false; },
      url: '/subpackageMember/couponInfo/couponInfo'
        + (this.data.operationScope === 'MERCHANT' ? '?scope=MERCHANT' : '')
    });
  }
})
