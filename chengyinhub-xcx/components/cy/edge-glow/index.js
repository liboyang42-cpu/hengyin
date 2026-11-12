const rm = require('../../../behaviors/reduced-motion.js')
const EASE = 'cubic-bezier(.32,.72,0,1)'
Component({
  behaviors: [rm],
  properties: { trigger: { type: Number, value: 0 }, lkVars: { type: String, value: '' } }, // lkVars:宿主给的 --lk-a/--lk-b 行内变量,没有就用玩家域成功绿
  data: { on: false, vars: '' },
  observers: { trigger(n) { if (n > 0) this.play() } },
  lifetimes: { detached() { clearTimeout(this._t) } },
  methods: {
    play() {
      if (this.data.reducedMotion) return // 减少动态:终态 = 不扫
      this.setData({ on: true, vars: this.data.lkVars }, () => {
        this.animate('.eg__sw', [{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }], 1300, () => {})
        this.animate('.eg', [{ opacity: 0, offset: 0, ease: EASE }, { opacity: 1, offset: 0.15 }, { opacity: 1, offset: 0.75 }, { opacity: 0, offset: 1 }], 1300, () => {})
        clearTimeout(this._t)
        this._t = setTimeout(() => this.setData({ on: false }), 1350)
      })
    },
  },
})
