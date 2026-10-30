// 画面风格预览(发布页):选一种风格时从底部弹出,给作者一个大概的样子 ——
// 同一份 look-fx(风格层粒子)与 look-transition(转场)副本,与玩家页逐字一致(theme-look.test.js 钉住)。
// 点「使用这个风格」才写回;关掉 = 不改。
const themeLook = require('../../utils/publish/theme-look.js');
const lookFx = require('../../utils/publish/look-fx.js');

// 每种风格的示例文案:让作者一眼认出这是哪种主题的样子(不是可配内容)
const SAMPLE = {
  STARDUST: { kick: '第一章', title: '提篮桥的方舟', story: '1938年11月的一个清晨，一艘邮轮停靠在黄浦江边。', opt: '走去霍山路' },
  PAPER: { kick: '档案 · 第七页', title: '救济点旧址', story: '名单上的字迹已经褪色，最后一行写着：明早六点，霍山路。', opt: '翻到下一页' },
  SURVIVAL: { kick: '外勤点 · 01', title: '风眼之前', story: '气象台说，风眼会在下午三点经过城区，停二十分钟。', opt: '趁风小出门' },
  CYBER: { kick: '// 第二站', title: '十一秒', story: '第三块灯牌又暗了。你数了数，正好十一秒。', opt: 'SOS，有人在求救' },
};

Component({
  properties: {
    show: { type: Boolean, value: false },
    look: { type: Object, value: null },
    reducedMotion: { type: Boolean, value: false },
    embedded: { type: Boolean, value: false },
  },
  data: { cls: '', sample: SAMPLE.STARDUST, tx: 'IRIS', title: '' },
  observers: {
    'show, look'(show, look) {
      this._stop();
      if (!show) return;
      const n = themeLook.normalizeLook(look);
      const style = themeLook.styleOf(n.style);
      this._look = n;
      const time = (n.times || []).find((t) => t);
      this.setData({ cls: themeLook.lookClass(n) + (time ? ' look-time--' + time.toLowerCase() : ''), sample: SAMPLE[n.style], tx: themeLook.resolveTransition(n), title: style.label });
      // 等面板升起、画布有尺寸再开画;常驻时只是换风格,画布已在
      this._t = setTimeout(() => this._paint(), this._painted && this.properties.embedded ? 0 : 360);
    },
  },
  lifetimes: { detached() { this._stop(); } },
  methods: {
    _stop() { clearTimeout(this._t); if (this._cv && this._raf) this._cv.cancelAnimationFrame(this._raf); this._raf = 0; },
    _paint() {
      wx.createSelectorQuery().in(this).select('#lpfx').fields({ node: true, size: true }).select('#lpfront').fields({ node: true, size: true }).exec((res) => {
        const item = res && res[0]; if (!item || !item.node || !this.properties.show) return;
        // 字前面那张:近星 / 水珠 / 沙雾,与玩家页同一份 drawFront
        const fr = res[1] && res[1].node, fctx = fr && fr.getContext('2d');
        const cv = item.node, ctx = cv.getContext('2d'), dpr = (wx.getWindowInfo && wx.getWindowInfo().pixelRatio) || 2;
        cv.width = item.width * dpr; cv.height = item.height * dpr; ctx.scale(dpr, dpr); if (fr) { fr.width = item.width * dpr; fr.height = item.height * dpr; fctx.scale(dpr, dpr); }
        const W = item.width, H = item.height, look = this._look, mod = lookFx.dustMod(look), state = lookFx.create(look, W, H);
        const col = mod.col ? mod.col.join(',') : '255,255,255';
        const specks = Array.from({ length: Math.round(60 * mod.n) }, () => ({ x: Math.random() * W, y: Math.random() * H, s: 0.6 + Math.random() * 1.6, a: 0.15 + Math.random() * 0.4, vy: -(0.05 + Math.random() * 0.15) * mod.speed }));
        this._cv = cv; this._painted = true;
        const loop = () => {
          if (!this.properties.show) return;
          ctx.clearRect(0, 0, W, H);
          specks.forEach((p) => {
            if (!this.properties.reducedMotion) { p.y += p.vy; if (p.y < -4) { p.y = H + 4; p.x = Math.random() * W; } }
            ctx.globalAlpha = p.a; ctx.fillStyle = 'rgb(' + col + ')'; ctx.beginPath(); ctx.arc(p.x, p.y, p.s, 0, 6.2832); ctx.fill();
          });
          if (!this.properties.reducedMotion) lookFx.draw(state, ctx, W, H, Date.now());
          if (fctx) { fctx.clearRect(0, 0, W, H); if (!this.properties.reducedMotion) lookFx.drawFront(state, fctx, W, H, Date.now()); }
          ctx.globalAlpha = 1;
          this._raf = cv.requestAnimationFrame(loop);
        };
        loop();
      });
    },
    onPlayTransition() {
      const tx = this.selectComponent('#lpTx');
      if (tx) tx.run(() => {});
    },
    onUse() { this.triggerEvent('use', { look: this._look }); },
    onClose() { this.triggerEvent('close'); },
  },
});
