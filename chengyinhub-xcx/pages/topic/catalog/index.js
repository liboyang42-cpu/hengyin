// §20.1 公开目录 + UI-P3 主题合集落地(城市合集)。结构照抄 pages/search2/result(深色玩家域、主题大卡)。
// 匿名只读:POST /api/public-catalog/city/list、/filters、/list。带 city_code 直达即城市合集落地,不经首页。
const app = getApp()
const catalog = require('../utils/public-catalog.js')

const PAGE_SIZE = 10
const ALL = '__all'

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    title: '城市目录',
    cityTabs: [],
    activeCity: ALL,
    chips: [],
    selected: {},
    cards: [],
    loading: true,
    loadingMore: false,
    hasMore: false,
    error: '',
    moreError: ''
  },

  onLoad(options) {
    const selected = catalog.selectedFromOptions(options)
    this.setData({ selected, activeCity: selected.city_code || ALL })
    this.loadMeta()
    this.reload()
  },

  onPullDownRefresh() {
    this.loadMeta()
    this.reload(() => wx.stopPullDownRefresh())
  },

  onReachBottom() { this.loadMore() },

  // 读失败都落到页面可见状态(error / moreError);筛选项读失败只是不出选择器
  post(url, data, onFail) {
    return new Promise((resolve) => {
      app.sendRequest({
        url, method: 'POST', data, hideLoading: true,
        success: (res) => resolve(res || {}),
        successStatusAbnormal: (res) => resolve(Object.assign({ httpFail: true }, res || {})),
        fail: () => { onFail({ error: '网络异常，请重试' }); resolve({ code: 'fail', netFail: true, handled: true }) }
      })
    })
  },

  // 城市与筛选项只影响选择器;读不到就不出选择器,列表照常
  loadMeta() {
    const seq = this._metaSeq = (this._metaSeq || 0) + 1
    this.post('/api/public-catalog/filters', {}, () => { if (seq === this._metaSeq) this.setData({ cityTabs: [], chips: [] }) }).then((res) => {
      if (seq !== this._metaSeq || res.code != 200 || !res.data) return
      const cities = catalog.cityOptions(res.data.cities)
      const cityTabs = cities.length ? [{ key: ALL, label: '全部' }].concat(cities.map((c) => ({ key: c.key, label: c.label }))) : []
      this._cities = cities
      this.setData({ cityTabs, chips: catalog.filterChips(res.data), title: this.titleFor(this.data.selected.city_code) })
    })
  },

  titleFor(cityCode) {
    const city = (this._cities || []).find((c) => c.key === cityCode)
    return city ? city.label : '城市目录'
  },

  reload(done) {
    const seq = this._listSeq = (this._listSeq || 0) + 1
    this._pageNum = 1
    this.setData({ loading: true, error: '', moreError: '', hasMore: false })
    this.post('/api/public-catalog/list', catalog.buildQuery(this.data.selected, { pageNum: 1, pageSize: PAGE_SIZE }),
      (patch) => { if (seq === this._listSeq) this.setData({ loading: false, error: patch.error }) }).then((res) => {
      if (seq !== this._listSeq) { if (typeof done === 'function') done(); return }
      if (res.handled) { if (typeof done === 'function') done(); return }
      const page = catalog.readPage(res)
      if (!page) {
        this.setData({ loading: false, error: res.netFail ? '网络异常，请重试' : ((app.getRequestErrorMessage && app.getRequestErrorMessage(res, '目录加载失败')) || '目录加载失败') })
      } else {
        const cards = catalog.cardList(page.rows)
        this.setData({
          loading: false, cards,
          hasMore: page.total != null && cards.length < page.total && page.rows.length >= PAGE_SIZE
        })
      }
      if (typeof done === 'function') done()
    })
  },

  loadMore() {
    if (this.data.loading || this.data.loadingMore || !this.data.hasMore) return
    const seq = this._listSeq
    const next = (this._pageNum || 1) + 1
    this.setData({ loadingMore: true, moreError: '' })
    this.post('/api/public-catalog/list', catalog.buildQuery(this.data.selected, { pageNum: next, pageSize: PAGE_SIZE }),
      () => { if (seq === this._listSeq) this.setData({ loadingMore: false, moreError: '加载更多失败' }) }).then((res) => {
      if (seq !== this._listSeq || res.handled) return
      const page = catalog.readPage(res)
      if (!page) { this.setData({ loadingMore: false, moreError: '加载更多失败' }); return }
      this._pageNum = next
      const seen = {}
      this.data.cards.forEach((c) => { seen[c.id] = true })
      const cards = this.data.cards.concat(catalog.cardList(page.rows).filter((c) => !seen[c.id]))
      this.setData({ loadingMore: false, cards,
        hasMore: page.total != null && cards.length < page.total && page.rows.length >= PAGE_SIZE })
    })
  },

  onCity(e) {
    const key = e.detail && e.detail.key
    if (!key || key === this.data.activeCity) return
    const selected = Object.assign({}, this.data.selected)
    if (key === ALL) delete selected.city_code
    else selected.city_code = key
    this.setData({ selected, activeCity: key, title: this.titleFor(selected.city_code) })
    this.reload()
  },

  onChip(e) {
    const key = e.currentTarget.dataset.key
    const chip = this.data.chips.find((c) => c.key === key)
    if (!chip) return
    this.setData({ selected: catalog.toggleFilter(this.data.selected, chip) })
    this.reload()
  },

  openCard(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: '/pages/topic/catalog/detail?id=' + encodeURIComponent(id) })
  },

  onShareAppMessage() {
    return { title: this.data.title, path: catalog.sharePath('pages/topic/catalog/index', this.data.selected) }
  }
})
