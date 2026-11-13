'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const lib = require('../../subpackageMember/utils/pass-library.js')

function libraryPage(merchant, response) {
  const requests = []
  let page
  const app = { globalData: { statusBarHeight: 24, navBarHeight: 44 }, sendRequest(o) { requests.push(o); o.success(response) } }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../subpackageMember/market/library/index.js'), 'utf8'), {
    getApp: () => app, Page: (value) => { page = value }, wx: { stopPullDownRefresh() {} },
    require(name) {
      if (name.endsWith('/pass-library.js')) return lib
      if (name.endsWith('/page-motion.js')) return { enter() {} }
      if (name.endsWith('/roleGuard.js')) return { isMerchantView: () => merchant }
      if (name.endsWith('/response-shape.js')) return { isBizOk: (r) => r.code === 200 }
      throw new Error('Unexpected dependency: ' + name)
    }
  })
  page.data = Object.assign({}, page.data)
  page.setData = (patch) => Object.assign(page.data, patch)
  return { page, requests }
}

for (const merchant of [false, true]) {
  test('公开主题库不因通行证关闭或账号视角禁用: merchant=' + merchant, () => {
    const rows = [{ id: '11', name: '官方主题', passCovered: true }, { id: '12', name: '玩家主题', passCovered: false }]
    const { page, requests } = libraryPage(merchant, { code: 200, data: { enabled: false, covered: true, rows, total: 2 } })
    page.onLoad({ kind: 'free' })
    assert.equal(requests.length, 1)
    assert.equal(requests[0].url, '/api/public-catalog/list')
    assert.equal(requests[0].method, 'POST')
    assert.equal(requests[0].data.product_type, '2')
    assert.equal(requests[0].data.pageSize, 100)
    assert.equal(page.data.state, 'ready')
    assert.deepEqual(Array.from(page.data.cards, (c) => [c.id, c.free]), [['11', true], ['12', false]])
  })
}

test('价签只认逐主题明确 true,缺失或非布尔事实不当全库免费', () => {
  for (const fact of [true, false, undefined, null, 'true', 1]) {
    assert.equal(lib.passLibraryCard({ id: '22', name: '俱乐部主题', passCovered: fact }).free, fact === true)
  }
  assert.equal(lib.passLibraryCard({ name: '缺少 ID', passCovered: true }), null)
  assert.equal(lib.passLibraryCard({ id: '22', passCovered: true }), null)
})

test('空目录正常可用,坏响应不伪装成通行证未开放', () => {
  const empty = libraryPage(false, { code: 200, data: { rows: [], total: 0 } }).page
  empty.onLoad({})
  assert.equal(empty.data.state, 'ready')
  assert.equal(empty.data.cards.length, 0)
  const failed = libraryPage(false, { code: 503, msg: '服务暂不可用' }).page
  failed.onLoad({})
  assert.equal(failed.data.state, 'error')
  assert.equal(failed.data.error, '服务暂不可用')
})
