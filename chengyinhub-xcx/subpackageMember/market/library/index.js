// l-after(2026-11-10)游戏主题库(业主批准 proto/game ps-library):标题就叫「游戏主题库」,不写几个主题;
// 一行一张俱乐部式卡(封面铺满、底部黑渐变、顶行 城标 + ¥0 可玩 + 城市、主题名、一行数据、标签片 + 右箭头);
// 筛选 全部 / 城市定向 / 自由探索。列表读公开目录;「¥0 可玩」只在服务端给了通行证覆盖事实时出(见 utils/pass-library.js)。
const app = getApp()
const lib = require('../../utils/pass-library.js')
const { enter } = require('../../../utils/page-motion.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    kinds: lib.KINDS,
    kind: 'all',
    state: 'loading', // loading | ready | error
    error: '',
    cards: [],
    hasMore: false,
    moreState: '', // '' | loading | error
    pm: 0
  },
  onLoad(options) {
    const kind = options && lib.KINDS.some((k) => k.key === options.kind) ? options.kind : 'all'
    this.setData({ kind })
    this.reload()
  },
  onPullDownRefresh() { this.reload(() => wx.stopPullDownRefresh()) },
  onReachBottom() { this.loadMore() },

  post(data) {
    return new Promise((resolve) => {
      app.sendRequest({
        url: '/api/public-catalog/list', method: 'POST', data, hideLoading: true, autoErrorToast: false,
        success: (res) => resolve(res || {}),
        successStatusAbnormal: (res) => resolve(Object.assign({ httpFail: true }, res || {})),
        fail: () => resolve({ code: 'fail', netFail: true })
      })
    })
  },

  reload(done) {
    const seq = this._seq = (this._seq || 0) + 1
    this._pageNum = 1
    this.setData({ state: 'loading', error: '', hasMore: false, moreState: '' })
    this.post(lib.query(this.data.kind, 1)).then((res) => {
      if (seq !== this._seq) return
      if (typeof done === 'function') done()
      const page = lib.readPage(res)
      if (!page) { this.setData({ state: 'error', error: res && res.netFail ? '网络异常，请重试' : ((res && res.msg) || '主题库暂未加载') }); return }
      const cards = lib.libraryList(page.rows)
      enter(this, 'pm', { once: true })
      this.setData({ state: 'ready', cards, hasMore: page.total == null ? page.rows.length >= lib.PAGE_SIZE : cards.length < page.total })
    })
  },
  loadMore() {
    if (this.data.state !== 'ready' || !this.data.hasMore || this.data.moreState === 'loading') return
    const seq = this._seq
    const next = (this._pageNum || 1) + 1
    this.setData({ moreState: 'loading' })
    this.post(lib.query(this.data.kind, next)).then((res) => {
      if (seq !== this._seq) return
      const page = lib.readPage(res)
      if (!page) { this.setData({ moreState: 'error' }); return }
      this._pageNum = next
      const cards = this.data.cards.concat(lib.libraryList(page.rows))
      this.setData({ cards, moreState: '', hasMore: page.total == null ? page.rows.length >= lib.PAGE_SIZE : cards.length < page.total })
    })
  },
  onKind(e) {
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.key
    if (!key || key === this.data.kind) return
    this.setData({ kind: key })
    this.reload()
  },
  openTopic(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id
    if (!id || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + encodeURIComponent(id), complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  }
})
