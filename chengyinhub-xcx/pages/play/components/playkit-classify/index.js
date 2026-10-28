// cy-playkit-classify · 分类题(施工文档 R3 · 证据板)
//
// ★ 答案(answer:itemId → binId)不在客户端:服务端只下发分类箱与卡片,判定在服务端。
//   组件只报「每张卡被放进了哪个箱」;全部分完之前按钮不可用,并写清还差几条。
//
// 判定回来只有整体对错(服务端没开逐张字段):判错不逐张标红(整列红等于假装知道是哪张错了),
// 只出整体提示条并允许继续改;整体通过才逐条变绿 + 行尾「对」。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');

/** items → 本地行。bin 是玩家选的分类箱 id,空串 = 还没分。 */
function paint(items) {
  return (items || []).map((it, i) => ({
    id: it.id || ('i' + i),
    label: it.label || '',
    bin: '',
  }));
}

/** 已经分好的条数。 */
function placedCount(rows) {
  return (rows || []).filter((r) => !!r.bin).length;
}

/* 2026-10-23 主人定稿(城瘾 排序连线分类试玩原型):一次一张卡摞在中间,点下面的分类卡片飞进去,
   分类里多一条小标签、数字加一;点小标签拿回来重放。 */
/** 牌堆:还没分的按原顺序,最上面那张在前;最多摞三张 */
function stackOf(rows) {
  return (rows || []).map((r, i) => Object.assign({ i }, r)).filter((r) => !r.bin).slice(0, 3);
}
/** 分类箱:每箱放进了哪几条 */
function binView(bins, rows) {
  return (bins || []).map((b, bi) => {
    const id = b.id || ('b' + bi);
    const chips = (rows || []).map((r, i) => ({ i, label: r.label, bin: r.bin })).filter((r) => r.bin === id);
    return { id, label: b.label || '', count: chips.length, chips };
  });
}

/** 全部分完才让提交:少一张服务端会整条打回「每张卡片都要放进一个分类」。 */
function canSubmit(placed, total) {
  return total > 0 && placed >= total;
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    prompt: { type: String, value: '' },
    bins: { type: Array, value: [] },
    items: { type: Array, value: [] },
    attempts: { type: Number, value: 0 },
    passed: { type: Boolean, value: false },
    lastCorrect: { type: Boolean, value: false },
  },
  data: {
    total: 0,
    placed: 0,
    verdict: '',          // '' 未判 / 'ok' 通过 / 'no' 判错
    stack: [],
    binRows: [],
    ghost: null,          // 飞进分类箱的那张(只是演出,数据已经落定)
  },
  observers: {
    'show, items, bins': function (show, items, bins) {
      if (!show) { this._configSignature = ''; return; }
      // 服务端回流会把同值的题面数组再赋一遍:题面没变就不重置台面(与停表 / typein 的签名同口径)
      const signature = JSON.stringify([items, bins]);
      if (signature === this._configSignature) return;
      this._configSignature = signature;
      const rows = paint(items);
      // 逐条归属只喂逻辑(牌堆 / 分类箱由它派生),不直接渲染:放实例字段,不进 data
      this._rows = rows;
      this.setData({ total: rows.length, placed: 0, verdict: '', ghost: null, stack: stackOf(rows), binRows: binView(this.data.bins, rows) });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage.begin();
    },
    'attempts, passed, lastCorrect': function (attempts, passed) {
      if (!(attempts > 0)) return;
      const verdict = passed ? 'ok' : 'no';
      if (verdict !== this.data.verdict) this.setData({ verdict });
      // 判错整块抖一下(原型 .phone.shake);不逐条标红 —— 服务端只给整体对错
      if (!passed) { const stage = this.selectComponent('#cy-play-stage'); if (stage && stage.nudge) stage.nudge(); }
    },
  },
  methods: {
    _paint: paint,              // 纯算法出口,供单测
    _placedCount: placedCount,
    _canSubmit: canSubmit,
    _stackOf: stackOf,
    _binView: binView,

    /** 点分类箱:把牌堆最上面那张放进去(data-i 缺省时取最上面那张) */
    onDropTop(e) {
      const top = this.data.stack[0];
      if (!top) return;
      const b = e.currentTarget.dataset.b, bi = Number(e.currentTarget.dataset.bi) || 0;
      // 飞的方向:按分类箱在第几列算,左边的往左下、右边的往右下
      const n = Math.max(1, this.data.binRows.length);
      const dx = Math.round(((bi + .5) / n - .5) * 520);
      if (!this.data.reducedMotion) {
        this.setData({ ghost: { label: top.label, dx, go: false } });
        clearTimeout(this._ghostT); clearTimeout(this._ghostT2);
        this._ghostT = setTimeout(() => this.setData({ 'ghost.go': true }), 30);
        this._ghostT2 = setTimeout(() => this.setData({ ghost: null }), 480);
      }
      this.onPickBin({ currentTarget: { dataset: { i: top.i, b } } });
    },
    /** 点分类箱里的小标签:拿回牌堆 */
    onTakeBack(e) {
      const i = Number(e.currentTarget.dataset.i);
      if (!(i >= 0) || i >= (this._rows || []).length) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      const rows = (this._rows || []).map((r, at) => (at === i ? Object.assign({}, r, { bin: '' }) : r));
      this._rows = rows;
      this.setData({ placed: placedCount(rows), verdict: '', stack: stackOf(rows), binRows: binView(this.data.bins, rows) });
    },

    onPickBin(e) {
      const i = Number(e.currentTarget.dataset.i);
      const bin = e.currentTarget.dataset.b || '';
      if (i < 0 || i >= (this._rows || []).length || !bin) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      const rows = (this._rows || []).map((r, at) => (at === i ? Object.assign({}, r, { bin: bin }) : r));
      // 改过了就是新的一版:上一次的判定标记撤掉
      this._rows = rows;
      this.setData({ placed: placedCount(rows), verdict: '', stack: stackOf(rows), binRows: binView(this.data.bins, rows) });
    },

    onSubmit() {
      if (!canSubmit(this.data.placed, this.data.total)) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      // ★ 只报放法,不报对错 —— 对错在服务端
      const placement = {};
      (this._rows || []).forEach((r) => { if (r.bin) placement[r.id] = r.bin; });
      this.triggerEvent('submit', { placement: placement });
    },
    onClose() { this._configSignature = ''; this.triggerEvent('close'); },
  },
});
