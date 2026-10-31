'use strict'
const { matrixFromPixels, inFinder, waveAt } = require('../../../utils/dot-qr.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')

const LOGO = '/images/cy_logo_mark.png'
const INK = '#111111' /* ds-ok: 入场码墨色,白底上扫码用 */

Component({
  properties: {
    src: { type: String, value: '' }, // 后端 qrcodeUrl(data:image/png;base64 或 https)
    size: { type: Number, value: 360 }, // rpx
    reducedMotion: { type: Boolean, value: false },
  },
  data: { fallback: false },
  observers: {
    src(v) { if (this._ready) this._load(v) },
    reducedMotion() { if (this._m) this._start() },
  },
  lifetimes: {
    ready() { this._ready = true; this._load(this.data.src) },
    detached() { this._stop(); this._epoch = (this._epoch || 0) + 1 },
  },
  pageLifetimes: {
    hide() { this._stop() },
    show() { if (this._m) this._start() },
  },
  methods: {
    _stop() {
      if (this._cv && this._raf) this._cv.cancelAnimationFrame(this._raf)
      this._raf = 0
    },
    _fail() { this._stop(); this._m = null; if (!this.data.fallback) this.setData({ fallback: true }) },
    // data URI 先落成本地文件,画布 createImage 两种来源都能读
    _localSrc(src, cb) {
      const m = /^data:image\/\w+;base64,(.+)$/.exec(src)
      if (!m) return cb(src)
      const path = wx.env.USER_DATA_PATH + '/cy-dot-qr.png'
      wx.getFileSystemManager().writeFile({ filePath: path, data: m[1], encoding: 'base64', success: () => cb(path), fail: () => cb('') })
    },
    _load(src) {
      const epoch = this._epoch = (this._epoch || 0) + 1
      this._stop(); this._m = null
      if (!src) return
      if (this.data.fallback) this.setData({ fallback: false })
      if (typeof wx.createOffscreenCanvas !== 'function') return this._fail()
      this._localSrc(src, (path) => {
        if (epoch !== this._epoch) return
        if (!path) return this._fail()
        const off = wx.createOffscreenCanvas({ type: '2d', width: 300, height: 300 })
        const img = off.createImage()
        img.onerror = () => { if (epoch === this._epoch) this._fail() }
        img.onload = () => {
          if (epoch !== this._epoch) return
          const w = img.width || 300, h = img.height || 300
          off.width = w; off.height = h
          const x = off.getContext('2d')
          x.fillStyle = '#ffffff'; x.fillRect(0, 0, w, h) /* ds-ok: 取样底色 */
          x.drawImage(img, 0, 0, w, h)
          let m = null
          try { m = matrixFromPixels(x.getImageData(0, 0, w, h).data, w, h) } catch (_) { m = null }
          if (!m) return this._fail()
          this._m = m
          this._mount(epoch)
        }
        img.src = path
      })
    },
    _mount(epoch) {
      this.createSelectorQuery().select('#dq').fields({ node: true, size: true }).exec((res) => {
        const item = res && res[0]
        if (epoch !== this._epoch || !item || !item.node || !item.width) return this._fail()
        const cv = this._cv = item.node, S = item.width
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
        const dpr = Math.min(3, info.pixelRatio || 2)
        cv.width = Math.round(S * dpr); cv.height = Math.round(S * dpr)
        const ctx = this._ctx = cv.getContext('2d'); ctx.scale(dpr, dpr)
        this._S = S
        this._prepare(cv, () => { if (epoch === this._epoch) this._start() })
      })
    },
    // 码心 logo 压成纯黑剪影,画一次留着
    _prepare(cv, done) {
      const img = cv.createImage()
      img.onload = () => {
        const L = 128, off = wx.createOffscreenCanvas({ type: '2d', width: L, height: L }), x = off.getContext('2d')
        x.drawImage(img, 0, 0, L, L)
        x.globalCompositeOperation = 'source-in'; x.fillStyle = INK; x.fillRect(0, 0, L, L)
        this._logo = off; done()
      }
      img.onerror = () => { this._logo = null; done() }
      img.src = LOGO
    },
    _start() {
      this._stop()
      if (!this._ctx || !this._m) return
      // 玩家「减少动态效果」开了就只画静止的黑码
      if (this.data.reducedMotion || readReducedMotion()) { this._draw(-1); return }
      const t0 = Date.now()
      let last = 0
      // 30 帧够看,省电
      const frame = () => { const t = Date.now() - t0; if (t - last >= 32) { last = t; this._draw(t) } this._raf = this._cv.requestAnimationFrame(frame) }
      frame()
    },
    _draw(t) {
      const { n, cells, hole } = this._m, ctx = this._ctx, S = this._S
      const cell = S / n, mid = (n - 1) / 2, maxD = Math.hypot(mid, mid)
      ctx.clearRect(0, 0, S, S)
      const colorAt = (r, c) => t < 0 ? { color: INK, pulse: 0 } : waveAt(t, Math.hypot(r - mid, c - mid) / maxD)
      for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
        if (!cells[r * n + c] || inFinder(n, r, c)) continue
        const w = colorAt(r, c)
        ctx.fillStyle = w.color
        ctx.beginPath(); ctx.arc((c + 0.5) * cell, (r + 0.5) * cell, cell * (0.42 + 0.06 * w.pulse), 0, Math.PI * 2); ctx.fill()
      }
      // 定位块:圆角方环 + 圆角方心(保持 1:1:3:1:1 比例,各家扫码都认),颜色跟着各自位置的波走
      const rr = (x0, y0, side, rad) => {
        ctx.beginPath(); ctx.moveTo(x0 + rad, y0)
        ctx.arcTo(x0 + side, y0, x0 + side, y0 + side, rad); ctx.arcTo(x0 + side, y0 + side, x0, y0 + side, rad)
        ctx.arcTo(x0, y0 + side, x0, y0, rad); ctx.arcTo(x0, y0, x0 + side, y0, rad); ctx.closePath()
      }
      ;[[0, 0], [0, n - 7], [n - 7, 0]].forEach(([r0, c0]) => {
        const w = colorAt(r0 + 3, c0 + 3), x0 = c0 * cell, y0 = r0 * cell
        ctx.strokeStyle = w.color; ctx.fillStyle = w.color; ctx.lineWidth = cell
        rr(x0 + cell / 2, y0 + cell / 2, cell * 6, cell * 2); ctx.stroke()
        rr(x0 + cell * 2, y0 + cell * 2, cell * 3, cell * 1.1); ctx.fill()
      })
      if (this._logo && hole) {
        const L = hole * cell * 0.82
        ctx.drawImage(this._logo, S / 2 - L / 2, S / 2 - L / 2, L, L)
      }
    },
  },
})
