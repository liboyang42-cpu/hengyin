// 简单页只生成草稿；发布仍只在 fabu。持久身份来自专属 journal，不保存原输入。
const app = getApp();
const { memberId, retryLogin, waitForInitialSession } = require('../../../utils/session/login-reload.js');
const journal = require('../../../utils/ai-theme-draft-journal.js');
const modal = require('../../../utils/modal.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const STATES = ['PREPARED', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED'];
const VIEW_FIELDS = 'contextAvailable,executeBefore,productType,reasonCode,replayed,requestId,result,resultAvailable,resultExpiresAt,state';
const ITEM_FIELDS = 'createdAt,cursor,executeBefore,prepareKey,productType,reasonCode,requestId,resultExpiresAt,state';
const LABELS = { PREPARED: '已准备', PROCESSING: '处理中', UNKNOWN: '结果待确认', SUCCEEDED: '已成功', FAILED: '已失败' };
const MAX_AI_PLAN_NODES = 3;
function terminal(record) { return record && ['SUCCEEDED', 'FAILED'].indexOf(record.state) >= 0; }
function makeRequestId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, char => {
    const random = Math.floor(Math.random() * 16);
    return (char === 'x' ? random : ((random & 3) | 8)).toString(16);
  });
}
function recordObject(value) { return !!value && typeof value === 'object' && !Array.isArray(value); }
function reason(value) { return value === null || (typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,79}$/.test(value)); }
function instant(value) { return typeof value === 'string' && /T.*Z$/.test(value) && Number.isFinite(Date.parse(value)); }
function optionalInstant(value) { return value === null || instant(value); }
// Long游标不经过Number转换；不安全JSON数字已经失真，必须拒绝。
function cursor(value) {
  const text = typeof value === 'string' ? value : '';
  return /^[1-9][0-9]{0,18}$/.test(text) && (text.length < 19 || text <= '9223372036854775807') ? text : null;
}
function older(left, right) { return left.length < right.length || (left.length === right.length && left < right); }
function aiDraftStorageKey(owner) { return journal.key(owner) ? 'ai_topic_draft:m' + owner : ''; }

Page({
  data: {
    creatorLoginRequired: false, statusBarHeight: 44, navBarHeight: 44,
    aiIdea: '', aiPlanProductType: null, aiPlanModeKnown: false, aiGenerating: false, aiError: '', aiPlan: null, aiPlanNodes: [], canEnterAiEditor: false,
    aiTraceId: '', aiQuotaLoaded: false, aiDraftPending: false, aiRemaining: 0, reducedMotion: false,
    aiIntentLabel: '', aiIntentIdentity: '', aiCanContinue: false, aiCanConfirmNew: false, aiHistoryPreviewLabel: '',
    aiRecoveryLoading: false, aiHistoryShow: false, aiHistoryItems: [],
    aiPrompts: ['老城建筑与咖啡', '周末亲子探险', '雨天室内漫游'], mode: 2, operationScope: ''
  },
  onCreatorLogin() { return retryLogin(this, app, this._resumeCreatorLoad); },
  _resumeCreatorLoad() {
    if (this._creatorOwnerChanged()) return;
    if (this._aiInitialised) this._resumeAiSession();
    else this.onLoad(this._creatorEntryOptions || {});
  },
  _creatorOwnerChanged() {
    if (!this._creatorOwner || this._creatorOwner === memberId(app)) return false;
    this._invalidateAi();
    this.setData({ creatorLoginRequired: true });
    wx.switchTab({ url: '/pages/template/index' });
    return true;
  },
  onLoad(options) {
    this._creatorEntryOptions = options || {};
    this._pageEpoch = (this._pageEpoch || 0) + 1;
    this._hidden = false; this._unloaded = false; this._hops = {}; this._writeEpoch = 0;
    if (!memberId(app)) {
      this.setData({ creatorLoginRequired: true });
      waitForInitialSession(this, app, this._resumeCreatorLoad);
      return;
    }
    this._creatorOwner = memberId(app);
    let seed = '';
    try { seed = decodeURIComponent(String((options && options.idea) || '')).trim().slice(0, 60); } catch (_) {}
    this.setData({ creatorLoginRequired: false, mode: Number(options && options.mode) === 2 ? 2 : 1,
      aiIdea: seed, operationScope: options && options.scope === 'MERCHANT' ? 'MERCHANT' : '',
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44,
      navBarHeight: (app.globalData && app.globalData.navBarHeight) || 44, reducedMotion: readReducedMotion() });
    this._aiInitialised = true;
    this._resumeAiSession();
  },
  onShow() {
    if (this._creatorOwnerChanged() || !this._aiInitialised || this._unloaded) return;
    if (this._hidden || !this._sameSession(this._sessionAtLoad)) this._resumeAiSession();
  },
  onHide() { this._hidden = true; this._invalidateAi(); },
  onUnload() { this._unloaded = true; this._hidden = true; this._invalidateAi(); this._prepareSnapshot = null; },
  _invalidateAi() {
    this._pageEpoch = (this._pageEpoch || 0) + 1; this._sheetContext = null; this._newActionPrompt = null;
    this.setData({ aiGenerating: false, aiRecoveryLoading: false, aiHistoryShow: false, aiCanContinue: false });
  },
  _session() {
    try {
      const store = app.getSession();
      const owner = memberId(app), auth = app.getAuthorization(), revision = store.getRevision();
      return journal.key(owner) && typeof auth === 'string' && auth && Number.isSafeInteger(revision)
        ? { owner, auth, revision } : null;
    } catch (_) { return null; }
  },
  _sameSession(stamp) {
    const now = this._session();
    return !!stamp && !!now && stamp.owner === now.owner && stamp.auth === now.auth && stamp.revision === now.revision;
  },
  _resumeAiSession() {
    this._invalidateAi(); this._hidden = false;
    this._sessionAtLoad = this._session();
    this._aiAnchor = this._aiPending && this._sessionAtLoad && this._aiPending.memberId === this._sessionAtLoad.owner
      ? Object.assign({}, this._aiPending) : null;
    this._aiPending = null; this._aiView = null;
    this._recoveryEmpty = false; this._recoverySeen = new Set(); this._recoveryKeys = new Set();
    this._journalBlocked = false;
    if (!this._sessionAtLoad) { this.setData({ aiError: '登录状态无法确认，请重新登录。' }); return; }
    this._restoreAiPending(); this.loadAiQuota();
  },
  _ensureAiSession() {
    if (this._hidden || this._unloaded || this._creatorOwnerChanged()) return false;
    if (!this._sameSession(this._sessionAtLoad)) { this._resumeAiSession(); return false; }
    return true;
  },
  _capture(lane, value) {
    const stamp = this._session();
    if (!stamp || this._hidden || this._unloaded) return null;
    this._hops = this._hops || {};
    const hop = (this._hops[lane] || 0) + 1; this._hops[lane] = hop;
    return Object.assign(stamp, { epoch: this._pageEpoch, lane, hop, writeEpoch: this._writeEpoch,
      record: value ? Object.assign({}, value) : null });
  },
  _baseCurrent(ctx) {
    if (!ctx || this._hidden || this._unloaded || this._pageEpoch !== ctx.epoch || this._hops[ctx.lane] !== ctx.hop
        || ctx.lane === 'quota' && ctx.writeEpoch !== this._writeEpoch) return false;
    if (!this._sameSession(ctx)) {
      const current = this._session(), transport = ctx.transport;
      if (!current || current.owner !== ctx.owner || !transport
          || typeof transport.isAuthenticatedContinuationCurrent !== 'function'
          || !transport.isAuthenticatedContinuationCurrent()) return false;
      const anchor = this._sessionAtLoad;
      if (anchor && anchor.owner === ctx.owner && anchor.auth === ctx.auth && anchor.revision === ctx.revision)
        this._sessionAtLoad = Object.assign({}, current);
      ctx.auth = current.auth; ctx.revision = current.revision;
    }
    return true;
  },
  _rejectAiSessionChange(ctx, response) {
    if (!response || response.reason !== 'sessionChanged' || !ctx || ctx.responded || this._hidden || this._unloaded
        || ctx.epoch !== this._pageEpoch || this._hops[ctx.lane] !== ctx.hop
        || ctx.lane === 'quota' && ctx.writeEpoch !== this._writeEpoch) return false;
    this._invalidateAi(); this._aiView = null; this._sessionAtLoad = this._session();
    return true;
  },
  _current(ctx) {
    if (!this._baseCurrent(ctx)) return false;
    const loaded = journal.load(wx, ctx.owner);
    const matches = ctx.record ? loaded.kind === 'READY' && JSON.stringify(loaded.value) === JSON.stringify(ctx.record)
      : loaded.kind === 'ABSENT';
    if (!matches) { if (loaded.kind !== 'READY') this._identityError(ctx); return false; }
    return this._baseCurrent(ctx);
  },
  _identityError(ctx) {
    if (!this._baseCurrent(ctx)) return;
    this._journalBlocked = true;
    this.setData({ aiError: '原起草身份无法可靠读取或保存，请恢复存储后查看原结果。', aiCanContinue: false, aiCanConfirmNew: false });
    if (ctx.lane === 'ai') this.setData({ aiGenerating: false });
    if (ctx.lane === 'recovery') this.setData({ aiRecoveryLoading: false });
  },
  _saveAi(ctx, next) {
    if (!this._current(ctx)) return false;
    const saved = journal.save(wx, ctx.owner, next, ctx.record);
    if (!saved.ok) { this._identityError(ctx); return false; }
    if (!this._baseCurrent(ctx)) return false;
    ctx.record = saved.value; this._aiPending = saved.value; this._journalBlocked = false;
    this._renderAiIdentity();
    return true;
  },
  _renderAiIdentity() {
    const p = this._aiPending, view = this._aiView;
    this.setData({ aiDraftPending: !!p && !terminal(p),
      aiIntentLabel: p ? (p.stage === 'EXECUTE_UNCONFIRMED' ? '原执行结果待确认' : p.state ? LABELS[p.state] : '准备结果待确认') : '',
      aiCanConfirmNew: !this._journalBlocked && !this._aiProtocolBlocked && !!p
        && (p.state === 'UNKNOWN' || (!p.requestId && p.stage === 'PREPARE_UNCONFIRMED')),
      aiIntentIdentity: p ? (p.requestId || p.prepareKey) : '',
      aiCanContinue: !this._journalBlocked && !this._aiProtocolBlocked && !this._historySelection && !!p && (!p.requestId
        ? !!this._prepareSnapshot && this._prepareSnapshot.owner === p.memberId && this._prepareSnapshot.prepareKey === p.prepareKey
        : journal.isServerId(p.requestId) && p.state === 'PREPARED' && !!view && view.contextAvailable
          && instant(view.executeBefore) && Date.parse(view.executeBefore) > Date.now()) });
  },
  loadAiQuota() {
    const ctx = this._capture('quota', this._aiPending);
    if (!ctx) return;
    ctx.transport = app.sendRequest({ url: '/api/ai/theme/draft/quota', method: 'POST', hideLoading: true,
      header: { 'Content-Type': 'application/json' },
      success: res => {
        if (!this._current(ctx) || ctx.responded) return;
        ctx.responded = true;
        const data = res && res.data;
        if (res && (res.code === 200 || res.code === '200') && data && data.limited
            && Number.isSafeInteger(data.remaining) && data.remaining >= 0)
          this.setData({ aiQuotaLoaded: true, aiRemaining: data.remaining });
      }, fail: response => {
        if (this._rejectAiSessionChange(ctx, response)) return;
        if (this._current(ctx)) ctx.responded = true;
      } });
  },
  onAiIdeaInput(e) { this.setData({ aiIdea: e.detail.value, aiError: '' }); },
  useAiPrompt(e) { this.setData({ aiIdea: e.currentTarget.dataset.text || '', aiError: '' }); },
  _beginAi(kind) {
    this._writeEpoch = (this._writeEpoch || 0) + 1; // An older quota response cannot overwrite a newer action/read.
    this._sheetContext = null;
    this.setData({ aiHistoryShow: false, aiHistoryPreviewLabel: '' });
    this._historySelection = null;
    const ctx = this._capture('ai', this._aiPending); if (!ctx) return null;
    ctx.kind = kind; this._aiView = null;
    this.setData({ aiGenerating: true, aiError: '', aiCanContinue: false });
    return ctx;
  },
  _syncAiRecord() {
    const ctx = this._capture('admission', this._aiPending); if (!ctx) return false;
    const loaded = journal.load(wx, ctx.owner);
    if (!this._baseCurrent(ctx)) return false;
    if (loaded.kind === 'READY') {
      if (JSON.stringify(loaded.value) !== JSON.stringify(this._aiPending)) {
        this._hops.ai = (this._hops.ai || 0) + 1;
        this._aiView = null; this.setData({ aiGenerating: false });
      }
      this._aiPending = loaded.value; this._journalBlocked = false; this._renderAiIdentity(); return true;
    }
    if (loaded.kind === 'ABSENT' && this._aiPending) {
      const original = this._aiPending; ctx.record = null;
      return this._saveAi(ctx, original);
    }
    if (loaded.kind === 'ABSENT' && !this._aiPending) {
      if (this._journalBlocked) this._recoveryEmpty = false;
      this._journalBlocked = false; return true;
    }
    this._identityError(ctx); return false;
  },
  generateAiPlan() {
    if (!this._ensureAiSession() || !this._syncAiRecord() || this.data.aiGenerating || this.data.aiRecoveryLoading) return;
    if (this._aiProtocolBlocked) { this.setData({ aiError: '原起草协议存在矛盾，请只读核对原记录，暂不能另起。' }); return; }
    if (this._journalBlocked) { this._identityError(this._capture('ai', this._aiPending)); return; }
    if (this._aiPending && !terminal(this._aiPending)) { this.checkAiDraftRequest(); return; }
    if (!this._aiPending && !this._recoveryEmpty) { this.openAiHistory(); return; }
    const idea = (this.data.aiIdea || '').trim();
    if (!idea) { this.setData({ aiError: '先说说你想做什么路线。' }); return; }
    if (this.data.aiQuotaLoaded && this.data.aiRemaining <= 0) {
      this.setData({ aiError: '这一轮的 AI 起草次数用完了，可以先手动填，或稍后再试。' }); return;
    }
    const ctx = this._beginAi('prepare'); if (!ctx) return;
    const prepareKey = makeRequestId();
    const value = { version: 2, memberId: ctx.owner, prepareKey, requestId: null,
      stage: 'PREPARE_UNCONFIRMED', state: null, reasonCode: null, createdAt: Date.now() };
    this._prepareSnapshot = Object.freeze({ owner: ctx.owner, prepareKey,
      payload: Object.freeze({ prepareKey, idea, productType: this.data.mode }) });
    if (!this._saveAi(ctx, value)) return;
    this._renderAiIdentity(); this._sendPrepare(ctx);
  },
  continueAiDraft() {
    if (!this._ensureAiSession() || !this._syncAiRecord() || this.data.aiGenerating || this.data.aiRecoveryLoading || this._journalBlocked || this._aiProtocolBlocked) return;
    const p = this._aiPending;
    if (!p) return;
    if (this._historySelection) { this.checkAiDraftRequest(); return; }
    if (!p.requestId && this._prepareSnapshot && this._prepareSnapshot.owner === p.memberId
        && this._prepareSnapshot.prepareKey === p.prepareKey) {
      const ctx = this._beginAi('prepare'); if (ctx) this._sendPrepare(ctx);
    } else if (p.state === 'PREPARED' && journal.isServerId(p.requestId)) this._executeAiOriginal();
    else this.checkAiDraftRequest();
  },
  _sendPrepare(ctx) {
    if (!this._current(ctx)) return;
    const snapshot = this._prepareSnapshot;
    if (!snapshot || snapshot.owner !== ctx.owner || snapshot.prepareKey !== ctx.record.prepareKey) return;
    this._sendAi(ctx, JSON.stringify(snapshot.payload), true);
  },
  _executeAiOriginal() {
    const p = this._aiPending, view = this._aiView;
    if (this._journalBlocked || this._aiProtocolBlocked || !p || !journal.isServerId(p.requestId) || p.state !== 'PREPARED'
        || !view || !view.contextAvailable || !instant(view.executeBefore) || Date.parse(view.executeBefore) <= Date.now()) {
      this.setData({ aiError: '原准备上下文不可用或已到期，请保留原记录并查看结果。', aiCanContinue: false }); return;
    }
    const ctx = this._beginAi('execute'); if (!ctx) return;
    if (!this._saveAi(ctx, Object.assign({}, ctx.record, { stage: 'EXECUTE_UNCONFIRMED' }))) return;
    this._writeEpoch = (this._writeEpoch || 0) + 1;
    this._sendAi(ctx, JSON.stringify({ requestId: ctx.record.requestId }), false);
  },
  checkAiDraftRequest() {
    if (!this._ensureAiSession() || !this._syncAiRecord() || this.data.aiGenerating || this.data.aiRecoveryLoading) return;
    if (!this._aiPending) { this._restoreAiPending(); return; }
    const ctx = this._beginAi('read'); if (!ctx) return;
    if (this._historyModeHint && this._historyModeHint.requestId === ctx.record.requestId)
      ctx.productTypeHint = this._historyModeHint.productType;
    this._sendAi(ctx, ctx.record.requestId
      ? { requestId: ctx.record.requestId } : { prepareKey: ctx.record.prepareKey }, false);
  },
  _sendAi(ctx, data, mayExecute) {
    if (!this._current(ctx)) return;
    const receive = (res, status) => {
      if (!this._current(ctx) || ctx.responded) return;
      ctx.responded = true;
      const permit = mayExecute && (res && (res.code === 200 || res.code === '200')) && status === 200;
      if (!this._acceptAiView(ctx, res && res.data, permit))
        this.setData({ aiError: '原起草结果还没确认，请稍后查看；不会自动另起一次。' });
    };
    const complete = () => { if (this._current(ctx)) this.setData({ aiGenerating: false }); };
    const success = res => receive(res, 200);
    const abnormal = (res, status) => receive(res, status || 0);
    const fail = res => {
      if (this._rejectAiSessionChange(ctx, res)) return;
      if (!this._current(ctx) || ctx.responded) return;
      if (res && res.data) receive(res, 0);
      else { ctx.responded = true; this.setData({ aiError: '网络暂时不可用，原起草结果还没确认，请查看原结果。' }); }
    };
    try {
      if (ctx.kind === 'prepare') ctx.transport = app.sendRequest({
        url: '/api/ai/theme/draft/prepare', method: 'POST', data, hideLoading: true, autoErrorToast: false,
        header: { 'Content-Type': 'application/json' }, success, successStatusAbnormal: abnormal, fail, complete
      });
      else if (ctx.kind === 'execute') ctx.transport = app.sendRequest({
        url: '/api/ai/theme/draft/execute', method: 'POST', data, hideLoading: true, autoErrorToast: false,
        header: { 'Content-Type': 'application/json' }, success, successStatusAbnormal: abnormal, fail, complete
      });
      else if (ctx.kind === 'read') ctx.transport = app.sendRequest({
        url: '/api/ai/theme/draft/request', method: 'GET', data, hideLoading: true, autoErrorToast: false,
        header: { 'Content-Type': 'application/json' }, success, successStatusAbnormal: abnormal, fail, complete
      });
    } catch (_) {
      if (this._current(ctx)) this.setData({ aiError: '网络暂时不可用，原身份已保留，请查看原结果。' });
      complete();
    }
  },
  _acceptAiView(ctx, view, mayExecute) {
    if (!recordObject(view) || Object.keys(view).sort().join(',') !== VIEW_FIELDS
        || STATES.indexOf(view.state) < 0 || (view.productType !== null && view.productType !== 1 && view.productType !== 2)
        || typeof view.replayed !== 'boolean'
        || typeof view.contextAvailable !== 'boolean' || typeof view.resultAvailable !== 'boolean'
        || !reason(view.reasonCode) || !optionalInstant(view.executeBefore) || !optionalInstant(view.resultExpiresAt)
        || (view.resultAvailable ? view.state !== 'SUCCEEDED' || !recordObject(view.result) : view.result !== null)) return false;
    const p = ctx.target || ctx.record;
    const snapshot = this._prepareSnapshot;
    const originalMode = snapshot && snapshot.owner === ctx.owner && snapshot.prepareKey === p.prepareKey ? snapshot.payload.productType : null;
    const hint = ctx.productTypeHint;
    if ((view.productType !== null && originalMode !== null && view.productType !== originalMode)
        || (hint != null && hint !== view.productType)
        || (view.requestId === this._appliedAiRequestId && this._aiResultMode != null
          && view.productType != null && view.productType !== this._aiResultMode)) {
      this._aiProtocolBlocked = true; this._renderAiIdentity(); this.refreshEnterEditorState();
      this.setData({ aiError: '原起草的路线模式不一致，记录保持不变，请只读核对。' }); return true;
    }
    ctx.originalMode = view.productType !== null ? view.productType : originalMode;
    if (view.requestId === null) {
      if (p.requestId !== null || view.state !== 'UNKNOWN' || view.reasonCode !== 'AI_DRAFT_PREPARE_UNCONFIRMED') return false;
      if (!ctx.readOnly) this._aiView = view;
      else this.setData({ aiHistoryPreviewLabel: '只读保留记录 · ' + p.prepareKey });
      this.setData({ aiError: '原准备结果尚未确认。保留原身份；原输入仍在本页时可明确继续原起草。' });
      this._renderAiIdentity(); return true;
    }
    if (p.requestId ? p.requestId !== view.requestId : !journal.isServerId(view.requestId)) return false;
    if (journal.isServerId(view.requestId) && (!instant(view.executeBefore)
        || (view.state === 'SUCCEEDED' && !instant(view.resultExpiresAt)))) return false;
    if (terminal(p) && p.state !== view.state) return false;
    const next = Object.assign({}, p, { requestId: view.requestId, state: view.state,
      reasonCode: view.reasonCode, stage: view.state === 'PREPARED' ? 'PREPARED' : 'KNOWN' });
    if (ctx.readOnly) {
      ctx.target = next; this._historySelection = next; this._renderAiIdentity();
      this.setData({ aiHistoryPreviewLabel: '只读历史 · ' + LABELS[view.state] + ' · ' + view.requestId });
    } else {
      if (!this._saveAi(ctx, next)) return true;
      this._aiProtocolBlocked = false; this._aiView = view; this._renderAiIdentity();
    }
    if (view.state === 'SUCCEEDED') {
      if (view.requestId === this._appliedAiRequestId && ctx.originalMode != null) {
        this._aiResultMode = ctx.originalMode; this.setData({ aiPlanModeKnown: true, aiPlanProductType: ctx.originalMode }); this.refreshEnterEditorState();
      }
      if (view.resultAvailable) {
        if (this._appliedAiRequestId !== view.requestId) {
          if (this._applyAiDraft({ code: 200, data: view.result }, ctx.kind !== 'execute' || view.replayed, ctx))
            this._appliedAiRequestId = view.requestId;
        } else this.loadAiQuota();
      } else this.setData({ aiError: view.reasonCode === 'AI_DRAFT_RESULT_EXPIRED'
        ? '原起草已成功，正文已到期；成功记录和原身份仍保留。' : '原起草已成功，正文暂不可用；请稍后查看原结果。' });
    } else if (view.state === 'FAILED') this.setData({ aiError: '原起草已明确失败，记录已保留；可明确另起一次。' });
    else if (view.state === 'UNKNOWN') this.setData({ aiError: '原起草结果暂时无法确认。默认只查看；另起一次需明确确认，可能额外占额度。' });
    else if (view.state === 'PROCESSING') this.setData({ aiError: '原起草还在进行中，请稍后查看原结果。' });
    else if (ctx.readOnly) this.setData({ aiError: '仅查看所选历史，当前原身份保持不变，不会执行所选记录。' });
    else if (mayExecute) this._executeAiOriginal();
    else if (!this.data.aiCanContinue) this.setData({ aiError: '原准备上下文不可用或已到期，请保留原记录并查看结果。' });
    return true;
  },
  _restoreAiPending() {
    const ctx = this._capture('restore', null); if (!ctx) return;
    const loaded = journal.load(wx, ctx.owner);
    if (!this._baseCurrent(ctx)) return;
    if (loaded.kind === 'READY') {
      this._aiPending = loaded.value; this._journalBlocked = false; this._renderAiIdentity();
      this.checkAiDraftRequest(); return;
    }
    if (loaded.kind !== 'ABSENT') { this._identityError(ctx); return; }
    if (this._aiAnchor && journal.valid(this._aiAnchor, ctx.owner)) {
      if (!this._saveAi(ctx, this._aiAnchor)) return;
      this._aiAnchor = null; this.checkAiDraftRequest(); return;
    }
    const oldKey = 'ai_topic_draft_request:m' + ctx.owner;
    let legacy;
    try { legacy = wx.getStorageSync(oldKey); } catch (_) { this._identityError(ctx); return; }
    if (!this._baseCurrent(ctx)) return;
    if (legacy !== '' && legacy !== null && legacy !== undefined) {
      if (!recordObject(legacy) || typeof legacy.requestId !== 'string' || !UUID.test(legacy.requestId)) {
        this._identityError(ctx); return;
      }
      const value = { version: 2, memberId: ctx.owner, prepareKey: null, requestId: legacy.requestId,
        stage: 'KNOWN', state: 'UNKNOWN', reasonCode: 'LEGACY_UNBOUND', createdAt: Date.now() };
      if (!this._saveAi(ctx, value)) return;
      try {
        const old = wx.getStorageSync(oldKey);
        if (this._current(ctx) && JSON.stringify(old) === JSON.stringify(legacy)) wx.removeStorageSync(oldKey);
      } catch (_) { /* 原身份已经可靠保存；不能因旧字段清理失败丢掉身份。 */ }
      this.checkAiDraftRequest(); return;
    }
    this._aiPending = null; this._renderAiIdentity(); this.openAiHistory();
  },
  openAiHistory() {
    if (!this._ensureAiSession() || this.data.aiGenerating || this.data.aiRecoveryLoading) return;
    this._recoverySeen = new Set(); this._recoveryKeys = new Set(); this._loadAiRecovery(null);
  },
  _loadAiRecovery(before) {
    const ctx = this._capture('recovery', this._aiPending); if (!ctx) return;
    this._historyLocal = false;
    this._sheetContext = null; this.setData({ aiRecoveryLoading: true, aiHistoryShow: false, aiError: '' });
    const receive = res => {
      if (!this._current(ctx) || ctx.responded) return;
      ctx.responded = true;
      const body = res && (res.code === 200 || res.code === '200') && res.data;
      const items = body && body.items;
      let previous = before, next = body && body.nextCursor;
      const valid = recordObject(body) && Object.keys(body).sort().join(',') === 'items,nextCursor'
        && Array.isArray(items) && items.length <= 20 && (next === null || cursor(next));
      const seen = new Set(this._recoverySeen), keys = new Set(this._recoveryKeys);
      let okay = valid;
      if (okay) for (const item of items) {
        const id = item && cursor(item.cursor);
        if (!recordObject(item) || Object.keys(item).sort().join(',') !== ITEM_FIELDS || !id
            || (previous && !older(id, previous)) || typeof item.prepareKey !== 'string' || !UUID.test(item.prepareKey)
            || (item.productType !== null && item.productType !== 1 && item.productType !== 2)
            || !journal.isServerId(item.requestId) || STATES.indexOf(item.state) < 0 || !reason(item.reasonCode)
            || !instant(item.createdAt) || !instant(item.executeBefore) || !optionalInstant(item.resultExpiresAt)
            || (item.state === 'SUCCEEDED' && !instant(item.resultExpiresAt))
            || seen.has(item.requestId) || keys.has(item.prepareKey)) { okay = false; break; }
        previous = id; seen.add(item.requestId); keys.add(item.prepareKey);
      }
      if (okay && next !== null && (items.length !== 20 || cursor(next) !== previous)) okay = false;
      const retained = okay ? journal.history(wx, ctx.owner) : null;
      if (!this._current(ctx)) return;
      this.setData({ aiRecoveryLoading: false }); this._hops.recovery++;
      if (!okay) {
        this._recoveryEmpty = false;
        this.setData({ aiError: '历史记录无法可靠读取，暂不能开始新的起草，请重试。' }); return;
      }
      this._recoverySeen = seen; this._recoveryKeys = keys;
      const retainedOkay = retained.kind === 'READY';
      this._recoveryEmpty = before === null && items.length === 0 && retainedOkay
        && !retained.values.some(value => !terminal(value));
      if (!retainedOkay) this.setData({ aiError: '本机保留记录无法可靠读取，暂不能另起，请恢复存储后重试。' });
      else if (!ctx.record && items.length === 0 && !this._recoveryEmpty)
        this.setData({ aiError: '本机尚有未确认的保留记录，请先查看本机保留记录。' });
      this._recoveryRows = items.map(item => Object.freeze(Object.assign({}, item)));
      this._recoveryNext = next === null ? null : cursor(next); this._recoveryBefore = before;
      const labels = items.map(item => LABELS[item.state] + ' · ' + item.requestId + ' · ' + item.createdAt.replace('T', ' ').slice(0, 16));
      if (this._recoveryNext) labels.push('更多记录');
      if (before !== null) labels.push('返回最新记录');
      if (items.length || before !== null) this._showAiHistory(labels);
    };
    ctx.transport = app.sendRequest({ url: '/api/ai/theme/draft/recovery', method: 'GET', data: before === null ? {} : { beforeId: before },
      header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
      success: receive, successStatusAbnormal: receive,
      fail: res => {
        if (this._rejectAiSessionChange(ctx, res)) return;
        if (!this._current(ctx) || ctx.responded) return;
        ctx.responded = true; this._recoveryEmpty = false;
        this.setData({ aiRecoveryLoading: false }); this._hops.recovery++;
        this.setData({ aiError: '历史记录读取失败，暂不能开始新的起草，请重试。' });
      }, complete: () => { if (this._current(ctx)) this.setData({ aiRecoveryLoading: false }); } });
  },
  openLocalAiHistory() {
    if (!this._ensureAiSession() || this.data.aiGenerating || this.data.aiRecoveryLoading) return;
    const ctx = this._capture('localHistory', this._aiPending); if (!ctx || !this._current(ctx)) return;
    const retained = journal.history(wx, ctx.owner);
    if (!this._current(ctx)) return;
    if (retained.kind !== 'READY') {
      this._recoveryEmpty = false;
      this.setData({ aiError: '本机保留记录无法可靠读取，请恢复存储后重试。' }); return;
    }
    this._historyLocal = true; this._recoveryRows = retained.values;
    this._recoveryNext = null; this._recoveryBefore = null;
    if (!retained.values.length) { this.setData({ aiError: '当前账号没有本机保留记录。' }); return; }
    this._showAiHistory(retained.values.map(value => '本机保留 · ' + (value.state ? LABELS[value.state] : '准备结果待确认')
      + ' · ' + (value.requestId || value.prepareKey)));
  },
  _showAiHistory(labels) {
    const ctx = this._capture('sheet', this._aiPending); if (!ctx) return;
    // UI event identity only: never a backend prepareKey, quota nonce or stored value.
    ctx.eventContext = 'sheet_' + makeRequestId();
    this._sheetContext = ctx;
    // A fresh instance owns each opening. Old queued events keep its old context.
    this.setData({ aiHistoryShow: false }, () => {
      if (this._sheetContext !== ctx || !this._current(ctx)) return;
      this.setData({ aiHistoryItems: labels, aiHistoryShow: true }, () => {
        if (this._sheetContext !== ctx || !this.data.aiHistoryShow) return;
        if (!this._current(ctx)) {
          if (this._sheetContext === ctx && this._baseCurrent(ctx)) {
            this._sheetContext = null; this.setData({ aiHistoryShow: false });
          }
          return;
        }
        const sheet = this.selectComponent('#ai-history-sheet');
        if (!sheet || typeof sheet.bindEventContext !== 'function' || !sheet.bindEventContext(ctx.eventContext)) {
          this._sheetContext = null;
          this.setData({ aiHistoryShow: false, aiError: '历史选择暂不可用，原身份已保留，请重新查看。' });
        }
      });
    });
  },
  _historyEventCurrent(e) {
    const ctx = this._sheetContext;
    return !!ctx && !!e && !!e.detail && this.data.aiHistoryShow
      && e.detail.eventContext === ctx.eventContext && this._current(ctx);
  },
  onAiHistoryCancel(e) { if (this._historyEventCurrent(e)) { this._sheetContext = null; this.setData({ aiHistoryShow: false }); } },
  onAiHistorySelect(e) {
    if (!this._historyEventCurrent(e)) return;
    const index = e.detail && e.detail.index, ctx = this._sheetContext;
    if (!Number.isSafeInteger(index) || index < 0 || index >= this.data.aiHistoryItems.length) return;
    this._sheetContext = null; this.setData({ aiHistoryShow: false });
    if (index === this._recoveryRows.length && this._recoveryNext) { this._loadAiRecovery(this._recoveryNext); return; }
    if (index >= this._recoveryRows.length) { this.openAiHistory(); return; }
    const item = this._recoveryRows[index], previous = ctx.record;
    if (this._historyLocal) {
      const read = this._beginAi('read'); if (!read) return;
      read.readOnly = true; read.target = Object.assign({}, item); this._historySelection = read.target;
      this._sendAi(read, item.requestId ? { requestId: item.requestId } : { prepareKey: item.prepareKey }, false); return;
    }
    const next = { version: 2, memberId: ctx.owner, prepareKey: item.prepareKey, requestId: item.requestId,
      stage: 'KNOWN', state: item.state, reasonCode: item.reasonCode,
      createdAt: previous && previous.requestId === item.requestId ? previous.createdAt : Date.now() };
    if (previous && previous.requestId === item.requestId && previous.prepareKey === item.prepareKey) {
      this._historyModeHint = { requestId: item.requestId, productType: item.productType };
      this.checkAiDraftRequest(); return;
    }
    if (previous && !terminal(previous)) {
      const read = this._beginAi('read'); if (!read) return;
      read.readOnly = true; read.target = next; this._historySelection = next; read.productTypeHint = item.productType;
      this._sendAi(read, { requestId: item.requestId }, false); return;
    }
    if (!this._adoptWithJournal(ctx, next, 'selectHistory')) return;
    this._historyModeHint = { requestId: item.requestId, productType: item.productType };
    this.checkAiDraftRequest();
  },
  _adoptWithJournal(ctx, next, method) {
    if (!this._current(ctx)) return false;
    if (typeof journal[method] !== 'function') {
      this.setData({ aiError: '安全身份保存接口尚未就绪，原记录已保留，请只读查看。' }); return false;
    }
    const saved = journal[method](wx, ctx.owner, next, ctx.record);
    if (!saved || !saved.ok) { this._identityError(ctx); return false; }
    if (!this._baseCurrent(ctx)) return false;
    const readback = journal.load(wx, ctx.owner);
    if (readback.kind !== 'READY' || JSON.stringify(readback.value) !== JSON.stringify(next)) { this._identityError(ctx); return false; }
    if (!this._baseCurrent(ctx)) return false;
    ctx.record = readback.value; this._aiPending = readback.value; this._aiView = null;
    this._journalBlocked = false; this._renderAiIdentity(); return true;
  },
  requestConfirmedNewAiAction() {
    if (!this._ensureAiSession() || !this._syncAiRecord() || this.data.aiGenerating || this.data.aiRecoveryLoading
        || !this.data.aiCanConfirmNew || this._aiProtocolBlocked) return;
    if (this._newActionPrompt && this._current(this._newActionPrompt)) return;
    const idea = (this.data.aiIdea || '').trim();
    if (!idea) { this.setData({ aiError: '先说说这次新起草的灵感。' }); return; }
    const ctx = this._capture('modal', this._aiPending); if (!ctx || !this._current(ctx)) return;
    const input = Object.freeze({ idea, productType: this.data.mode });
    this._newActionPrompt = ctx;
    modal.show({ title: '另起一次 AI 起草？',
      content: '上一次的结果仍可能返回。原记录会保留；另起一次不是重试，可能额外占用 AI 额度。是否明确另起？',
      confirmText: '确认另起', cancelText: '保留原记录',
      success: result => {
        if (!this._current(ctx) || this._newActionPrompt !== ctx || ctx.answered) return;
        ctx.answered = true; this._newActionPrompt = null;
        if (!result || result.confirm !== true) return;
        const prepareKey = makeRequestId();
        const next = { version: 2, memberId: ctx.owner, prepareKey, requestId: null, stage: 'PREPARE_UNCONFIRMED',
          state: null, reasonCode: null, createdAt: Date.now() };
        if (!this._adoptWithJournal(ctx, next, 'beginConfirmedNewAction')) return;
        this._prepareSnapshot = Object.freeze({ owner: ctx.owner, prepareKey,
          payload: Object.freeze({ prepareKey, idea: input.idea, productType: input.productType }) });
        const prepare = this._beginAi('prepare'); if (prepare) this._sendPrepare(prepare);
      },
      fail: () => { if (this._current(ctx) && this._newActionPrompt === ctx) this._newActionPrompt = null; },
      complete: () => { if (this._current(ctx) && this._newActionPrompt === ctx) this._newActionPrompt = null; }
    });
  },
  /** 只认带完整草稿的明确成功;结构不合格 fail-closed,已有草稿与点位原样保留。 */
  _applyAiDraft(res, replayed, ctx) {
    if (ctx && !this._current(ctx)) return false;
    const draft = res && res.data && res.data.draft;
    const nodes = draft && Array.isArray(draft.nodes) ? draft.nodes : [];
    const hasUsableNodes = nodes.every((node) => node && typeof node === 'object' && !Array.isArray(node)
      && ['merchantName', 'roleText', 'task'].every(field => node[field] == null || typeof node[field] === 'string')
      && String(node.merchantName || node.roleText || node.task || '').trim());
    if (!res || (res.code !== 200 && res.code !== '200') || !draft || typeof draft.title !== 'string' || !draft.title.trim() || !nodes.length || !hasUsableNodes
        || ['subtitle', 'storyline'].some(field => draft[field] != null && typeof draft[field] !== 'string')) {
      this.setData({ aiError: (res && res.msg) || 'AI 生成的主题不完整，请换一种说法重试' });
      return false;
    }
    if (nodes.length > MAX_AI_PLAN_NODES) {
      this.setData({ aiError: 'AI 草稿最多只能包含不超过 3 个节点，请换一种说法重试。' });
      return false;
    }
    this._planEpoch = (this._planEpoch || 0) + 1;
    this._aiResultMode = ctx ? ctx.originalMode : null;
    const freshQuota = !replayed && Number.isSafeInteger(res.data.remainingQuota) && res.data.remainingQuota >= 0;
    this.setData({
      aiPlanModeKnown: this._aiResultMode === 1 || this._aiResultMode === 2, aiPlanProductType: this._aiResultMode,
      aiPlan: { title: draft.title, subtitle: draft.subtitle || '', storyline: draft.storyline || '' },
      aiPlanNodes: nodes.map((node, index) => ({
        name: node.merchantName || node.roleText || node.task || ('节点 ' + (index + 1)),
        description: node.task || node.roleText || '',
        address: '', longitude: '', latitude: '', confirmed: false
      })),
      aiTraceId: res.data.traceId || '',
      // 回放的是当时那一份结果,其中的剩余额度已过时:不拿它当现值,改为重新读额度。
      aiQuotaLoaded: freshQuota ? true : this.data.aiQuotaLoaded,
      aiRemaining: freshQuota ? res.data.remainingQuota : this.data.aiRemaining
    }, () => { if (!ctx || this._current(ctx)) this.refreshEnterEditorState(); });
    if (this._aiResultMode == null) this.setData({ aiError: '原起草的路线模式未能确认，当前只能查看草稿，暂不能进入编辑器。' });
    if (replayed) this.loadAiQuota();
    return true;
  },
  refreshEnterEditorState() {
    const plan = this.data.aiPlan || {};
    const nodes = this.data.aiPlanNodes || [];
    const canEnterAiEditor = !this._aiProtocolBlocked && !!String(plan.title || '').trim()
      && nodes.length > 0 && (this._aiResultMode === 1 || this._aiResultMode === 2)
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
    if (!this._ensureAiSession()) return;
    if (this._aiResultMode !== 1 && this._aiResultMode !== 2) {
      this.setData({ aiError: '原起草的路线模式未能确认，当前只能查看草稿。' }); return;
    }
    const index = Number(e.currentTarget.dataset.index), ctx = this._capture('poi', this._aiPending), planEpoch = this._planEpoch;
    if (!ctx || !Number.isSafeInteger(index) || !this.data.aiPlanNodes[index]) return;
    wx.choosePoi({
      success: (poi) => {
        if (!this._current(ctx) || planEpoch !== this._planEpoch || this._aiProtocolBlocked) return;
        if (!poi || poi.longitude == null || poi.latitude == null) { this.setData({ aiError: '请选择一个有坐标的具体地点。' }); return; }
        this.setData({
          ['aiPlanNodes[' + index + '].name']: poi.name || this.data.aiPlanNodes[index].name,
          ['aiPlanNodes[' + index + '].address']: poi.address || poi.name || '',
          ['aiPlanNodes[' + index + '].longitude']: String(poi.longitude),
          ['aiPlanNodes[' + index + '].latitude']: String(poi.latitude),
          ['aiPlanNodes[' + index + '].confirmed']: true,
          aiError: ''
        }, () => { if (this._current(ctx) && planEpoch === this._planEpoch) this.refreshEnterEditorState(); });
      }
    });
  },
  enterAiEditor() {
    if (!this._ensureAiSession()) return;
    if (this._aiProtocolBlocked) { this.setData({ aiError: '原起草协议存在矛盾，暂不能进入编辑器。' }); return; }
    const plan = this.data.aiPlan || {};
    const nodes = this.data.aiPlanNodes || [];
    if (!plan.title || !nodes.length) { this.setData({ aiError: '请先生成主题草稿。' }); return; }
    if (this._aiResultMode !== 1 && this._aiResultMode !== 2) {
      this.setData({ aiError: '原起草的路线模式未能确认，当前可查看草稿，暂不能进入编辑器。' }); return;
    }
    if (nodes.some((node) => !node.confirmed || !node.longitude || !node.latitude)) {
      this.setData({ aiError: '请逐个在地图中确认地点后再继续。' });
      return;
    }
    const draft = {
      name: plan.title.trim(), subtitle: plan.subtitle || '', description: plan.storyline || '',
      productType: this._aiResultMode, aiSimple: true, aiTraceId: this.data.aiTraceId,
      chapters: [{ name: '路线', description: plan.storyline || '', imgArr: '', nodes: nodes.map((node, index) => ({
        name: node.name || ('节点 ' + (index + 1)), description: node.description || '', address: node.address,
        longitude: node.longitude, latitude: node.latitude, nodeTime: 30, businessTime: '', sortID: index + 1,
        templateId: 0, templateName: '', hookText: '', cardHookLong: '', fragmentText: ''
      })) }]
    };
    if (!this._ensureAiSession()) return;
    const ctx = this._capture('editor', this._aiPending), key = ctx && aiDraftStorageKey(ctx.owner);
    if (!key || !this._current(ctx)) return;
    try {
      wx.setStorageSync(key, draft);
      if (!this._current(ctx)) return;
      if (JSON.stringify(wx.getStorageSync(key)) !== JSON.stringify(draft)) throw new Error('draft readback');
    } catch (_) { this.setData({ aiError: '草稿无法可靠保存，请稍后再试。' }); return; }
    if (!this._current(ctx)) return;
    wx.navigateTo({
      url: '/pages/publish/fabu/index?mode=' + this._aiResultMode
        + (this.data.operationScope ? '&scope=MERCHANT' : '')
    });
  },
});
