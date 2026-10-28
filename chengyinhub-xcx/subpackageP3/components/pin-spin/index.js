// cy pin-spin · 可旋转的珐琅勋章(2026-10-28 业主:勋章要能点着转)。
// 正面 / 背面(拉丝金属,不放字)/ 两张侧边都由勋章墙同一份 enamel-pin.js 画,按画布实际像素画(放大显示会糊):先在本 canvas 上逐张画好转成临时图,
// 再用 canvas.createImage 读回来逐帧 drawImage —— 只用勋章墙已在真机验证过的那几个接口。
// 交互:点一下转两圈停回正面;按住左右拖动跟手,松手带惯性、慢慢吸回最近的正面。
// 减弱动态效果:点一下直接翻到背面,一秒后翻回;拖动照常(由手指驱动,不算自动动画)。
const ENAMEL = require('../../pages/badge-wall/index/enamel-pin.js');

const MAX_FACE_PX = 900;

Component({
  properties: {
    glyph: { type: String, value: '' },
    track: { type: Number, value: 0 },     // 勋章墙类别轨道,决定金属成色(enamel-pin.js optsFor)
    reduced: { type: Boolean, value: false },
  },
  lifetimes: {
    ready() { this._dead = false; this._theta = 0; this._vel = 0; this._init(); },
    detached() { this._dead = true; this._stop(); },
  },
  methods: {
    _init() {
      const that = this;
      this.createSelectorQuery().select('#spincv').fields({ node: true, size: true }).exec(function (res) {
        const item = res && res[0];
        if (that._dead) return;
        if (!item || !item.node || !item.width) { that.triggerEvent('unsupported', { reason: 'renderer' }); return; }
        that._canvas = item.node;
        that._ctx = item.node.getContext('2d');
        that._w = item.width; that._h = item.height;
        const win = (wx.getWindowInfo && wx.getWindowInfo()) || {};
        that._dpr = win.pixelRatio || 2;
        that._buildFaces();
      });
    },
    _buildFaces() {
      const that = this, canvas = this._canvas, ctx = this._ctx, key = this.data.glyph || 'DEFAULT';
      const opts = ENAMEL.optsFor(this.data.track);
      const FACE_PX = Math.min(MAX_FACE_PX, Math.round(Math.min(this._w, this._h) * this._dpr * 0.78));
      this._facePx = FACE_PX;
      const jobs = [
        ['front', function () { ENAMEL.render(ctx, FACE_PX, key, false, opts); }],
        ['back', function () { ENAMEL.renderBack(ctx, FACE_PX, key, opts); }],
        ['edge', function () { ENAMEL.renderEdge(ctx, FACE_PX, key, false, opts); }],
        ['edgeDark', function () { ENAMEL.renderEdge(ctx, FACE_PX, key, true, opts); }],
      ];
      const faces = {};
      canvas.width = FACE_PX; canvas.height = FACE_PX;
      (function next(i) {
        if (that._dead) return;
        if (i >= jobs.length) { that._start(faces); return; }
        try { jobs[i][1](); } catch (e) { that.triggerEvent('unsupported', { reason: 'renderer' }); return; }
        wx.canvasToTempFilePath({
          canvas: canvas, fileType: 'png', destWidth: FACE_PX, destHeight: FACE_PX,
          success(r) {
            const img = canvas.createImage();
            img.onload = function () { faces[jobs[i][0]] = img; next(i + 1); };
            img.onerror = function () { if (!that._dead) that.triggerEvent('unsupported', { reason: 'asset' }); };
            img.src = r.tempFilePath;
          },
          fail() { if (!that._dead) that.triggerEvent('unsupported', { reason: 'renderer' }); },
        });
      })(0);
    },
    _start(faces) {
      this._faces = faces;
      this._canvas.width = Math.round(this._w * this._dpr); this._canvas.height = Math.round(this._h * this._dpr);
      this._draw();
      this.triggerEvent('loaded');
      if (!this.data.reduced) this._spinTo(1);   // 进页先转一圈,告诉人它能转
    },
    _draw() {
      const size = this._facePx;   // 面图与显示 1:1,不再放大
      ENAMEL.spin(this._ctx, this._canvas.width, this._canvas.height, this._faces, this._theta, size);
    },
    _stop() { if (this._raf && this._canvas) this._canvas.cancelAnimationFrame(this._raf); this._raf = 0; },
    _loop(step) {
      const that = this;
      this._stop();
      const frame = function (ts) { if (that._dead) return; if (step(ts) !== false) that._raf = that._canvas.requestAnimationFrame(frame); else that._raf = 0; };
      this._raf = this._canvas.requestAnimationFrame(frame);
    },
    /** 转 turns 圈,停在正面;减弱动态效果时改成翻面再翻回 */
    _spinTo(turns) {
      const TWO_PI = Math.PI * 2, that = this;
      if (this.data.reduced) {
        this._theta = Math.round(this._theta / TWO_PI) * TWO_PI + Math.PI; this._draw();
        setTimeout(function () { if (that._dead) return; that._theta = Math.round(that._theta / TWO_PI) * TWO_PI; that._draw(); }, 1000);
        return;
      }
      const from = this._theta, to = Math.round(from / TWO_PI) * TWO_PI + turns * TWO_PI, dur = 1300 + turns * 250;
      let t0 = 0;
      this._loop(function (ts) {
        if (!t0) t0 = ts;
        const k = Math.min(1, (ts - t0) / dur), e = 1 - Math.pow(1 - k, 4);
        that._theta = from + (to - from) * e; that._draw();
        return k < 1;
      });
    },
    /** 松手:惯性衰减,再吸回最近的正面 */
    _settle() {
      const TWO_PI = Math.PI * 2, that = this;
      this._loop(function () {
        that._theta += that._vel; that._vel *= 0.95;
        if (Math.abs(that._vel) < 0.004) {
          const d = Math.round(that._theta / TWO_PI) * TWO_PI - that._theta;
          if (Math.abs(d) < 0.002) { that._theta += d; that._draw(); return false; }
          that._theta += d * 0.12;
        }
        that._draw();
        return true;
      });
    },
    onTouchStart(e) {
      if (!this._faces) return;
      this._stop();
      const x = e.touches[0].clientX;
      this._drag = { last: x, moved: 0 };
      this._vel = 0;
    },
    onTouchMove(e) {
      if (!this._drag) return;
      const x = e.touches[0].clientX, dx = x - this._drag.last;
      this._drag.last = x; this._drag.moved += Math.abs(dx);
      this._theta += dx * 0.012; this._vel = dx * 0.012;
      this._draw();
    },
    onTouchEnd() {
      if (!this._drag) return;
      const tap = this._drag.moved < 6;
      this._drag = null;
      if (tap) this._spinTo(2); else this._settle();
    },
  },
});
