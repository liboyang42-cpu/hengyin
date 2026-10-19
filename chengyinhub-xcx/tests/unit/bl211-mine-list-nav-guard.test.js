'use strict'
// BL-211:我的举报/我的纠错/收藏据点列表行连点只跳一次;complete 回调或 onShow 后可再次打开。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const CASES = [
  { rel: 'subpackageMember/report-mine/index.js', handler: 'openDetail', id: 7, url: '/subpackageMember/report-detail/index?reportId=7' },
  { rel: 'subpackageMember/correction-mine/index.js', handler: 'openDetail', id: 8, url: '/subpackageMember/correction-detail/index?contributionId=8' },
  { rel: 'subpackageRoam/node-favorites/index.js', handler: 'openNode', id: 'n9', url: '/subpackageRoam/poi-detail/index?poiId=n9' },
]

function boot(rel) {
  const navs = []
  global.getApp = () => ({
    globalData: {},
    getImgUrl: (u) => u,
    sendRequest: () => ({ abort() {} }),
  })
  global.getCurrentPages = () => [{}, {}]
  global.wx = { navigateTo: (o) => navs.push(o), navigateBack() {}, switchTab() {}, stopPullDownRefresh() {} }
  const file = path.join(__dirname, '..', '..', rel)
  let def
  global.Page = (c) => { def = c }
  delete require.cache[require.resolve(file)]
  require(file)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  return { page, navs }
}

const tap = (id) => ({ currentTarget: { dataset: { id } } })

for (const c of CASES) {
  test(`${c.rel}:complete 未回调时连点两次只跳 1 次`, () => {
    const { page, navs } = boot(c.rel)
    page[c.handler](tap(c.id))
    page[c.handler](tap(c.id))
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, c.url)
  })

  test(`${c.rel}:complete 回调后可再次打开`, () => {
    const { page, navs } = boot(c.rel)
    page[c.handler](tap(c.id))
    navs[0].complete()
    page[c.handler](tap(c.id))
    assert.equal(navs.length, 2)
  })

  test(`${c.rel}:onShow 后可再次打开`, () => {
    const { page, navs } = boot(c.rel)
    page[c.handler](tap(c.id))
    page.onShow()
    page[c.handler](tap(c.id))
    assert.equal(navs.length, 2)
  })

  test(`${c.rel}:无效 id 不跳也不上锁`, () => {
    const { page, navs } = boot(c.rel)
    page[c.handler](tap(''))
    page[c.handler](tap(c.id))
    assert.equal(navs.length, 1)
  })
}
