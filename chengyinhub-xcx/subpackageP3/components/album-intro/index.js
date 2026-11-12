'use strict'
// 收集册打开过场(r2 a4-v3-27「收集册打开要有一个过场动画」+ r3「Access the collection album page」)。
// ① 邮票落到图上钉住、路线连起来(refs2/album-map-stamps-frames);
// ② 接着变成环装卡堆(refs4/album-ring-stack-frames):三段各一张卡穿在环上,箭头翻页,点卡进那一段。
// 只在从「我的」三叠小卡打开时出现(页面参数 from=profile);段间切换(redirectTo)不重播。
// 只动 transform / opacity;减少动态效果(系统或 App 设置)时整段不出,直接是收集册。
const { readReducedMotion } = require('../../../utils/motion-preference.js')
const { ALBUM_ROUTES, ALBUM_SEGS } = require('../album-head/routes.js')

const LAND_MS = 40
const RING_MS = 1500
const FLIP_MS = 520
const OUT_MS = 320

function navTopPx() {
  const g = (typeof getApp === 'function' && getApp() && getApp().globalData) || {}
  return (g.statusBarHeight || 20) + (g.navBarHeight || 44)
}

Component({
  properties: {
    active: { type: String, value: 'medal' },
  },
  data: { show: false, land: false, ring: false, out: false, still: false, flip: -1, idx: 0, pages: ALBUM_SEGS, navTop: 64 },
  lifetimes: {
    attached() {
      const pages = getCurrentPages()
      const page = pages[pages.length - 1]
      const from = page && page.options ? page.options.from : ''
      if (from !== 'profile' || readReducedMotion()) return
      const idx = Math.max(0, ALBUM_SEGS.findIndex(s => s.key === this.data.active))
      this.setData({ show: true, idx, navTop: navTopPx() })
      this._t = [
        setTimeout(() => this.setData({ land: true }), LAND_MS),
        setTimeout(() => this.toRing(), RING_MS),
      ]
    },
    detached() { this._clear() },
  },
  methods: {
    _clear() { (this._t || []).forEach(clearTimeout); this._t = [] },
    // 点邮票段 = 跳过,直接到卡堆
    toRing() {
      if (this.data.ring) return
      this._clear()
      this.setData({ ring: true, land: true })
    },
    // 箭头:当前卡绕环翻过去,翻完无过渡地落到最后,下一张成为正面
    next() {
      if (this.data.flip >= 0) return
      const n = ALBUM_SEGS.length
      const cur = this.data.idx
      this.setData({ flip: cur })
      this._t.push(setTimeout(() => {
        this.setData({ still: true, flip: -1, idx: (cur + 1) % n })
        this._t.push(setTimeout(() => this.setData({ still: false }), 40))
      }, FLIP_MS))
    },
    // 点卡:就是这一段 → 收起过场;别的段 → 就地换页(不带 from,不重播)
    enter(e) {
      const key = e.currentTarget.dataset.key
      if (!ALBUM_ROUTES[key] || this.data.flip >= 0) return
      if (key === this.data.active) return this.close()
      if (this._going) return
      this._going = true
      wx.redirectTo({ url: ALBUM_ROUTES[key], complete: () => { this._going = false } })
    },
    close() {
      this._clear()
      this.setData({ out: true })
      this._t = [setTimeout(() => this.setData({ show: false }), OUT_MS)]
    },
    noop() {},
  },
})
