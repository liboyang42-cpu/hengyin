'use strict'
// 收集册打开过场(2026-11-10 r2 a4-v3-27 业主「收集册打开要有一个过场动画 你来设计 参考x上的」,
// 参考 refs2/album-map-stamps-frames.jpg:照片邮票落到地图上钉住、路线连起来)。
// 只在从「我的」三叠小卡打开收集册时播一次(页面参数 from=profile);段间切换(redirectTo)不重播。
// 只动 transform / opacity;减少动态效果(系统或 App 设置)时不播。点一下跳过。
const { readReducedMotion } = require('../../../utils/motion-preference.js')

const LAND_MS = 40
const OUT_MS = 1800
const DONE_MS = 2200

Component({
  data: { show: false, land: false, out: false },
  lifetimes: {
    attached() {
      const pages = getCurrentPages()
      const page = pages[pages.length - 1]
      const from = page && page.options ? page.options.from : ''
      if (from !== 'profile' || readReducedMotion()) return
      this.setData({ show: true })
      this._t = [
        setTimeout(() => this.setData({ land: true }), LAND_MS),
        setTimeout(() => this.setData({ out: true }), OUT_MS),
        setTimeout(() => this.setData({ show: false }), DONE_MS),
      ]
    },
    detached() { (this._t || []).forEach(clearTimeout) },
  },
  methods: {
    skip() {
      (this._t || []).forEach(clearTimeout)
      this.setData({ out: true })
      this._t = [setTimeout(() => this.setData({ show: false }), 360)]
    },
    noop() {},
  },
})
