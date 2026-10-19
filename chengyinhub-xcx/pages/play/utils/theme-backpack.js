const sessionClient = require('../../../utils/game-session-client.js');
const toast = require('../../../utils/toast.js');
<<<<<<< public
const gameplay = require('./theme-gameplay.js');
const rewards = require('./theme-rewards.js');
const trade = require('./theme-trade.js');
const plans = require('./theme-plans.js');
const titles = { WAIT: '休息恢复', CHALLENGE: '归零挑战', STEPS: '步行恢复', BLOCK_HP_COST: '暂停消耗任务', BLOCK_PAID: '暂停付费任务', THEME_FAIL: '本局结束' };

function presentZeroHp(view) {
  const zero = view.zeroHp;
  if (!zero || zero.phase === 'HEALTHY') return null;
  const actions = Array.isArray(zero.allowedActions) ? zero.allowedActions : [];
  const effective = zero.effectivePolicy || zero.policy;
  const usedText = Number.isInteger(zero.used) && Number.isInteger(zero.limit) && zero.limit > 0 ? '已恢复 ' + zero.used + ' / ' + zero.limit + ' 次' : '';
  const thenText = titles[zero.thenPolicy] || (zero.phase === 'BLOCKED' ? titles[effective] : '');
  return { ...zero, title: titles[effective] || '归零状态',
    canSteps: ['AWAITING_BASELINE', 'RECOVERING'].includes(zero.phase) && zero.policy === 'STEPS' && actions.includes('ZERO_HP_STEPS'),
    canSync: ['AWAITING_BASELINE', 'RECOVERING'].includes(zero.phase) && actions.includes('ZERO_HP_SYNC'),
    progressText: Number.isInteger(zero.progress) && Number.isInteger(zero.required) && zero.required > 0 ? zero.progress + ' / ' + zero.required : '',
    usedText, thenText,
    metaText: [zero.restoreTo > 0 && zero.phase !== 'BLOCKED' ? '恢复到 ' + zero.restoreTo : '', usedText, thenText ? (zero.phase === 'BLOCKED' ? '当前处理：' : '用尽后：') + thenText : ''].filter(Boolean).join(' · '),
    memberText: zero.recoveryMemberName || (zero.recoveryMemberId ? '队员 ' + zero.recoveryMemberId : ''),
  };
}
function present(view, nodes) {
  const names = gameplay.labels(view);
  const contents = Array.isArray(view.contents) ? view.contents : [];
  const claimOnly = rewards.ended(view);
  const items = contents.filter(i => i.kind === 'ITEM').map(i => Object.assign({}, i, {
    name: i.name || gameplay.valueName(i.id, view),
    statLabel: gameplay.valueName(i.statKey || i.stat, view), initial: String(i.name || gameplay.valueName(i.id, view)).slice(0, 1),
    effectText: i.stat || i.statKey ? gameplay.valueName(i.statKey || i.stat, view) + ' ' + (i.delta > 0 ? '+' : '') + i.delta : i.content || '随身物资',
    canUse: !claimOnly && !i.wear && !i.worn && !!(i.stat || i.statKey) && (view.scope !== 'TEAM' || (view.availableActions || []).includes('ITEM_USE')),
    canDiscard: !claimOnly && view.allowDiscard === true && (view.scope !== 'TEAM' || (view.availableActions || []).includes('ITEM_DISCARD')),
  }));
  const clues = contents.filter(i => i.kind !== 'ITEM' && !i.stat && !i.statKey).concat((view.events || []).map(e => ({ id: e.id, name: e.name || '线索', content: e.content })))
    // 服务端回读的已获得线索：范围单列，内容为来源站点的线索段落（正文与图片原样保留）
    .concat((view.clues || []).map(c => {
      const blocks = [];
      (Array.isArray(c.sources) ? c.sources : []).forEach(source => (Array.isArray(source.blocks) ? source.blocks : []).forEach(block => blocks.push({
        source: source.title || '', text: block.text || '', images: (Array.isArray(block.images) ? block.images : []).filter(Boolean),
      })));
      return { id: c.id + ':' + (c.scope || ''), name: c.name || '线索', scopeText: c.scope === 'PUBLIC' ? '全场公开' : '本队线索', blocks };
    }));
  const valueRows = [];
  if (view.hpEnabled) valueRows.push({ key: 'hp', name: names.hp, value: view.hp, max: view.hpMax, zero: view.awaitingChallenge || view.zeroHp && view.zeroHp.phase !== 'HEALTHY' });
  if (view.luckEnabled) valueRows.push({ key: 'luck', name: names.luck, value: view.luck, max: view.luckMax });
  if (view.actionLimit > 0) valueRows.push({ key: 'actions', name: names.action, value: view.actions, max: view.actionLimit });
  (view.attributes || []).filter(row => row.visible !== false && !String(row.key).startsWith('sys.')).forEach(row => valueRows.push({ key: row.key, name: row.label, value: row.value, max: row.max }));
  const challenge = view.challenge || {};
  const challengeIds = [challenge.nodeId].concat(challenge.alternativeNodeIds || []).filter(id => Number.isSafeInteger(Number(id)) && Number(id) > 0);
  return Object.assign({}, view, trade.present(view, nodes), plans.present(view, nodes), { items, clues, valueRows, claimOnly, names, actionRows: gameplay.actionRows(view, nodes).filter(row => view.scope !== 'TEAM' || row.kind !== 'PLAN'),
    challengeNodes: challengeIds.map((id, index) => ({ id: Number(id), name: ((nodes || []).find(node => Number(node.nodeId) === Number(id)) || {}).name || (index ? '备选挑战' : '主挑战') })),
    challengeRestoreText: Number.isInteger(challenge.restoreTo) && challenge.restoreTo > 0 ? '恢复到 ' + challenge.restoreTo : '恢复',
    challengeProgressLabel: view.scope === 'TEAM' ? '全队进度' : '挑战进度',
    challengeProgressText: typeof challenge.progress === 'number' && typeof challenge.goal === 'number' ? challenge.progress + '/' + challenge.goal : '',
    challengeRetryText: challenge.disabledReason === 'CHALLENGE_LIMIT' ? '挑战次数已达上限（' + challenge.used + '/' + challenge.limit + '）' : challenge.disabledReason || '失败后可免费重试',
    pendingRows: Object.keys(view.pending || {}).map(id => ({ ...view.pending[id], name: view.pending[id].name || rewards.itemName(view.pending[id].itemId, view), id, canClaim: !claimOnly || rewards.canClaim(view, id) })) });
}
function methods(req) {
  const client = () => sessionClient.createGameSessionClient({ sendRequest(options) {
    req(options.url, options.method, options.data, options.header, true).then(res => res.netFail ? options.fail() : options.success(res), options.fail);
  } });
  const bagMethods = {
    _applyZeroHpView(view) {
      const zero = presentZeroHp(view), key = view.sessionId + ':' + (zero && zero.episodeId || '');
      if (key !== this._zeroHpKey) {
        this._zeroHpKey = key; this._zeroHpDueKey = null;
        this.setData({ zeroHpError: '', zeroHpPermissionDenied: false });
      }
      this._zeroHpClock = zero && Number.isFinite(zero.serverNow) && Number.isFinite(zero.deadlineAt)
        ? { receivedAt: Date.now(), serverNow: zero.serverNow, deadlineAt: zero.deadlineAt } : null;
      this.setData({ zeroHpView: zero, zeroHpCountdown: '' });
      this._stopZeroHpTimer(); this._startZeroHpTimer();
      if ((view.result || view.resultCard || {}).reason === 'HP_ZERO' && this._zeroHpResultSession !== view.sessionId && !this._themeHidden) {
        this._zeroHpResultSession = view.sessionId;
        this.setData({ themeBackpackOpen: false, advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
        this.openThemeResultCard();
      }
    },
    _startZeroHpTimer() {
      if (this._themeHidden || this._zeroHpTimer || !this._zeroHpClock || !this.data.zeroHpView || this.data.zeroHpView.phase !== 'RECOVERING' || this.data.isMock || this.data.isPreview) return;
      this._tickZeroHpClock();
      this._zeroHpTimer = setInterval(() => this._tickZeroHpClock(), 1000);
    },
    _stopZeroHpTimer() { if (this._zeroHpTimer) clearInterval(this._zeroHpTimer); this._zeroHpTimer = null; },
    _tickZeroHpClock() {
      const clock = this._zeroHpClock, zero = this.data.zeroHpView;
      if (!clock || !zero) return;
      const seconds = Math.max(0, Math.ceil((clock.deadlineAt - clock.serverNow - (Date.now() - clock.receivedAt)) / 1000));
      this.setData({ zeroHpCountdown: seconds > 0 ? Math.floor(seconds / 60) + '分' + seconds % 60 + '秒（预计）' : '时间已到，正在确认恢复状态' });
      const key = this._zeroHpKey + ':' + clock.deadlineAt;
      // 倒计时只显示估算，到点仍须服务器回读确认恢复或超时。
      if (!seconds && zero.canSync && this._zeroHpDueKey !== key && !this._themeSending && !this.data.zeroHpBusy && !this._gameplayPending && !this._themePending && !this._planPending && !this._tradePending) {
        this._zeroHpDueKey = key; this.syncZeroHpState();
      }
    },
    syncZeroHpState() {
      if (!this.data.zeroHpView || !this.data.zeroHpView.canSync || this.data.zeroHpBusy) return Promise.resolve(false);
      return this._themeGameplayCommand({ operation: 'ZERO_HP_SYNC' });
    },
    async syncZeroHpSteps() {
      const zero = this.data.zeroHpView, view = this.data.themeBag;
      if (!zero || !zero.canSteps || this.data.zeroHpBusy || this._themeSending || this.data.isMock || this.data.isPreview) return false;
      if (this._gameplayPending && this._gameplayPending.input.operation.startsWith('ZERO_HP_')) return this.retryThemeBagAction();
      if (this._gameplayPending) { this.setData({ zeroHpError: '请先核对上一条操作' }); return false; }
      if (this._themePending || this._planPending || this._tradePending) { this.setData({ zeroHpError: '请先核对上一条操作' }); return false; }
      const epoch = this._themeEpoch || 0, episodeId = zero.episodeId, sessionId = view.sessionId;
      this.setData({ zeroHpBusy: true, zeroHpError: '', zeroHpPermissionDenied: false });
      try {
        const weRun = await new Promise((resolve, reject) => wx.login({
          success: login => {
            if (epoch !== (this._themeEpoch || 0)) { resolve(null); return; }
            if (!login || !login.code) { reject({ message: '微信登录态获取失败，请重试' }); return; }
            wx.getWeRunData({
              success: run => run && run.encryptedData && run.iv ? resolve({ episodeId, code: login.code, encryptedData: run.encryptedData, iv: run.iv }) : reject({ message: '微信运动数据未获取，请重试' }),
              fail: error => {
                const denied = !error || !error.errMsg || /auth|deny|denied|scope/i.test(error.errMsg);
                reject({ message: denied ? '需要授权微信运动。请开启微信运动权限后重试同步' : '微信运动数据获取失败，请重试', permissionDenied: denied });
              },
            });
          },
          fail: () => reject({ message: '微信登录态获取失败，请重试' }),
        }));
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (this.data.themeBag.sessionId !== sessionId || !this.data.zeroHpView || this.data.zeroHpView.episodeId !== episodeId || !this.data.zeroHpView.canSteps) {
          this.setData({ zeroHpError: '恢复状态已更新，请重新同步' }); return false;
        }
        return await this._themeGameplayCommand({ operation: 'ZERO_HP_STEPS', weRun });
      } catch (error) {
        if (epoch === (this._themeEpoch || 0)) this.setData({ zeroHpError: error.message || '同步失败，请重试', zeroHpPermissionDenied: error.permissionDenied === true });
        return false;
      } finally { if (epoch === (this._themeEpoch || 0)) this.setData({ zeroHpBusy: false }); }
    },
    openZeroHpWeRunSettings() {
      if (!this.data.zeroHpPermissionDenied || this.data.zeroHpBusy) return;
      const epoch = this._themeEpoch || 0;
      wx.openSetting({ success: result => {
        if (epoch !== (this._themeEpoch || 0)) return;
        const granted = result.authSetting && result.authSetting['scope.werun'] === true;
        this.setData({ zeroHpPermissionDenied: !granted, zeroHpError: granted ? '已开启微信运动，请重新同步' : '请开启微信运动权限后重试同步' });
      }, fail: () => { if (epoch === (this._themeEpoch || 0)) this.setData({ zeroHpError: '设置未打开，请重试' }); } });
=======
const { chinaParts } = require('../../../utils/datetime.js');
const resultPoster = require('./theme-result-poster.js');
const { isThemePlayInstanceId } = require('./play-run-session.js');
// Server-owned theme state. Global capability is never a topic/team permission.
function emptyBag() { return { contents: [], items: [], clues: [], slots: [], pendingRows: [], used: 0, capacity: 0, hpEnabled: false }; }
function present(view) {
  const contents = Array.isArray(view.contents) ? view.contents : [];
  const items = contents.filter(i => i.kind === 'ITEM' && i.stat).map(item => Object.assign({}, item, { statLabel: currentStatLabel(view, item.stat) }));
  const records = presentRecordedEvents(view.events, view.broadcasts, false);
  const clues = contents.filter(i => !i.stat).concat(records);
  const slots = [];
  // Presentation only; amounts and capacity always come from the server.
  const capacity = Math.max(0, Math.min(100, Number(view.capacity) || 0));
  const used = contents.filter(i => i.kind === 'ITEM').reduce((n, i) => n + Math.max(0, Number(i.quantity) || 0), 0);
  for (let n = 0; n < Math.max(capacity, Math.min(used, 100)); n++) slots.push({ filled: n < used });
  const pending = view.pending || {};
  return Object.assign({}, view, { items, clues, currentStats: presentCurrentStats(view), recordedEventCount: records.length, slots, pendingRows: Object.keys(pending).map(id => ({ id, name: pending[id].name, quantity: pending[id].quantity })) });
}
function currentStatLabel(view, code) {
  const name = code === 'HP' ? view.hpName : code === 'LUCK' ? view.luckName : null;
  // Missing names stay neutral protocol labels; the client cannot invent this theme's meaning.
  return typeof name === 'string' && name.trim() ? name : typeof code === 'string' ? code : '';
}
function presentCurrentStats(view) {
  const numeric = value => typeof value === 'number' && Number.isFinite(value);
  const rows = [];
  [['HP', 'hp'], ['LUCK', 'luck']].forEach(([code, key]) => {
    if (view[key + 'Enabled'] !== true || !numeric(view[key])) return;
    const max = view[key + 'Max'];
    rows.push({ key, label: currentStatLabel(view, code), value: view[key], hasMax: numeric(max), max: numeric(max) ? max : null });
  });
  // Server config validates a configured actionLimit as >=1; projected 0 means no configured budget.
  if (numeric(view.actions) && numeric(view.actionLimit) && view.actionLimit > 0)
    rows.push({ key: 'actions', label: '行动用量', value: view.actions, hasMax: true, max: view.actionLimit });
  return rows;
}
function same(a, b) { return String(a == null ? '' : a) === String(b == null ? '' : b); }
function positive(n) { return /^\d+$/.test(String(n)) && Number(n) > 0; }
function unknown(res) { return !res || res.netFail || res.httpFail; }
function scopeDenied(res) {
  return !!res && (res.code == 401 || res.code == 403 || ['主题运行时暂未开放', '主题不存在', '活动已取消', '活动当前不可用', '活动与主题不匹配', '请先报名并完成支付', '请先购买自玩通行证', '自玩通行证已过期'].includes(res.msg));
}
function terminal(view) { return view && ['ENDED', 'COMPLETED'].includes(view.status); }
function supportsResultImageShare(api) {
  try { return !!(api && typeof api.showShareImageMenu === 'function' && api.canIUse
    && api.canIUse('showShareImageMenu.object.needShowEntrance') === true); } catch (error) { return false; }
}
function actionId(prefix) { return prefix + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2); }
const HISTORY_PAGE_SIZE = 20;
function exactReadId(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  const text = String(value == null ? '' : value);
  return /^[1-9]\d{0,18}$/.test(text) ? text : '';
}
function compareIds(a, b) { return a.length === b.length ? (a === b ? 0 : a > b ? 1 : -1) : a.length > b.length ? 1 : -1; }
function activityLabel(value) { return value == null || String(value) === '0' ? '自玩' : exactReadId(value) ? '场次 ' + String(value) : ''; }
function resultTime(value) {
  if (!Number.isSafeInteger(value) || value <= 0) return '';
  const p = chinaParts(value); if (!p) return '';
  const pad = n => String(n).padStart(2, '0');
  return p.year + '-' + pad(p.month) + '-' + pad(p.day) + ' ' + pad(p.hours) + ':' + pad(p.minutes);
}
function presentHistoryPage(raw, topicId, cursor) {
  if (!Array.isArray(raw) || raw.length > HISTORY_PAGE_SIZE) throw new Error('历史记录格式异常，请刷新');
  let before = cursor || '';
  return raw.map(row => {
    const id = exactReadId(row && row.sessionId);
    if (!id || !same(row.topicId, topicId) || !activityLabel(row.activityId) || (before && compareIds(id, before) >= 0)) throw new Error('历史记录范围或顺序已变化，请刷新');
    before = id;
    const hasCard = terminal(row) && !!row.result && !!resultTime(row.result.endedAt);
    return { sessionId: id, sceneName: typeof row.sceneName === 'string' ? row.sceneName : '',
      activityLabel: activityLabel(row.activityId), statusLabel: row.status === 'COMPLETED' ? (hasCard ? '已完成' : '结果尚未结算') : row.status === 'ENDED' ? '已结束' : '尚未结束',
      hasCard, endedAtText: row.result ? resultTime(row.result.endedAt) : '', recordedEvents: presentRecordedEvents(row.events, null, true) };
  });
}
function presentCard(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || typeof raw.success !== 'boolean' || !resultTime(raw.endedAt)) throw new Error('结果格式异常，请重试');
  const text = value => typeof value === 'string' ? value : '';
  const number = value => typeof value === 'number' && Number.isFinite(value);
  const contents = value => Array.isArray(value) ? value.filter(x => x && typeof x === 'object').map(x => ({ name: text(x.name), image: text(x.image), quantity: number(x.quantity) ? x.quantity : 0 })) : [];
  const stats = [];
  [['hp', text(raw.hpName) || '生命值'], ['luck', text(raw.luckName) || '幸运值'], ['actions', '行动'], ['rescues', '救援']].forEach(([key, label]) => {
    if (Object.prototype.hasOwnProperty.call(raw, key) && number(raw[key])) stats.push({ key, label, value: raw[key] });
  });
  const next = raw.nextScene && typeof raw.nextScene === 'object' && text(raw.nextScene.name)
    ? { name: text(raw.nextScene.name), asOfText: resultTime(raw.nextScene.asOf) } : null;
  const unlocks = raw.unlocks && Number.isSafeInteger(raw.unlocks.total) && raw.unlocks.total >= 0
    ? { total: raw.unlocks.total, known: Array.isArray(raw.unlocks.known) ? raw.unlocks.known.map(x => ({ title: text(x && x.title) })) : [] } : null;
  return { success: raw.success, endedAtText: resultTime(raw.endedAt), cover: text(raw.cover), scene: text(raw.scene),
    title: text(raw.title), summary: text(raw.summary), stats, items: contents(raw.items), medals: contents(raw.medals),
    epilogues: Array.isArray(raw.epilogues) ? raw.epilogues.map(x => ({ name: text(x && x.name), line: text(x && x.line) })) : [],
    exhibits: Array.isArray(raw.exhibits) ? raw.exhibits.filter(x => typeof x === 'string') : [], nextScene: next, unlocks };
}
/** Passive read projection only. No delivery request or effect replay is issued here. */
function presentRecordedEvents(events, broadcasts, historical) {
  const receipts = new Map();
  if (!historical && Array.isArray(broadcasts)) broadcasts.forEach(row => {
    if (!row || typeof row.eventId !== 'string' || !row.eventId) return;
    // Ambiguous duplicate receipt keys are unknown; do not silently choose the best status.
    if (receipts.has(row.eventId)) { receipts.set(row.eventId, null); return; }
    const status = ['PENDING', 'FAILED', 'REACHED'].includes(row.status) ? row.status : '';
    const attempts = Number.isSafeInteger(row.attempts) && row.attempts >= 0 ? row.attempts : null;
    receipts.set(row.eventId, status ? { status, attempts } : null);
  });
  const labels = { PENDING: '站内消息待生成', FAILED: '站内消息暂未生成', REACHED: '站内消息已生成', UNCONFIRMED: '已记录，消息状态未同步', ARCHIVED: '历史已记录，不重新发送' };
  const seen = new Set();
  return (Array.isArray(events) ? events : []).filter(row => row && typeof row.id === 'string' && row.id && !seen.has(row.id) && seen.add(row.id)).map(row => {
    const receipt = receipts.get(row.id);
    const status = historical ? 'ARCHIVED' : receipt ? receipt.status : 'UNCONFIRMED';
    return { id: row.id, kind: 'EVENT', name: typeof row.name === 'string' && row.name ? row.name : '线索',
      content: typeof row.content === 'string' ? row.content : '', deliveryStatus: status, deliveryLabel: labels[status],
      deliveryAttempts: receipt && !historical ? receipt.attempts : null };
  });
}
// Independently re-project the local author DTO; no arbitrary source values reach setData.
function presentThemeOverview(value) {
  if (!value || value.kind !== 'CONFIG' || value.version !== 1 || typeof value.clipped !== 'boolean'
      || !Array.isArray(value.rows) || !value.rows.length || value.rows.length > 600) return null;
  const sections = ['说明', '规则', '目录', '章节', '广播', '场景'];
  const rows = [];
  for (const row of value.rows) {
    if (!row || !sections.includes(row.section) || typeof row.label !== 'string' || row.label.length > 80
        || typeof row.detail !== 'string' || row.detail.length > 240) return null;
    rows.push({ section: row.section, label: row.label, detail: row.detail });
  }
  return { rows, clipped: value.clipped };
}
function methods(req, app, modal, nativeApi) {
  const posterApi = () => nativeApi || (typeof wx !== 'undefined' ? wx : null);
  return {
    _themeOwner() { return app && app.getUserID ? app.getUserID() : ''; },
    _retireThemeOverview() {
      this._themeOverviewContext = null; this._themeOverviewValue = null; this._themeOverviewEnvelope = null;
      this.setData({ themeOverviewAvailable: false, themeOverviewShow: false, themeOverviewRows: [],
        themeOverviewPage: 0, themeOverviewPages: 0, themeOverviewClipped: false });
    },
    _newThemeOverviewContext() {
      this._retireThemeOverview();
      const context = { owner: String(this._themeOwner() || ''), used: false };
      this._themeOverviewContext = context;
      return context;
    },
    _themePreviewContextLive(context) {
      return !!context && context === this._themeOverviewContext && !context.used && !this._themeHidden
        && this.data.isPreview === true && !this.data.isMock && context.owner === String(this._themeOwner() || '');
    },
    _acceptThemeOverview(envelope, context) {
      if (!this._themePreviewContextLive(context)) return false;
      context.used = true;
      this._themeOverviewValue = null; this._themeOverviewEnvelope = null;
      this.setData({ themeOverviewAvailable: false, themeOverviewShow: false, themeOverviewRows: [],
        themeOverviewPage: 0, themeOverviewPages: 0, themeOverviewClipped: false });
      if (!envelope || !/^[1-9]\d{0,18}$/.test(context.owner) || envelope.owner !== context.owner
          || typeof envelope.isCurrent !== 'function') return false;
      let current = false;
      try { current = envelope.isCurrent() === true; } catch (_) { /* Retire failed source guards. */ }
      const value = current && presentThemeOverview(envelope.value);
      if (!value) return false;
      // Copy fields and rows; do not retain mutable caller DTOs or put the guard in page data.
      this._themeOverviewEnvelope = { owner: envelope.owner, isCurrent: envelope.isCurrent };
      this._themeOverviewValue = value;
      this.setData({ themeOverviewAvailable: true, themeOverviewPages: Math.ceil(value.rows.length / 20), themeOverviewClipped: value.clipped });
      return true;
    },
    _themeOverviewLive() {
      const context = this._themeOverviewContext, envelope = this._themeOverviewEnvelope;
      let current = false;
      try { current = !!envelope && envelope.isCurrent() === true; } catch (_) { /* Fail closed. */ }
      if (this._themeHidden || !this.data.isPreview || this.data.isMock || !context || !context.used
          || !this._themeOverviewValue || !envelope || envelope.owner !== String(this._themeOwner() || '') || !current) {
        this._retireThemeOverview(); return false;
      }
      return true;
    },
    _showThemeOverviewPage(page) {
      if (!this._themeOverviewLive()) return false;
      const value = this._themeOverviewValue, pages = Math.ceil(value.rows.length / 20);
      if (!Number.isInteger(page) || page < 1 || page > pages) return false;
      this.setData({ themeOverviewShow: true, themeOverviewPage: page,
        themeOverviewRows: value.rows.slice((page - 1) * 20, page * 20).map(row => Object.assign({}, row)), sessionToolsOpen: false });
      return true;
    },
    openThemeOverview() { return this._showThemeOverviewPage(1); },
    previousThemeOverviewPage() { return this._showThemeOverviewPage(this.data.themeOverviewPage - 1); },
    nextThemeOverviewPage() { return this._showThemeOverviewPage(this.data.themeOverviewPage + 1); },
    closeThemeOverview() { this.setData({ themeOverviewShow: false, themeOverviewRows: [], themeOverviewPage: 0 }); },

    _themeContext() { return [this._themeOwner(), this.data.topicId || '', this.data.activityId || 0].join(':'); },
    _themeLive(epoch, context) { return !this._themeHidden && epoch === (this._themeEpoch || 0) && context === this._themeContext(); },
    _themeReset() {
      this._cancelThemeReads(true);
      this._retireThemeCache();
      this._stopThemePoll(); this._themeEpoch = (this._themeEpoch || 0) + 1;
      this._themeEnabled = false; this._themeEligible = false; this._themeLoading = null; this._themeNodesSession = null;
      this._themeSending = null; this._themeReplaySending = null; this._themePreparing = null; this._themeControlRequest = null;
      this._themePending = null; this._themeReplayPending = null; this._themeTeam = null;
      this._themeChapterEntered = null; this._themeStepBaseline = false;
      this._themeIdentity = this._themeContext();
      if (this.data.themeRuntimeReady || this.data.themeBag.sessionId || this.data.themeBackpackOpen) {
        this.setData({ themeRuntimeReady: false, themeBag: emptyBag(), themeBackpackOpen: false,
          themeBagBusy: false, themeBagUnknown: false, themeBagError: '', themeControlBusy: false,
          themeReplayBusy: false, themeReplayUnknown: false, themeReplayError: '', themeTeamLeader: false, themeTeamNotice: '' });
      }
    },
    _retireThemeCache() {
      const hadThemeReference = this._themeCacheMode === 'theme' || this._themeCacheInstance != null;
      this._themeCacheMode = 'pending'; this._themeCacheInstance = null;
      if (hadThemeReference && this._retireThemeDraftReference) this._retireThemeDraftReference();
    },
    _themeStoryScope(scope) {
      if (this._themeIdentity !== this._themeContext() || this._themeHidden || this._themeCacheMode === 'pending' || !this._themeCacheMode)
        return Object.assign({}, scope, { instanceResolved: false });
      if (this._themeCacheMode === 'legacy') return scope;
      return Object.assign({}, scope, { instanceResolved: true, playInstanceId: this._themeCacheInstance });
    },
    _themeOnShow() {
      this._themeHidden = false;
      if (this._themeOverviewEnvelope) this._themeOverviewLive();
      if (this._themeIdentity !== this._themeContext()) this._themeReset();
      // A new eligible /nodes response revalidates the capability before polling/writes resume.
      this._themeEnabled = false; this._retireThemeCache();
    },
    _themeOnHide(unload) {
      this._retireThemeOverview();
      this._cancelThemeReads(true);
      this._themeHidden = true; this._retireThemeCache(); this._stopThemePoll(); this._themeEpoch = (this._themeEpoch || 0) + 1;
      this._themeEnabled = false; this._themeLoading = null;
      // A hidden response cannot establish success. Keep exactly the submitted intent for a user retry.
      if (this._themePending) this.setData({ themeBagUnknown: true, themeBagBusy: false, themeBagError: '操作结果未确认，请返回后重试' });
      if (this._themeReplayPending) this.setData({ themeReplayUnknown: true, themeReplayBusy: false, themeReplayError: '重开结果未确认，请返回后重试同一次操作' });
      this._themeSending = null; this._themeReplaySending = null; this._themePreparing = null; this._themeControlRequest = null;
      if (this.data.themeControlBusy) this.setData({ themeControlBusy: false });
      if (unload) this._themeReset();
    },
    _themeNodesFailed(response, request) {
      if (!request || request.epoch !== (this._themeEpoch || 0) || !same(request.owner, this._themeOwner())) return;
      this._themeEnabled = false; this._stopThemePoll();
      if (response.code == 401 || response.code == 403 || /登录|认证/.test(response.msg || '')) this._themeReset();
    },
    _themeNodesRequest() {
      if (this._themeIdentity !== this._themeContext()) this._themeReset();
      return { owner: this._themeOwner(), activity: this.data.activityId || 0, topic: this.data.topicId || '', epoch: this._themeEpoch || 0 };
    },
    _acceptThemeNodes(response, request) {
      if (!request || request.epoch !== (this._themeEpoch || 0) || !same(request.owner, this._themeOwner())
          || !same(request.activity, this.data.activityId || 0) || (request.topic && !same(request.topic, this.data.topicId)) || this._themeHidden) return Promise.resolve(false);
      if (this.data.isMock || this.data.isPreview || response.themeRuntimeAvailable !== true || !positive(this._themeOwner())) {
        this._themeReset();
        if (!this.data.isMock && !this.data.isPreview && response.themeRuntimeAvailable !== true && positive(this._themeOwner())) this._themeCacheMode = 'legacy';
        return Promise.resolve(false);
      }
      if (this._themeIdentity !== this._themeContext()) this._themeReset();
      this._retireThemeCache();
      this._themeEligible = true; this._themeNodesSession = response.routeState && response.routeState.sessionId;
      const epoch = this._themeEpoch || 0, context = this._themeContext();
      return req('/api/play/theme/capabilities', 'GET', {}, null, true).then(res => {
        if (!this._themeLive(epoch, context)) return false;
        if (res.code != 200 || !res.data || res.data.enabled !== true) { this._themeReset(); return false; }
        this._themeEnabled = true;
        return this.refreshThemeBackpack();
      }).catch(() => { if (this._themeLive(epoch, context)) this._themeReset(); return false; });
    },
    _themeAllowed() {
      if (this._themeIdentity !== this._themeContext()) { this._themeReset(); return false; }
      return !!(this._themeEnabled && this._themeEligible && !this._themeHidden && positive(this._themeOwner())
        && positive(this.data.topicId) && !this.data.isPreview && !this.data.isMock);
>>>>>>> private-github/featu
    },
    _applyThemeView(view) {
      if (!view || !positive(view.sessionId) || !same(view.topicId, this.data.topicId) || !same(view.activityId || 0, this.data.activityId || 0)) return false;
      if (!isThemePlayInstanceId(view.playInstanceId)) return false;
      const previous = this.data.themeBag;
<<<<<<< public
      if (previous.sessionId === view.sessionId) { const ids = new Set((previous.events || []).map(e => e.id)); const added = (view.events || []).find(e => !ids.has(e.id)); if (added) toast(added.name || '获得新线索'); }
      const next = present(view, this.data.nodes);
      const sameRound = previous.sessionId === view.sessionId && previous.world && view.world && previous.world.round === view.world.round;
      const acceptKeys = sameRound && this._themePlanAcceptanceDirty ? this.data.themePlanAcceptKeys || [] : view.world && view.world.ownAcceptance && view.world.ownAcceptance.planKeys || [];
      if (!sameRound) this._themePlanAcceptanceDirty = false;
      next.planRows = next.planRows.map(row => ({ ...row, acceptSelected: acceptKeys.includes(row.proposalKey) }));
      this.setData({ themeBag: next, themePlanAcceptKeys: acceptKeys, themeBagError: this._gameplayPending && this._gameplayPending.input.operation.startsWith('ZERO_HP_') && this.data.themeBagUnknown ? this.data.themeBagError : '' });
      this._applyZeroHpView(view);
      if (this._applyR5OutcomeView) this._applyR5OutcomeView(view);
      if (this._applyPlayerOpsView) this._applyPlayerOpsView(view);
      if (this._themeChallengeNodeId && this.data.playKit && this.data.playKit.kit) {
        if (view.awaitingChallenge === false) {
          this._themeChallengeNodeId = null;
          this.setData({ advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
        } else this.setData({ 'playKit.kit.challenge': view.challenge || null });
      }
      const preview = this.data.themeTradePreview;
      if (preview && (preview.revision !== view.revision || preview.authorityEpoch !== view.authorityEpoch
        || !this.data.themeBag.tradeTargets.some(row => String(row.teamId) === String(preview.payload.toTeamId) && row.enabled))) this.setData({ themeTradePreview: null });
      this._stopThemePoll(); this._startThemePoll();
=======
      if (same(previous.sessionId, view.sessionId) && Number(previous.version) > Number(view.version)) return false;
      if (previous.sessionId && !same(previous.sessionId, view.sessionId)) {
        this._cancelThemeReads(true);
        if (this._resetThemeRunUi) this._resetThemeRunUi(view.sessionId);
        this._themePending = null; this._themeChapterEntered = null; this._themeStepBaseline = false;
        this.setData({ themeBagUnknown: false, themeBagBusy: false });
      }
      if (this._themeReplayPending && view.teamId && !same(this._themeReplayPending.payload.teamId, view.teamId)) {
        this._themeReplayPending = null;
        this.setData({ themeReplayUnknown: false, themeReplayError: '队伍已变更，请重新确认' });
      }
      // Announce only a newly accepted event within the same run; rejected/stale views stay silent.
      if (same(previous.sessionId, view.sessionId)) {
        const ids = new Set((previous.events || []).map(event => event.id));
        const added = (view.events || []).find(event => !ids.has(event.id));
        if (added && typeof wx !== 'undefined') toast(added.name || '获得新线索');
      }
      this._themeUiSession = view.sessionId;
      this._themeCacheMode = 'theme'; this._themeCacheInstance = view.playInstanceId;
      this.setData({ themeRuntimeReady: true, themeBag: present(view), themeBagError: this._themePending ? this.data.themeBagError : '' });
      return true;
>>>>>>> private-github/featu
    },
    _themeSyncGuard() { return { epoch: this._themeEpoch || 0, context: this._themeContext(), sessionId: this.data.themeBag.sessionId }; },
    _startThemePoll() {
<<<<<<< public
      const bag = this.data.themeBag;
      if (this._themeHidden || this._themeTimer || !bag.sessionId || !(bag.timeEggs || bag.stepEggs || bag.awaitingChallenge || bag.zeroHp && ['RECOVERING', 'AWAITING_BASELINE'].includes(bag.zeroHp.phase) || this.data.themeBackpackOpen && (bag.planRows || []).length) || !['ACTIVE', 'RUNNING'].includes(bag.status)) return;
      this._themeTimer = setInterval(() => { if (!this._themeSending) (bag.stepEggs ? this._syncThoughts([]) : Promise.resolve()).then(() => this.refreshThemeBackpack()); }, bag.awaitingChallenge || bag.zeroHp && ['RECOVERING', 'AWAITING_BASELINE'].includes(bag.zeroHp.phase) || (bag.planRows || []).length ? 10000 : 30000);
    },
    _stopThemePoll() { if (this._themeTimer) clearInterval(this._themeTimer); this._themeTimer = null; },
    refreshThemeBackpack() {
      if (this.data.isMock || this.data.isPreview) return Promise.resolve(false);
      if (this.data.themeBag.scope === 'TEAM') return this._loadPlayerGameModule();
      if (!this.data.topicId) return Promise.resolve(false);
=======
      if (!this._themeAllowed() || this._themeTimer || this.data.themeBag.status !== 'ACTIVE') return;
      this._themeTimer = setInterval(() => {
        if (!this._themeSending && !this._themeReplaySending && !this._themePending && !this._themeReplayPending) (this.data.themeBag.stepEggs && this._syncThoughts ? this._syncThoughts([], this._themeSyncGuard()) : Promise.resolve()).then(() => this.refreshThemeBackpack());
      }, 30000);
    },
    _stopThemePoll() { if (this._themeTimer) clearInterval(this._themeTimer); this._themeTimer = null; },
    refreshThemeBackpack() {
      if (!this._themeAllowed()) return Promise.resolve(false);
>>>>>>> private-github/featu
      if (this._themeLoading) return this._themeLoading;
      const epoch = this._themeEpoch || 0, context = this._themeContext(), mutation = this._themeMutation || 0;
      const loading = req('/api/play/theme/state', 'GET', Object.assign({ topicId: this.data.topicId }, this._sessionParams()), null, true).then(res => {
<<<<<<< public
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (res.code == 200 && res.data) {
          if (this.data.themeBag.scope === 'TEAM') return false;
          this._applyThemeView(res.data);
          if (this._routeState && String(this._routeState.sessionId) === String(res.data.sessionId) && this._routeState.version < res.data.version && !this._themeSending) this.refreshRouteState();
          if (this.data.chapterFull) this._enterThemeChapter();
=======
        if (!this._themeLive(epoch, context) || mutation !== (this._themeMutation || 0)) return false;
        if (res.code == 200 && res.data && this._themeNodesSession && !same(this._themeNodesSession, res.data.sessionId)) {
          // Team replay can happen between /nodes and /state. Rebuild the whole screen instead of
          // combining new inventory with the previous run's progress/configuration.
          this._themeEnabled = false; this._retireThemeCache(); this._stopThemePoll();
          this._cancelThemeReads(true);
          this.setData({ themeRuntimeReady: false, themeBackpackOpen: false });
          if (this.loadData) this.loadData(false);
          return false;
        }
        if (res.code == 200 && this._applyThemeView(res.data)) {
          if (this._routeState && same(this._routeState.sessionId, res.data.sessionId)
              && this._routeState.version < res.data.version && !this._themeSending && this.refreshRouteState) this.refreshRouteState();
>>>>>>> private-github/featu
          if (res.data.status !== 'ACTIVE') this._stopThemePoll(); else this._startThemePoll();
          if (res.data.teamReplayRequired) this._loadThemeTeam();
          if (res.data.stepEggs && this._askWeRunOnce && this._syncThoughts && !this._themeStepBaseline) {
            this._themeStepBaseline = true; this._askWeRunOnce(); this._syncThoughts([], this._themeSyncGuard());
          }
          if (this.data.chapterFull) this._enterThemeChapter();
          return true;
        }
        if (scopeDenied(res)) this._themeReset();
        else if (this.data.themeBackpackOpen) this.setData({ themeBagError: res.msg || '背包暂未加载，点击重试' });
        return false;
      }).catch(() => {
        if (this._themeLive(epoch, context)) this.setData({ themeBagError: '背包暂未加载，点击重试' });
        return false;
      }).finally(() => { if (this._themeLoading === loading) this._themeLoading = null; });
      this._themeLoading = loading; return loading;
    },
    openThemeBackpack() {
      if (!this._themeAllowed()) return Promise.resolve(false);
      this.setData({ themeBackpackOpen: true, themeBackpackTab: 'items' });
      return this.refreshThemeBackpack();
    },
    closeThemeBackpack() { this._cancelThemeReads(true); this.setData({ themeBackpackOpen: false }); },
    changeThemeBackpackTab(e) {
      const key = e && e.detail && e.detail.key;
      if (!['items', 'clues', 'history'].includes(key)) return Promise.resolve(false);
      this.closeThemeCard();
      if (key !== 'history') { this._themeHistorySeq = (this._themeHistorySeq || 0) + 1; this._themeHistoryLoading = null; this.setData({ themeHistoryBusy: false }); }
      this.setData({ themeBackpackTab: key });
      return key === 'history' && !this.data.themeHistoryLoaded ? this.loadThemeHistory() : Promise.resolve(true);
    },
    _cancelThemeReads(clear) {
      this._themeCardProjection = null; this._retireThemePoster();
      this._themeHistorySeq = (this._themeHistorySeq || 0) + 1;
      this._themeCardSeq = (this._themeCardSeq || 0) + 1;
      this._themeHistoryLoading = null; this._themeCardLoading = null;
      const state = { themeHistoryBusy: false, themeCardBusy: false, themeCardShow: false, themeCard: null, themeCardTarget: null, themeCardError: '' };
      if (clear) Object.assign(state, { themeHistoryRows: [], themeHistoryCursor: '', themeHistoryHasMore: false, themeHistoryLoaded: false, themeHistoryError: '', themeHistoryExpandedId: '' });
      if (this.data.themeCardShow || this.data.themeHistoryBusy || this.data.themeCardBusy || this.data.themeHistoryError || this.data.themeCardError || (clear && (this.data.themeHistoryLoaded || this.data.themeHistoryRows.length))) this.setData(state);
    },
    _themeReadLive(epoch, context) {
      return this._themeLive(epoch, context) && this._themeAllowed() && this.data.themeBackpackOpen;
    },
    loadThemeHistory() {
      if (!this._themeAllowed() || !this.data.themeBackpackOpen || this.data.themeBackpackTab !== 'history') return Promise.resolve(false);
      if (this._themeHistoryLoading) return this._themeHistoryLoading.promise;
      if (this.data.themeHistoryLoaded && !this.data.themeHistoryHasMore) return Promise.resolve(false);
      const cursor = this.data.themeHistoryLoaded ? this.data.themeHistoryCursor : '';
      const params = { topicId: this.data.topicId, limit: HISTORY_PAGE_SIZE };
      if (cursor) params.beforeSessionId = cursor;
      const epoch = this._themeEpoch || 0, context = this._themeContext(), seq = this._themeHistorySeq = (this._themeHistorySeq || 0) + 1;
      const loading = {}; this._themeHistoryLoading = loading;
      this.setData({ themeHistoryBusy: true, themeHistoryError: '' });
      const live = () => this._themeReadLive(epoch, context) && seq === this._themeHistorySeq && this.data.themeBackpackTab === 'history';
      loading.promise = req('/api/play/theme/history', 'GET', params, null, true).then(res => {
        if (!live()) return false;
        if (res.code != 200) throw new Error(res.msg || '历史记录暂未加载');
        const rows = presentHistoryPage(res.data, this.data.topicId, cursor);
        const previous = cursor ? this.data.themeHistoryRows : [];
        this.setData({ themeHistoryRows: previous.concat(rows), themeHistoryLoaded: true,
          themeHistoryCursor: rows.length ? rows[rows.length - 1].sessionId : cursor,
          themeHistoryHasMore: rows.length === HISTORY_PAGE_SIZE, themeHistoryError: '' });
        return true;
      }).catch(err => {
        if (live()) this.setData({ themeHistoryError: err.message || '历史记录暂未加载，点击重试' });
        return false;
      }).finally(() => {
        if (this._themeHistoryLoading === loading) { this._themeHistoryLoading = null; if (live()) this.setData({ themeHistoryBusy: false }); }
      });
      return loading.promise;
    },
    reloadThemeHistory() {
      if (!this._themeAllowed() || !this.data.themeBackpackOpen) return Promise.resolve(false);
      this._cancelThemeReads(true);
      this.setData({ themeBackpackTab: 'history' });
      return this.loadThemeHistory();
    },
    toggleHistoryThemeRecords(e) {
      if (!this._themeAllowed() || !this.data.themeBackpackOpen || this.data.themeCardShow || this.data.themeBackpackTab !== 'history') return false;
      const id = exactReadId(e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.sessionid);
      const row = this.data.themeHistoryRows.find(item => item.sessionId === id);
      if (!row || !Array.isArray(row.recordedEvents) || !row.recordedEvents.length) return false;
      this.setData({ themeHistoryExpandedId: this.data.themeHistoryExpandedId === id ? '' : id });
      return true;
    },
    openCurrentThemeCard() {
      const current = this.data.themeBag;
      if (!terminal(current) || !current.result) return Promise.resolve(false);
      return this._readThemeCard({ sessionId: current.sessionId, sceneName: current.sceneName || '', activityLabel: activityLabel(current.activityId) });
    },
    openHistoryThemeCard(e) {
      const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.sessionid;
      const row = this.data.themeHistoryRows.find(item => same(item.sessionId, id));
      if (!row || !row.hasCard) return Promise.resolve(false);
      return this._readThemeCard(row);
    },
    _readThemeCard(row) {
      if (!this._themeAllowed() || !this.data.themeBackpackOpen || !exactReadId(row && row.sessionId)) return Promise.resolve(false);
      this._themeCardProjection = null; this._retireThemePoster();
      const target = { sessionId: exactReadId(row.sessionId), sceneName: row.sceneName || '', activityLabel: row.activityLabel || '' };
      const epoch = this._themeEpoch || 0, context = this._themeContext(), seq = this._themeCardSeq = (this._themeCardSeq || 0) + 1;
      const loading = {}; this._themeCardLoading = loading;
      this.setData({ themeCardShow: true, themeCardBusy: true, themeCardTarget: target, themeCard: null, themeCardError: '' });
      const live = () => this._themeReadLive(epoch, context) && seq === this._themeCardSeq && this.data.themeCardShow;
      loading.promise = req('/api/play/theme/card', 'GET', { sessionId: target.sessionId }, null, true).then(res => {
        if (!live()) return false;
        if (res.code != 200) throw new Error(res.msg || '本局结果暂未加载');
        // Only /card's public projection is rendered. Never substitute the raw themeView.result.
        const card = presentCard(res.data);
        this._themeCardProjection = card;
        this.setData({ themeCard: card, themeCardError: '' });
        return true;
      }).catch(err => {
        if (live()) this.setData({ themeCardError: err.message || '本局结果暂未加载，点击重试' });
        return false;
      }).finally(() => {
        if (this._themeCardLoading === loading) { this._themeCardLoading = null; if (live()) this.setData({ themeCardBusy: false }); }
      });
      return loading.promise;
    },
    retryThemeCard() { return this.data.themeCardTarget ? this._readThemeCard(this.data.themeCardTarget) : Promise.resolve(false); },
    closeThemeCard() {
      this._themeCardProjection = null; this._retireThemePoster();
      this._themeCardSeq = (this._themeCardSeq || 0) + 1; this._themeCardLoading = null;
      this.setData({ themeCardShow: false, themeCardBusy: false, themeCard: null, themeCardTarget: null, themeCardError: '' });
    },
    _retireThemePoster() {
      this._themePosterSeq = (this._themePosterSeq || 0) + 1;
      if (this._themePosterLease) this._themePosterLease.dispose();
      this._themePosterLease = null; this._themePosterGuard = null;
      // In-flight native work owns its pin until callback/timeout; never launch another in parallel.
      this.setData({ themePosterPath: '', themePosterBusy: !!this._themePosterRendering,
        themePosterNativeBusy: !!this._themePosterNative, themePosterError: '', themePosterNotice: '',
        themePosterClipped: false, themePosterTextOnly: false, themePosterShareAvailable: false });
    },
    _themePosterLive(guard) {
      return !!guard && this._themeAllowed() && this._themeReadLive(guard.epoch, guard.context) && this.data.themeRuntimeReady
        && this.data.themeCardShow && !this.data.themeCardBusy && !!this.data.themeCard
        && guard.card === this._themeCardProjection && guard.cardSeq === this._themeCardSeq
        && guard.seq === this._themePosterSeq;
    },
    generateThemePoster() { return this._generateThemePoster(false); },
    generateThemeTextPoster() { return this._generateThemePoster(true); },
    _generateThemePoster(textOnly) {
      if (!this._themeAllowed() || !this.data.themeRuntimeReady || !this.data.themeBackpackOpen || !this.data.themeCardShow
          || this.data.themeCardBusy || !this.data.themeCard || !this._themeCardProjection
          || this._themePosterRendering || this._themePosterNative) return Promise.resolve(false);
      this._retireThemePoster();
      const guard = { epoch: this._themeEpoch || 0, context: this._themeContext(), cardSeq: this._themeCardSeq,
        card: this._themeCardProjection, seq: this._themePosterSeq };
      const job = {}; this._themePosterRendering = job;
      this.setData({ themePosterBusy: true });
      return Promise.resolve().then(() => {
        // This reference is populated only by the selected successful /card response, never /state or /history.
        const snapshot = resultPoster.publicSnapshot(guard.card, textOnly);
        return resultPoster.render(posterApi(), snapshot, () => this._themePosterLive(guard));
      }).then(lease => {
        if (!this._themePosterLive(guard)) { lease.dispose(); return false; }
        this._themePosterLease = lease; this._themePosterGuard = guard;
        this.setData({ themePosterPath: lease.path, themePosterClipped: lease.clipped,
          themePosterTextOnly: lease.textOnly, themePosterShareAvailable: supportsResultImageShare(posterApi()) });
        return true;
      }).catch(error => {
        if (this._themePosterLive(guard)) this.setData({ themePosterError: error.code === 'COVER'
          ? '封面未能加载，请重试或选择生成文字版' : error.code === 'CANVAS'
            ? '当前微信版本暂不支持结果图，请稍后重试' : '结果图未能生成，请重试' });
        return false;
      }).finally(() => {
        if (this._themePosterRendering === job) { this._themePosterRendering = null; this.setData({ themePosterBusy: false }); }
      });
    },
    saveThemePoster() { return this._useThemePoster('save'); },
    shareThemePoster() { return this._useThemePoster('share'); },
    _useThemePoster(operation) {
      const guard = this._themePosterGuard, lease = this._themePosterLease;
      if (!this._themePosterLive(guard) || !lease || this._themePosterNative || this._themePosterRendering) return Promise.resolve(false);
      const api = posterApi(), method = operation === 'save' ? 'saveImageToPhotosAlbum' : 'showShareImageMenu';
      if (!api || typeof api[method] !== 'function' || (operation === 'share' && !supportsResultImageShare(api))) {
        this.setData({ themePosterError: operation === 'save' ? '当前微信版本暂不支持保存图片' : '当前微信版本暂不支持安全分享图片' });
        return Promise.resolve(false);
      }
      const release = lease.pin(), job = {}; this._themePosterNative = job;
      this.setData({ themePosterNativeBusy: true, themePosterError: '', themePosterNotice: '' });
      // No route/query, entrancePath or page-share hook is involved. No automatic permission/settings request.
      return resultPoster.callbackOperation((success, fail) => api[method](operation === 'save'
        ? { filePath: lease.path, success, fail }
        : { path: lease.path, needShowEntrance: false, success, fail }), resultPoster.NATIVE_TIMEOUT).then(() => {
        if (this._themePosterLive(guard)) this.setData({ themePosterNotice: operation === 'save' ? '已保存到相册' : '已打开系统分享菜单' });
        return true;
      }).catch(error => {
        if (this._themePosterLive(guard)) this.setData({ themePosterError: /cancel/i.test(error.message || '')
          ? '已取消，可重新选择' : operation === 'save' ? '未能保存，请确认相册权限后手动重试' : '未能打开分享菜单，请重试' });
        return false;
      }).finally(() => {
        release();
        if (this._themePosterNative === job) { this._themePosterNative = null; this.setData({ themePosterNativeBusy: false }); }
      });
    },
<<<<<<< public
    openThemeBackpack() { this.setData({ themeBackpackOpen: true, themeBackpackTab: this.data.themeBag.awaitingChallenge || this.data.zeroHpView ? 'game' : 'items', themeBagError: '' }); return this.refreshThemeBackpack(); },
    openThemeGameplay() { this.setData({ sessionToolsOpen: false, themeBackpackOpen: true, themeBackpackTab: (this.data.themeBag.planRows || []).length || this.data.themeBag.actionRows.length || this.data.themeBag.tradeTargets.length || this.data.themeBag.tradeRows.length || this.data.themeBag.awaitingChallenge ? 'game' : 'items', themeBagError: '' }); return this.refreshThemeBackpack(); },
    _applyThemeTeamView(player, projection) {
      this._applyThemeView(Object.assign({}, player, { scope: 'TEAM', sessionId: projection.sessionId,
        activityId: projection.activityId, revision: projection.revision, status: projection.status, claimOnly: rewards.ended(player),
        availableActions: projection.availableActions || [], inventoryEnabled: true,
        hpEnabled: typeof player.hp === 'number', authorityEpoch: player.authorityEpoch }));
    },
    closeThemeBackpack() { this.setData({ themeBackpackOpen: false }); },
    changeThemeBackpackTab(e) { this.setData({ themeBackpackTab: e.currentTarget && e.currentTarget.dataset.key || e.detail.key }); },
    // 背包物资动作：团队局走团队命令（只有 ITEM_USE/ITEM_DISCARD/ITEM_RECEIVE），个人局走主题动作
    _themeCommand(operation, contentId, discardOwned) {
      const view = this.data.themeBag;
      if (!this._themePending && !this._gameplayPending && rewards.ended(view) && (operation !== 'RECEIVE' || !rewards.canClaim(view, contentId))) return Promise.resolve(false);
      if (view.scope === 'TEAM') return this._themeGameplayCommand(Object.assign({ operation: 'ITEM_' + operation, contentId }, discardOwned === undefined ? {} : { discardOwned }));
      return this._themePersonalCommand(operation, contentId, discardOwned);
    },
    // 通用局在任务结算/路线推进时发章节奖励；只有旧版个人局发送章节动作。
    _themeChapterCommand(operation, contentId) {
      if (this.data.themeBag.scope === 'TEAM' || this.data.themeBag.runtimeScope === 'PLAYER' || rewards.ended(this.data.themeBag)) return Promise.resolve(true);
      return this._themePersonalCommand(operation, contentId);
    },
    _themePersonalCommand(operation, contentId, discardOwned) {
      if (this._themeSending) return Promise.resolve(false);
      if (this._gameplayPending || this._tradePending || this._planPending) { toast('请先核对上一条操作'); return Promise.resolve(false); }
      if (this._themePending && (this._themePending.operation !== operation || this._themePending.contentId !== contentId)) { toast('请先重试上一条操作'); return Promise.resolve(false); }
      const view = this.data.themeBag;
      if (!view.sessionId || this.data.isPreview || this.data.isMock) return Promise.resolve(false);
      if (!this._themePending && rewards.ended(view) && (operation !== 'RECEIVE' || !rewards.canClaim(view, contentId))) return Promise.resolve(false);
      const command = this._themePending || Object.assign({ sessionId: view.sessionId, expectedVersion: view.version, actionId: 'bag_' + Date.now().toString(36) + Math.random().toString(36).slice(2), operation, contentId }, discardOwned === undefined ? {} : { discardOwned });
      this._themePending = command; this._themeSending = true;
      this.setData({ themeBagBusy: true, themeBagError: '' });
      const epoch = this._themeEpoch || 0;
      return req('/api/play/theme/action', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (res.code == 200 && res.data) {
          this._themePending = null; this.setData({ themeBagUnknown: false }); this._applyThemeView(res.data);
          if (operation === 'RECEIVE') { toast('待领奖励已领取'); this.setData({ themePendingClaim: null }); }
          return this.refreshThemeBackpack().then(() => this.refreshRouteState()).then(() => true);
        }
        if (res.netFail) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        else { this._themePending = null; const message = res.msg || '操作失败'; return this.refreshThemeBackpack().then(() => { this.setData({ themeBagUnknown: false, themeBagError: message }); return false; }); }
        return false;
      }).finally(() => { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); });
    },
    onThemeBagAction(e) {
      const d = e.currentTarget.dataset;
      if (d.operation === 'RECEIVE' && rewards.ended(this.data.themeBag)) return this.openThemePendingClaim(d.id);
      const item = this.data.themeBag.items.find(row => row.id === d.id);
      if (item && (d.operation === 'USE' && !item.canUse || d.operation === 'DISCARD' && !item.canDiscard)) return Promise.resolve(false);
      this.setData({ themeItemDetail: null }); return this._themeCommand(d.operation, d.id);
    },
    retryThemeBagAction() { if (this._themeSending) return Promise.resolve(false); if (this._gameplayPending && this._gameplayPending.input.operation.startsWith('QUEST_')) return this._readThemeGameplayReceipt(); return this._gameplayPending ? this._themeGameplayCommand(this._gameplayPending.input) : this._themePending ? this._themeCommand(this._themePending.operation, this._themePending.contentId, this._themePending.discardOwned) : this.refreshThemeBackpack(); },
    onThemeGameplayAction(e) {
      const row = (this.data.themeBag.actionRows || [])[Number(e.currentTarget.dataset.index)];
      const opening = e.currentTarget.dataset.open === true || e.currentTarget.dataset.open === 'true';
      if (!row || (opening ? !row.openable : !row.enabled) || this._themeSending) return Promise.resolve(false);
      if (opening || row.operation === 'OPEN_QUEST' || row.operation === 'OPEN_OPERATION') {
        const node = this._nodeById(Number(row.nodeId));
        if (!node) { this.setData({ themeBagError: '任务节点尚未加载，请稍后重试' }); return Promise.resolve(false); }
        this.setData({ themeBackpackOpen: false });
        if (row.operation === 'OPEN_OPERATION') {
          this.setData({ advancedPlay: { show: true, node }, playKit: { show: false, kit: null } });
        } else if (!node.hasAdvanced && node.vm === 5) {
          this.stopNav(); this.setData({ 'sheet.node': node }); this.onArriveTap();
        } else this.startGame(node);
        return Promise.resolve(true);
      }
      if ((row.operation === 'QUEST_START' || row.operation === 'OPERATION_START' || row.operation === 'FACILITY_USE') && row.costOptions.length && row.frozenIndex < 0) {
        this.setData({ themeCostChoice: { row, selected: -1 } }); return Promise.resolve(true);
      }
      return this._prepareThemeGameplayAction({ operation: row.operation, contentId: row.key }, row);
    },
    onThemeCancelGameplayAction(e) {
      const row = (this.data.themeBag.actionRows || [])[Number(e.currentTarget.dataset.index)];
      if (!row || !row.cancelable) return Promise.resolve(false);
      return this._themeGameplayCommand({ operation: row.kind === 'QUEST' ? 'QUEST_CANCEL' : 'OPERATION_CANCEL', contentId: row.key });
    },
    selectThemeCostOption(e) {
      const choice = this.data.themeCostChoice;
      const index = Number(e.currentTarget.dataset.index);
      if (choice && choice.row.costOptions[index] && choice.row.costOptions[index].affordable) this.setData({ 'themeCostChoice.selected': index });
    },
    closeThemeCostChoice() { this.setData({ themeCostChoice: null }); },
    confirmThemeCostChoice() {
      const choice = this.data.themeCostChoice;
      if (!choice || choice.selected < 0 || !choice.row.costOptions[choice.selected].affordable) return Promise.resolve(false);
      this.setData({ themeCostChoice: null });
      return this._prepareThemeGameplayAction({ operation: choice.row.operation, contentId: choice.row.key, costOption: choice.selected }, choice.row);
    },
    openThemeItem(e) {
      const item = this.data.themeBag.items.find(row => row.id === e.currentTarget.dataset.id);
      if (item) this.setData({ themeItemDetail: item });
    },
    closeThemeItem() { this.setData({ themeItemDetail: null }); },
    showThemeItemInfo() { this.setData({ 'themeItemDetail.showInfo': true }); },
    _prepareThemeGameplayAction(input, row) {
      if (!['QUEST_RESOLVE', 'FACILITY_USE'].includes(input.operation)) return this._themeGameplayCommand(input);
      if (input.operation === 'FACILITY_USE' && row.targetScope === 'PARTICIPANTS') {
        const view = this.data.themeBag;
        const choice = gameplay.participantChoice(view, input);
        const own = choice.rows && choice.rows.find(team => team.teamId === view.teamId && team.eligible === true);
        if (own && choice.payerRequired) return this._readThemeRewardPreview({ ...input, participants: [own.teamId] });
        this.setData({ themeRewardPreview: { input, participantRows: choice.rows, payerRequired: choice.payerRequired, pack: null, loading: false, error: choice.rows ? '参与目录缺少可参与的本队，请刷新后重试' : choice.error } });
        return Promise.resolve(false);
      }
      if (input.operation === 'QUEST_RESOLVE' && row.choices && row.choices.length > 1) {
        this.setData({ themeQuestChoice: { input, choices: row.choices } }); return Promise.resolve(true);
      }
      if (row.choices && row.choices.length === 1) input.choiceKey = row.choices[0].choiceKey;
      return this._readThemeRewardPreview(input);
    },
    selectThemeQuestChoice(e) {
      const sheet = this.data.themeQuestChoice;
      const choice = sheet && sheet.choices[Number(e.currentTarget.dataset.index)];
      if (!choice) return Promise.resolve(false);
      this.setData({ themeQuestChoice: null });
      return this._readThemeRewardPreview({ ...sheet.input, choiceKey: choice.choiceKey });
    },
    closeThemeQuestChoice() { this.setData({ themeQuestChoice: null }); },
    selectThemeParticipant(e) {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || this._themeSending || this._gameplayPending) return Promise.resolve(false);
      const id = Number(e.currentTarget.dataset.id);
      const choice = gameplay.participantChoice(this.data.themeBag, sheet.input);
      const target = choice.rows && choice.rows.find(row => row.teamId === id);
      if (!target || target.eligible !== true || target.required) return Promise.resolve(false);
      const ids = choice.participants.includes(id) ? choice.participants.filter(key => key !== id) : choice.participants.concat(id);
      return this._readThemeRewardPreview({ ...sheet.input, participants: ids, discardRewards: [] });
    },
    _readThemeRewardPreview(input) {
      const view = this.data.themeBag;
      if (!view.sessionId || rewards.ended(view) || this._themeSending || this._themePending || this._gameplayPending || this._tradePending || this._planPending || this.data.isMock || this.data.isPreview) return Promise.resolve(false);
      const team = view.scope === 'TEAM';
      const sequence = this._themePreviewSequence = (this._themePreviewSequence || 0) + 1;
      const epoch = this._themeEpoch || 0;
      const participant = gameplay.participantChoice(view, input);
      if (participant.error) {
        this.setData({ themeRewardPreview: { input, participantRows: participant.rows, payerRequired: participant.payerRequired, pack: null, loading: false, error: participant.error } });
        return Promise.resolve(false);
      }
      const previous = this.data.themeRewardPreview;
      this.setData({ themeRewardPreview: { input, participantRows: participant.rows, payerRequired: participant.payerRequired, pack: previous && previous.pack, loading: true, error: '' } });
      const choices = {};
      for (const key of ['choiceKey', 'costOption', 'discardRewards']) if (input[key] !== undefined) choices[key] = input[key];
      if (participant.participants) choices.participants = participant.participants;
      const command = team ? { activityId: view.activityId, action: input.operation, payload: {
        [input.operation === 'FACILITY_USE' ? 'facilityKey' : 'questKey']: input.contentId, ...choices } }
        : { sessionId: view.sessionId, expectedVersion: view.version, operation: input.operation, contentId: input.contentId, ...choices };
      return req(team ? '/api/game/session/shared/preview' : '/api/play/theme/gameplay-preview', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (sequence !== this._themePreviewSequence || epoch !== (this._themeEpoch || 0)) return false;
        const pack = res.code == 200 && res.data && rewards.preview(res.data, view);
        const valid = pack && (team ? Number.isSafeInteger(res.data.expectedRevision) && Number.isSafeInteger(res.data.authorityEpoch)
          : String(res.data.sessionId) === String(view.sessionId) && Number.isSafeInteger(res.data.version));
        this.setData({ themeRewardPreview: { input, participantRows: participant.rows, payerRequired: participant.payerRequired, pack: valid ? pack : null, loading: false,
          basis: valid ? { sessionId: view.sessionId, version: res.data.version, revision: res.data.expectedRevision, authorityEpoch: res.data.authorityEpoch } : null,
          error: valid ? '' : res.msg || '奖励预览暂未加载，请重新读取' } });
        return !!valid;
      });
    },
    changeThemeRewardDiscard(e) {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || sheet.loading || !sheet.pack) return Promise.resolve(false);
      const owner = sheet.pack.owners.find(row => row.own);
      const row = owner && owner.rewards.find(item => item.itemId === e.currentTarget.dataset.id);
      const quantity = Number(e.detail.value);
      if (!row || !Number.isSafeInteger(quantity) || quantity < 0 || quantity > row.quantity) {
        this.setData({ 'themeRewardPreview.error': '放弃数量须为 0 到本次奖励数量之间的整数' }); return Promise.resolve(false);
      }
      const rows = owner.rewards.map(item => item === row ? { ...item, discard: quantity } : item);
      return this._readThemeRewardPreview({ ...sheet.input, discardRewards: rewards.selection(rows) });
    },
    rereadThemeRewardPreview() {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || sheet.loading) return Promise.resolve(false);
      const reset = !sheet.pack && sheet.error && sheet.input.discardRewards;
      const input = reset ? { ...sheet.input, discardRewards: [] } : sheet.input;
      return this.refreshThemeBackpack().then(() => this._readThemeRewardPreview(input)).then(ok => {
        if (ok && reset) this.setData({ 'themeRewardPreview.notice': '已重新读取全部奖励，请重新选择要放弃的数量' });
        return ok;
      });
    },
    closeThemeRewardPreview() { this._themePreviewSequence = (this._themePreviewSequence || 0) + 1; this.setData({ themeRewardPreview: null }); },
    confirmThemeRewardPreview() {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || sheet.loading || sheet.error || !sheet.pack || sheet.pack.overflow || this._themeSending) return Promise.resolve(false);
      this._themeCommandError = '';
      return this._themeGameplayCommand({ ...sheet.input, previewVersion: sheet.pack.previewVersion,
        expectedVersion: sheet.basis.version, expectedRevision: sheet.basis.revision, authorityEpoch: sheet.basis.authorityEpoch }).then(ok => {
        if (ok) this.closeThemeRewardPreview();
        else if (!this.data.themeBagUnknown && /预览.*过期|本局已更新|REVISION|STALE/.test(this._themeCommandError || '')) {
          return this._readThemeRewardPreview(sheet.input).then(loaded => {
            this.setData({ 'themeRewardPreview.notice': loaded ? '状态已变化，已重新读取奖励，请再次确认' : '状态已变化，请重新读取并调整放弃选择' }); return false;
          });
        } else this.setData({ 'themeRewardPreview.error': this.data.themeBagError || '操作失败，请核对后重试' });
        return ok;
      });
    },
    openThemePendingClaim(source) {
      const view = this.data.themeBag;
      if (!rewards.ended(view) || !rewards.canClaim(view, source)) return Promise.resolve(false);
      this.setData({ themePendingClaim: { source, reward: { ...view.pending[source], name: view.pending[source].name || rewards.itemName(view.pending[source].itemId, view) },
        owned: (view.items || view.contents || []).filter(row => row.kind === 'ITEM').map(row => ({ ...row, discard: 0 })), error: '' } });
      return Promise.resolve(true);
    },
    changeThemeOwnedDiscard(e) {
      const sheet = this.data.themePendingClaim;
      if (!sheet || this._themeSending || this._themePending || this._gameplayPending) return;
      const index = sheet.owned.findIndex(row => row.id === e.currentTarget.dataset.id);
      const quantity = Number(e.detail.value);
      if (!this.data.themeBag.allowDiscard || index < 0 || !Number.isSafeInteger(quantity) || quantity < 0 || quantity > sheet.owned[index].quantity) {
        this.setData({ 'themePendingClaim.invalid': true, 'themePendingClaim.error': '丢弃数量须为 0 到持有数量之间的整数' }); return;
      }
      this.setData({ ['themePendingClaim.owned.' + index + '.discard']: quantity, 'themePendingClaim.invalid': false, 'themePendingClaim.error': '' });
    },
    closeThemePendingClaim() { if (!this._themeSending) this.setData({ themePendingClaim: null }); },
    confirmThemePendingClaim() {
      const sheet = this.data.themePendingClaim;
      if (!sheet || sheet.invalid || this._themeSending) return Promise.resolve(false);
      return this._themeCommand('RECEIVE', sheet.source, rewards.selection(sheet.owned)).then(ok => {
        if (ok) this.setData({ themePendingClaim: null });
        else this.setData({ 'themePendingClaim.error': this.data.themeBagError || '领取失败，请核对库存后重试' });
        return ok;
      });
    },
    async _readThemeGameplayReceipt(failureMessage) {
      const pending = this._gameplayPending;
      if (!pending) return false;
      const { command } = pending, team = command.activityId != null;
      const epoch = this._themeEpoch || 0;
      this._themeSending = true; this.setData({ themeBagBusy: true });
      try {
        const result = team ? await client().readReceipt(command.activityId, command.requestId) : null;
        if (epoch !== (this._themeEpoch || 0)) return false;
        const receipt = result && result.receipt;
        const matched = receipt && receipt.action === command.action && receipt.requestId === command.requestId && String(receipt.activityId) === String(command.activityId);
        const applied = matched && result.status === 'matched';
        const failed = matched && result.status === 'business-error' && receipt.outcome === 'FAILED';
        if (applied || failed || failureMessage) this._gameplayPending = null;
        if (failureMessage) this._themeCommandError = failureMessage;
        if (applied && pending.input.previewVersion) this.closeThemeRewardPreview();
        await this.refreshThemeBackpack(); await this.refreshRouteState();
        if (epoch !== (this._themeEpoch || 0)) return false;
        this.setData({ themeBagUnknown: !applied && !failed && !failureMessage, themeBagError: applied ? '' : failureMessage || (failed ? result.message : (pending.error ? pending.error + '；' : '') + '操作结果待核对，请勿重复提交') });
        return !!applied;
      } finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    _themeGameplayCommand(input) {
      const view = this.data.themeBag;
      if (this._themeSending || !view.sessionId || this.data.isPreview || this.data.isMock) return Promise.resolve(false);
      if (this._themePending || this._tradePending || this._planPending) { toast('请先核对上一条操作'); return Promise.resolve(false); }
      if (this._gameplayPending && JSON.stringify(this._gameplayPending.input) !== JSON.stringify(input)) { toast('请先核对上一条操作'); return Promise.resolve(false); }
      if (this._gameplayPending && input.operation.startsWith('QUEST_') && this.data.themeBagUnknown) return this._readThemeGameplayReceipt();
      if (!this._gameplayPending && rewards.ended(view) && (input.operation !== 'ITEM_RECEIVE' || !rewards.canClaim(view, input.contentId))) return Promise.resolve(false);
      const team = view.scope === 'TEAM';
      const payload = team ? { contentId: input.contentId } : null;
      if (team && input.operation.startsWith('QUEST_')) { delete payload.contentId; payload.questKey = input.contentId; }
      if (team && input.operation === 'OPERATION_START') { delete payload.contentId; payload.operationKind = 'FACILITY'; payload.operationKey = input.contentId; }
      if (team && input.operation === 'OPERATION_CANCEL') delete payload.contentId;
      if (team && input.operation === 'FACILITY_USE') { delete payload.contentId; payload.facilityKey = input.contentId; }
      if (team && input.operation === 'RECOVER') { delete payload.contentId; payload.recoveryKey = input.contentId; }
      if (team && input.operation.startsWith('ZERO_HP_')) delete payload.contentId;
      const selections = {};
      for (const key of ['costOption', 'choiceKey', 'discardRewards', 'previewVersion', 'discardOwned', 'weRun']) if (input[key] !== undefined) selections[key] = input[key];
      if (!this._gameplayPending) {
        const participant = gameplay.participantChoice(view, input);
        if (participant.error) { this.setData({ themeBagError: participant.error }); return Promise.resolve(false); }
        if (participant.participants) selections.participants = participant.participants;
      }
      if (team) Object.assign(payload, selections);
      const command = this._gameplayPending ? this._gameplayPending.command : team
        ? { activityId: view.activityId, requestId: 'pg-theme-' + Date.now() + '-' + Math.random().toString(36).slice(2), expectedRevision: input.expectedRevision === undefined ? view.revision : input.expectedRevision,
          action: input.operation, payload, authorityEpoch: input.authorityEpoch === undefined ? view.authorityEpoch : input.authorityEpoch }
        : Object.assign({ sessionId: view.sessionId, expectedVersion: input.expectedVersion === undefined ? view.version : input.expectedVersion,
          actionId: 'theme_' + Date.now() + Math.random().toString(36).slice(2), operation: input.operation, contentId: input.contentId },
          selections);
      this._gameplayPending = { input, command }; this._themeSending = true;
      this.setData({ themeBagBusy: true, themeBagError: '' });
      const epoch = this._themeEpoch || 0;
      return req(team ? '/api/game/session/command' : '/api/play/theme/gameplay-action', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        const receipt = res.data || {};
        if (input.operation.startsWith('QUEST_') && res.code != 200 && !res.netFail && !res.httpFail) this._gameplayPending.error = res.msg || '操作失败';
        if (input.operation.startsWith('QUEST_') && (res.code != 200 || team && (receipt.replayed || receipt.outcome !== 'APPLIED' || receipt.requestId !== command.requestId || receipt.action !== command.action || String(receipt.activityId) !== String(command.activityId)))) return this._readThemeGameplayReceipt(!team && !res.netFail && !res.httpFail && res.code != 200 ? res.msg || '操作失败' : undefined);
        const sameReceipt = team && String(receipt.activityId) === String(command.activityId)
          && receipt.requestId === command.requestId && receipt.action === command.action
          && Number.isSafeInteger(receipt.receiptId) && receipt.receiptId > 0
          && Number.isSafeInteger(receipt.revision);
        if (res.code == 200 && (team ? sameReceipt && receipt.outcome === 'APPLIED'
          : res.data && String(receipt.sessionId) === String(command.sessionId) && Number.isSafeInteger(receipt.version))) {
          this._gameplayPending = null;
          this.setData({ themeBagUnknown: false });
          const result = team ? receipt.result && (receipt.result.result || receipt.result) || {} : receipt.settlement || {};
          const text = trade.recoveryText(result, gameplay.labels(view).hp);
          if (text) { toast(text); this.setData({ themeRecoveryText: text }); }
          else if (input.operation === 'ITEM_RECEIVE') toast('待领奖励已领取');
          if (input.previewVersion) this.closeThemeRewardPreview();
          if (input.operation === 'ITEM_RECEIVE') this.setData({ themePendingClaim: null });
          const staleZeroReceipt = input.operation.startsWith('ZERO_HP_') && (String(receipt.sessionId) !== String(this.data.themeBag.sessionId) || this.data.themeBag.version > receipt.version);
          if (!team && !staleZeroReceipt) this._applyThemeView(receipt);
          return this.refreshThemeBackpack().then(() => input.operation.startsWith('QUEST_') ? this.refreshRouteState() : true).then(() => true);
        }
        if (input.operation.startsWith('QUEST_')) return this._readThemeGameplayReceipt();
        if (res.netFail || res.httpFail || res.code == 200 && (team ? !sameReceipt || receipt.outcome === 'PENDING' : true)) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        else {
          this._gameplayPending = null;
          const message = team && sameReceipt && receipt.outcome === 'FAILED' ? receipt.result && receipt.result.reason || '操作失败' : res.msg || '操作失败';
          this._themeCommandError = message;
          return this.refreshThemeBackpack().then(() => { this.setData({ themeBagUnknown: false, themeBagError: message }); return false; });
        }
        return false;
      }).catch(() => {
        if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
=======
    _themeCommand(operation, contentId) {
      if (!this._themeAllowed() || this._themeSending || this._themeReplaySending || this._themeReplayPending || this._themePreparing) return Promise.resolve(false);
      if (this._themePending && (this._themePending.operation !== operation || this._themePending.contentId !== contentId)) {
        this.setData({ themeBagError: '请先重试上一条操作' }); return Promise.resolve(false);
      }
      const view = this.data.themeBag;
      if (!view.sessionId) return Promise.resolve(false);
      const command = this._themePending || Object.freeze({ sessionId: view.sessionId, expectedVersion: view.version, actionId: actionId('bag_'), operation, contentId });
      this._themePending = command; this._themeMutation = (this._themeMutation || 0) + 1;
      const epoch = this._themeEpoch || 0, context = this._themeContext();
      const sending = {}; this._themeSending = sending;
      this.setData({ themeBagBusy: true, themeBagError: '' });
      return req('/api/play/theme/action', 'POST', command, { 'content-type': 'application/json' }, true).catch(() => ({ netFail: true })).then(res => {
        if (!this._themeLive(epoch, context)) return false;
        if (res.code == 200 && this._applyThemeView(res.data)) {
          this._themePending = null; this.setData({ themeBagUnknown: false });
          if (this.refreshRouteState) this.refreshRouteState(); return true;
        }
        if (unknown(res) || res.code == 200) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        else {
          this._themePending = null; this.setData({ themeBagUnknown: false, themeBagError: res.msg || '操作失败，请刷新后重新确认' });
          if (scopeDenied(res)) { this._themeReset(); return false; }
          return this.refreshThemeBackpack().then(() => false);
        }
>>>>>>> private-github/featu
        return false;
      }).finally(() => {
        if (this._themeSending === sending) { this._themeSending = null; if (this._themeLive(epoch, context)) this.setData({ themeBagBusy: false }); }
      });
    },
    _themeCurrentControlAllowed() {
      return this._themeAllowed() && this.data.themeBackpackOpen && !this.data.themeCardShow
        && this.data.themeBackpackTab !== 'history' && this.data.themeBag.status === 'ACTIVE';
    },
    requestThemeRescue() {
      if (!this._themeCurrentControlAllowed() || this.data.themeBag.awaitingRescue !== true) return Promise.resolve(false);
      return this._themeCommand('RESCUE', null);
    },
    requestThemeEnd() {
      if (!this._themeCurrentControlAllowed() || this._themePreparing || this._themeSending || this._themeReplaySending || this._themeReplayPending) return Promise.resolve(false);
      // The original confirmed END is still unresolved: the visible retry keeps its exact receipt key.
      if (this._themePending) return this._themePending.operation === 'END' ? this._themeCommand('END', null) : Promise.resolve(false);
      const epoch = this._themeEpoch || 0, context = this._themeContext(), preparing = {};
      this._themePreparing = preparing; this._themeControlRequest = preparing; this.setData({ themeControlBusy: true, themeBagError: '' });
      return this.refreshThemeBackpack().then(ok => {
        if (!ok || !this._themeLive(epoch, context) || this._themePreparing !== preparing || !this._themeCurrentControlAllowed()) return false;
        const current = this.data.themeBag;
        const intent = { sessionId: current.sessionId, playInstanceId: current.playInstanceId, version: current.version };
        return new Promise(resolve => {
          modal.show({ title: '结束我的当前局？',
            content: '确认后，你的当前局将结束，不能继续在这一局操作；结果按主题原规则结算，不会因此视为成功通关。旧记录保留。' + (current.teamReplayRequired ? '这不会替其他队员结束，也不会自动整队重开。' : ''),
            confirmText: '确认结束', cancelText: '继续游玩',
            success: answer => {
              if (!answer.confirm) return resolve(false);
              const latest = this.data.themeBag;
              if (!this._themeLive(epoch, context) || this._themePreparing !== preparing || !this._themeCurrentControlAllowed()
                  || !same(intent.sessionId, latest.sessionId) || intent.playInstanceId !== latest.playInstanceId || intent.version !== latest.version) {
                if (this._themeLive(epoch, context)) this.setData({ themeBagError: '本局状态已更新，请重新确认' });
                return resolve(false);
              }
              this._themePreparing = null;
              resolve(this._themeCommand('END', null));
            }, fail: () => resolve(false) });
        });
      }).finally(() => {
        if (this._themePreparing === preparing) this._themePreparing = null;
        if (this._themeControlRequest === preparing) { this._themeControlRequest = null; if (this._themeLive(epoch, context)) this.setData({ themeControlBusy: false }); }
      });
    },
<<<<<<< public
    openThemeChallenge(e) {
      const id = Number(e.currentTarget.dataset.id);
      const challenge = this.data.themeBag.challenge || {};
      if (rewards.ended(this.data.themeBag) || !this.data.themeBag.awaitingChallenge || challenge.disabledReason || !this.data.themeBag.challengeNodes.some(row => row.id === id)) return;
      const node = this._nodeById(id);
      if (!node) { this.setData({ themeBagError: '挑战节点尚未加载，请稍后重试' }); return; }
      this._themeChallengeNodeId = id;
      this.setData({ themeBackpackOpen: false, advancedPlay: { show: true, node }, playKit: { show: false, kit: null } });
=======
    onThemeBagAction(e) { const d = e.currentTarget.dataset; return this._themeCommand(d.operation, d.id); },
    retryThemeBagAction() { return this._themePending ? this._themeCommand(this._themePending.operation, this._themePending.contentId) : this.refreshThemeBackpack(); },
    _loadThemeTeam(expectedId) {
      if (!this._themeAllowed() || !positive(this.data.activityId)) return Promise.resolve(null);
      const epoch = this._themeEpoch || 0, context = this._themeContext();
      const boundId = expectedId || this.data.themeBag.teamId;
      return req('/api/team/my', 'POST', {}, null, true).then(res => {
        if (!this._themeLive(epoch, context)) return null;
        if (res.code != 200 || !Array.isArray(res.data)) {
          if (!unknown(res) && (res.code == 401 || res.code == 403 || /登录|认证/.test(res.msg || ''))) {
            this._themeReplayPending = null; this.setData({ themeReplayUnknown: false });
          }
          throw new Error(res.msg || '队伍信息暂未加载');
        }
        const teams = res.data.filter(t => t && Number(t.ownerType) === 2 && same(t.ownerId, this.data.activityId)
          && [0, 1].includes(Number(t.status)) && (!boundId || same(t.id, boundId)));
        if (teams.length !== 1) {
          this._themeTeam = null; this._themeReplayPending = null;
          this.setData({ themeTeamLeader: false, themeTeamNotice: teams.length ? '有多个队伍，请先确认本场队伍' : '当前未加入本场队伍', themeReplayUnknown: false });
          return null;
        }
        return req('/api/team/info', 'POST', { teamId: teams[0].id }, { 'content-type': 'application/json' }, true).then(info => {
          if (!this._themeLive(epoch, context)) return null;
          if (info.code != 200 || !info.data) {
            if (!unknown(info) && (info.code == 401 || info.code == 403 || /退出|解散|不存在|无权|失效|登录|认证|结束/.test(info.msg || ''))) {
              this._themeReplayPending = null; this.setData({ themeReplayUnknown: false });
            }
            throw new Error(info.msg || '队伍信息暂未加载');
          }
          const d = info.data, t = d.team;
          if (!t || !same(t.id, teams[0].id) || Number(t.ownerType) !== 2 || !same(t.ownerId, this.data.activityId)
              || ![0, 1].includes(Number(t.status)) || d.joined !== true) {
            this._themeTeam = null; this._themeReplayPending = null;
            this.setData({ themeTeamLeader: false, themeReplayUnknown: false, themeTeamNotice: '已退出或队伍已结束，请重新进入' }); return null;
          }
          const leader = d.leader === true && same(t.leaderMemberId, this._themeOwner());
          this._themeTeam = { id: t.id, leader, context };
          if (!leader && this._themeReplayPending) { this._themeReplayPending = null; this.setData({ themeReplayUnknown: false }); }
          this.setData({ themeTeamLeader: leader, themeTeamNotice: leader ? '由你确认后，当前队员共同进入新一局' : '整队重开需由当前队长确认' });
          return this._themeTeam;
        });
      }).catch(err => {
        if (this._themeLive(epoch, context)) { this._themeTeam = null; this.setData({ themeTeamLeader: false, themeTeamNotice: err.message || '队伍信息暂未加载' }); }
        return null;
      });
    },
    requestThemeReplay() {
      if (!this._themeAllowed() || this._themePreparing || this._themeReplaySending || this._themeSending || this._themePending) return Promise.resolve(false);
      if (this._themeReplayPending) return this.retryThemeReplay();
      const view = this.data.themeBag;
      if (!terminal(view)) { this.setData({ themeReplayError: '请先结束当前局，再重玩' }); return Promise.resolve(false); }
      const epoch = this._themeEpoch || 0, context = this._themeContext(), source = view.sessionId;
      const preparing = {}; this._themePreparing = preparing;
      this.setData({ themeReplayBusy: true, themeReplayError: '' });
      return (view.teamReplayRequired ? this._loadThemeTeam() : Promise.resolve(null)).then(team => {
        if (!this._themeLive(epoch, context) || !same(source, this.data.themeBag.sessionId) || (view.teamReplayRequired && (!team || !team.leader))) return false;
        return new Promise(resolve => {
          modal.show({ title: view.teamReplayRequired ? '整队一起重开？' : '开始新一局？',
            content: view.teamReplayRequired ? '当前队员会共同进入同一新局。每人的背包、分数和角色仍按原玩法处理，旧局保留为历史。需所有当前队员的旧局均已结束，是否确认？' : '本次行程保留为历史，按主题当前开放设置开始新一局。',
            confirmText: '确认重开', cancelText: '取消',
            success: result => {
              if (!result.confirm || !this._themeLive(epoch, context) || !same(source, this.data.themeBag.sessionId)) return resolve(false);
              const payload = view.teamReplayRequired ? {
                teamId: team.id, activityId: this.data.activityId, topicId: this.data.topicId,
                sourceSessionId: source, previousTeamRunId: view.teamRunId || null, actionId: actionId('team_')
              } : { sessionId: source };
              this._themeReplayPending = { context, payload: Object.freeze(payload), team: !!view.teamReplayRequired };
              resolve(this._sendThemeReplay());
            }, fail: () => resolve(false) });
        });
      }).finally(() => {
        if (this._themePreparing === preparing) { this._themePreparing = null; if (this._themeLive(epoch, context) && !this._themeReplaySending) this.setData({ themeReplayBusy: false }); }
      });
    },
    onThemeReplayError() { return this._themeReplayPending ? this.retryThemeReplay() : this.refreshThemeBackpack(); },
    retryThemeReplay() {
      if (!this._themeAllowed() || !this._themeReplayPending || this._themeReplaySending || this._themePreparing || this._themeSending || this._themePending) return Promise.resolve(false);
      const pending = this._themeReplayPending;
      if (pending.context !== this._themeContext()) { this._themeReset(); return Promise.resolve(false); }
      const preparing = {}; this._themePreparing = preparing;
      return (pending.team ? this._loadThemeTeam(pending.payload.teamId) : Promise.resolve({ leader: true })).then(team => {
        if (!team || !team.leader || this._themeReplayPending !== pending) return false;
        return this._sendThemeReplay();
      }).finally(() => { if (this._themePreparing === preparing) this._themePreparing = null; });
    },
    _sendThemeReplay() {
      const pending = this._themeReplayPending;
      if (!this._themeAllowed() || !pending || this._themeReplaySending || pending.context !== this._themeContext()) return Promise.resolve(false);
      const epoch = this._themeEpoch || 0, context = pending.context, sending = {};
      this._themeReplaySending = sending; this._themeMutation = (this._themeMutation || 0) + 1;
      this.setData({ themeReplayBusy: true, themeReplayError: '' });
      return req(pending.team ? '/api/play/theme/team/replay' : '/api/play/theme/replay', 'POST', pending.payload, { 'content-type': 'application/json' }, true)
        .catch(() => ({ netFail: true })).then(res => {
          if (!this._themeLive(epoch, context)) return false;
          const validTeam = !pending.team || (res.data && same(res.data.teamId, pending.payload.teamId)
            && positive(res.data.teamRunId) && res.data.playInstanceId === 'team-replay:' + res.data.teamRunId
            && (pending.payload.previousTeamRunId == null || same(res.data.previousTeamRunId, pending.payload.previousTeamRunId)));
          const newSession = res.data && !same(res.data.sessionId, pending.team ? pending.payload.sourceSessionId : pending.payload.sessionId);
          if (res.code == 200 && validTeam && newSession && this._applyThemeView(res.data)) {
            this._themeReplayPending = null; this._themeChapterEntered = null; this._themeEnabled = false;
            this.setData({ themeReplayUnknown: false, themeReplayError: '', themeBackpackOpen: false, themeRuntimeReady: false });
            // Re-read nodes/progress/config together for the newly authoritative instance.
            if (this.loadData) this.loadData(false); return true;
          }
          if (unknown(res) || res.code == 200) {
            this.setData({ themeReplayUnknown: true, themeReplayError: '重开结果未确认，重试会查询同一次重开' }); return false;
          }
          this._themeReplayPending = null;
          this.setData({ themeReplayUnknown: false, themeReplayError: res.code == 409 ? '本局或队伍已更新，请刷新后重新确认重开' : (res.msg || '暂不能重开，请确认队伍和主题状态') });
          return this.refreshThemeBackpack().then(() => false);
        }).finally(() => {
          if (this._themeReplaySending === sending) { this._themeReplaySending = null; if (this._themeLive(epoch, context)) this.setData({ themeReplayBusy: false }); }
        });
>>>>>>> private-github/featu
    },
    _themeChapterIndex() {
      const binding = (this.data.themeBag.chapterBindings || []).find(c => same(c.chapterId, (this.data.chapter || {}).id));
      return binding ? binding.index : -1;
    },
    _endThemeChapter() {
      if (!this._themeAllowed()) return Promise.resolve(true);
      const index = this._themeChapterIndex();
      if ((this.data.nodes || []).some(n => same(n.chapterId, (this.data.chapter || {}).id) && !n.done)) return Promise.resolve(true);
      if (!(this.data.themeBag.chapterIndexes || []).includes(index)) return Promise.resolve(true);
<<<<<<< public
      return this.refreshThemeBackpack().then(() => this._themeChapterCommand('CHAPTER_END', String(index)));
    },
    _enterThemeChapter() {
      if (!this.data.themeBag.sessionId || this.data.isPreview || this.data.isMock) return;
      const index = this._themeChapterIndex();
      if (index < 0 || !(this.data.themeBag.chapterIndexes || []).includes(index) || this._themeChapterEntered === index || this._themeSending) return;
      this._themeChapterEntered = index;
      this._themeChapterCommand('CHAPTER_START', String(index)).then(ok => { if (!ok) this._themeChapterEntered = null; });
=======
      return this.refreshThemeBackpack().then(ok => ok && this._themeCommand('CHAPTER_END', String(index)));
    },
    _enterThemeChapter() {
      if (!this._themeAllowed() || this._themePreparing || this._themeSending || this._themePending || this._themeReplayPending || this._themeReplaySending || !this.data.themeBag.sessionId) return;
      const index = this._themeChapterIndex(), key = this.data.themeBag.sessionId + ':' + index;
      if (index < 0 || !(this.data.themeBag.chapterIndexes || []).includes(index) || this._themeChapterEntered === key) return;
      this._themeChapterEntered = key;
      this._themeCommand('CHAPTER_START', String(index)).then(ok => { if (!ok && this._themeChapterEntered === key) this._themeChapterEntered = null; });
>>>>>>> private-github/featu
    },
  };
}
<<<<<<< public
module.exports = { present, methods, data: Object.assign({}, trade.data, plans.data, { zeroHpView: null, zeroHpCountdown: '', zeroHpBusy: false, zeroHpError: '', zeroHpPermissionDenied: false, themeBag: { contents: [], items: [], clues: [], slots: [], pendingRows: [], actionRows: [], challengeNodes: [], planRows: [], tradeTargets: [], tradeRows: [], used: 0, capacity: 6, hpEnabled: false }, themeBackpackOpen: false, themeBackpackTab: 'items', themeBagTabs: [{ key: 'items', label: '物品' }, { key: 'clues', label: '线索' }, { key: 'game', label: '任务' }], themeCostChoice: null, themeRewardPreview: null, themePendingClaim: null, themeItemDetail: null, themeQuestChoice: null, themeBagBusy: false, themeBagUnknown: false, themeBagError: '' }) };
=======
module.exports = { presentThemeOverview, present, presentCurrentStats, presentCard, presentHistoryPage, presentRecordedEvents, methods, data: { themeBag: emptyBag(), themeRuntimeReady: false,
  themeOverviewAvailable: false, themeOverviewShow: false, themeOverviewRows: [], themeOverviewPage: 0, themeOverviewPages: 0, themeOverviewClipped: false,
  themeBackpackOpen: false, themeBackpackTab: 'items', themeBagTabs: [{ key: 'items', label: '物品' }, { key: 'clues', label: '线索' }, { key: 'history', label: '历史' }],
  themeBagBusy: false, themeBagUnknown: false, themeBagError: '', themeControlBusy: false, themeReplayBusy: false, themeReplayUnknown: false,
  themeReplayError: '', themeTeamLeader: false, themeTeamNotice: '',
  themeHistoryRows: [], themeHistoryCursor: '', themeHistoryHasMore: false, themeHistoryLoaded: false, themeHistoryBusy: false, themeHistoryError: '', themeHistoryExpandedId: '',
  themeCardShow: false, themeCardBusy: false, themeCardTarget: null, themeCard: null, themeCardError: '',
  themePosterPath: '', themePosterBusy: false, themePosterNativeBusy: false, themePosterError: '', themePosterNotice: '',
  themePosterClipped: false, themePosterTextOnly: false, themePosterShareAvailable: false } };
>>>>>>> private-github/featu
