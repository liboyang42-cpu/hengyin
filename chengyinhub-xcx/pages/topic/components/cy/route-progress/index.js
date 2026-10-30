/* 路线进度条(2026-10-22 业主):
   · 进入视野后**只走一遍**,快速填到真实进度,颜色由浅渐深;
   · 每过一个章节点亮一个小圆点,并冒出「第 N 章 ✓」;
   · 只有真的全部完成才开宝箱庆祝,不会自己归零重来。
   进度只认后端已下发的 unlockedChapterCount / totalChapterCount,没买或没开局就是 0,不演示假进度。 */
const DUR = 2200;
// easeOutCubic 的反函数:已知进度 p 求到达时刻,用来安排每个圆点何时点亮
const timeAt = (p) => 1 - Math.cbrt(1 - p);

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    total: { type: Number, value: 0 },
    done: { type: Number, value: 0 },
    labels: { type: Array, value: [] },
    tone: { type: String, value: 'city' },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { marks: [], fill: 0, lit: 0, win: false, running: false, plus: '', msg: '' },
  observers: {
    'total, done, labels'() { this._prepare(); },
  },
  lifetimes: {
    attached() { this._prepare(); },
    ready() {
      const io = this.createIntersectionObserver({ thresholds: [0.35] });
      this._io = io;
      io.relativeToViewport().observe('.rp', (res) => { if (res.intersectionRatio >= 0.35) this._play(); });
    },
    detached() { if (this._io) this._io.disconnect(); this._clear(); },
  },
  methods: {
    _clear() { (this._timers || []).forEach(clearTimeout); this._timers = []; },
    _prepare() {
      this._clear(); this._played = false;
      const n = Math.max(0, Number(this.data.total) || 0);
      const marks = [];
      for (let i = 1; i < n; i++) marks.push({ left: (i / n * 100).toFixed(2), label: (this.data.labels || [])[i - 1] || ('第 ' + i + ' 段') });
      this.setData({ marks, fill: 0, lit: 0, win: false, running: false, plus: '',
        msg: this.data.tone === 'free' ? '没有固定顺序，去一家亮一格' : '按顺序到站，一章一章点亮' });
    },
    _play() {
      if (this._played) return;
      this._played = true;
      const n = Math.max(0, Number(this.data.total) || 0);
      const d = Math.max(0, Math.min(n, Number(this.data.done) || 0));
      if (!n) return;
      const target = d / n;
      if (this.data.reducedMotion) { this.setData({ fill: target * 100, lit: Math.min(d, n - 1), win: d === n && n > 0 }); return; }
      this.setData({ running: true, fill: target * 100 });
      // 填充本身由 CSS 过渡完成(时长 DUR、缓出);这里只按时间表点亮圆点
      for (let k = 1; k <= Math.min(d, n - 1); k++) {
        const t = timeAt((k / n) / target) * DUR;
        this._timers.push(setTimeout(() => {
          const m = this.data.marks[k - 1] || {};
          this.setData({ lit: k, plus: this.data.tone === 'free' ? '+1 · ' + (m.label || '') : (m.label || '') + ' ✓', plusLeft: m.left }); // 原型:城市定向「第一章 ✓」,自由探索「+1 · 店名」
        }, t));
      }
      this._timers.push(setTimeout(() => {
        this.setData({ running: false, plus: '' });
        if (d === n) this.setData({ win: true, msg: this.data.tone === 'free' ? '全部到访，宝箱已打开' : '全部章节完成，宝箱已打开' });
      }, DUR + 60));
    },
  },
});
