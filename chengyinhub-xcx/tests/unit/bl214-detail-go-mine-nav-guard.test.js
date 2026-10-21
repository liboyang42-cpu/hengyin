'use strict'
// BL-214:纠错进度/举报进度「查看我的全部」连点只跳一次;complete 回调或 onShow 后可再次跳转。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function loadPage(rel, stack) {
  const nav = []
  let def
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => ({ globalData: {}, sendRequest: () => ({ abort() {} }) })
  global.Page = (d) => { def = d }
  global.getCurrentPages = () => stack.map((route) => ({ route }))
  global.wx = {
    navigateTo: (o) => nav.push({ kind: o.url, o }), navigateBack: (o) => nav.push({ kind: 'back', o }),
    redirectTo() {}, stopPullDownRefresh() {}, showToast() {}, showModal() {},
  }
  const file = path.join(ROOT, rel)
  delete require.cache[require.resolve(file)]
  require(file)
  const p = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) })
  p.setData = (patch, cb) => { Object.assign(p.data, patch); if (cb) cb() }
  const restore = () => Object.assign(global, prev)
  return { p, nav, restore }
}

const PAGES = [
  { rel: 'subpackageMember/report-detail/index.js', list: 'subpackageMember/report-mine/index' },
]

for (const pg of PAGES) {
  const self = pg.rel.replace(/\.js$/, '')
  const cases = [
    { name: '上一页是列表', stack: [pg.list, self], kind: 'back' },
    { name: '从系统消息进来', stack: ['pages/member/index/index', self], kind: '/' + pg.list },
  ]
  for (const c of cases) {
    test(`BL-214 ${pg.rel}:${c.name},complete 未回调时连调两次只跳 1 次`, () => {
      const h = loadPage(pg.rel, c.stack)
      try {
        h.p.goMine()
        h.p.goMine()
        assert.deepEqual(h.nav.map((n) => n.kind), [c.kind])
      } finally { h.restore() }
    })

    test(`BL-214 ${pg.rel}:${c.name},complete 后可再次跳转`, () => {
      const h = loadPage(pg.rel, c.stack)
      try {
        h.p.goMine()
        h.nav[0].o.complete()
        h.p.goMine()
        assert.deepEqual(h.nav.map((n) => n.kind), [c.kind, c.kind])
      } finally { h.restore() }
    })

    test(`BL-214 ${pg.rel}:${c.name},onShow 后可再次跳转`, () => {
      const h = loadPage(pg.rel, c.stack)
      try {
        h.p.goMine()
        h.p.onShow()
        h.p.goMine()
        assert.equal(h.nav.length, 2)
      } finally { h.restore() }
    })
  }

  test(`BL-214 ${pg.rel}:navigateBack 失败仍兜底 redirectTo 列表`, () => {
    const h = loadPage(pg.rel, [pg.list, self])
    const redirects = []
    global.wx.redirectTo = (o) => redirects.push(o.url)
    try {
      h.p.goMine()
      h.nav[0].o.fail()
      assert.deepEqual(redirects, ['/' + pg.list])
    } finally { h.restore() }
  })
}
