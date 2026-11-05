'use strict'

// 地图实时天气氛围(第九轮 m-rain):雨丝 / 雪点 / 雾纱画在地图之上、卡片与按钮之下,不接手势。
// 读 GET /api/map/weather(后端按片区缓存、失败回 clear);任何失败、晴天、减弱动态效果、页面隐藏:什么都不画,也不提示。
const core = require('./weather-core.js')

const RAIN = 'rgba(205,216,235,.42)'
// 第十二轮 k7(j4 N3):雪点比地图上的地点小圆更淡、更散,大小不一,不和白色地点点混成一片
const SNOW = 'rgba(232,238,250,.34)'

function weatherKey(w) { return w ? w.kind + ':' + w.intensity : '' }

Component({
  options: { virtualHost: true },
  properties: {
    lat: { type: Number, value: 0 },
    lng: { type: Number, value: 0 },
    active: { type: Boolean, value: false },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { on: false },
  observers: {
    'lat, lng, active, reducedMotion'() { this._sync() },
  },
  lifetimes: {
    attached() { this._alive = true },
    ready() { this._sync() },
    detached() {
      this._alive = false
      this._stop()
      this._canvas = null; this._ctx = null; this._weather = null; this._parts = null
    },
  },
  methods: {
    _sync() {
      if (!this._alive) return
      const d = this.data
      if (d.reducedMotion) { this._show(null); return }
      if (!d.active) { this._stop(); return }
      const cell = core.cellOf(d.lat, d.lng)
      if (!cell) { this._show(null); return }
      const hit = core.cached(cell, Date.now())
      if (hit !== undefined) { this._show(hit); return }
      this._fetch(cell)
    },
    _fetch(cell) {
      if (this._pending === cell) return
      this._pending = cell
      const done = (weather) => {
        if (this._pending === cell) this._pending = null
        core.remember(cell, weather, Date.now())
        if (this._alive) this._sync()
      }
      const app = typeof getApp === 'function' ? getApp() : null
      if (!app || typeof app.sendRequest !== 'function') { done(null); return }
      // 只发片区中心,不发玩家精确坐标。
      const parts = cell.split(':')
      const lat = ((Number(parts[0]) + 0.5) / core.CELLS_PER_DEGREE).toFixed(3)
      const lng = ((Number(parts[1]) + 0.5) / core.CELLS_PER_DEGREE).toFixed(3)
      try {
        // 氛围层读不到就不画:业务失败不自动弹,HTTP 异常与断网由下面两个回调接住(同样不弹)。
        app.sendRequest({ url: '/api/map/weather', method: 'GET', data: { lat, lng }, hideLoading: true, autoErrorToast: false,
          success: (res) => done(res && (res.code === 200 || res.code === '200') ? core.normalize(res.data) : null),
          successStatusAbnormal: () => done(null),
          fail: () => done(null),
        })
      } catch (e) { done(null) }
    },
    _show(weather) {
      const d = this.data
      if (!weather || d.reducedMotion || !d.active) {
        this._stop()
        this._weather = null; this._parts = null
        if (this._ctx) this._ctx.clearRect(0, 0, this._w, this._h)
        if (d.on) { this._canvas = null; this._ctx = null; this.setData({ on: false }) }
        return
      }
      if (weatherKey(weather) === weatherKey(this._weather) && this._ctx) {
        if (weather.kind !== 'fog') this._start()
        return
      }
      this._stop()
      this._weather = weather
      this._withCanvas(() => {
        if (!this._alive || this._weather !== weather) return
        this._parts = core.makeParticles(weather, this._w, this._h)
        this._ctx.clearRect(0, 0, this._w, this._h)
        if (weather.kind === 'fog') this._drawFog(weather)
        else this._start()
      })
    },
    _withCanvas(cb) {
      if (this._ctx) { cb(); return }
      const query = () => {
        this.createSelectorQuery().select('#mapweather').fields({ node: true, size: true }).exec((res) => {
          const item = res && res[0]
          if (!this._alive || !item || !item.node || !item.width || !item.height) return
          const win = (wx.getWindowInfo && wx.getWindowInfo()) || {}
          const dpr = core.dprOf(win.pixelRatio)
          const canvas = item.node
          canvas.width = Math.round(item.width * dpr)
          canvas.height = Math.round(item.height * dpr)
          const ctx = canvas.getContext('2d')
          if (!ctx) return
          ctx.scale(dpr, dpr)
          this._canvas = canvas; this._ctx = ctx; this._w = item.width; this._h = item.height
          cb()
        })
      }
      if (this.data.on) query()
      else this.setData({ on: true }, query)
    },
    _start() {
      if (this._raf || !this._canvas || !this._weather || this._weather.kind === 'fog') return
      let last = 0
      const frame = (ts) => {
        this._raf = 0
        if (!this._alive || !this._ctx || !this._weather || !this.data.active || this.data.reducedMotion) return
        const k = last && ts > last ? Math.min(3, (ts - last) / 16.7) : 1
        last = ts
        core.step(this._weather, this._parts, this._w, this._h, k)
        this._drawParticles()
        this._raf = this._canvas.requestAnimationFrame(frame)
      }
      this._raf = this._canvas.requestAnimationFrame(frame)
    },
    _stop() {
      if (this._raf && this._canvas) this._canvas.cancelAnimationFrame(this._raf)
      this._raf = 0
    },
    _drawParticles() {
      const ctx = this._ctx, w = this._weather
      ctx.clearRect(0, 0, this._w, this._h)
      ctx.globalAlpha = w.kind === 'snow' ? 0.5 + 0.3 * w.intensity : 0.55 + 0.45 * w.intensity
      if (w.kind === 'rain') {
        ctx.strokeStyle = RAIN; ctx.lineWidth = 1; ctx.beginPath()
        for (const p of this._parts) { ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - 2.4, p.y + 13 * p.v) }
        ctx.stroke()
      } else {
        ctx.fillStyle = SNOW
        for (const p of this._parts) { ctx.beginPath(); ctx.arc(p.x, p.y, 2 + p.s * 3, 0, 7); ctx.fill() }
      }
      ctx.globalAlpha = 1
    },
    /** 雾是一层静止的纱,画一次,不跑动画。 */
    _drawFog(w) {
      const ctx = this._ctx, a = 0.5 + 0.5 * w.intensity
      const g = ctx.createLinearGradient(0, 0, 0, this._h)
      g.addColorStop(0, 'rgba(170,176,190,' + (0.34 * a).toFixed(3) + ')')
      g.addColorStop(1, 'rgba(120,126,140,' + (0.5 * a).toFixed(3) + ')')
      ctx.fillStyle = g
      ctx.fillRect(0, 0, this._w, this._h)
    },
  },
})
