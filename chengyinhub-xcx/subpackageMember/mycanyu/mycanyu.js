const app = getApp();
const { summarizeOrderState } = require('../../utils/order-status.js');
const { getScene } = require('../../utils/scene-registry.js');
const { isRecordList } = require('../../utils/response-shape.js');

function registrationId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : '';
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return '';
  const digits = value.replace(/^0+/, '');
  return digits && (digits.length < 19 || (digits.length === 19 && digits <= '9223372036854775807')) ? value : '';
}
function currentOwner() { return typeof app.getUserID === 'function' ? String(app.getUserID() || '') : ''; }

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'not_started', label: '未开始' },
  { key: 'in_progress', label: '进行中' },
  { key: 'completed', label: '已完成' }
];

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight || 20,
    navBarHeight: app.globalData.navBarHeight || 44,
    tabs: TABS,
    activeTab: 'all',
    list: [],
    sceneStack: [],
    sceneCurrent: null,
    firstLoading: true,
    refreshing: false,
    errorMsg: ''},

  // 页内隐私弹窗:没有它 app.js 会回退到 navigateTo(/pages/privacy/index) 把本页盖住。

  showPrivacyGate() {

    this.setData({ privacyGateShow: true });

  },

  onPrivacyGateSettled() {

    this.setData({ privacyGateShow: false });

  },


  onLoad() {
    this._participationHidden = false;
    this._participationDisposed = false;
    this.getList();
  },

  onShow() {
    if (this._participationDisposed) return;
    const reload = this._participationHidden || this._participationOwner !== currentOwner();
    this._participationHidden = false;
    if (reload) this.getList();
  },
  onHide() {
    this._participationHidden = true;
    this._participationEpoch = (this._participationEpoch || 0) + 1;
  },
  onUnload() {
    this._participationDisposed = true;
    this._participationEpoch = (this._participationEpoch || 0) + 1;
  },

  onBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: '/pages/member/index/index' });
  },

  onRetry() {
    this.getList();
  },

  formatDate(value) {
    if (!value) return '';
    const match = String(value).match(/(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/);
    if (!match) return '';
    return match[2].padStart(2, '0') + '.' + match[3].padStart(2, '0');
  },

  formatDateRange(startDate, endDate) {
    const start = this.formatDate(startDate);
    const end = this.formatDate(endDate);
    return start && end ? start + ' - ' + end : (start || end || '时间待定');
  },

  normalize(item) {
    const isTopic = Number(item.ownerType) === 1;
    const source = isTopic ? (item.cmsTopic || {}) : (item.cmsActivity || {});
    const orderState = summarizeOrderState(item);
    const isFreeExplore = Number(item.purchaseKind) === 3 || Number(source.productType) === 2;
    return Object.assign({}, item, {
      sourceName: source.name || '活动信息待补充',
      sourceCover: source.imgUrl || source.imgArr || '',
      sourceAddress: source.address || source.addressName || '地点待定',
      dateText: this.formatDateRange(source.startDate, source.endDate),
      stateKey: orderState.key,
      stateText: orderState.text,
      verificationText: item.verificationStatus === 1 || item.verificationStatus === '1' ? '已核销'
        : item.verificationStatus === 0 || item.verificationStatus === '0' ? '未核销' : '核销状态待确认',
      voucherAvailable: !!registrationId(item.id),
      stateVariant: orderState.key === 'not_started' ? 'info'
        : orderState.key === 'in_progress' ? 'success' : 'neutral',
      typeLabel: isTopic ? (isFreeExplore ? '自由探索' : '城市定向') : '线下活动'
    });
  },

  renderList() {
    const all = this.allList || [];
    const list = this.data.activeTab === 'all'
      ? all
      : all.filter(item => item.stateKey === this.data.activeTab);
    this.setData({ list: list });
  },

  getList(fromPullDown) {
    if (this._participationHidden || this._participationDisposed) return;
    const that = this;
    const owner = currentOwner();
    if (this._participationOwner !== owner) {
      this.allList = undefined;
      this._participationListOwner = undefined;
      this.setData({ list: [], sceneStack: [], sceneCurrent: null });
    }
    this._participationOwner = owner;
    const epoch = this._participationEpoch = (this._participationEpoch || 0) + 1;
    const current = () => !that._participationHidden && !that._participationDisposed
      && epoch === that._participationEpoch && owner === currentOwner();
    const stopPullAfterComplete = fromPullDown === true;
    const hasSnapshot = Array.isArray(this.allList);
    const finishLoadError = (res, fallback) => {
      if (!current()) return;
      const message = app.getRequestErrorMessage(res, fallback);
      if (hasSnapshot) {
        that.setData({
          firstLoading: false,
          refreshing: false,
          errorMsg: '',
        });
      } else {
        that.setData({
          firstLoading: false,
          refreshing: false,
          errorMsg: message,
        });
      }
    };
    this.setData({
      firstLoading: !hasSnapshot,
      refreshing: hasSnapshot,
      errorMsg: '',
    });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/registration/my-joined',
      method: 'POST',
      success(res) {
        if (!current()) return;
        // R9-14:服务端回包 code 可能是数字 200(与 app.js 同口径),只认字符串会把正常记录判成失败。
        if (res && (res.code === '200' || res.code === 200) && isRecordList(res.data)) {
          that.allList = res.data.map(item => that.normalize(item));
          that._participationListOwner = owner;
          that.renderList();
          that.setData({ firstLoading: false, refreshing: false, errorMsg: '' });
          return;
        }
        finishLoadError(res, '参与记录加载失败，请重试');
      },
      successStatusAbnormal(res) {
        finishLoadError(res, '服务暂时不可用，请稍后再试');
      },
      fail(res) {
        finishLoadError(res, '网络异常，请重试');
      },
      complete() {
        if (!current()) return;
        if (stopPullAfterComplete && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh();
      }
    });
  },

  switchTab(e) {
    const key = e.detail && e.detail.key !== undefined ? e.detail.key : e.currentTarget.dataset.index;
    this.setData({ activeTab: key }, () => this.renderList());
  },

  // 参与详情是三级弹窗,由本页托管;不再 navigateTo 压新页面栈。
  goOrder(e) {
    const id = e.currentTarget.dataset.id;
    if (!id) return;
    const scene = getScene('member-participation-detail', { id });
    if (!scene) return;
    this.setData({ sceneStack: [scene], sceneCurrent: scene });
  },
  viewVoucher(e) {
    if (this._participationHidden || this._participationDisposed || !currentOwner()
        || this._participationListOwner !== currentOwner()) return;
    const id = registrationId(e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id);
    if (!id) return;
    const row = (this.data.list || []).find(item => registrationId(item.id) === id);
    if (!row) return;
    wx.navigateTo({ url: '/subpackageMember/signup/index?focusId=' + encodeURIComponent(row.id) });
  },
  blockSceneTouch() {},
  closeScene() {
    this.setData({ sceneStack: [], sceneCurrent: null });
  },

  onPullDownRefresh() {
    this.getList(true);
  },

  onShareAppMessage() {
    return { title: '我的参与', path: '/subpackageMember/mycanyu/mycanyu' };
  }
});
