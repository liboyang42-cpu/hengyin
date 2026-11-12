/* r3-pass:通行证到账的分步动效(结构参考 refs4/pass-purchase-motion-frames.jpg:黑 → 暖橙渐变、
   金色物件由碎片拼起、+1 逐个弹出、拨盘转开、链条连起、分页点 + 白色「继续」、右上「跳过」)。
   物件是我们自己的城市通票 + 城瘾印章,不是保险库或第三方美术。
   steps 只来自 utils/pass-unlock-view.js(服务端已付订单 + 商品详情);没有的事实没有那一步。
   只用 transition;减少动态效果时每一步直接给终态、不震动。 */
const { haptic } = require('../../../../utils/motion.js')

const ROW_GAP = 320
const LINK_GAP = 140

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    show: { type: Boolean, value: false },
    steps: { type: Array, value: [] },
    face: { type: String, value: '' },
    top: { type: Number, value: 88 }, // 「跳过」放在胶囊下面,不和胶囊抢位置
    reducedMotion: { type: Boolean, value: false }
  },
  data: { i: 0, cur: null, on: false, obj: 'scattered', hit: 0, turned: false, open: false, last: false },
  observers: {
    show(v) { if (v) this._begin(); else this._clear() }
  },
  lifetimes: { detached() { this._clear() } },
  methods: {
    noop() {},
    _at(ms, fn) {
      const t = setTimeout(fn, this.data.reducedMotion ? 0 : ms)
      ;(this._t = this._t || []).push(t)
    },
    _clear() { (this._t || []).forEach(clearTimeout); this._t = [] },
    _begin() {
      this._clear()
      if (!this.data.steps.length) { this.triggerEvent('done'); return }
      this.setData({ on: false })
      this._go(0) // _go 会清掉上一步的计时器,所以淡入要排在它后面
      this._at(20, () => this.setData({ on: true }))
    },
    _go(i) {
      this._clear()
      const steps = this.data.steps
      const cur = steps[i]
      const reduced = this.data.reducedMotion
      const last = i === steps.length - 1
      if (reduced) {
        this.setData({ i, cur, last, obj: cur.key === 'pass' ? 'open' : 'assembled', hit: (cur.rows || cur.links || []).length, turned: true, open: true })
        return
      }
      // 每一步先放初态,下一帧再挂终态类,让 transition 跑起来
      this.setData({ i, cur, last, hit: 0, turned: false, open: false, obj: cur.key === 'pass' && i === 0 ? 'scattered' : 'assembled' })
      if (cur.key === 'pass') {
        this._at(650, () => this.setData({ obj: 'assembled' }))
        this._at(1700, () => { this.setData({ obj: 'open' }); haptic({ type: 'light' }) })
      } else if (cur.key === 'themes' || cur.key === 'seats') {
        const n = (cur.rows || cur.links || []).length
        const gap = cur.key === 'themes' ? ROW_GAP : LINK_GAP
        for (let k = 1; k <= n; k++) this._at(380 + k * gap, () => this.setData({ hit: k }))
      } else if (cur.key === 'dial') {
        this._at(120, () => this.setData({ turned: true }))
        this._at(1900, () => { this.setData({ open: true }); haptic({ type: 'light' }) })
      }
    },
    onNext() {
      if (this.data.last) { this.triggerEvent('done'); return }
      this._go(this.data.i + 1)
    },
    onSkip() { this._clear(); this.triggerEvent('done') }
  }
})
