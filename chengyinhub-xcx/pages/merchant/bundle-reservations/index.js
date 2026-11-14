// 自由探索套票：商家看未来 7 天预约、对已过时未到店的预约记爽约。
// 读 GET /api/free-explore/bundle/merchant/reservations(from,to 毫秒)，记爽约 POST .../merchant/reservation/{id}/no-show。
// 权限与归属由服务端按 VERIFY 岗位解析店主后判定；记爽约只消耗该权益，不是核销、不产生分成。
const app = getApp();
const toast = require('../../../utils/toast.js');
const modal = require('../../../utils/modal.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../utils/merchant-access-policy.js');

const DAY = 24 * 3600 * 1000;
const STATUS_TEXT = { BOOKED: '待到店', CANCELLED: '已取消', WAIVED: '已豁免取消', LATE_CANCELLED: '晚取消', NO_SHOW: '未到店', FULFILLED: '已使用', MERCHANT_EXITED: '商家已退出' };

function isOk(res) { return !!(res && (res.code === 200 || res.code === '200')); }
function exactId(v) { return (typeof v === 'string' || typeof v === 'number') && /^[1-9]\d{0,18}$/.test(String(v)) ? String(v) : ''; }
function errorText(res, fallback) { return (app.getRequestErrorMessage && app.getRequestErrorMessage(res, fallback)) || fallback; }

// startAt 可能是毫秒数或 'yyyy-MM-dd HH:mm:ss'：统一成毫秒，按东八区解析
function startMs(value) {
  if (typeof value === 'number') return value;
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(String(value || ''));
  return m ? Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4] - 8, +m[5]) : NaN;
}
function pad(n) { return n < 10 ? '0' + n : String(n); }
function timeText(ms) {
  const d = new Date(ms + 8 * 3600 * 1000);
  return (d.getUTCMonth() + 1) + '月' + d.getUTCDate() + '日 ' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes());
}

function shape(list) {
  if (!Array.isArray(list)) return null;
  const now = Date.now();
  const rows = [];
  for (const row of list) {
    const id = exactId(row && row.id);
    const ms = startMs(row && row.startAt);
    if (!id || !Number.isFinite(ms) || !STATUS_TEXT[row.status]) return null;
    rows.push({ id, ms, timeText: timeText(ms), status: row.status, statusText: STATUS_TEXT[row.status],
      canNoShow: row.status === 'BOOKED' && ms <= now });
  }
  return rows;
}

Page({
  data: { statusBarHeight: 20, navBarHeight: 44, pageState: 'loading', errorMessage: '', rows: [], busyId: '' },

  onLoad() {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    this._merchantAccess = inactiveAccess();
    this.setData({ statusBarHeight: info.statusBarHeight || 20, navBarHeight: (app.globalData && app.globalData.navBarHeight) || 44 });
    this.loadAccess();
  },
  onShow() { merchantTheme.merchantPageShow(); },
  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { this._unloaded = true; merchantTheme.merchantPageRestore(); },

  onNavBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/merchant/index/index' });
  },

  loadAccess() {
    this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true, autoErrorToast: false, url: '/api/merchant/access/me', method: 'POST',
      success: (res) => {
        if (!isOk(res) || !res.data || typeof res.data !== 'object') { this.setData({ pageState: 'error', errorMessage: errorText(res, '经营身份加载失败') }); return; }
        const access = normalizeMerchantAccess(res.data);
        this._merchantAccess = access;
        if (!access.active || !access.canVerify) { this.setData({ pageState: 'no-permission', rows: [] }); return; }
        this.loadList();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' })
    });
  },

  retry() { if (this._merchantAccess && this._merchantAccess.canVerify) this.loadList(); else this.loadAccess(); },

  onPullDownRefresh() {
    const state = this.data.pageState;
    if (this._merchantAccess && this._merchantAccess.canVerify && (state === 'ready' || state === 'empty')) { this.loadList(true); return; }
    wx.stopPullDownRefresh();
    if (state !== 'loading') this.retry();
  },

  loadList(fromPull) {
    const seq = this._seq = (this._seq || 0) + 1;
    if (!fromPull) this.setData({ pageState: 'loading', errorMessage: '' });
    const from = Date.now() - 2 * DAY;
    const done = () => { if (fromPull) wx.stopPullDownRefresh(); return seq === this._seq && !this._unloaded; };
    app.sendRequest({
      hideLoading: true, autoErrorToast: false, url: '/api/free-explore/bundle/merchant/reservations', method: 'GET',
      data: { from, to: from + 9 * DAY },
      success: (res) => {
        if (!done()) return;
        const rows = isOk(res) ? shape(res.data) : null;
        if (!rows) {
          if (fromPull) toast('刷新失败，稍后再试');
          else this.setData({ pageState: 'error', errorMessage: errorText(res, '预约加载失败') });
          return;
        }
        this.setData({ rows, pageState: rows.length ? 'ready' : 'empty', errorMessage: '' });
      },
      fail: (res) => {
        if (!done()) return;
        if (fromPull) toast('刷新失败，稍后再试');
        else this.setData({ pageState: 'error', errorMessage: errorText(res, '网络异常，请重试') });
      }
    });
  },

  markNoShow(e) {
    const id = exactId(e.currentTarget.dataset.id);
    const row = this.data.rows.find(r => r.id === id);
    if (!row || !row.canNoShow || this.data.busyId) return;
    modal.show({
      title: '记为未到店？', content: row.timeText + ' 的预约将扣除这次权益，不产生核销与分成，记录后不能撤销。',
      success: (r) => {
        if (!r.confirm || this._unloaded || this.data.busyId) return;
        this.setData({ busyId: id });
        app.sendRequest({
          hideLoading: true, autoErrorToast: false, url: '/api/free-explore/bundle/merchant/reservation/' + id + '/no-show', method: 'POST', data: {},
          success: (res) => {
            if (this._unloaded) return;
            this.setData({ busyId: '' });
            if (isOk(res)) { toast('已记为未到店'); this.loadList(true); return; }
            toast(errorText(res, '记录失败，请刷新后确认'));
            this.loadList(true);
          },
          fail: () => {
            if (this._unloaded) return;
            this.setData({ busyId: '' });
            toast('结果未确认，请刷新查看');
            this.loadList(true);
          }
        });
      }
    });
  }
});
