'use strict'
// 收集册三段 = 三个原路由;切段用 redirectTo 就地换页(不往栈里压),返回键回到进收集册之前那一页
// r3-visual:切段时新的一段从切换方向滑进来(via=l / r,页面自己的 animate,只动 transform / opacity;减少动态效果不滑)
const { ALBUM_ROUTES, ALBUM_SEGS } = require('./routes.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')
// 三段页面各自的内容根节点
const ROOTS = { medal: '.bw', stamp: '.al-root', object: '.oc-root' }

Component({
  properties: {
    active: { type: String, value: 'medal' },
  },
  data: {
    segs: ALBUM_SEGS,
  },
  lifetimes: {
    attached() {
      const pages = getCurrentPages()
      const page = pages[pages.length - 1]
      const via = page && page.options ? page.options.via : ''
      if ((via !== 'l' && via !== 'r') || readReducedMotion() || !page || typeof page.animate !== 'function') return
      const dx = via === 'r' ? 48 : -48
      page.animate(ROOTS[this.data.active] || '.bw', [
        { translateX: dx, opacity: 0, ease: 'ease-out' },
        { translateX: 0, opacity: 1, ease: 'ease-out' },
      ], 280, () => { if (typeof page.clearAnimation === 'function') page.clearAnimation(ROOTS[this.data.active] || '.bw', () => {}) })
    },
  },
  methods: {
    go(e) {
      const key = e.currentTarget.dataset.key
      if (!ALBUM_ROUTES[key] || key === this.data.active || this._going) return
      this._going = true
      const order = ALBUM_SEGS.map(s => s.key)
      const via = order.indexOf(key) > order.indexOf(this.data.active) ? 'r' : 'l'
      wx.redirectTo({ url: ALBUM_ROUTES[key] + '?via=' + via, complete: () => { this._going = false } })
    },
  },
})
