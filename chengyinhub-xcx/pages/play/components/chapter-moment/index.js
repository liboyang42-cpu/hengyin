// 动效 v2 · 时刻 1(章节开始)与时刻 3(通关),规格 docs/delivery/questo-ui-20261109/proto/motion-v2。
// 动画走 this.animate()(缓动取样成带 offset 的多帧,见 utils/chapter-moment.js)与 WXSS transition;没有 @keyframes。
// animate 结束后元素会回到静态样式,所以每段动画的终态同时写进 class(*On / *Hold),不靠 fill-forwards。
// 点任意处:动画中 → 直接跳到终态;终态后:开始屏发 go(进正文),通关屏不响应(按钮才是出口)。
// 减少动态(应用内开关 reduced-motion;系统 prefers-reduced-motion 由 WXSS @media 去掉过渡)→ 立即终态。
const M = require('../../utils/chapter-moment.js');

const CLEAR = { opacity: true, rotate: true, scale: true, translateX: true, translateY: true };

Component({
  properties: {
    mode: { type: String, value: 'start' },          // start | finale
    info: { type: Object, value: {} },               // { number, kicker, title, stats:[{v,k}] }
    reducedMotion: { type: Boolean, value: false },
  },
  data: {
    view: { kick: '', words: [], digits: [], ring: [], stats: [], particles: [], tiles: [], title: '' },
    dy: 0, skip: false,
    dotHold: false, dotGone: false, ringOn: false, numOn: false, haloOn: false, raysOn: false,
    kickOn: false, wordsOn: 0, statsOn: false, ctaOn: false,
    ttlOn: false, rowOn: false, actsOn: false, orbHold: false, orbGone: false, medalOn: false,
  },
  lifetimes: {
    ready() {
      this._timers = []; this._anims = new Set();
      const start = this.data.mode !== 'finale';
      const view = start ? M.buildStart(this.data.info) : M.buildFinaleView(this.data.info);
      let dy = 0;
      try {
        const win = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {};
        const h = Number(win.windowHeight) || 0;
        if (h && h < 844) dy = M.rpx(Math.max(-96, (h - 844) / 2));   // 矮屏整体上提,按钮不压到读数
      } catch (e) { dy = 0; }
      this.setData({ view, dy }, () => {
        if (this._gone) return;
        if (this.data.reducedMotion) { this._end(false); return; }
        if (start) this._playStart(); else this._playFinale();
      });
    },
    detached() { this._gone = true; this._stop(); },
  },
  methods: {
    noop() {},
    _at(fn, ms) { this._timers.push(setTimeout(() => { if (!this._gone) fn(); }, ms)); },
    _stop() {
      (this._timers || []).forEach(clearTimeout); this._timers = [];
      clearInterval(this._count); this._count = 0;
      this._looping = false;
    },
    /** 一段 animate;结束(或元素不在)时回调。 */
    _play(sel, frames, dur, done) {
      if (this._gone) return;
      this._anims.add(sel);
      let called = false;
      const fin = () => { if (called || this._gone) return; called = true; if (done) done(); };
      try { this.animate(sel, frames, dur, fin); } catch (e) { fin(); }
    },
    /** 循环动画(慢转 / 呼吸);animate 结束再起一轮。 */
    _loop(sel, frames, dur) {
      if (this._gone || this._stopped) return;
      this._play(sel, frames, dur, () => this._loop(sel, frames, dur));
    },
    _clearAnims() {
      this._stopped = true;
      this._anims.forEach((sel) => { try { this.clearAnimation(sel, CLEAR, () => {}); } catch (e) { /* 元素已卸载 */ } });
      this._anims.clear();
    },

    // ---------- 时刻 1 ----------
    _playStart() {
      const S = M.START, E = M.EASE, v = this.data.view, t = v.times;
      this._stopped = false;
      // 0ms:只有底色和一颗光点长出来
      this._play('#dot', M.sampleFrames({ scale: [[0, 0], [.5, 1.6], [1, 1]] }, E.ease, 8), S.dotGrow, () => this.setData({ dotHold: true }));
      // 300ms:光点沿环排开,点阵数字逐点亮起(各点的延迟在内联 transition-delay 里)
      this._at(() => this.setData({ ringOn: true, numOn: true }), S.ringAt);
      this._at(() => this._play('#dot', M.sampleFrames({ opacity: [[0, 1], [1, 0]] }, null, 4), S.dotFadeDur, () => this.setData({ dotGone: true })), S.dotFadeAt);
      // 1100ms:光晕与光线轻推一次,之后保持极慢转动
      this._at(() => {
        this._play('#halo', M.sampleFrames({ opacity: [[0, 0], [.5, 1], [1, .7]], scale: [[0, .6], [.5, 1.05], [1, 1]] }, E.easeOut, 8), S.haloDur, () => this.setData({ haloOn: true }));
        this._play('#rays', M.sampleFrames({ opacity: [[0, 0], [.5, .9], [1, .35]], rotate: [[0, 0], [.5, 20], [1, 40]] }, E.easeOut, 8), S.raysDur, () => this.setData({ raysOn: true }));
        this._loop('#ring', M.sampleFrames({ rotate: [[0, 0], [1, 360]] }, null, 2), S.spinDur);
      }, S.haloAt);
      // 1300ms 起:眉标 → 章名逐字(模糊→清晰)→ 读数滚到位 → 按钮最后
      this._at(() => this.setData({ kickOn: true }), t.kick);
      t.words.forEach((at, i) => this._at(() => this.setData({ wordsOn: i + 1 }), at));
      this._at(() => { this._countUp(); this.setData({ statsOn: true }); }, t.stats);
      this._at(() => { this._settled = true; this.setData({ ctaOn: true }); }, t.cta);
    },
    _countUp() {
      const stats = this.data.view.stats;
      if (!stats.length) return;
      const t0 = Date.now();
      clearInterval(this._count);
      this._count = setInterval(() => {
        if (this._gone) { clearInterval(this._count); return; }
        const patch = {}; let live = false;
        stats.forEach((s, i) => {
          const p = Math.min(1, (Date.now() - t0) / s.dur);
          patch['view.stats[' + i + '].shown'] = M.statAt(s.spec, M.countEase(p), s.v);
          if (p < 1) live = true;
        });
        this.setData(patch);
        if (!live) { clearInterval(this._count); this._count = 0; }
      }, 24);
    },

    // ---------- 时刻 3 ----------
    _playFinale() {
      const F = M.FINALE, E = M.EASE, v = this.data.view;
      this._stopped = false;
      // 第一幕 0–900ms:粒子向中心汇聚,核心旋转放大
      v.particles.forEach((p, i) => this._at(() => this._play('#p' + i, M.sampleFrames({
        opacity: [[0, 0], [.3, 1], [1, 0]], translateX: [[0, p.x], [1, 0]], translateY: [[0, p.y], [1, 0]],
      }, E.gather, 10), F.chargeDur), p.delay));
      this._play('#orb', M.sampleFrames({
        opacity: [[0, 0], [.5, 1], [1, 1]], rotate: [[0, 45], [.5, 225], [1, 405]], scale: [[0, .4], [.5, .9], [1, 1.1]],
      }, E.charge, 10), F.chargeDur);
      // 第二幕 900ms:白光一闪,光环向外扩散,核心散去
      this._at(() => {
        this._play('#flash', M.sampleFrames({ opacity: [[0, 0], [.5, 1], [1, 0]] }, E.easeOut, 8), F.flashDur);
        this._play('#orb', M.sampleFrames({ opacity: [[0, 1], [1, 0]], scale: [[0, 1.1], [1, 1.6]], rotate: [[0, 405], [1, 405]] }, null, 4), F.orbOutDur, () => this.setData({ orbGone: true }));
        this._play('#ringx', M.sampleFrames({ opacity: [[0, 1], [1, 0]], scale: [[0, .3], [1, 2.4]] }, E.out, 10), F.ringxDur);
        this.setData({ orbHold: true });   // 先起动画再写状态,setData 的渲染耗时不拖慢这一闪
      }, F.flashAt);
      // 第三幕 1100ms:光线在后,勋章从下方升起落定(只有这一个主角)
      this._at(() => {
        this._play('#frays', M.sampleFrames({ opacity: [[0, 0], [1, 1]], rotate: [[0, 0], [1, 30]], scale: [[0, .6], [1, 1]] }, E.out, 8), F.raysDur, () => {
          this.setData({ raysOn: true });
          this._loop('#frays', M.sampleFrames({ rotate: [[0, 30], [1, 390]] }, null, 2), F.raysSpinDur);
        });
        this._play('#medal', M.sampleFrames({
          opacity: [[0, 0], [.5, 1], [1, 1]], translateY: [[0, 40], [.5, -6], [1, 0]], scale: [[0, .7], [.5, 1.03], [1, 1]],
        }, E.nav, 10), F.medalDur, () => this.setData({ medalOn: true }));
      }, F.raysAt);
      this._at(() => this._loop('#glow', M.sampleFrames({ opacity: [[0, 0], [.5, .18], [1, 0]] }, null, 6), F.glowDur), F.glowAt);
      // 1700ms 起:已通关 + 主题名 → 读数卡 → 一行保存/分享 + 主按钮最后
      this._at(() => this.setData({ kickOn: true }), F.kickAt);
      this._at(() => this.setData({ ttlOn: true }), F.ttlAt);
      this._at(() => this.setData({ rowOn: true }), F.rowAt);
      this._at(() => { this._settled = true; this.setData({ actsOn: true }); }, F.actsAt);
    },

    // ---------- 终态 / 跳过 ----------
    /** 直接落到终态;animated=true 时(点击跳过)先去掉所有过渡。 */
    _end(skip) {
      this._stop();
      this._clearAnims();
      this._settled = true;
      const v = this.data.view, patch = { skip: !!skip };
      if (this.data.mode !== 'finale') {
        Object.assign(patch, { dotHold: false, dotGone: true, ringOn: true, numOn: true, haloOn: true, raysOn: true, kickOn: true, wordsOn: v.words.length, statsOn: true, ctaOn: true });
        v.stats.forEach((s, i) => { patch['view.stats[' + i + '].shown'] = s.v; });
      } else {
        Object.assign(patch, { orbHold: false, orbGone: true, medalOn: true, raysOn: true, kickOn: true, ttlOn: true, rowOn: true, actsOn: true });
      }
      this.setData(patch, () => {
        if (this._gone) return;
        this._stopped = false;
        // 终态之后仍保留极慢的环转 / 光线转
        if (this.data.mode !== 'finale') this._loop('#ring', M.sampleFrames({ rotate: [[0, 0], [1, 360]] }, null, 2), M.START.spinDur);
        else this._loop('#frays', M.sampleFrames({ rotate: [[0, 30], [1, 390]] }, null, 2), M.FINALE.raysSpinDur);
      });
    },
    onTap() {
      if (!this._settled) { this._end(true); return; }
      if (this.data.mode !== 'finale') this.triggerEvent('go');
    },
    onGo() { this.triggerEvent('go'); },
    onSave() { this.triggerEvent('save'); },
    onClose() { this.triggerEvent('continue'); },
  },
});
