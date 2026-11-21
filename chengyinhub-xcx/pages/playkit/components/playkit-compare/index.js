// cy-playkit-compare · 时间轴对照(R16)
//
// ★ 答案(该标出哪几条)不在客户端:服务端只下发两条时间线的题面,判定也在服务端。
//   组件只让玩家把条目标出来、把 marked 交上去(后端 SUBMIT_COMPARE 读的就是 marked)。
//
// 判定回来只有整体对错,所以判错不逐行标红,只出整体提示条、允许改了再交;
// 通过才把标出的那几行变绿。次数用尽(finished 且没过)同排序:收摊,大按钮变「继续」。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');

/** 两侧题面 → 本地分组。顺序原样(时间轴顺序就是题面),只加一个 marked。 */
function paint(left, right, marked) {
  const on = new Set(marked || []);
  return [['left', left], ['right', right]].map(([key, side]) => ({
    key,
    label: (side && side.label) || '',
    rows: ((side && side.items) || []).map((it, i) => ({
      id: it.id || (key + i), time: it.time || '', text: it.text || '', marked: on.has(it.id),
    })),
  }));
}

/** 按两侧题面顺序收集标出的 id —— 这就是要交给服务端的 marked */
function markedIds(sides) {
  const ids = [];
  (sides || []).forEach((side) => side.rows.forEach((row) => { if (row.marked) ids.push(row.id); }));
  return ids;
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false },   // 故事流里就地玩:过了之后往下走用故事流自己的「继续故事」,本屏不再出第二个「继续」
    show: { type: Boolean, value: false },
    prompt: { type: String, value: '' },
    left: { type: Object, value: {} },
    right: { type: Object, value: {} },
    lastMarked: { type: Array, value: [] },
    attempts: { type: Number, value: 0 },
    passed: { type: Boolean, value: false },
    finished: { type: Boolean, value: false },
    lastCorrect: { type: Boolean, value: false },
  },
  data: {
    sides: [],
    markedCount: 0,
    verdict: '',        // '' 未判 / 'ok' 通过 / 'no' 判错
  },
  observers: {
    'show, left, right': function (show, left, right) {
      if (!show) { this._configSignature = ''; return; }
      // 服务端回流会把同值的题面再赋一遍:题面没变就不重置台面
      const signature = JSON.stringify([left, right]);
      if (signature === this._configSignature) return;
      this._configSignature = signature;
      // 交过就按服务端记的上一次标法摆(重进页面也在);没交过从空白开始
      const sides = paint(left, right, this.data.attempts > 0 ? this.data.lastMarked : []);
      const verdict = this.data.attempts > 0 ? (this.data.passed ? 'ok' : (this.data.finished ? 'no' : '')) : '';
      this.setData({ sides, markedCount: markedIds(sides).length, verdict });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage.begin();
    },
    // 回执来了才画对错。attempts 为 0 说明这是刚打开的旧视图,不是刚判完
    'attempts, passed, lastCorrect': function (attempts, passed) {
      if (!(attempts > 0)) return;
      const verdict = passed ? 'ok' : 'no';
      if (verdict !== this.data.verdict) this.setData({ verdict });
      if (!passed) { const stage = this.selectComponent('#cy-play-stage'); if (stage && stage.nudge) stage.nudge(); }
    },
  },
  methods: {
    _paint: paint,          // 纯算法出口,供单测
    _markedIds: markedIds,

    onToggle(e) {
      if (this.data.verdict === 'ok' || this.data.finished) return;
      const id = e.currentTarget.dataset.id;
      const sides = this.data.sides.map((side) => Object.assign({}, side, {
        rows: side.rows.map((row) => (row.id === id ? Object.assign({}, row, { marked: !row.marked }) : row)),
      }));
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      // 改了就是新的一版:上一次的判定提示撤掉
      this.setData({ sides, markedCount: markedIds(sides).length, verdict: '' });
    },
    onSubmit() {
      if (!this.data.markedCount || this.data.verdict === 'ok' || this.data.finished) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      // ★ 只报玩家标了哪些,不报对错 —— 对错在服务端
      this.triggerEvent('submit', { marked: markedIds(this.data.sides) });
    },
    onClose() { this._configSignature = ''; this.triggerEvent('close'); },
  },
});
