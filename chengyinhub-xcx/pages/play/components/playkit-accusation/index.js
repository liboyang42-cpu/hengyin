// cy-playkit-accusation · 指认结案(施工清单 UG-03 / UG-04,§27.9;l-kits 2026-11-10 照 proto/game gp-kit-accuse 改版)
//
// 五步固定:看线索板(便签可点开)→ 翻页看嫌疑人(一次一张,只显示服务端给的已获证词)→ 「指认 X」
// → 居中弹窗二次确认后才交原 SUBMIT_QA 的 optionId → 结论只读服务端 accusation.result,整屏插图(cy-illus)。
// ★ 正确人物、结局文案、留痕码都不在确认前的回包里;本组件不判对错、不推断「提前」。
// ★ readyForBase 不是成功:有限次数答错耗尽时它也为 true —— 这里根本不读它。
// ★ 便签只画服务端回读的已获得线索(view.clues);没有就没有便签,不编占位。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');

const FLIP_MS = 480;    // = --cy-motion-flip(MOTION.md §2.1);减少动态效果时 10ms
/* 便签在板上的位置与倾斜(原型三张:左上 / 中下 / 右上;再多的循环错开,最多画 6 张) */
const PIN_SLOTS = [[40, 36, -4], [260, 120, 3], [460, 32, -2], [110, 136, 4], [340, 48, -3], [520, 130, 2]];
const MAX_PINS = 6;

function withInitials(suspects) {
  return (suspects || []).map((s) => Object.assign({}, s, { initial: Array.from(String(s.name || ''))[0] || '' }));
}

/** 二次确认文案:必须写明「这是最终指认」(UG-03)。r2 起由本屏自己的确认层展示,不再走系统弹窗。 */
function confirmContent(name) {
  return '这是最终指认：' + (name || '所选人物');
}

/** 结论 → 插图(cy-illus):结案 = 成功,失败 = 失败,结算确认中 = 调查中。 */
function animKind(outcome, pending) {
  if (outcome === 'SOLVED' || outcome === 'EARLY') return 'success';
  if (outcome === 'FAILED') return 'fail';
  return pending ? 'wait' : '';
}

/** 已获得线索 → 板上的便签(位置 / 倾斜按槽位循环)。 */
function pinsOf(clues) {
  return (clues || []).slice(0, MAX_PINS).map((c, i) => {
    const slot = PIN_SLOTS[i % PIN_SLOTS.length];
    return { id: c.id || String(i), name: c.name || '线索', text: c.text || '', img: c.img || '', x: slot[0], y: slot[1], rot: slot[2] };
  });
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false },
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    boardImg: { type: String, value: '' },
    boardAlt: { type: String, value: '' },
    intro: { type: Array, value: [] },
    suspectList: { type: Array, value: [] },
    clues: { type: Array, value: [] },
    outcome: { type: String, value: '' },
    outcomeLabel: { type: String, value: '' },
    pending: { type: Boolean, value: false },
    wrongAttempt: { type: Boolean, value: false },
    retryNote: { type: String, value: '' },
    screen: { type: Array, value: [] },
  },
  data: { suspects: [], pins: [], cur: 0, outIdx: -1, inIdx: -1, confirmOpen: false, confirmText: '', cfOn: false, clueOpen: -1, illus: '', endOn: false },
  observers: {
    'outcome, pending, show': function (outcome, pending, show) {
      const illus = animKind(outcome, pending);
      const ending = !!show && (!!illus);
      this.setData({ illus });
      this._clearEnd();
      if (!ending) { this.setData({ endOn: false }); return; }
      // 结果整屏:先落起点样式,两帧后再加 is-on 让标题 / 结局段 / 按钮依次升上来(减少动态效果直接终态)
      if (this.data.reducedMotion) { this.setData({ endOn: true }); return; }
      this._endT = setTimeout(() => { this._endT = null; this.setData({ endOn: true }); }, 34);
    },
    clues: function (clues) { this.setData({ pins: pinsOf(clues) }); },
    'show, suspectList': function (show, list) {
      if (!show) { this._clearFlip(); this.setData({ confirmOpen: false, cfOn: false, clueOpen: -1, outIdx: -1, inIdx: -1 }); return; }
      const suspects = withInitials(list);
      const cur = Math.min(this.data.cur, Math.max(0, suspects.length - 1));
      this.setData({ suspects, cur });
    },
  },
  lifetimes: { detached() { this._clearFlip(); this._clearEnd(); this._clearPop(); } },
  methods: {
    _confirmContent: confirmContent,
    _animKind: animKind,
    _pinsOf: pinsOf,
    _clearFlip() { if (this._flipT) { clearTimeout(this._flipT); this._flipT = null; } },
    _clearEnd() { if (this._endT) { clearTimeout(this._endT); this._endT = null; } },
    _clearPop() { if (this._popT) { clearTimeout(this._popT); this._popT = null; } },
    /** 翻到下一位:当前这张绕竖轴翻出去、下一张翻进来;翻完才换「指认 X」。 */
    onFlipNext() {
      const n = this.data.suspects.length;
      if (n < 2 || this._flipT || this.data.confirmOpen) return;
      const cur = this.data.cur;
      const next = (cur + 1) % n;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ outIdx: cur, inIdx: next });
      this._flipT = setTimeout(() => {
        this._flipT = null;
        this.setData({ cur: next, outIdx: -1, inIdx: -1 });
      }, this.data.reducedMotion ? 10 : FLIP_MS);
    },
    /** 弹窗两帧后加 is-on:起点样式先落地,过渡才看得见(与 cy-illus 同一做法)。
        字段写死成字面量,不拼 patch:动态 setData 门禁核不出写了哪些顶层字段。 */
    _popIn() {
      this._clearPop();
      if (this.data.reducedMotion) { this.setData({ cfOn: true }); return; }
      this.setData({ cfOn: false });
      this._popT = setTimeout(() => { this._popT = null; this.setData({ cfOn: true }); }, 34);
    },
    onOpenClue(e) {
      const i = Number(e.currentTarget.dataset.i);
      if (!this.data.pins[i] || this.data.confirmOpen) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ clueOpen: i });
      this._popIn();
    },
    onCloseClue() { this._clearPop(); this.setData({ clueOpen: -1, cfOn: false }); },
    /** 线索图读不出来:只撤这一张图,文字照旧 —— 不显示破图(与 UG-02 选项图同一规则)。 */
    onClueImgError(e) {
      const i = Number(e.currentTarget.dataset.i);
      if (!(this.data.pins[i] && this.data.pins[i].img)) return;
      this.setData({ ['pins[' + i + '].img']: '' });
    },
    /** 「指认 X」:先开居中确认弹窗;弹窗里再确认才交原 SUBMIT_QA。 */
    onConfirm() {
      const pick = this.data.suspects[this.data.cur];
      if (!pick || this.data.confirmOpen || this._flipT) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ confirmOpen: true, clueOpen: -1, confirmText: confirmContent(pick.name) });
      this._popIn();
    },
    onConfirmCancel() { this._clearPop(); this.setData({ confirmOpen: false, cfOn: false }); },
    onConfirmGo() {
      const pick = this.data.suspects[this.data.cur];
      if (!pick || !this.data.confirmOpen) return;
      this._clearPop();
      this.setData({ confirmOpen: false, cfOn: false });
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', { optionId: pick.id });
    },
    noop() {},
    onClose() { this.triggerEvent('close'); },
  },
});
