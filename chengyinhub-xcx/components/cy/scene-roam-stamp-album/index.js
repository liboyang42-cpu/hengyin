'use strict'

const app = getApp()
const { isRecord, isRecordList } = require('../../../utils/response-shape.js')
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js')

const URLS = { mine: '/api/roam/stamp/list', received: '/api/roam/stamp/received' }

function isNonNegativeInteger(value) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
}

function isStamp(row) {
  const validId = (typeof row.id === 'number' && Number.isFinite(row.id))
    || (typeof row.id === 'string' && row.id.trim() !== '')
  return validId && typeof row.picUrl === 'string' && row.picUrl.trim() !== ''
}

Component({
  behaviors: [reducedMotionBehavior],
  data: {
    tab: 'mine', // mine=我的集邮册(/list) | received=收到的(/received,投一张换回来的)
    tabs: [{ key: 'mine', label: '我的' }, { key: 'received', label: '收到的' }],
    items: [],
    total: 0,
    pageNum: 1,
    loading: false,
    loaded: false,
    error: false,
    noMore: false,
  },
  lifetimes: {
    attached() {
      /* 原型是 grid-template-columns:repeat(3,1fr) + aspect-ratio:1,格子尺寸交给 CSS,
         不再在 JS 里量窗宽算散落坐标(那套是「收藏夹」皮肤的东西,已随它退场)。 */
      this.load()
    },
    detached() { this._loadToken = (this._loadToken || 0) + 1 },
  },
  methods: {
    // 两个 tab 各留一份分页状态,来回切不重复拉、也不串页
    onTabChange(e) {
      const next = e.detail && e.detail.key
      if (!next || next === this.data.tab || !URLS[next]) return
      this._loadToken = (this._loadToken || 0) + 1 // 作废在途的另一边请求
      const cache = this._tabCache = this._tabCache || {}
      const d = this.data
      cache[d.tab] = { items: d.items, total: d.total, pageNum: d.pageNum, loaded: d.loaded, error: d.error, noMore: d.noMore }
      const restored = cache[next] || { items: [], total: 0, pageNum: 1, loaded: false, error: false, noMore: false }
      this.setData(Object.assign({ tab: next, loading: false }, restored), () => {
        if (!restored.loaded) this.load()
      })
    },
    load() {
      if (this.data.loading || this.data.noMore) return
      const token = (this._loadToken || 0) + 1
      this._loadToken = token
      this.setData({ loading: true, error: false })
      app.sendRequest({
        url: URLS[this.data.tab],
        method: 'POST',
        data: { pageNum: this.data.pageNum, pageSize: 50 },
        hideLoading: true,
        silentError: true,
        success: (res) => {
          if (token !== this._loadToken) return
          const data = res && res.code == '200' && isRecord(res.data) ? res.data : null
          if (!data || !isRecordList(data.list) || !isNonNegativeInteger(data.total)
            || data.list.some((row) => !isStamp(row))) {
            this.setData({ loading: false, error: true })
            return
          }
          const rows = data.list
          // caption 是投票人写的那一句(≤30 字,机审过 check_state != 2),压在票面底部 ——
          // 原型 .stkcell 的每一格都带这么一行,现码原来只有图。没有就不渲染那一层。
          const merged = this.data.items.concat(rows.map((row) => ({
            id: row.id,
            picUrl: row.picUrl.trim(),
            caption: typeof row.caption === 'string' ? row.caption.trim() : '',
          })))
          const total = Number(data.total) || 0
          this.setData({ items: merged, total, pageNum: this.data.pageNum + 1, noMore: rows.length === 0 || merged.length >= total, loading: false, loaded: true, error: false })
        },
        fail: () => { if (token === this._loadToken) this.setData({ loading: false, error: true }) },
        successStatusAbnormal: () => { if (token === this._loadToken) this.setData({ loading: false, error: true }) },
      })
    },
    retryLoad() { this.setData({ error: false }, () => this.load()) },
    loadMore() { this.load() },
    openCamera() { this.triggerEvent('camera') },
    close() { this.triggerEvent('close') },
  },
})
