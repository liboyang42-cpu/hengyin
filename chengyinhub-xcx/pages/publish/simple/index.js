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
function aiDraftStorageKey() {
  const memberId = app.getUserID && app.getUserID();
  return memberId === null || memberId === undefined || memberId === ''
    ? '' : 'ai_topic_draft:m' + memberId;
}
const { readReducedMotion } = require('../../../utils/motion-preference.js');

const MAX_AI_PLAN_NODES = 3;

Page({
  data: {
    // 导航让位实高:模拟器上 env(safe-area-inset-top) 恒为 0,--cy-safe-top 会矮一半、把自带头压进导航区
    statusBarHeight: 44,
    navBarHeight: 44,
    // 简单创建固定走：一句话 → AI 草稿 → POI 确认 → 专业编辑器。
    aiIdea: '',
    aiGenerating: false,
    aiError: '',
    aiUnknown: false,
    aiPlan: null,
    aiPlanNodes: [],
    canEnterAiEditor: false,
    aiTraceId: '',
    aiQuotaLoaded: false,
    aiRemaining: 0, reducedMotion: false,
    aiPrompts: ['老城建筑与咖啡', '周末亲子探险', '雨天室内漫游'],
    // 模式:2 自由 / 1 定向
    mode: 2,
    operationScope: '',
  },

  onLoad(options) {
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
    const key = aiDraftStorageKey();
    const pending = key && wx.getStorageSync && wx.getStorageSync(key + ':request');
    if (pending && typeof pending.requestId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(pending.requestId)) {
      this._pendingAiDraft = pending;
      this.setData({ mode: Number(pending.mode) === 2 ? 2 : 1, aiUnknown: true });
      this.recoverAiPlan();
    }
  },

  onUnload() { this._aiDisposed = true; },

  // ===== 简单 AI 对话 =====
  loadAiQuota() {
    app.sendRequest({
      url: '/api/ai/theme/draft/quota',
      method: 'POST',
      hideLoading: true,
      header: { 'Content-Type': 'application/json', 'Authorization': app.getAuthorization() },
      success: (res) => {
        const data = res && res.data;
        if ((res.code === 200 || res.code === '200') && data && data.limited) {
          this.setData({ aiQuotaLoaded: true, aiRemaining: Number(data.remaining) || 0 });
        }
      }
    });
  },
  onAiIdeaInput(e) { if (this.data.aiUnknown) return; this.setData({ aiIdea: e.detail.value, aiError: '' }); },
  useAiPrompt(e) { if (this.data.aiUnknown) return; this.setData({ aiIdea: e.currentTarget.dataset.text || '', aiError: '' }); },
  generateAiPlan() {
    const idea = (this.data.aiIdea || '').trim();
    if (!idea) { this.setData({ aiError: '先说说你想做什么路线。' }); return; }
    if (this.data.aiGenerating || this.data.aiUnknown) return;
    // 配额已用完就不要再发一趟注定被后端拒掉的请求 —— 页面顶部明明写着「还可生成 0 次」,
    // 输入框和发送钮却照常可用(2026-08-19 B51 实拍)。后端那道闸保留,这里只是不让用户白跑。
    if (this.data.aiQuotaLoaded && Number(this.data.aiRemaining) <= 0) {
      this.setData({ aiError: '这一轮的 AI 起草次数用完了,可以先手动填,或稍后再试。' });
      return;
    }
    const owner = aiDraftStorageKey();
    if (!owner) { this.setData({ aiError: '请先登录后生成草稿。' }); return; }
    const requestId = 'td-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + Math.random().toString(36).slice(2);
    this._pendingAiDraft = { requestId, mode: this.data.mode };
    try { wx.setStorageSync(owner + ':request', this._pendingAiDraft); }
    catch (e) { this.setData({ aiError: '生成请求未能保存，请稍后重试。' }); return; }
    this.setData({ aiGenerating: true, aiError: '' });
    app.sendRequest({
      url: '/api/ai/theme/draft',
      method: 'POST',
      hideLoading: true,
      data: JSON.stringify({ requestId, idea, productType: this.data.mode }),
      header: { 'Content-Type': 'application/json', 'Authorization': app.getAuthorization() },
      success: (res) => { if (this.isCurrentAiRequest(requestId, owner)) this.applyAiDraftResult(res, requestId); },
      fail: () => { if (this.isCurrentAiRequest(requestId, owner)) this.markAiUnknown(); },
      successStatusAbnormal: () => { if (this.isCurrentAiRequest(requestId, owner)) this.markAiUnknown(); },
      complete: () => { if (this.isCurrentAiRequest(requestId, owner)) this.setData({ aiGenerating: false }); }
    });
  },
  isCurrentAiRequest(requestId, owner) {
    return !this._aiDisposed && owner === aiDraftStorageKey() && this._pendingAiDraft
      && this._pendingAiDraft.requestId === requestId;
  },
  recoverAiPlan() {
    const pending = this._pendingAiDraft;
    const owner = aiDraftStorageKey();
    if (!pending || !owner || this.data.aiGenerating) return;
    const requestId = pending.requestId;
    this.setData({ aiGenerating: true, aiUnknown: true, aiError: '正在查询原生成结果…' });
    app.sendRequest({
      url: '/api/ai/theme/draft/status', method: 'POST', hideLoading: true,
      data: JSON.stringify({ requestId }),
      header: { 'Content-Type': 'application/json', 'Authorization': app.getAuthorization() },
      success: (res) => { if (this.isCurrentAiRequest(requestId, owner)) this.applyAiDraftResult(res, requestId); },
      fail: () => { if (this.isCurrentAiRequest(requestId, owner)) this.markAiUnknown(); },
      successStatusAbnormal: () => { if (this.isCurrentAiRequest(requestId, owner)) this.markAiUnknown(); },
      complete: () => { if (this.isCurrentAiRequest(requestId, owner)) this.setData({ aiGenerating: false }); },
    });
  },
  applyAiDraftResult(res, requestId) {
    const result = res && res.data;
    if (!res || String(res.code) !== '200' || !result || result.requestId !== requestId) { this.markAiUnknown(); return; }
    if (result.outcomeStatus === 'FAILED') {
      try { wx.setStorageSync(aiDraftStorageKey() + ':request', ''); } catch (e) { /* The server retains this terminal state. */ }
      this._pendingAiDraft = null;
      this.setData({ aiUnknown: false, aiGenerating: false, aiError: result.safeText || '原生成任务已失败，可重新描述后生成；次数以当前额度为准。' });
      this.loadAiQuota();
      return;
    }
    if (result.outcomeStatus !== 'SUCCEEDED') { this.markAiUnknown(); return; }

        const draft = res && res.data && res.data.draft;
        const nodes = draft && Array.isArray(draft.nodes) ? draft.nodes : [];
        const hasUsableNodes = nodes.every((node) => node && typeof node === 'object'
          && String(node.merchantName || node.roleText || node.task || '').trim());
        if ((res.code !== 200 && res.code !== '200') || !draft || !String(draft.title || '').trim() || !nodes.length || !hasUsableNodes) {
          this.setData({ aiError: (res && res.msg) || 'AI 返回的主题不完整，请查询原结果。', aiUnknown: true });
          return;
        }
        if (nodes.length > MAX_AI_PLAN_NODES) {
          this.setData({ aiError: 'AI 草稿最多只能包含不超过 3 个点位，请查询原结果。', aiUnknown: true });
          return;
        }
        const key = aiDraftStorageKey();
        try { wx.setStorageSync(key + ':request', ''); } catch (e) { /* A saved request can safely be queried again. */ }
        this._pendingAiDraft = null;
        this.setData({
          aiUnknown: false, aiGenerating: false, aiError: '',
          aiPlan: { title: draft.title, subtitle: draft.subtitle || '', storyline: draft.storyline || '' },
          aiPlanNodes: nodes.map((node, index) => ({
            name: node.merchantName || node.roleText || node.task || ('点位 ' + (index + 1)),
            description: node.task || node.roleText || '',
            address: '', longitude: '', latitude: '', confirmed: false
          })),
          aiTraceId: res.data.traceId || '',
          aiQuotaLoaded: res.data.remainingQuota != null ? true : this.data.aiQuotaLoaded,
          aiRemaining: res.data.remainingQuota != null ? Number(res.data.remainingQuota) : this.data.aiRemaining
        }, () => this.refreshEnterEditorState());

  },
  markAiUnknown() {
    this.setData({ aiUnknown: true, aiError: '生成结果待确认，请查询原生成结果；暂不能再次生成，已有草稿仍可编辑。' });
    this.loadAiQuota();
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
        name: node.name || ('点位 ' + (index + 1)), description: node.description || '', address: node.address,
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
