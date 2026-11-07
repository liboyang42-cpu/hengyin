// 移植桌面「预制人生故事流原型.html」morphChapterNumber，章节内容由调用页提供。
Component({
  properties: {
    number: { type: Number, value: 1 },
    total: { type: Number, value: 1 },
    title: { type: String, value: '' },
    subtitle: { type: String, value: '' },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { entered: false, leaving: false, bars: [], canvasReady: false },
  lifetimes: {
    ready() {
      this.setData({ bars: Array.from({ length: this.data.total }, (_, i) => i + 1) });
      this._enterTimer = setTimeout(() => this.setData({ entered: true }), 20);
      this._paint();
    },
    detached() { this._stop(); clearTimeout(this._enterTimer); clearTimeout(this._leaveTimer); },
  },
  pageLifetimes: { hide() { this._stop(); clearTimeout(this._leaveTimer); this.setData({ leaving: false }); }, show() { if (this._canvas && !this.data.leaving) this._paint(); } },
  methods: {
    _stop() {
      this._epoch = (this._epoch || 0) + 1;
      if (this._canvas && this._raf) this._canvas.cancelAnimationFrame(this._raf);
      this._raf = 0;
    },
    _paint() {
      this._stop();
      if (this.data.reducedMotion || this.data.leaving) return;
      const epoch = this._epoch;
      this.createSelectorQuery().select('#chapterNumber').fields({ node: true, size: true }).exec(res => {
        const item = res && res[0];
        if (epoch !== this._epoch || !item || !item.node || !item.width || !item.height) return;
        const cv = item.node, W = item.width, H = item.height;
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
        const dpr = Math.min(2, info.pixelRatio || 1);
        this._canvas = cv;
        cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
        const x = cv.getContext('2d'); x.scale(dpr, dpr);
        x.fillStyle = '#fff'; x.font = '800 ' + (190 * W / 375) + 'px sans-serif'; /* ds-ok 原型数字取样遮罩 */
        x.textAlign = 'center'; x.textBaseline = 'middle';
        x.fillText(String(this.data.number).padStart(2, '0'), W / 2, H * .36);
        let pixels;
        try { pixels = x.getImageData(0, 0, cv.width, cv.height).data; }
        catch (_) { return; } // 保留文字，画布读像素不可用时仍可继续。
        const pts = [];
        for (let yy = 0; yy < H; yy += 4) for (let xx = 0; xx < W; xx += 4) {
          if (pixels[(Math.floor(yy * dpr) * cv.width + Math.floor(xx * dpr)) * 4 + 3] > 120) {
            pts.push({ sx: Math.random() * W, sy: H * .2 + Math.random() * H * .55,
              x: xx, y: yy, a: .3 + Math.random() * .7, p: Math.random() * 6.28 });
          }
        }
        this.setData({ canvasReady: true });
        const started = Date.now();
        const frame = () => {
          if (epoch !== this._epoch) return;
          const t = (Date.now() - started) / 1000;
          const k = Math.max(0, Math.min(1, (t - .2) / 1.65));
          const e = 1 - Math.pow(1 - k, 3), out = Math.max(0, t - 2.8);
          x.clearRect(0, 0, W, H); x.fillStyle = '#fff0c8'; /* ds-ok 用户指定原型金色粒子 */
          for (const p of pts) {
            x.globalAlpha = p.a * Math.min(1, t * 1.3) * Math.max(0, 1 - out * .8);
            x.fillRect(p.sx + (p.x - p.sx) * e + out * 14 * Math.sin(p.p),
              p.sy + (p.y - p.sy) * e - out * out * 20, 1.3, 1.3);
          }
          if (t < 4.1) this._raf = cv.requestAnimationFrame(frame);
        };
        frame();
      });
    },
    continueStory() {
      if (this.data.leaving) return;
      this._stop();
      this.setData({ leaving: true });
      this._leaveTimer = setTimeout(() => this.triggerEvent('continue'), this.data.reducedMotion ? 0 : 380);
    },
  },
});
