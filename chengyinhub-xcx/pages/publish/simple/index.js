// 城瘾 · 创建城市路线(简易版)—— 简单 AI 共创版
// 流程:一句话灵感 → AI 主题草稿 → 逐点位地图确认 POI → 进专业编辑器 fabu 细化
//
// ⚠️ 本页**不就地发布**。它曾经挂着整套「站点编辑 + 预览 + 存草稿 + 直接发布」链路
// (onPublish / onSaveDraft / validate / buildPayload / 地图重绘 / tip …),但页面上
// 一个绑定都没有 —— wxml 只剩 AI 那条线,那块 JS 是纯孤儿(2026-09 审查 #14 删除)。
// 它的反馈通道本身就是坏的:tip() 写 data.toast,而 wxml 从不渲染 toast 字段,
// 真要重新接线必须先换成 cy-toast / cy-inline-error 通道。
// 发布落点在 fabu;简易版成功埋点因此也归 fabu 一份(simple 不再自行 track)。
const app = getApp();
const { memberId, retryLogin, waitForInitialSession } = require('../../../utils/session/login-reload.js');
function aiDraftStorageKey() {
  const memberId = app.getUserID && app.getUserID();
  return memberId === null || memberId === undefined || memberId === ''
    ? '' : 'ai_topic_draft:m' + memberId;
}
const { readReducedMotion } = require('../../../utils/motion-preference.js');

// W10b #22:一次起草 = 一个 requestId。结果没确认前只回读这一个,不另起一次、也不拿额度涨落当证据。
function aiRequestStorageKey() {
  const memberId = app.getUserID && app.getUserID();
  return memberId === null || memberId === undefined || memberId === ''
    ? '' : 'ai_topic_draft_request:m' + memberId;
}
function makeRequestId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (char) {
    const random = Math.floor(Math.random() * 16);
    return (char === 'x' ? random : ((random & 0x3) | 0x8)).toString(16);
  });
}
const AI_PENDING_CODES = ['AI_DRAFT_IN_PROGRESS', 'AI_DRAFT_OUTCOME_UNKNOWN', 'AI_DRAFT_STATE_UNAVAILABLE'];

const MAX_AI_PLAN_NODES = 3;

Page({
  data: {
    creatorLoginRequired: false,
    // 导航让位实高:模拟器上 env(safe-area-inset-top) 恒为 0,--cy-safe-top 会矮一半、把自带头压进导航区
    statusBarHeight: 44,
    navBarHeight: 44,
    // 简单创建固定走：一句话 → AI 草稿 → POI 确认 → 专业编辑器。
    aiIdea: '',
    aiGenerating: false,
    aiError: '',
    aiPlan: null,
    aiPlanNodes: [],
    canEnterAiEditor: false,
    aiTraceId: '',
    aiQuotaLoaded: false,
    // 上一次起草结果未确认:生成键改为「查看结果」,只回读原 requestId。
    aiDraftPending: false,
    aiRemaining: 0, reducedMotion: false,
    aiPrompts: ['老城建筑与咖啡', '周末亲子探险', '雨天室内漫游'],
    // 模式:2 自由 / 1 定向
    mode: 2,
    operationScope: '',
  },

  onCreatorLogin() {
    return retryLogin(this, app, this._resumeCreatorLoad);
  },
  _resumeCreatorLoad() { if (this._creatorOwnerChanged()) return; this.onLoad(this._creatorEntryOptions || {}); },
  _creatorOwnerChanged() {
    if (!this._creatorOwner || this._creatorOwner === memberId(app)) return false;
    this._invalidateAiRequests();
    this.setData({ creatorLoginRequired: true });
    wx.switchTab({ url: '/pages/template/index' });
    return true;
  },

  onLoad(options) {
    this._unloaded = false;
    this._invalidateAiRequests();
    this._aiPending = null;
    this._aiPendingKey = '';
    this.setData({ aiDraftPending: false });
    this._creatorEntryOptions = options || {};
    if (!memberId(app)) {
      this.setData({ creatorLoginRequired: true });
      waitForInitialSession(this, app, this._resumeCreatorLoad);
      return;
    }
    this._creatorOwner = memberId(app);
    this.setData({ creatorLoginRequired: false });
    const mode = Number(options && options.mode) === 2 ? 2 : 1;
    // 从模板货架点「配置」过来时带模板名当初始想法:不接住的话这一页是一张白纸,
    // 用户点的是「配置某个主题」,落地却什么都没带过来。手打进来的照旧是空。
    const seedIdea = decodeURIComponent(String((options && options.idea) || '')).trim().slice(0, 60);
    this.setData({
      mode,
      aiIdea: seedIdea,
      operationScope: options && options.scope === 'MERCHANT' ? 'MERCHANT' : '',
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44,
      navBarHeight: (app.globalData && app.globalData.navBarHeight) || 44, reducedMotion: readReducedMotion(),
    });
    this.loadAiQuota();
    this._restoreAiPending();
  },

  onShow() { this._creatorOwnerChanged(); },
  onUnload() { this._unloaded = true; this._invalidateAiRequests(); },

  // ===== 简单 AI 对话 =====
  _invalidateAiRequests() {
    this._aiEpoch = (this._aiEpoch || 0) + 1;
    this._aiRequest = null;
    this._aiQuotaRequest = null;
    if (!this._unloaded) this.setData({ aiGenerating: false });
  },
  _beginAiRequest(quota) {
    const slot = quota ? '_aiQuotaRequest' : '_aiRequest';
    const request = { slot, owner: memberId(app), epoch: this._aiEpoch,
      key: quota ? '' : this._aiPendingKey, handled: false };
    this[slot] = request;
    // 旧额度回包不能覆盖新一次起草/回读之后的额度。
    if (!quota) this._aiQuotaRequest = null;
    return request;
  },
  _isAiRequestCurrent(request) {
    return !this._unloaded && this[request.slot] === request && request.epoch === this._aiEpoch;
  },
  _acceptAiResponse(request, response) {
    if (!this._isAiRequestCurrent(request) || request.handled) return false;
    // 成功回调的认证由 transport 校验；其自有续登允许 token 更新，不能在这里比较旧 token。
    if (request.owner !== memberId(app) || (response && response.reason === 'sessionChanged')) {
      this._invalidateAiRequests();
      return false;
    }
    request.handled = true;
    return true;
  },
  _finishAiRequest(request) {
    if (!this._isAiRequestCurrent(request)) return;
    if (request.owner !== memberId(app)) { this._invalidateAiRequests(); return; }
    this[request.slot] = null;
    if (request.slot === '_aiRequest') this.setData({ aiGenerating: false });
  },
  loadAiQuota() {
    if (this._unloaded || this._creatorOwnerChanged()) return;
    if (!memberId(app)) { this.setData({ creatorLoginRequired: true }); return; }
    const request = this._beginAiRequest(true);
    app.sendRequest({
      url: '/api/ai/theme/draft/quota',
      method: 'POST',
      hideLoading: true,
      header: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (!this._acceptAiResponse(request, res)) return;
        const data = res && res.data;
        if ((res.code === 200 || res.code === '200') && data && data.limited) {
          this.setData({ aiQuotaLoaded: true, aiRemaining: Number(data.remaining) || 0 });
        }
      },
      fail: (res) => { this._acceptAiResponse(request, res); },
      complete: () => this._finishAiRequest(request)
    });
  },
  onAiIdeaInput(e) { this.setData({ aiIdea: e.detail.value, aiError: '' }); },
  useAiPrompt(e) { this.setData({ aiIdea: e.currentTarget.dataset.text || '', aiError: '' }); },
  generateAiPlan() {
    if (this._unloaded || this._creatorOwnerChanged()) return;
    if (!memberId(app)) { this.setData({ creatorLoginRequired: true }); return; }
    if (this.data.aiGenerating) return;
    const pending = this._aiPending;
    // 结果未确认:先回读那一次。只有服务端已明说「结果无法确认」且用户改了灵感,才算放弃它另起一次。
    if (pending && !(pending.unknown && (this.data.aiIdea || '').trim() !== pending.idea)) {
      this.checkAiDraftRequest();
      return;
    }
    const idea = (this.data.aiIdea || '').trim();
    if (!idea) { this.setData({ aiError: '先说说你想做什么路线。' }); return; }
    // 配额已用完就不要再发一趟注定被后端拒掉的请求 —— 页面顶部明明写着「还可生成 0 次」,
    // 输入框和发送钮却照常可用(2026-08-19 B51 实拍)。后端那道闸保留,这里只是不让用户白跑。
    if (this.data.aiQuotaLoaded && Number(this.data.aiRemaining) <= 0) {
      this.setData({ aiError: '这一轮的 AI 起草次数用完了，可以先手动填，或稍后再试。' });
      return;
    }
    const requestId = makeRequestId();
    this._setAiPending({ requestId, idea, mode: this.data.mode }, aiRequestStorageKey());
    const request = this._beginAiRequest(false);
    this.setData({ aiGenerating: true, aiError: '' });
    app.sendRequest({
      url: '/api/ai/theme/draft',
      method: 'POST',
      hideLoading: true,
      data: JSON.stringify({ idea, productType: this.data.mode, requestId }),
      header: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (!this._acceptAiResponse(request, res)) return;
        if (res && AI_PENDING_CODES.indexOf(res.errorCode) >= 0) {
          this._markAiPendingUnknown(res.errorCode === 'AI_DRAFT_OUTCOME_UNKNOWN', request.key);
          this.setData({ aiError: res.msg || '上一次起草的结果还没确认，稍后点「查看结果」。' });
          return;
        }
        // 服务端已给出明确结论(成功或失败):这个 requestId 收尾,下一次是新的一次起草。
        this._setAiPending(null, request.key);
        this._applyAiDraft(res, !!(res && res.replayed));
      },
      fail: (res) => {
        if (!this._acceptAiResponse(request, res)) return;
        // 请求可能已经到了服务端:结果未知,锁住重试,只能回读这一次。
        this._markAiPendingUnknown(false, request.key);
        this.setData({ aiError: '网络暂时不可用，上一次起草的结果还没确认，稍后点「查看结果」。' });
      },
      complete: () => this._finishAiRequest(request)
    });
  },
  /** 只读回读上一次起草(GET /api/ai/theme/draft/request):不调模型、不动额度。 */
  checkAiDraftRequest() {
    if (this._unloaded || this._creatorOwnerChanged()) return;
    const pending = this._aiPending;
    if (!pending || this.data.aiGenerating) return;
    const request = this._beginAiRequest(false);
    this.setData({ aiGenerating: true, aiError: '' });
    app.sendRequest({
      url: '/api/ai/theme/draft/request',
      method: 'GET',
      hideLoading: true,
      autoErrorToast: false,
      data: { requestId: pending.requestId },
      success: (res) => {
        if (!this._acceptAiResponse(request, res)) return;
        const st = res && (res.code === 200 || res.code === '200') && res.data;
        const state = st && st.requestId === pending.requestId ? st.state : '';
        if (state === 'SUCCEEDED') {
          this._setAiPending(null, request.key);
          this._applyAiDraft({ code: 200, data: st.result }, true);
        } else if (state === 'FAILED') {
          this._setAiPending(null, request.key);
          this.setData({ aiError: st.message || 'AI 起草没有成功，可以再试一次。' });
        } else if (state === 'NOT_FOUND' || state === 'UNKNOWN') {
          // NOT_FOUND 也可能是超过24小时保留期，不能证明原请求未送达。
          this._markAiPendingUnknown(true, request.key);
          this.setData({ aiError: '上一次起草的结果暂时无法确认。稍后再查看；改一下灵感再点「生成」会作为新的一次起草，可能再用一次额度。' });
        } else if (state === 'PROCESSING') {
          this.setData({ aiError: '上一次起草还在进行中，稍后点「查看结果」。' });
        } else {
          this.setData({ aiError: '暂时查不到上一次起草的结果，稍后再试。' });
        }
      },
      fail: (res) => {
        if (this._acceptAiResponse(request, res)) this.setData({ aiError: '网络暂时不可用，稍后点「查看结果」。' });
      },
      complete: () => this._finishAiRequest(request)
    });
  },
  _setAiPending(pending, key) {
    this._aiPending = pending;
    this._aiPendingKey = key;
    this.setData({ aiDraftPending: !!pending });
    if (!key) return;
    try {
      if (pending) wx.setStorageSync(key, pending);
      else wx.removeStorageSync(key);
    } catch (e) { /* 只是跨重开的便利;读写失败时本页内存里的锁照样生效 */ }
  },
  _markAiPendingUnknown(confirmedUnknown, key) {
    if (!this._aiPending) return;
    this._setAiPending(Object.assign({}, this._aiPending, { unknown: !!confirmedUnknown || !!this._aiPending.unknown }), key);
  },
  _restoreAiPending() {
    const key = aiRequestStorageKey();
    let pending = null;
    try { pending = key ? wx.getStorageSync(key) : null; } catch (e) { pending = null; }
    if (!pending || typeof pending.requestId !== 'string' || !pending.requestId) return;
    this._aiPending = pending;
    this._aiPendingKey = key;
    this.setData({ aiDraftPending: true, aiIdea: pending.idea || this.data.aiIdea });
    this.checkAiDraftRequest();
  },
  /** 只认带完整草稿的明确成功;结构不合格 fail-closed,已有草稿与点位原样保留。 */
  _applyAiDraft(res, replayed) {
    const draft = res && res.data && res.data.draft;
    const nodes = draft && Array.isArray(draft.nodes) ? draft.nodes : [];
    const hasUsableNodes = nodes.every((node) => node && typeof node === 'object'
      && String(node.merchantName || node.roleText || node.task || '').trim());
    if ((res.code !== 200 && res.code !== '200') || !draft || !String(draft.title || '').trim() || !nodes.length || !hasUsableNodes) {
      this.setData({ aiError: (res && res.msg) || 'AI 生成的主题不完整，请换一种说法重试' });
      return;
    }
    if (nodes.length > MAX_AI_PLAN_NODES) {
      this.setData({ aiError: 'AI 草稿最多只能包含不超过 3 个节点，请换一种说法重试。' });
      return;
    }
    this.setData({
      aiPlan: { title: draft.title, subtitle: draft.subtitle || '', storyline: draft.storyline || '' },
      aiPlanNodes: nodes.map((node, index) => ({
        name: node.merchantName || node.roleText || node.task || ('节点 ' + (index + 1)),
        description: node.task || node.roleText || '',
        address: '', longitude: '', latitude: '', confirmed: false
      })),
      aiTraceId: res.data.traceId || '',
      // 回放的是当时那一份结果,其中的剩余额度已过时:不拿它当现值,改为重新读额度。
      aiQuotaLoaded: !replayed && res.data.remainingQuota != null ? true : this.data.aiQuotaLoaded,
      aiRemaining: !replayed && res.data.remainingQuota != null ? Number(res.data.remainingQuota) : this.data.aiRemaining
    }, () => this.refreshEnterEditorState());
    if (replayed) this.loadAiQuota();
  },
  refreshEnterEditorState() {
    const plan = this.data.aiPlan || {};
    const nodes = this.data.aiPlanNodes || [];
    const canEnterAiEditor = !!String(plan.title || '').trim()
      && nodes.length > 0
      && nodes.every((node) => node.confirmed && node.longitude && node.latitude);
    if (canEnterAiEditor !== this.data.canEnterAiEditor) this.setData({ canEnterAiEditor });
  },
  onAiPlanTitleInput(e) {
    this.setData({ 'aiPlan.title': e.detail.value }, () => this.refreshEnterEditorState());
  },
  onAiPlanStoryInput(e) { this.setData({ 'aiPlan.storyline': e.detail.value }); },
  onAiNodeNameInput(e) { this.setData({ ['aiPlanNodes[' + e.currentTarget.dataset.index + '].name']: e.detail.value }); },
  onAiNodeStoryInput(e) { this.setData({ ['aiPlanNodes[' + e.currentTarget.dataset.index + '].description']: e.detail.value }); },
  confirmAiNodePoi(e) {
    const index = Number(e.currentTarget.dataset.index);
    wx.choosePoi({
      success: (poi) => {
        if (!poi || poi.longitude == null || poi.latitude == null) { this.setData({ aiError: '请选择一个有坐标的具体地点。' }); return; }
        this.setData({
          ['aiPlanNodes[' + index + '].name']: poi.name || this.data.aiPlanNodes[index].name,
          ['aiPlanNodes[' + index + '].address']: poi.address || poi.name || '',
          ['aiPlanNodes[' + index + '].longitude']: String(poi.longitude),
          ['aiPlanNodes[' + index + '].latitude']: String(poi.latitude),
          ['aiPlanNodes[' + index + '].confirmed']: true,
          aiError: ''
        }, () => this.refreshEnterEditorState());
      }
    });
  },
  enterAiEditor() {
    const plan = this.data.aiPlan || {};
    const nodes = this.data.aiPlanNodes || [];
    if (!plan.title || !nodes.length) { this.setData({ aiError: '请先生成主题草稿。' }); return; }
    if (nodes.some((node) => !node.confirmed || !node.longitude || !node.latitude)) {
      this.setData({ aiError: '请逐个在地图中确认地点后再继续。' });
      return;
    }
    const draft = {
      name: plan.title.trim(), subtitle: plan.subtitle || '', description: plan.storyline || '',
      productType: this.data.mode, aiSimple: true, aiTraceId: this.data.aiTraceId,
      chapters: [{ name: '路线', description: plan.storyline || '', imgArr: '', nodes: nodes.map((node, index) => ({
        name: node.name || ('节点 ' + (index + 1)), description: node.description || '', address: node.address,
        longitude: node.longitude, latitude: node.latitude, nodeTime: 30, businessTime: '', sortID: index + 1,
        templateId: 0, templateName: '', hookText: '', cardHookLong: '', fragmentText: ''
      })) }]
    };
    wx.removeStorageSync('ai_topic_draft');
    const key = aiDraftStorageKey();
    if (!key) { this.setData({ aiError: '请先登录再继续编辑。' }); return; }
    wx.setStorageSync(key, draft);
    wx.navigateTo({
      url: '/pages/publish/fabu/index?mode=' + this.data.mode
        + (this.data.operationScope ? '&scope=MERCHANT' : '')
    });
  },
});
