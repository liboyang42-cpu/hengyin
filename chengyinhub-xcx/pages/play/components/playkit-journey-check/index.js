// cy-playkit-journey-check · R14 旅程检定一屏(掷 → 结果 → 结算)
//
// 数据源不是 playKit:入口在 encounter 视图的 allowedActions(含 'check'),
// 题面在 enc.check,判定与文案走 /api/play/check/{roll,reroll,settle}。
// 这一屏只画服务端给的东西,不自己判成败、不自己编文案:
//   · roll 回执有骰子 / 达成值 / DC / success —— 先出结果;
//   · settle 回执才有 text / failCostLabel —— 结算后才出文案。
// ★ 失败也推进:结算后照常可以退出,这一屏不拦节点完成(产品口径)。
const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const replayMotionBehavior = require('../../../../behaviors/replay-motion.js');
const motion = require('../../../../utils/motion.js');
const { createD20Renderer } = require('../../utils/d20-renderer.js');


/** 重掷条件:掷过、没结算、没重掷过,且幸运还有剩。回执里 luck 缺省时不猜。 */
function canReroll(receipt) {
  if (!receipt || receipt.settled || receipt.rerolled) return false;
  if (receipt.r5) return receipt.canReroll === true && receipt.rerollRemaining > 0 && !receipt.pending;
  return Number(receipt.luck) > 0;
}

/* 2026-10-23 主人定稿(城瘾 D20 与盲品试玩原型):三档检定也用「D20 加值」那颗三维二十面骰,
   按住摇骰蓄力、松手掷出;难度线随点数涨,过线变绿、差一点变红;天然 20 镀金,天然 1 只是演出。
   成败永远照回执,这里只演。 */
const HOLD_FULL_MS = 1400;
/** 难度条:1–20 的位置(百分比)。加值把达成值推过 20 时贴满,不出界 */
function trackPct(n) { return Math.max(0, Math.min(100, (Number(n) - 1) / 19 * 100)); }
/** 过线 / 差几点 —— 结果屏那一行,算法出口供单测 */
function marginText(receipt) {
  if (!receipt || receipt.detailsHidden || receipt.total == null || receipt.dc == null) return '';
  const diff = Number(receipt.total) - Number(receipt.dc);
  return diff >= 0 ? '过线 ' + diff + ' 点' : '差 ' + (-diff) + ' 点';
}
function natTag(receipt) {
  if (!receipt) return '';
  return receipt.nat === 'CRIT' ? '天然 20' : (receipt.nat === 'FUMBLE' ? '天然 1 · 手滑了' : '');
}

Component({
  behaviors: [reducedMotionBehavior, replayMotionBehavior],
  properties: {
    unknown: { type: Boolean, value: false },
    show: { type: Boolean, value: false },
    loading: { type: Boolean, value: false },
    skill: { type: String, value: '' },
    tier: { type: String, value: '' },
    mods: { type: Array, value: [] },        // 题面条件修正:[{label,value,held}]
    advantage: { type: Boolean, value: false },
    disadvantage: { type: Boolean, value: false },
    receipt: { type: Object, value: null },  // checkReceiptView() 的产物;没掷时 null
    r5: { type: Boolean, value: false },
    allowedActions: { type: Array, value: [] },
    disabledReason: { type: String, value: '' },
  },
  data: {
    rollVisible: true,
    settleVisible: true,
    rerollVisible: false,
    verdictText: '',
    marginText: '',
    natTag: '',
    landed: true,       // 骰子停稳之前不读数:点数、过线与否都等它落地
    holding: false,
    charge: 0,
    trackFill: 0,
    trackDc: 0,
    dieError: false,
  },
  observers: {
    'r5, allowedActions': function (r5, allowedActions) {
      this.setData({ rollVisible: !r5 || (allowedActions || []).includes('ROLL_CHECK'),
        settleVisible: !r5 || (allowedActions || []).includes('SUBMIT_CHECK') });
    },
    // 请求失败(没有新回执、loading 落下):放开按钮让玩家再掷;骰子自己最多转 8 秒就停
    loading: function (loading) {
      if (loading || !this._rolling || this.data.receipt) return;
      clearTimeout(this._rollReset);
      this._rollReset = setTimeout(() => { if (!this.data.receipt && !this.data.loading) this._rolling = false; }, 300);
    },
    'show, tier, receipt': function (show, tier, receipt) {
      if (!show) return;
      const r = receipt || null;
      /* 重掷要重播骰子。判据是「上一次也是没结算的掷」—— 那种情况 wx:elif 分支不切换、
         骰子个数不变,节点全部复用,jc-die 与 .72s/.82s 的错峰读数一条都不播。
         首次从题面进结果屏时分支真的切了、节点是新建的,不走这里,否则会多摘一帧。 */
      const prev = this._seenReceipt;
      if (r && !r.settled && prev && !prev.settled && r !== prev) this.replayMotion();
      const fresh = !!r && !r.settled && r !== prev && !(prev && prev.dice && r.dice && prev.dice.join() === r.dice.join() && prev.rerolled === r.rerolled);
      this._seenReceipt = r;
      this.setData({
        rerollVisible: canReroll(r),
        verdictText: r ? (r.success ? '过线了' : '没过线') : '',
        marginText: marginText(r),
        natTag: natTag(r),
        trackFill: r ? trackPct(r.total) : 0,
        trackDc: r ? trackPct(r.dc) : 0,
      });
      if (r && (r.settled || r.detailsHidden)) { this._destroyDie(); this.setData({ landed: true }); return; }   // 画布随 wx:if 卸掉,渲染器跟着停
      this._syncDie(r, fresh);
    },
  },
  lifetimes: {
    ready() { this._dieMounted = true; this._mountDie(); },
    detached() { this._dieMounted = false; this._stopHold(); this._destroyDie(); },
  },
  pageLifetimes: {
    hide() { this._stopHold(); },
  },
  methods: {
    onRecover() { if (!this.data.loading) this.triggerEvent('action', { action: 'recover' }); },
    _canReroll: canReroll,     // 纯算法出口,供单测
    _marginText: marginText,
    _trackPct: trackPct,

    /* ---------- 三维骰子 ---------- */
    _dieCount(r) { return r && Array.isArray(r.dice) && r.dice.length === 2 ? 2 : ((this.data.advantage || this.data.disadvantage) && !r ? 2 : 1); },
    _destroyDie() { this._dieVersion = (this._dieVersion || 0) + 1; if (this._die) this._die.dispose(); this._die = null; this._dieN = 0; },
    _mountDie() {
      if (!this._dieMounted || !this.data.show || this.data.receipt && this.data.receipt.detailsHidden) return;
      const r = this.data.receipt, count = this._dieCount(r);
      if (this._die && this._dieN === count) return;
      this._destroyDie();
      const version = this._dieVersion;
      wx.nextTick(() => {
        this.createSelectorQuery().select('#jcCanvas').fields({ node: true, size: true }).exec((res) => {
          if (version !== this._dieVersion) return;
          const item = res && res[0];
          if (!item || !item.node || !item.width || !item.height) { this.setData({ dieError: true, landed: true }); return; }
          try {
            const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
            this._die = createD20Renderer(item.node, item.width, item.height, count, info.pixelRatio, () => this._onLanded(), () => this.setData({ dieError: true, landed: true }));
            this._dieN = count;
            if (r && r.dice && r.dice.length === count) { this._die.settle(r.dice, r.kept, true); this._die.setGold(r.nat === 'CRIT'); }
          } catch (error) { this.setData({ dieError: true, landed: true }); }
        });
      });
    },
    /** 新回执:转着的骰子落到权威点数;重进页面 / 已结算直接摆好,不重演 */
    _syncDie(r, fresh) {
      if (!r) { this.setData({ landed: true }); this._mountDie(); return; }
      if (!this._die || this._dieN !== this._dieCount(r)) { this.setData({ landed: true }); this._mountDie(); return; }
      const animate = fresh && this._rolling && !this.data.reducedMotion;
      this._rolling = false;
      try {
        if (animate) { this.setData({ landed: false }); this._die.settle(r.dice, r.kept, false); }
        else { this._die.settle(r.dice, r.kept, true); this._die.setGold(r.nat === 'CRIT'); this.setData({ landed: true }); }
      } catch (error) { this.setData({ dieError: true, landed: true }); }
    },
    _onLanded() {
      const r = this.data.receipt;
      if (this._die && r) this._die.setGold(r.nat === 'CRIT');
      if (!this.data.landed && r) motion.haptic({ reducedMotion: this.data.reducedMotion, type: r.success ? 'medium' : 'heavy' });
      this.setData({ landed: true });
    },

    /* ---------- 按住摇骰 ---------- */
    _canRoll() { return !this.data.unknown && !this.data.loading && !this.data.receipt && !this._rolling && !(this.data.r5 && !this.data.allowedActions.includes('ROLL_CHECK')); },
    onHoldStart() {
      if (!this._canRoll()) return;
      this._holdAt = Date.now();
      this.setData({ holding: true, charge: 0 });
      this._holdTimer = setInterval(() => {
        const level = Math.min(1, (Date.now() - this._holdAt) / HOLD_FULL_MS);
        if (this._die) this._die.charge(level, this.data.reducedMotion);
        this.setData({ charge: Math.round(level * 100) / 100 });
      }, 60);
    },
    _stopHold() { if (this._holdTimer) clearInterval(this._holdTimer); this._holdTimer = null; this._holdAt = 0; },
    onHoldEnd() {
      if (!this._holdAt) return;
      this._stopHold();
      this.setData({ holding: false, charge: 0 });
      this._released = Date.now();     // 紧跟着的那次 tap 不再重复掷
      this._throw();
    },

    /** 掷 / 重掷 / 结算收口成一个事件:页面按 detail.action 分派,与 kitaction 同一手法 */
    onRoll() {
      // 读屏 / 轻点:没按住也能掷;刚松手的那次 tap 是同一下,不算第二掷
      if (this._released && Date.now() - this._released < 500) return;
      this._throw();
    },
    _throw() {
      if (!this._canRoll()) return;
      this._rolling = true;
      if (this._die) this._die.roll(this.data.reducedMotion);
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('action', { action: 'roll' });
    },
    onReroll() {
      if (this.data.unknown || this.data.loading || !this.data.rerollVisible) return;
      this._rolling = true;
      if (this._die) this._die.roll(this.data.reducedMotion);
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('action', { action: 'reroll' });
    },
    onSettle() {
      if (this.data.unknown || this.data.loading || !this.data.receipt || this.data.receipt.settled || this.data.receipt.pending) return;
      if (this.data.r5 && !this.data.allowedActions.includes('SUBMIT_CHECK')) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('action', { action: 'settle' });
    },
    onClose() { this.triggerEvent('close'); },
  },
});
