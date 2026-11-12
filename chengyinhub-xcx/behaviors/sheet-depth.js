/**
 * behaviors/sheet-depth.js —— 叠层景深(qx-u3)
 *
 * 挂在弹层宿主上(cy-sheet / cy-scene-sheet / project-drawer / project-layer):
 * show 变 true 登记到 utils/sheet-depth.js,变 false 或卸载时出表;
 * 登记表告诉它「上面还有一层」时置 _under,宿主 wxml 把它拼成自己的 --under 类,
 * wxss 负责缩放 .94 + 上移 10px + 压暗(transition,减动效下直接到位)。
 */
const depth = require('../utils/sheet-depth.js')

// 单测沙箱里没有全局 Behavior:退回原样对象(同 exit-motion.js)
const define = typeof Behavior === 'function' ? Behavior : (o) => o

module.exports = define({
  data: { _under: false },
  observers: {
    show(value) {
      if (value && this.data.noDepth) return
      if (value) depth.open(this)
      else depth.close(this)
    },
  },
  lifetimes: {
    detached() { this._sheetDead = true; depth.close(this) },
  },
  methods: {
    _applySheetDepth(under) {
      if (this._sheetDead || this.data._under === under) return
      this.setData({ _under: under })
    },
  },
})
