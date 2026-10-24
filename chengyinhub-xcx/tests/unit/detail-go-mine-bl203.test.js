'use strict'
// BL-203:纠错进度/举报进度「查看我的全部」从列表进来时返回,不叠页;缺参与过期下拉回包都收转圈。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function loadPage(rel, stack) {
  const requests = []
  const nav = []
  const stops = []
  let def
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => requests.push(o) })
  global.Page = (d) => { def = d }
  global.getCurrentPages = () => stack.map((route) => ({ route }))
  global.wx = {
    stopPullDownRefresh: () => stops.push(1), navigateTo: (o) => nav.push(o.url), navigateBack: () => nav.push('back'),
    showToast() {}, showModal() {},
  }
  const file = path.join(ROOT, rel)
  delete require.cache[require.resolve(file)]
  require(file)
  const p = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) })
  p.setData = (patch, cb) => { Object.assign(p.data, patch); if (cb) cb() }
  const restore = () => Object.assign(global, prev)
  return { p, requests, nav, stops, restore }
}

const PAGES = [
  { rel: 'subpackageMember/report-detail/index.js', list: 'subpackageMember/report-mine/index',
    opt: { reportId: '7' }, body: { id: 7, stage: 'SUBMITTED' } },
]

function answer(requests, body) {
  for (const r of requests.splice(0)) {
    if (r.url.endsWith('/taxonomy')) r.success({ code: 200, data: { reasons: [] } })
    else r.success({ code: 200, data: body })
  }
}

for (const pg of PAGES) {
  test('BL-203 ' + pg.rel + ':上一页是列表时返回，否则打开列表', () => {
    const self = pg.rel.replace(/\.js$/, '')
    const h = loadPage(pg.rel, [pg.list, self])
    try {
      h.p.goMine()
      assert.deepEqual(h.nav, ['back'])
    } finally { h.restore() }
    const h2 = loadPage(pg.rel, ['pages/member/index/index', self])
    try {
      h2.p.goMine()
      assert.deepEqual(h2.nav, ['/' + pg.list])
    } finally { h2.restore() }
    const h3 = loadPage(pg.rel, [self])
    try {
      h3.p.goMine()
      assert.deepEqual(h3.nav, ['/' + pg.list])
    } finally { h3.restore() }
  })

  test('BL-203 ' + pg.rel + ':缺参下拉与过期下拉回包都收转圈', () => {
    const h = loadPage(pg.rel, [])
    try {
      h.p.onLoad({})
      assert.equal(h.p.data.state, 'missing-param')
      h.p.onPullDownRefresh()
      assert.equal(h.requests.length, 0)
      assert.equal(h.stops.length, 1)
    } finally { h.restore() }
    const h2 = loadPage(pg.rel, [])
    try {
      h2.p.onLoad(pg.opt)
      answer(h2.requests, pg.body)
      assert.equal(h2.p.data.state, 'ready')
      h2.p.onPullDownRefresh()
      const pull = h2.requests.splice(0)
      h2.p.retry()
      answer(h2.requests, pg.body)
      assert.equal(h2.stops.length, 0)
      answer(pull, pg.body)
      assert.equal(h2.stops.length, 1, '过期的下拉回包也要收转圈')
    } finally { h2.restore() }
  })
}
