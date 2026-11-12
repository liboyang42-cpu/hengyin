// cy-playkit-accusation · 指认结案(施工清单 UG-03 / UG-04,§27.9)
//
// 五步固定:看人物卡 → 点卡看档案(只显示服务端给的已获证词)→ 选中可反复改,不耗机会
// → 「确认指认」二次确认后才交原 SUBMIT_QA 的 optionId → 结论只读服务端 accusation.result。
// ★ 正确人物、结局文案、留痕码都不在确认前的回包里;本组件不判对错、不推断「提前」。
// ★ readyForBase 不是成功:有限次数答错耗尽时它也为 true —— 这里根本不读它。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const modal = require('../../../../utils/modal.js');

function withInitials(suspects) {
  return (suspects || []).map((s) => Object.assign({}, s, { initial: Array.from(String(s.name || ''))[0] || '' }));
}

/** 二次确认文案:必须写明「这是最终指认」(UG-03)。 */
function confirmContent(name) {
  return '这是最终指认：' + (name || '所选人物');
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
  data: { suspects: [], selected: '', dossier: null },
  observers: {
    'show, suspectList': function (show, list) {
      if (!show) { this.setData({ selected: '', dossier: null }); return; }
      const suspects = withInitials(list);
      const keep = suspects.find((s) => s.id === this.data.selected) || null;
      this.setData({ suspects, selected: keep ? keep.id : '', dossier: keep });
    },
  },
  methods: {
    _confirmContent: confirmContent,
    onPick(e) {
      const id = e.currentTarget.dataset.id;
      const dossier = this.data.suspects.find((s) => s.id === id) || null;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ selected: id, dossier });
    },
    onReset() { this.setData({ selected: '', dossier: null }); },
    onConfirm() {
      const pick = this.data.dossier;
      if (!pick || this._asking) return;
      this._asking = true;
      modal.show({
        title: '确认指认',
        content: confirmContent(pick.name),
        confirmText: '确认指认',
        cancelText: '再想想',
        success: (res) => {
          if (res.confirm && this.data.selected === pick.id) this.triggerEvent('submit', { optionId: pick.id });
        },
        complete: () => { this._asking = false; },
      });
    },
    onClose() { this.triggerEvent('close'); },
  },
});
