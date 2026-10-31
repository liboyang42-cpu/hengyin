// cy-playkit-sort · 排序题(施工文档 R3 · 证据板)
//
// ★ 答案(answerOrder)不在客户端:服务端只下发**打乱顺序**的 items,判定也在服务端。
//   组件只做两件事:让玩家把顺序排出来、把 order 交上去。前端判对错的话,
//   抓个包就能直接看到答案,这一题当场作废。
//
// 拖拽 + 上移/下移两条路都留着:拖拽是手感,上移/下移是读屏器与手抖的人唯一能走的路 ——
// 只给拖拽等于把一部分玩家挡在门外(设计规格明写:不能只靠拖拽)。
//
// 判定回来只有**整体对错**(服务端有意不给逐位置对错 —— 逐位提示配合可重排
// 等于把答案一格格试出来)。所以判错**不逐行标红**,只出一条整体提示条并允许继续重排;
// 整体通过才逐行变绿(那意味着每一位都对)。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');

/** items → 本地行。就三个字段 —— 对错由 wxml 按 verdict 上色,别把答案状态混进来。 */
function paint(items) {
  return (items || []).map((it, i) => ({
    id: it.id || ('i' + i),
    label: it.label || '',
    img: it.img || '',
  }));
}

/** 把第 from 行搬到第 to 格。越界/原地返回原数组引用,调用方据此不再 setData。 */
function moveRow(list, from, to) {
  const rows = list || [];
  if (from === to || from < 0 || to < 0 || from >= rows.length || to >= rows.length) return rows;
  const next = rows.slice();
  const [one] = next.splice(from, 1);
  next.splice(to, 0, one);
  return next;
}

/** 通过时交上去的那份顺序,按题面签名记在模块里:倒计时等后续玩法会把本组件整个卸载再挂回来,
 *  实例字段跟着丢,回来就又画成出题时的乱序却标着「排好了」(实走 A14)。 */
const passedOrders = {};

/** 有东西可排才能提交:空列表 = 配置还没来,按钮亮着也没东西可交。 */
function canSubmit(rows) {
  return (rows || []).length > 0;
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false },   // 故事流里就地玩:过了之后往下走用故事流自己的「继续故事」,本屏不再出第二个「继续」
    show: { type: Boolean, value: false },
    prompt: { type: String, value: '' },
    items: { type: Array, value: [] },
    lastOrder: { type: Array, value: [] },
    attempts: { type: Number, value: 0 },
    passed: { type: Boolean, value: false },
    finished: { type: Boolean, value: false },
    lastCorrect: { type: Boolean, value: false },
  },
  data: {
    // ⚠️ 派生行换键名(rows),别写回被监听的 items —— 那会自触发死循环(见 playkit-observer-selfloop)
    rows: [],
    dragging: -1,
    dragOffset: 0,      // 正在拖那一行相对它当前格子的偏移(px),跟手
    picked: -1,         // 点选对调:先点的那一行
    verdict: '',        // '' 未判 / 'ok' 通过 / 'no' 判错
  },
  observers: {
    'show, items': function (show, items) {
      if (!show) { this._configSignature = ''; return; }
      // 服务端回流会把同值的题面数组再赋一遍:题面没变就不重置台面(与停表 / typein 的签名同口径)
      const signature = JSON.stringify(items);
      if (signature === this._configSignature) return;
      this._configSignature = signature;
      this._dragTo = -1;
      // 排对过就按服务端的上一次提交顺序摆(重进页面也在);本机缓存只兜底同一会话里服务端还没回来的那一下
      const serverOrder = (this.data.lastOrder || []).length ? this.data.lastOrder : null;
      const kept = this.data.passed && (serverOrder || passedOrders[signature]);
      const rows = kept ? kept.map((id) => paint(items).find((r) => r.id === id)).filter(Boolean) : paint(items);
      this.setData({ rows, dragging: -1, dragOffset: 0, picked: -1, verdict: kept ? 'ok' : '' });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage.begin();
    },
    // 回执来了才画对错。attempts 为 0 说明这一次是刚打开的旧视图,不是刚判完
    'attempts, passed, lastCorrect': function (attempts, passed) {
      if (!(attempts > 0)) return;
      const verdict = passed ? 'ok' : 'no';
      if (passed && this._configSignature && this._submittedOrder) passedOrders[this._configSignature] = this._submittedOrder;
      if (verdict !== this.data.verdict) this.setData({ verdict });
      // 判错整列抖一下(原型 .phone.shake);不逐行标红 —— 服务端只给整体对错
      if (!passed) { const stage = this.selectComponent('#cy-play-stage'); if (stage && stage.nudge) stage.nudge(); }
    },
  },
  methods: {
    _paint: paint,          // 纯算法出口,供单测
    _moveRow: moveRow,
    _canSubmit: canSubmit,

    /** 拖拽:按下时量一次行高(含行间距),移动时那一行跟手;越过半行就和邻居换位,
     *  换完把偏移扣掉一行,手指下面始终是同一行。 */
    onDragStart(e) {
      if (this.data.verdict === 'ok') return;   // 过了就别再动;判错还要重排再交
      const i = Number(e.currentTarget.dataset.i);
      this._dragFrom = i;
      this._dragAt = i;
      this._dragY = (e.touches && e.touches[0]) ? e.touches[0].clientY : 0;
      this._moved = false;
      this._dragTo = i;
      wx.createSelectorQuery().in(this).select('.so__list').boundingClientRect((rect) => {
        const n = this.data.rows.length;
        this._rowH = (rect && rect.height > 0 && n > 0) ? rect.height / n : 0;
      }).exec();
    },
    onDragMove(e) {
      if (this._dragFrom == null || !(this._rowH > 0)) return;
      const y = (e.touches && e.touches[0]) ? e.touches[0].clientY : this._dragY;
      const dy = y - this._dragY;
      if (!this._moved) {
        if (Math.abs(dy) < 8) return;
        this._moved = true;
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      }
      const n = this.data.rows.length;
      const target = Math.max(0, Math.min(n - 1, this._dragFrom + Math.round(dy / this._rowH)));
      this._dragTo = target;
      const dragOffset = Math.round(dy - (target - this._dragFrom) * this._rowH);
      if (target === this._dragAt) { this.setData({ dragging: target, dragOffset, picked: -1 }); return; }
      const rows = moveRow(this.data.rows, this._dragAt, target);
      this._dragAt = target;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ rows, dragging: target, dragOffset, picked: -1, verdict: '' });
    },
    onDragEnd() {
      const moved = this._moved;
      this._dragFrom = null;
      this._rowH = 0;
      this._dragTo = -1;
      if (moved) this._releasedAt = Date.now();   // 紧跟着的那次 tap 不算点选
      if (this.data.dragging >= 0 || this.data.dragOffset) this.setData({ dragging: -1, dragOffset: 0 });
    },
    /** 点选对调:先点一行(描边),再点另一行两行对调;点同一行取消 */
    onTapRow(e) {
      if (this.data.verdict === 'ok') return;
      if (this._releasedAt && Date.now() - this._releasedAt < 400) return;
      const i = Number(e.currentTarget.dataset.i);
      const p = this.data.picked;
      if (p < 0) { motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' }); this.setData({ picked: i }); return; }
      if (p === i) { this.setData({ picked: -1 }); return; }
      const rows = this.data.rows.slice();
      const t = rows[p]; rows[p] = rows[i]; rows[i] = t;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.setData({ rows, picked: -1, verdict: '' });
    },

    _applyMove(from, to) {
      const rows = moveRow(this.data.rows, from, to);
      if (rows === this.data.rows) return;
      // 动过了就是新的一版:上一次的判定标记全部撤掉
      this._dragTo = -1;
      this.setData({ rows, dragging: -1, picked: -1, verdict: '' });
    },

    onSubmit() {
      // 已通过就不再交:再交一次服务端只会回「这一局排序已经结束了」并弹出待确认面板(实走 A15)
      if (!canSubmit(this.data.rows) || this.data.verdict === 'ok' || this.data.passed) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      // ★ 只报顺序,不报对错 —— 对错在服务端
      this._submittedOrder = this.data.rows.map((r) => r.id);
      this.triggerEvent('submit', { order: this._submittedOrder });
    },
    onClose() { this._configSignature = ''; this.triggerEvent('close'); },
  },
});
