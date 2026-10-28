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
const { exactId, reader, sameReader } = require('../../../utils/user-character.js');
const { createRecovery } = require('../../../utils/team-event-command.js');
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
    attendanceEventId: '',
    commandRecovery: null,
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
    privacyGateShow: false,
  },

  // 赛事签到组件的扫码(wx.scanCode)需要隐私授权：页内弹窗承接，不让 app.js 回退整页跳隐私页。
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },

  onLoad(q) {
    const raw = q && q.id;
    const id = exactId(raw);
    merchantTheme.merchantPageShow();
    this._unloaded = false;
    this._reader = reader(app);
    if (!id) {
      this.setData({ phase: 'missing' });
      return;
    }
    this._id = id;
    this.setData({attendanceEventId:id});
    this._openRecovery();
    this.load();
  },

  onShow() {
    merchantTheme.merchantPageShow();
    const reload = this._needsReload || this._reader && !sameReader(app, this._reader);
    if (reload) this._clearAccount();
    if (this._id) this._openRecovery();
    if (reload) this.load();
  },
  onHide() { if (this._recovery) this._recovery.dispose(); merchantTheme.merchantPageRestore(); },
  onUnload() {
    if (this._recovery) this._recovery.dispose();
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

  _openRecovery() {
    if (this._recovery) this._recovery.dispose();
    this._recovery = createRecovery({ app, platform: wx, domain: 'EVENT', targetId: this._id,
      changed: value => {
        const forms = value.input ? emptyForms() : this.data.forms;
        if (value.input) {
          if (value.action === 'pause') for (const field of rules.PAUSE_FIELDS) forms.pause[field].extra = value.input[field] || '';
          if (value.action === 'resume') forms.resume.reason = value.input.reason || '';
        }
        this.setData({ commandRecovery: value,
          busyAction: value.state || value.busy ? value.action || 'pending' : '', forms });
      },
      terminal: (receipt, command) => {
        this.setData({ result: { action: command.action,
          tone: receipt.state === 'APPLIED' ? 'success' : 'error',
          title: receipt.message, sub: '原请求号：' + receipt.requestId } });
        // A business snapshot is displayed separately; it never proves the original command outcome.
        this.load();
      } });
  },
  queryCommand() { if (this._recovery) this._recovery.query(); },
  retryCommand() { if (this._recovery) this._recovery.retry(); },
  cancelCommand() { if (this._recovery) this._recovery.cancel(); },
  acknowledgeCommand() { if (this._recovery) this._recovery.acknowledge(); },

  _clearAccount() {
    this._loadSeq = (this._loadSeq || 0) + 1;
    this._reader = reader(app);
    this._needsReload = true;
    this._pendingVerify = null;
    this._state = null;
    this._busy = false;
    this.setData({ phase: 'error', loadErrKind: 'no-permission', loadError: '账号状态已变化，请重新加载',
      refreshing: false, view: null, forms: emptyForms(), busyAction: '', result: null,
      check: { known: false, pass: false, blockers: [], risks: [] }, avail: rules.actionAvailability(null) });
  },
  _sameAccount(captured) {
    if (this._unloaded) return false;
    if (!sameReader(app, captured)) {
      if (!this._reader || this._reader.id === captured.id && this._reader.token === captured.token) this._clearAccount();
      return false;
    }
    return true;
  },
  load() {
    if (!this._id || this._unloaded) return;
    if (this._reader && !sameReader(app, this._reader)) this._clearAccount();
    this._reader = reader(app);
    this._needsReload = false;
    const seq = (this._loadSeq || 0) + 1;
    this._loadSeq = seq;
    const hasSnapshot = this.data.phase === 'ready';
    if (hasSnapshot) this.setData({ refreshing: true });
    else this.setData({ phase: 'loading', loadError: '', deniedReason: '' });
    const captured = reader(app);
    const current = () => seq === this._loadSeq && this._sameAccount(captured);
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
        this._loadEventState(seq, hasSnapshot);
      },
      fail: () => {
        if (!current()) return;
        this._readBackDone(null);
        if (hasSnapshot) {
          this.setData({ refreshing: false });
          toast('网络没连上，显示上次读到的状态');
          return;
        }
        this.setData({ phase: 'error', loadErrKind: 'network', loadError: '网络没连上，请重试' });
      },
    });
  },

  // 已排期以后公开详情能读;草稿在公开详情里不可见,回落到「我发布的」里的同一条服务端记录。
  _loadEventState(seq, hasSnapshot) {
    const captured = reader(app);
    const current = () => seq === this._loadSeq && this._sameAccount(captured);
    app.sendRequest({
      url: '/api/official/events/' + this._id, method: 'GET',
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!current()) return;
        if (isOk(res) && res.data && typeof res.data === 'object' && Number(res.data.contractVersion) >= 2) {
          this._applyState(res.data);
          return;
        }
        this._loadStateFromMine(seq, hasSnapshot);
      },
      fail: () => {
        if (!current()) return;
        this._loadStateFromMine(seq, hasSnapshot);
      },
    });
  },

  _loadStateFromMine(seq, hasSnapshot) {
    const captured = reader(app);
    const current = () => seq === this._loadSeq && this._sameAccount(captured);
    app.sendRequest({
      url: '/api/official/my-published', method: 'GET',
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!current()) return;
        const events = isOk(res) && res.data && Array.isArray(res.data.events) ? res.data.events : [];
        const row = events.find((item) => item && String(item.id) === String(this._id));
        this._applyState(row || null);
      },
      // 两路都断网 = 什么都没读到:同 publish-check 断网,不当成「读到了」,有快照不冲掉
      fail: () => {
        if (!current()) return;
        this._readBackDone(null);
        if (hasSnapshot) {
          this.setData({ refreshing: false });
          toast('网络没连上，显示上次读到的状态');
          return;
        }
        this.setData({ phase: 'error', loadErrKind: 'network', loadError: '网络没连上，请重试' });
      },
    });
  },

  _applyState(raw) {
    const state = rules.normalizeEventState(raw);
    const view = {
      title: state.title || '未命名官方活动',
      statusText: Number.isInteger(state.status) ? (activityStatusText(state.status, 'owner') || '状态待确认') : '状态待确认',
      controlText: CONTROL_TEXT[state.controlState] || '—',
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
    if (this._recovery && this._recovery.pending()) return;
    const nextForms = Object.assign({}, this.data.forms);
    nextForms[name] = value;
    if (this.data.formError.action === name) this.setData({ forms: nextForms, formError: { action: '', text: '' } });
    else this.setData({ forms: nextForms });
  },

  // ===================== 命令 =====================

  onAction(e) {
    if (this._reader && !this._sameAccount(this._reader)) return;
    const action = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.action;
    if (this._recovery && this._recovery.pending()) return;
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
    this._confirmationReader = reader(app);
    dc.open(CONFIRM_KEY[action], { name: (this.data.view && this.data.view.title) || '这场活动' });
  },

  onConfirmSchedule() { return this._submit('schedule'); },
  onConfirmPause() { return this._submit('pause'); },
  onConfirmResume() { return this._submit('resume'); },

  _submit(action) {
    if (this._confirmationReader && !sameReader(app, this._confirmationReader)) return;
    if (!exactId(this._id) || this._reader && !this._sameAccount(this._reader)) return;
    const dc = this.selectComponent && this.selectComponent('#dc-' + action);
    if (!dc || this.data.phase !== 'ready' || this.data.refreshing) return;
    const avail = this.data.avail[action];
    if (!avail || !avail.enabled || action === 'schedule' && !this.data.check.pass
        || rules.validateForm(action, this.data.forms[action])) return;
    if (!this._recovery) this._openRecovery();
    if (this._recovery.pending()) return;
    dc.close();
    return this._recovery.start(action, rules.buildPayload(action, this.data.forms[action]));
  },

  // Legacy callers may still request a snapshot refresh; snapshots cannot settle a command receipt.
  _readBackDone() {},

  _withClearedForm(action) {
    const next = Object.assign({}, this.data.forms);
    next[action] = emptyForms()[action];
    return next;
  },

  onDismissResult() { this.setData({ result: null }); },
});
