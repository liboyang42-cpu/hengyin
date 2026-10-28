const app = getApp();
const { activityStatusText } = require('../../../utils/activity-status');
const policy = require('../../../utils/identity/identity-policy.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { isRecord, isRecordList } = require('../../../utils/response-shape.js');

const { reader, sameReader, exactId } = require('../../../utils/user-character.js');
const BC_ST = { 0: '草稿', 1: '待处理／部分待核', 2: '投递处理结束（非已读）', 3: '已撤' };
const ROLE = { player: '玩家', club: '俱乐部', merchant: '商家' };

function hasUsableId(value) {
  if (Number.isInteger(value)) return value > 0;
  return typeof value === 'string' && /^[1-9]\d*$/.test(value);
}

function finiteCount(value) {
  return Number.isInteger(value) && value >= 0;
}

function nonBlank(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

/** BL-80:stats = { reached, clicked }(OfficialBroadcastServiceImpl.reachStats);形状不对返回空串。 */
function broadcastStatsText(res) {
  if (!res || !(res.code == 200 || res.code == '200') || !isRecord(res.data)) return '';
  const reached = Number(res.data.reached);
  const clicked = Number(res.data.clicked);
  if (!finiteCount(reached) || !finiteCount(clicked)) return '';
  if (isRecord(res.data.deliveryCounts)) {
    const c=res.data.deliveryCounts;
    return '站内已入库 '+(c.INAPP_STORED||0)+' · 订阅渠道受理 '+(c.CHANNEL_ACCEPTED||0)+' · 明确拒绝 '+(c.REJECTED||0)+' · 未提交 '+(c.NOT_SUBMITTED||0)+' · 可恢复 '+(c.RETRYABLE||0)+' · UNKNOWN '+(c.UNKNOWN||0)+' · 历史待核 '+(c.LEGACY_UNCONFIRMED||0)+' · 频控未发 '+(c.CAPPED||0)+'；不代表已读';
  }
  return '站内触达 ' + reached + ' 人 · 点击 ' + clicked + ' 人';
}

function validPayload(data) {
  return isRecord(data)
    && isRecordList(data.events)
    && isRecordList(data.broadcasts)
    && data.events.every(item => hasUsableId(item.id))
    && data.broadcasts.every(item => hasUsableId(item.id));
}

Page({
  data: {
    events: [],
    broadcasts: [],
    loading: true,
    refreshing: false,
    hasLoaded: false,
    error: '',
    isMerchantViewer: false,
  },

  onLoad() { this._unloaded = false; this.syncViewerTheme(); this.fetch(); },
  onShow() { this._broadcastHidden=false; this._navigating = false; this.syncViewerTheme(); if (this._loaded) this.fetch(); },
  onHide() { this._broadcastHidden=true; merchantTheme.merchantPageRestore(); },
  onUnload() {
    merchantTheme.merchantPageRestore();
    this._unloaded = true;
    this._fetchSeq = (this._fetchSeq || 0) + 1;
  },

  syncViewerTheme() {
    const isMerchantViewer = policy.isMerchantView({
      role: app.getUserRole(),
      userType: app.getUserType(),
      debugView: wx.getStorageSync('debug_user_view'),
    });
    this.setData({ isMerchantViewer });
    if (isMerchantViewer) merchantTheme.merchantPageShow();
    else merchantTheme.merchantPageRestore();
  },

  onPullDownRefresh() { this.fetch(); },

  fetch() {
    const requestId = (this._fetchSeq || 0) + 1;
    this._fetchSeq = requestId;
    const actor=reader(app);
    if(this._broadcastReader&&!sameReader(app,this._broadcastReader))this.setData({events:[],broadcasts:[],hasLoaded:false});
    this._broadcastReader=actor;
    const hasLoaded = !!this.data.hasLoaded;
    if (hasLoaded) this.setData({ loading: false, refreshing: true });
    else this.setData({ loading: true, refreshing: false, error: '' });
    const isCurrent = () => !this._unloaded && sameReader(app,actor) && requestId === this._fetchSeq;
    app.sendRequest({
      // 下拉刷新的收圈绑在真实完成上;非下拉场景下 stopPullDownRefresh 是 no-op
      complete() { wx.stopPullDownRefresh(); },
      url: '/api/official/my-published', method: 'GET', hideLoading: true, silentError: true,
      success: (res) => {
        if (!isCurrent()) return;
        const data = res && res.data;
        if (res && (res.code == 200 || res.code == '200') && validPayload(data)) {
          this.setData({
            events: data.events.map(this._decoEvent),
            broadcasts: data.broadcasts.map(this._decoBc),
            loading: false,
            refreshing: false,
            hasLoaded: true,
            error: '',
          });
          this._loaded = true;
        } else this._setFetchFailure(res, hasLoaded, requestId);
      },
      fail: (res) => { if(isCurrent())this._setFetchFailure(res, hasLoaded, requestId); },
      successStatusAbnormal: (res) => { if(isCurrent())this._setFetchFailure(res, hasLoaded, requestId); },
    });
  },

  _setFetchFailure(res, hasLoaded, requestId) {
    if (this._unloaded || requestId !== this._fetchSeq) return;
    const message = app.getRequestErrorMessage(res, '发布记录加载失败');
    if (hasLoaded) this.setData({ loading: false, refreshing: false });
    else this.setData({ loading: false, refreshing: false, error: message });
  },

  _decoEvent(event) {
    const status = Number.isInteger(event.status) ? activityStatusText(event.status, 'owner') : '';
    return Object.assign({}, event, {
      _title: nonBlank(event.title, '未命名官方活动'),
      _city: nonBlank(event.city, '—'),
      _category: nonBlank(event.category, ''),
      _st: status || '状态待确认',
      // 只给 V2 合同活动挂主办控制台入口;是否真是本场主办由控制台页的 publish-check 决定
      _isV2: Number(event.contractVersion) >= 2,
    });
  },

  _decoBc(b) {
    const audience = typeof b.audience === 'string'
      ? b.audience.split(',').map(item => item.trim()).filter(Boolean)
      : [];
    const audienceKnown = audience.length > 0 && audience.every(item => ROLE[item]);
    return Object.assign({}, b, {
      _title: nonBlank(b.title, '未命名官方通知'),
      _aud: audienceKnown ? audience.map(item => ROLE[item]).join(' / ') : '—',
      _mode: b.copyMode === 2 ? '分开发' : (b.copyMode === 1 ? '一起发' : '—'),
      _st: Number.isInteger(b.status) && BC_ST[b.status] ? BC_ST[b.status] : '状态待确认',
      _reach: finiteCount(b.reachedCount) ? '站内触达 ' + b.reachedCount + ' 人' : '—',
      _city: nonBlank(b.city, '—'),
    });
  },

  // BL-80 通知复盘:只读 reached/clicked;失败如实写在原触达行,再点一次重试。
  openBroadcastStats(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.index);
    const item = Number.isInteger(index) ? this.data.broadcasts[index] : null;
    if (!item || !hasUsableId(item.id) || item._statsLoading) return;
    const id = exactId(item.id);if(!id)return;
    const actor=reader(app);
    const key = 'broadcasts[' + index + ']';
    const sameItem = () => !this._unloaded && sameReader(app,actor) && this.data.broadcasts[index] && String(this.data.broadcasts[index].id) === id;
    this.setData({ [key + '._statsLoading']: true, [key + '._statsText']: '正在读取复盘…' });
    app.sendRequest({
      url: '/api/official/broadcast/' + encodeURIComponent(String(id)) + '/stats',
      method: 'GET', hideLoading: true, silentError: true, autoErrorToast: false,
      success: (res) => {
        if (!sameItem()) return;
        const text = broadcastStatsText(res);
        this.setData({
          [key + '._statsLoading']: false,
          [key + '._canRecover']: !!(res && res.code == 200 && res.data && res.data.canRecoverInapp),
          [key + '._statsText']: text || (app.getRequestErrorMessage(res, '复盘没读到') + '，点一下重试'),
        });
      },
      fail: (res) => {
        if (!sameItem()) return;
        this.setData({
          [key + '._statsLoading']: false,
          [key + '._statsText']: app.getRequestErrorMessage(res, '网络连接失败') + '，点一下重试',
        });
      },
    });
  },

  recoverBroadcast(e) {
    const index=Number(e.currentTarget.dataset.index),item=this.data.broadcasts[index];
    if(!item||!item._canRecover)return;const id=exactId(item.id);if(!id)return;
    const actor=reader(app);
    require('../../../utils/modal.js').show({title:'安全恢复原投递',content:'仅沿原收件人和原意图恢复。UNKNOWN及历史待核不会重发。',success:r=>{
      if(!r.confirm||this._unloaded||this._broadcastHidden||!sameReader(app,actor))return;
      app.sendRequest({url:'/api/official/broadcast/'+id+'/recover',method:'POST',hideLoading:true,autoErrorToast:false,
       success:()=>{if(sameReader(app,actor))this.openBroadcastStats({currentTarget:{dataset:{index}}});},
       fail:()=>{if(sameReader(app,actor))this.openBroadcastStats({currentTarget:{dataset:{index}}});},
       successStatusAbnormal:()=>{if(sameReader(app,actor))this.openBroadcastStats({currentTarget:{dataset:{index}}});}});
    }});
  },

  openEvent(e) { if (this._navigating) return; this._navigating = true; // 跳转中不响应连点,免得叠两层详情
    wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + e.currentTarget.dataset.id, complete: () => { this._navigating = false; } });
  },

  // 草稿在公开详情里不可见,发布等主办操作统一从控制台进
  openOrganizer(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset.id;
    if (!hasUsableId(id) || this._navigating) return; // 跳转中不响应连点,免得叠两层控制台
    this._navigating = true;
    wx.navigateTo({ url: '/pages/activity/official-organizer/index?id=' + id, complete: () => { this._navigating = false; } });
  },
});
