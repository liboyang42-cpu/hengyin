'use strict'

// 第九轮 m1 第 3 项:地点卡。数据就是地图上那一枚点(名字 / 照片 / 地址 / 距离),
// 评分沿用据点详情原来的读接口(/api/city/nodes/{id} 的 averageRating / ratingCount),不另开一份。正文仍在原来的详情里,点卡片本身进去。
const app = typeof getApp === 'function' ? getApp() : null

function ok(res) { return res && (res.code === 200 || res.code === '200') }
function ratingText(avg, count) {
  const a = Number(avg)
  if (!Number.isFinite(a) || a <= 0) return ''
  if (count != null && !(Number(count) > 0)) return ''
  return a.toFixed(1)
}

Component({
  properties: {
    place: { type: Object, value: null, observer(p) { this._onPlace(p) } },
    top: { type: Number, value: 150 },
    originY: { type: Number, value: 270 },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { show: false, shown: false, rating: '', ratingError: false },
  lifetimes: {
    detached() { this._seq = (this._seq || 0) + 1; if (this._inTimer) clearTimeout(this._inTimer) },
  },
  methods: {
    _onPlace(p) {
      const seq = this._seq = (this._seq || 0) + 1
      if (this._inTimer) { clearTimeout(this._inTimer); this._inTimer = null }
      if (!p || !p.name) { this.setData({ show: false, shown: false, rating: '', ratingError: false }); return }
      this.setData({ show: true, shown: !!this.data.reducedMotion, rating: '', ratingError: false })
      if (!this.data.reducedMotion) this._inTimer = setTimeout(() => { if (seq === this._seq) this.setData({ shown: true }) }, 20)
      this._loadRating(p, seq)
    },
    _loadRating(p, seq) {
      if (!app || typeof app.sendRequest !== 'function') return
      const done = (avg, count) => { if (seq === this._seq) this.setData({ rating: ratingText(avg, count) }) }
      if (p.shareKind === 'poi' && p.shareId) {
        app.sendRequest({ url: '/api/city/nodes/' + p.shareId, method: 'GET', hideLoading: true, silentError: true,
          // 评分只是卡上的一格:读不到时这一格显示「—」(ratingError),不弹错,卡上其余内容照常
          success: (res) => { const d = ok(res) && res.data; if (d) done(d.averageRating, d.ratingCount); else if (seq === this._seq) this.setData({ ratingError: true }) },
          successStatusAbnormal: () => { if (seq === this._seq) this.setData({ ratingError: true }) },
          fail: () => { if (seq === this._seq) this.setData({ ratingError: true }) } })
      }
    },
    onOpen() { this.triggerEvent('open', { id: this.data.place && this.data.place.id }) },
    onClose() { this.triggerEvent('close') },
    onNavigate() {
      const p = this.data.place || {}
      const latitude = Number(p.lat), longitude = Number(p.lng)
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return
      wx.openLocation({ latitude, longitude, name: p.name || '', address: p.address || '', scale: 17 })
    },
  },
})

module.exports = { ratingText }
