// 官方活动 V2 · 主办控制台(发布 / 暂停 / 恢复)。
// 2026-10-15 用户决定:邀请候任主办 / 转交暂不做;「提交排期」对用户叫「发布活动」;暂停用「预设 + 补充文字」。
//
// 入口:官方活动详情页与「我发布的」里,仅在服务端 publish-check 放行时出现。
// 权限真源在服务端:本页先打 publish-check,非 200 一律落「无权」态,不渲染任何控件;
// 每个命令的成败只认服务端回包,成功后再回读活动状态核对,核不上的不宣称已生效。
// 规则与文案口径见同目录 organizer-rules.js。
const toast = require('../../../utils/toast.js');
const { activityStatusText } = require('../../../utils/activity-status');
const merchantTheme = require('../../../utils/merchant-theme.js');
const rules = require('./organizer-rules.js');
const app = getApp();

const ACTION_LABEL = { schedule: '发布活动', pause: '暂停活动', resume: '恢复活动' };
const CONFIRM_KEY = {
  schedule: 'official.organizer.schedule',
  pause: 'official.organizer.pause',
  resume: 'official.organizer.resume',
};
const PAUSE_FIELD_LABEL = { reason: '暂停原因', impactScope: '影响范围', alternativePlan: '替代方案', notificationPlan: '通知策略' };
const CONTROL_TEXT = { NORMAL: '正常运行', PAUSED: '已暂停' };

function emptyForms() {
  const pause = {};
  rules.PAUSE_FIELDS.forEach((key) => { pause[key] = { preset: '', extra: '' }; });
  return { pause, resume: { reason: '' } };
}

// 暂停四项的渲染清单(标签 + 预设选项),只读常量,页面按它循环出「选项 + 补充文字」
const PAUSE_SECTIONS = rules.PAUSE_FIELDS.map((key) => ({
  key,
  label: PAUSE_FIELD_LABEL[key],
  presets: rules.PAUSE_PRESETS[key],
}));

function isOk(res) {
  return !!res && (res.code == 200 || res.code == '200');
}

function stringList(value) {
  return Array.isArray(value) ? value.filter((item) => typeof item === 'string' && item.trim()) : [];
}

Page({
  data: {
    phase: 'loading', // loading | missing | denied | error | ready
    refreshing: false,
    deniedReason: '',
    loadErrKind: 'network',
    loadError: '',
    view: null,
    check: { known: false, pass: false, blockers: [], risks: [] },
    avail: rules.actionAvailability(null),
    forms: emptyForms(),
    pauseSections: PAUSE_SECTIONS,
    formError: { action: '', text: '' },
    busyAction: '',
    result: null,
  },

  onLoad(q) {
    const raw = q && q.id;
    const id = rules.isPositiveId(raw) ? Number(raw) : 0;
    merchantTheme.merchantPageShow();
    this._unloaded = false;
    if (!id) {
      this.setData({ phase: 'missing' });
      return;
    }
    this._id = id;
    this.load();
  },

  onShow() { merchantTheme.merchantPageShow(); },
  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() {
    merchantTheme.merchantPageRestore();
    this._unloaded = true;
    this._loadSeq = (this._loadSeq || 0) + 1;
  },

  onPullDownRefresh() { if (this._id) this.load(); else wx.stopPullDownRefresh(); },

  onBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.redirectTo({ url: '/pages/activity/official-mine/index' });
  },

  // ===================== 读取 =====================

  load() {
    if (!this._id) return;
    const seq = (this._loadSeq || 0) + 1;
    this._loadSeq = seq;
    const hasSnapshot = this.data.phase === 'ready';
    if (hasSnapshot) this.setData({ refreshing: true });
    else this.setData({ phase: 'loading', loadError: '', deniedReason: '' });
    const current = () => !this._unloaded && seq === this._loadSeq;
    app.sendRequest({
      // 模板字面量:两道 U1 门禁都能把整条路径(含 /publish-check 尾段)对上后端 Mapping
      url: `/api/official/v2/events/${this._id}/publish-check`, method: 'GET',
      hideLoading: true, autoErrorToast: false,
      complete() { wx.stopPullDownRefresh(); },
      success: (res) => {
        if (!current()) return;
        if (!isOk(res)) {
          // 服务端拒绝 = 当前账号不是本场主办/协办(或已转出)。控件全部收起,只讲原因。
          this._readBackDone(false);
          this.setData({
            phase: 'denied', refreshing: false, view: null,
            deniedReason: app.getRequestErrorMessage(res, '当前账号不是这场活动的主办方'),
          });
          return;
        }
        const body = res.data && typeof res.data === 'object' ? res.data : {};
        const blockers = stringList(body.blockers);
        this.setData({
          check: {
            known: Array.isArray(body.blockers),
            pass: Array.isArray(body.blockers) && blockers.length === 0,
            blockers,
            risks: stringList(body.risks),
          },
        });
        this._loadEventState(seq);
      },
      fail: () => {
        if (!current()) return;
        this._readBackDone(null);
        if (hasSnapshot) {
          this.setData({ refreshing: false });
          toast('网络没连上，显示上次读到的状态');
          return;
        }
        this.setData({ phase: 'error', loadErrKind: 'network', loadError: '网络没连上，连接恢复后可以重试。' });
      },
    });
  },

  // 已排期以后公开详情能读;草稿在公开详情里不可见,回落到「我发布的」里的同一条服务端记录。
  _loadEventState(seq) {
    const current = () => !this._unloaded && seq === this._loadSeq;
    app.sendRequest({
      url: '/api/official/events/' + this._id, method: 'GET',
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!current()) return;
        if (isOk(res) && res.data && typeof res.data === 'object' && Number(res.data.contractVersion) >= 2) {
          this._applyState(res.data);
          return;
        }
        this._loadStateFromMine(seq);
      },
      fail: () => {
        if (!current()) return;
        this._loadStateFromMine(seq);
      },
    });
  },

  _loadStateFromMine(seq) {
    const current = () => !this._unloaded && seq === this._loadSeq;
    app.sendRequest({
      url: '/api/official/my-published', method: 'GET',
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!current()) return;
        const events = isOk(res) && res.data && Array.isArray(res.data.events) ? res.data.events : [];
        const row = events.find((item) => item && String(item.id) === String(this._id));
        this._applyState(row || null);
      },
      fail: () => {
        if (!current()) return;
        this._applyState(null);
      },
    });
  },

  _applyState(raw) {
    const state = rules.normalizeEventState(raw);
    const view = {
      title: state.title || '未命名官方活动',
      statusText: Number.isInteger(state.status) ? (activityStatusText(state.status, 'owner') || '状态待确认') : '状态待确认',
      controlText: CONTROL_TEXT[state.controlState] || '控制状态待确认',
      paused: state.controlState === rules.CONTROL_PAUSED,
      pausedReason: state.pausedReason,
      resumeNote: state.resumeNote,
      stateKnown: Number.isInteger(state.status),
    };
    this._state = state;
    this.setData({ phase: 'ready', refreshing: false, view, avail: rules.actionAvailability(state) });
    this._readBackDone(true);
  },

  // ===================== 表单 =====================

  onFieldInput(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const value = e && e.detail && typeof e.detail.value === 'string' ? e.detail.value : '';
    if (ds.form === 'pause') this._setPauseEntry(ds.key, 'extra', value);
    else if (ds.form === 'resume' && ds.key === 'reason') this._setForms('resume', { reason: value });
  },

  // 暂停预设:再点一次已选中的预设 = 取消选择
  onPickPreset(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const entry = this.data.forms.pause[ds.key];
    if (!entry || this.data.busyAction) return;
    const presets = rules.PAUSE_PRESETS[ds.key] || [];
    if (presets.indexOf(ds.value) < 0) return;
    this._setPauseEntry(ds.key, 'preset', entry.preset === ds.value ? '' : ds.value);
  },

  _setPauseEntry(key, part, value) {
    const pause = this.data.forms.pause;
    if (!pause[key]) return;
    const nextEntry = Object.assign({}, pause[key]);
    nextEntry[part] = value;
    const nextPause = Object.assign({}, pause);
    nextPause[key] = nextEntry;
    this._setForms('pause', nextPause);
  },

  _setForms(name, value) {
    const nextForms = Object.assign({}, this.data.forms);
    nextForms[name] = value;
    if (this.data.formError.action === name) this.setData({ forms: nextForms, formError: { action: '', text: '' } });
    else this.setData({ forms: nextForms });
  },

  // ===================== 命令 =====================

  onAction(e) {
    const action = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.action;
    if (!CONFIRM_KEY[action] || this._busy || this.data.refreshing || this.data.phase !== 'ready') return;
    const avail = this.data.avail[action];
    // 原因已在按钮上方以 hint / 发布检查列表写明,这里只给一句短提示
    if (!avail || !avail.enabled) {
      toast('当前状态不能执行这个操作');
      return;
    }
    if (action === 'schedule' && !this.data.check.pass) {
      toast(this.data.check.known ? '发布检查未通过，先处理阻塞项' : '发布检查没读到，请先刷新');
      return;
    }
    const message = rules.validateForm(action, this.data.forms[action]);
    if (message) {
      this.setData({ formError: { action, text: message } });
      return;
    }
    const dc = this.selectComponent && this.selectComponent('#dc-' + action);
    if (!dc) return; // 拿不到确认组件就什么都不做,绝不绕过确认直接提交
    dc.open(CONFIRM_KEY[action], { name: (this.data.view && this.data.view.title) || '这场活动' });
  },

  onConfirmSchedule() { this._submit('schedule'); },
  onConfirmPause() { this._submit('pause'); },
  onConfirmResume() { this._submit('resume'); },

  _submit(action) {
    const dc = this.selectComponent && this.selectComponent('#dc-' + action);
    if (!dc || this._busy) return;
    this._busy = true;
    dc.busyOn();
    this.setData({ busyAction: action, result: null });
    const id = this._id;
    app.sendRequest({
      // 三个字面量逐条写在 url 里,供 UI-GATE U1 与后端 Mapping 一一核对;action 已在 onAction 收敛为三个键
      url: action === 'schedule' ? `/api/official/v2/events/${id}/schedule`
        : action === 'pause' ? `/api/official/v2/events/${id}/pause`
          : `/api/official/v2/events/${id}/resume`,
      method: 'POST',
      data: rules.buildPayload(action, this.data.forms[action]),
      header: { 'content-type': 'application/json' },
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (this._unloaded) return;
        if (isOk(res)) {
          this._afterAccepted(action, dc);
          return;
        }
        // 业务拒绝:服务端原话(已过统一脱敏)留在确认框里给重试,也落一条页内说明
        const text = app.getRequestErrorMessage(res, ACTION_LABEL[action] + '没有完成');
        this._busy = false;
        this.setData({ busyAction: '', result: { action, tone: 'error', title: ACTION_LABEL[action] + '没有完成', sub: text } });
        dc.failed(text);
      },
      successStatusAbnormal: (body, status) => {
        if (this._unloaded) return;
        if (rules.isUnknownOutcome(status)) this._afterUnknown(action, dc);
        else {
          const text = app.getRequestErrorMessage(body, ACTION_LABEL[action] + '没有完成');
          this._busy = false;
          this.setData({ busyAction: '', result: { action, tone: 'error', title: ACTION_LABEL[action] + '没有完成', sub: text } });
          dc.failed(text);
        }
      },
      fail: () => {
        if (this._unloaded) return;
        this._afterUnknown(action, dc);
      },
    });
  },

  // 服务端受理(200)→ 回读核对 → 只有回读一致才给「已生效」回执
  _afterAccepted(action, dc) {
    this._pendingVerify = { action, dc, unknown: false };
    this.load();
  },

  // 断网 / 5xx / 超时:不知道服务端有没有执行。先回读,读到预期状态才算数,不引导盲目重发。
  _afterUnknown(action, dc) {
    this._pendingVerify = { action, dc, unknown: true };
    this.load();
  },

  // 回读结束(readable: true=读到最新状态, false=控制台已被拒绝, null=回读本身失败)
  _readBackDone(readable) {
    const pending = this._pendingVerify;
    if (!pending) return;
    this._pendingVerify = null;
    this._busy = false;
    const { action, dc, unknown } = pending;
    const label = ACTION_LABEL[action];
    const verdict = readable === true ? rules.verifyReadBack(action, this._state) : (readable === null ? 'unread' : 'mismatch');
    if (verdict === 'confirmed') {
      if (action !== 'schedule') this.setData({ forms: this._withClearedForm(action) });
      this.setData({ busyAction: '', result: { action, tone: 'success', title: label + '已生效', sub: '已按平台最新记录刷新。' } });
      dc.done('');
      return;
    }
    dc.close();
    let result;
    if (unknown) {
      result = verdict === 'unread'
        ? { action, tone: 'unknown', title: label + '结果未确认', sub: '网络中断，暂时不知道平台是否已执行，也没读到最新状态。请稍后下拉刷新核对，再决定是否重新操作。' }
        : { action, tone: 'unknown', title: label + '结果未确认', sub: '这次提交没有收到明确答复，当前显示的是刚读到的平台记录，请以它为准再决定是否重新操作。' };
    } else {
      result = { action, tone: 'unknown', title: label + '已受理，但状态未刷新', sub: verdict === 'unread' ? '回读最新状态失败，请下拉刷新核对。' : '回读到的活动状态与预期不一致，请下拉刷新；仍不一致请联系平台处理。' };
    }
    this.setData({ busyAction: '', result });
  },

  _withClearedForm(action) {
    const next = Object.assign({}, this.data.forms);
    next[action] = emptyForms()[action];
    return next;
  },

  onDismissResult() { this.setData({ result: null }); },
});
