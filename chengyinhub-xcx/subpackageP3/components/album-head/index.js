'use strict'
// 收集册三段 = 三个原路由;切段用 redirectTo 就地换页(不往栈里压),返回键回到进收集册之前那一页
const ROUTES = {
  medal: '/subpackageP3/pages/badge-wall/index/index',
  stamp: '/subpackageP3/pages/stamp-album/index/index',
  object: '/subpackageP3/pages/object-cards/index/index',
}

Component({
  properties: {
    active: { type: String, value: 'medal' },
  },
  data: {
    segs: [{ key: 'medal', label: '勋章' }, { key: 'stamp', label: '邮票' }, { key: 'object', label: '藏品' }],
  },
  methods: {
    go(e) {
      const key = e.currentTarget.dataset.key
      if (!ROUTES[key] || key === this.data.active || this._going) return
      this._going = true
      wx.redirectTo({ url: ROUTES[key], complete: () => { this._going = false } })
    },
  },
})
