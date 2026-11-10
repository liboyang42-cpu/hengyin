// cy-playkit-accusation · 指认结案(施工清单 UG-03 / UG-04,§27.9)
//
// 五步固定:看人物卡 → 点卡看档案(只显示服务端给的已获证词)→ 选中可反复改,不耗机会
// → 「确认指认」二次确认后才交原 SUBMIT_QA 的 optionId → 结论只读服务端 accusation.result。
// ★ 正确人物、结局文案、留痕码都不在确认前的回包里;本组件不判对错、不推断「提前」。
// ★ readyForBase 不是成功:有限次数答错耗尽时它也为 true —— 这里根本不读它。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');

function withInitials(suspects) {
  return (suspects || []).map((s) => Object.assign({}, s, { initial: Array.from(String(s.name || ''))[0] || '' }));
}

/** 二次确认文案:必须写明「这是最终指认」(UG-03)。r2 起由本屏自己的确认层展示,不再走系统弹窗。 */
function confirmContent(name) {
  return '这是最终指认：' + (name || '所选人物');
}

/** 结论 → 动图种类(r2 b2-ug-04):结案 = 庆祝,失败 = 失败,结算确认中 = 搜查。 */
function animKind(outcome, pending) {
  if (outcome === 'SOLVED' || outcome === 'EARLY') return 'success';
  if (outcome === 'FAILED') return 'fail';
  return pending ? 'search' : '';
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
    outcome: { type: String, value: '' },
    outcomeLabel: { type: String, value: '' },
    pending: { type: Boolean, value: false },
    wrongAttempt: { type: Boolean, value: false },
    retryNote: { type: String, value: '' },
    screen: { type: Array, value: [] },
  },
  data: { suspects: [], selected: '', dossier: null, confirmOpen: false, confirmText: '', anim: '' },
  observers: {
    'outcome, pending': function (outcome, pending) {
      this.setData({ anim: animKind(outcome, pending) });
    },
    'show, suspectList': function (show, list) {
      if (!show) { this.setData({ selected: '', dossier: null, confirmOpen: false }); return; }
      const suspects = withInitials(list);
      const keep = suspects.find((s) => s.id === this.data.selected) || null;
      this.setData({ suspects, selected: keep ? keep.id : '', dossier: keep });
    },
  },
  methods: {
    _confirmContent: confirmContent,
    _animKind: animKind,
    onPick(e) {
      const id = e.currentTarget.dataset.id;
      const dossier = this.data.suspects.find((s) => s.id === id) || null;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ selected: id, dossier });
    },
    /** 「确认指认」:先开本屏的确认层(关闭在右上角);层里再确认才交原 SUBMIT_QA。 */
    onConfirm() {
      const pick = this.data.dossier;
      if (!pick || this.data.confirmOpen) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ confirmOpen: true, confirmText: confirmContent(pick.name) });
    },
    onConfirmCancel() { this.setData({ confirmOpen: false }); },
    onConfirmGo() {
      const pick = this.data.dossier;
      if (!pick || !this.data.confirmOpen) return;
      this.setData({ confirmOpen: false });
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      if (this.data.selected === pick.id) this.triggerEvent('submit', { optionId: pick.id });
    },
    noop() {},
    onClose() { this.triggerEvent('close'); },
  },
});
