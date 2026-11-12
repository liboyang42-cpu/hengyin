// 游戏主题库(业主批准 proto/game ps-library)。qx-final 合并:界面取 l-after(一行一张俱乐部式卡、底部黑渐变、标题不写数量,
// 筛选 全部 / 城市定向 / 自由探索),数据取公开目录,不依赖通行证开关或账号视角。
// 「¥0 可玩」只在服务端给了逐主题覆盖事实(passCovered === true)时出。
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
    pm: 0
  },
  onLoad(options) {
    const kind = options && lib.KINDS.some((k) => k.key === options.kind) ? options.kind : 'all'
    this.setData({ kind })
    this.reload()
  },
  onPullDownRefresh() { this.reload() },
  onUnload() { this._unloaded = true },

  reload() {
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading', error: '' })
    app.sendRequest({
      url: '/api/public-catalog/list', method: 'POST', data: Object.assign(lib.query(this.data.kind, 1), { pageSize: 100 }), hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (seq !== this._seq || this._unloaded) return
        wx.stopPullDownRefresh()
        const d = lib.readPage(res)
        if (!d) { this.setData({ state: 'error', error: (res && res.msg) || '主题库暂未加载' }); return }
        enter(this, 'pm', { once: true })
        this.setData({ state: 'ready', cards: lib.passLibraryList(d.rows) })
      },
      fail: () => {
        if (seq !== this._seq || this._unloaded) return
        wx.stopPullDownRefresh()
        this.setData({ state: 'error', error: '网络异常，请重试' })
      }
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
