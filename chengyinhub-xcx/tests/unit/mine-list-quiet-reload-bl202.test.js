'use strict'
// BL-202:我的纠错/我的举报 静默回读保留已展开条数;下拉被后发请求取代时仍收转圈。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function loadPage(rel) {
  const requests = []
  const stops = []
  let def
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx }
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => requests.push(o) })
  global.Page = (d) => { def = d }
  global.wx = { stopPullDownRefresh: () => stops.push(1), navigateTo() {}, showToast() {} }
  const file = path.join(ROOT, rel)
  delete require.cache[require.resolve(file)]
  require(file)
  const p = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) })
  p.setData = (patch, cb) => { Object.assign(p.data, patch); if (cb) cb() }
  const restore = () => Object.assign(global, prev)
  return { p, requests, stops, restore }
}

const PAGES = [
  { rel: 'subpackageMember/correction-mine/index.js', row: (i) => ({ id: i + 1, reviewStatus: 'PENDING' }) },
  { rel: 'subpackageMember/report-mine/index.js', row: (i) => ({ id: i + 1, stage: 'SUBMITTED' }) },
]

// 把当前在途请求全部按 list 回包(举报页另有 taxonomy 一路)
function answer(requests, list) {
  for (const r of requests.splice(0)) {
    if (r.url.endsWith('/taxonomy')) r.success({ code: 200, data: { reasons: [] } })
    else r.success({ code: 200, data: list })
  }
}

for (const pg of PAGES) {
  test('BL-202 ' + pg.rel + ':onShow 静默回读后保留已展开的 40 条', () => {
    const h = loadPage(pg.rel)
    try {
      const list = Array.from({ length: 50 }, (_, i) => pg.row(i))
      h.p.onLoad()
      answer(h.requests, list)
      h.p.loadMore()
      assert.equal(h.p.data.rows.length, 40)
      h.p.onShow()
      answer(h.requests, list)
      assert.equal(h.p.data.rows.length, 40, '静默回读不缩回')
      h.p.onPullDownRefresh()
      answer(h.requests, list)
      assert.equal(h.p.data.rows.length, 20, '下拉刷新仍重置为首页')
    } finally { h.restore() }
  })

  test('BL-202 ' + pg.rel + ':下拉被后发请求取代时仍调用 stopPullDownRefresh', () => {
    const h = loadPage(pg.rel)
    try {
      const list = [pg.row(0)]
      h.p.onLoad()
      answer(h.requests, list)
      h.p.onPullDownRefresh()
      const pull = h.requests.splice(0)
      h.p.retry()
      answer(h.requests, list)
      assert.equal(h.stops.length, 0)
      answer(pull, list) // 下拉那次后到,已过期
      assert.equal(h.stops.length, 1, '过期的下拉回包也要收转圈')
    } finally { h.restore() }
  })
}
