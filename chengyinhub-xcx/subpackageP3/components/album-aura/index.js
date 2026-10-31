'use strict'
const { readReducedMotion } = require('../../../utils/motion-preference.js')

// 玩家「减少动态效果」开着时色块不漂(根节点挂 aura--reduced)
Component({
  data: { reduced: false },
  lifetimes: {
    attached() { if (readReducedMotion()) this.setData({ reduced: true }) },
  },
})
