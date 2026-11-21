// cy-playkit-match · 连线题(施工文档 R3 · 证据板)
//
// ★ 先点左边一张(描边表示选中)再点右边一张,两张卡之间画一条线,每一对一个颜色;
//   再点已配对的卡就取消。
//
// ★ 答案(pairs)不在客户端:服务端只下发左右两组条目(右侧已打乱),判定在服务端。
//   组件交上去的是玩家自己连的那些 [左 id, 右 id];连满之前按钮不可用,
//   差一条服务端也会整条打回 —— 与其让玩家点一下挨一句报错,不如按钮先不亮。
//
// 判定回来只有整体对错(服务端没开逐条字段):判错不逐条标红,只出整体提示条,改了就能再交;
// 整体通过才全部变绿(那意味着每条连线都在答案里)。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');

/** 一列条目 → 本地行。badge = 它在 pairs 里的序号 +1(0 = 还没连),渲染时按 verdict 上色。 */
function paintSide(list, side, pairs) {
  const at = side === 'l' ? 0 : 1;
  return (list || []).map((it, i) => {
    const id = it.id || (side + i);
    const index = (pairs || []).findIndex((p) => p[at] === id);
    return { id: id, label: it.label || '', badge: index < 0 ? 0 : index + 1 };
  });
}

/* 2026-11-21 主人走查「连线只有一排圆圈、从来不画线」:
   线按**真实量到的锚点**画(两张卡内侧边上的小圆点),不再按写死的行高推算 ——
   推算那一版在内嵌故事流、长文案换行、字号放大时都会和卡片错开,看着就像没画。
   量不到(组件还没上屏 / 测试环境)就不画,不崩。 */
const PALETTE = 6;
/** 两个锚点矩形(相对视口)→ 一条线(相对 origin 那块画布):起点、长度、角度。纯函数,供单测。 */
function lineFor(a, b, origin) {
  if (!a || !b || !origin) return null;
  const x1 = a.left + a.width / 2 - origin.left, y1 = a.top + a.height / 2 - origin.top;
  const x2 = b.left + b.width / 2 - origin.left, y2 = b.top + b.height / 2 - origin.top;
  const dx = x2 - x1, dy = y2 - y1;
  const len = Math.round(Math.sqrt(dx * dx + dy * dy) * 10) / 10;
  if (!(len > 0)) return null;
  return { x: Math.round(x1 * 10) / 10, y: Math.round(y1 * 10) / 10, len, deg: Math.round(Math.atan2(dy, dx) * 1800 / Math.PI) / 10 };
}
/** pairs + 量到的锚点 → 连线列表。anchors 键 = 'l:<左 id>' / 'r:<右 id>'。fresh 那条先收成 0 长,下一拍画出来 */
function pairLines(pairs, anchors, origin, freshKey) {
  return (pairs || []).map((p, n) => {
    const seg = lineFor((anchors || {})['l:' + p[0]], (anchors || {})['r:' + p[1]], origin);
    if (!seg) return null;
    const key = p[0] + '-' + p[1];
    return Object.assign(seg, { key, c: n % PALETTE, fresh: freshKey === key });
  }).filter(Boolean);
}

/** 连满才让提交:左右一一对应,少一条服务端不收,按钮亮着也没意义。 */
function canSubmit(pairedCount, total) {
  return total > 0 && pairedCount >= total;
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false },   // 故事流里就地玩:过了之后往下走用故事流自己的「继续故事」,本屏不再出第二个「继续」
    show: { type: Boolean, value: false },
    prompt: { type: String, value: '' },
    left: { type: Array, value: [] },
    right: { type: Array, value: [] },
    attempts: { type: Number, value: 0 },
    passed: { type: Boolean, value: false },
    lastCorrect: { type: Boolean, value: false },
  },
  data: {
    // ⚠️ 派生行换键名(leftRows/rightRows),别写回被监听的 left/right —— 那会自触发死循环
    leftRows: [],
    rightRows: [],
    selected: '',
    pairedCount: 0,
    total: 0,
    verdict: '',          // '' 未判 / 'ok' 通过 / 'no' 判错
    lines: [],
  },
  observers: {
    'show, left, right': function (show, left, right) {
      if (!show) { this._configSignature = ''; return; }
      // 服务端回流会把同值的题面数组再赋一遍:题面没变就不重置台面(与停表 / typein 的签名同口径)
      const signature = JSON.stringify([left, right]);
      if (signature === this._configSignature) return;
      this._configSignature = signature;
      this._paintPairs([], '', left, right);
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
  lifetimes: { detached() { clearTimeout(this._drawT); } },
  methods: {
    _paintSide: paintSide,      // 纯算法出口,供单测
    _pairLines: pairLines,
    _lineFor: lineFor,
    _canSubmit: canSubmit,

    /** 重画两列与角标。任何改动都要把上一次的判定撤掉 —— 红角标跟着新连线走是误导。 */
    _paintPairs(pairs, selected, left, right, freshKey) {
      const l = left || this.data.left || [];
      const r = right || this.data.right || [];
      // ⚠️ pairs 只喂逻辑(拆线 / 提交)不渲染:进 data 就是死数据字段,放实例字段
      this._pairs = pairs;
      const leftRows = paintSide(l, 'l', pairs), rightRows = paintSide(r, 'r', pairs);
      const fresh = this.data.reducedMotion ? '' : (freshKey || '');
      this.setData({
        selected: selected,
        leftRows, rightRows,
        pairedCount: pairs.length,
        total: l.length,
        verdict: '',
      }, () => this._measureLines(fresh));
    },

    /** 量两列锚点再画线。新连的那条先以 0 长落地,下一拍放开,线就从左往右画出来(减弱动效时直接画全)。 */
    _measureLines(fresh) {
      const pairs = this._pairs || [];
      if (!pairs.length) { if (this.data.lines.length) this.setData({ lines: [] }); return; }
      if (typeof this.createSelectorQuery !== 'function') return;
      const q = this.createSelectorQuery();
      q.select('.ma__cols').boundingClientRect();
      q.selectAll('.ma__anchor').boundingClientRect();
      q.exec((res) => {
        const origin = res && res[0];
        const list = (res && res[1]) || [];
        if (!origin || !list.length) { this.setData({ lines: [] }); return; }
        const anchors = {};
        list.forEach((r) => { if (r && r.dataset && r.dataset.key) anchors[r.dataset.key] = r; });
        this._anchors = anchors; this._origin = origin;
        this.setData({ lines: pairLines(this._pairs, anchors, origin, fresh) });
        if (fresh) {
          clearTimeout(this._drawT);
          this._drawT = setTimeout(() => this.setData({ lines: pairLines(this._pairs, this._anchors, this._origin, '') }), 40);
        }
      });
    },

    onTapLeft(e) {
      const id = e.currentTarget.dataset.id;
      const row = this.data.leftRows.filter((r) => r.id === id)[0] || {};
      // 再点已配对的卡 = 取消(设计规格),不是「换个连法」。取消掉自己那张时把选中态一起收掉
      if (row.badge) {
        this._unpair((p) => p[0] !== id, this.data.selected === id ? '' : this.data.selected);
        return;
      }
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ selected: this.data.selected === id ? '' : id });
    },
    onTapRight(e) {
      const id = e.currentTarget.dataset.id;
      const row = this.data.rightRows.filter((r) => r.id === id)[0] || {};
      if (row.badge) { this._unpair((p) => p[1] !== id, this.data.selected); return; }
      const leftId = this.data.selected;
      if (!leftId) return;                       // 先点左边那张,右边单点没有意义
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      // 左边若已连过别人、或右边被占着,先把旧线拆掉再连 —— 一一对应
      const next = this._pairs
        .filter((p) => p[0] !== leftId && p[1] !== id)
        .concat([[leftId, id]]);
      this._paintPairs(next, '', null, null, leftId + '-' + id);
    },
    _unpair(keep, selected) {
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this._paintPairs(this._pairs.filter(keep), selected);
    },

    onSubmit() {
      // 已通过就不再交(实走 A15:再交只会撞「这一局连线已经结束了」)
      if (!canSubmit(this.data.pairedCount, this.data.total) || this.data.verdict === 'ok' || this.data.passed) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      // ★ 只报玩家连的那些,不报对错 —— 对错在服务端
      this.triggerEvent('submit', { pairs: (this._pairs || []).map((p) => [p[0], p[1]]) });
    },
    onClose() { this._configSignature = ''; this.triggerEvent('close'); },
  },
});
