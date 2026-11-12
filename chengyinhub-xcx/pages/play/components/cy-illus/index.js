// cy-illus:风格 B 精致插画(25 枚,D-118 起全部同一套画法)。
// 进场与待机全部是 transform / opacity / clip-path 过渡(仓库 keyframes 棘轮固定 146,不新增):
//   进场 = is-on 后按 art-b.js MOTION.enter 逐个加 e1、e2…;待机 = 每圈按 MOTION.steps 逐个累加 p1、p2…,周期 T 到了撤掉重来。没有 MOTION 的图只做通用弹出。
// 减少动态效果(系统或应用内开关):直接终态,不起计时器。页面隐藏时暂停待机。
const art = require('./art.js');
const { MOTION } = require('./art-b.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');

const SNAP_MS = 40; // 一圈开头先撤掉所有阶段类,留一帧让「瞬回」落地,再加第一个阶段

function stages(prefix, n) { let s = ''; for (let i = 1; i <= n; i++) s += (i > 1 ? ' ' : '') + prefix + i; return s; }

Component({
  options: { addGlobalClass: false },
  properties: {
    name: { type: String, value: 'clue', observer: '_paint' },
    size: { type: Number, value: 128 },      // rpx
    n: { type: null, value: null, observer: '_paint' }, // 兼容旧调用:章节印现在是白星,不画数字
    delay: { type: Number, value: 0 },       // ms,多张错峰
    bare: { type: Boolean, value: false },   // 不要底层(旧画法白色有机形 / 风格 B 脚下软影)
    loop: { type: Boolean, value: true },    // 待机往复
    reducedMotion: { type: null, value: null }, // 宿主已知时传入;不传则读系统 + 应用内开关
  },
  data: { kind: 'clue', layers: [], on: false, idle: false, reduced: false, ec: '', pc: '' },
  lifetimes: {
    attached() { this._paint(); this._start(); },
    detached() { this._stop(); },
  },
  pageLifetimes: {
    show() { if (this._hidden) { this._hidden = false; this._loop(); } },
    hide() { this._hidden = true; this._stopIdle(); },
  },
  methods: {
    _paint() {
      const l = art.layers(this.properties.name, this.properties.n);
      const changed = this.data.kind !== l.name;
      this.setData({ kind: l.name, layers: l.layers });
      // 换了另一枚(例如判定从调查中变成成功):时间表不同,从进场重来
      if (changed && this._started) this._start();
    },
    _isReduced() {
      const r = this.properties.reducedMotion;
      if (r === true || r === false) return r;
      try { return !!readReducedMotion(); } catch (e) { return false; }
    },
    _start() {
      this._stop();
      this._started = true;
      const reduced = this._isReduced();
      if (reduced) { this.setData({ reduced: true, on: true, idle: false, ec: '', pc: '' }); return; }
      this.setData({ reduced: false, on: false, idle: false, ec: '', pc: '' });
      // 两帧后再加 is-on,保证起点样式先落地(与 look-transition 的 is-pre 同一做法)
      this._enterT = setTimeout(() => {
        this.setData({ on: true });
        const m = MOTION[this.data.kind] || {};
        (m.enter || []).forEach((at, i) => this._push(this._fx, () => this.setData({ ec: stages('e', i + 1) }), at));
        if (m.T) this._push(this._fx, () => this._loop(), m.idleAt || 0);
      }, 34 + Math.max(0, this.properties.delay | 0));
    },
    _push(list, fn, ms) { list.push(setTimeout(fn, ms)); },
    _loop() {
      this._stopIdle();
      if (!this.properties.loop || this.data.reduced || this._hidden || !this.data.on) return;
      const m = MOTION[this.data.kind];
      if (!m || !m.T) return;
      this.setData({ idle: true, pc: '' });
      m.steps.forEach((at, i) => this._push(this._idle, () => this.setData({ pc: stages('p', i + 1) }), Math.max(SNAP_MS, at)));
      this._push(this._idle, () => this._loop(), m.T);
    },
    _stopIdle() { clearTimeout(this._idleT); (this._idle || []).forEach(clearTimeout); this._idle = []; },
    _stop() { clearTimeout(this._enterT); (this._fx || []).forEach(clearTimeout); this._fx = []; this._stopIdle(); },
    /** 宿主想重播进场(例如结果切换)时调用。 */
    replay() { this._start(); },
  },
});
