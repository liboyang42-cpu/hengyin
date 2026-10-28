'use strict'
// BL-69:商家公开页「收藏据点」。在架节点读 /api/city/nodes/merchant/{id},切换走 /api/city/nodes/{id}/favorite,
// 以返回的 favorited 回读;无节点不展示,失败如实提示不改本地态。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { shapeMerchantNode, readFavoriteToggle } = require('../../subpackageMember/components/cy/profile/merchant-node-favorite.js')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE_JS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js')
const PROFILE_WXML = path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml')

test('shapeMerchantNode 只认 poiId 正整数,favorited 只认 true', () => {
  assert.deepEqual(shapeMerchantNode({ poiId: 9, name: '据点', favorited: true }), { poiId: 9, name: '据点', favorited: true })
  assert.equal(shapeMerchantNode({ poiId: 9, favorited: 'true' }).favorited, false)
  assert.equal(shapeMerchantNode(null), null)
  assert.equal(shapeMerchantNode(undefined), null)
  assert.equal(shapeMerchantNode([]), null)
  assert.equal(shapeMerchantNode({ poiId: 0 }), null)
  assert.equal(shapeMerchantNode({ poiId: 'abc' }), null)
})

test('readFavoriteToggle 只认 code 200 + 布尔 favorited', () => {
  assert.equal(readFavoriteToggle({ code: 200, data: { favorited: true } }), true)
  assert.equal(readFavoriteToggle({ code: '200', data: { favorited: false } }), false)
  assert.equal(readFavoriteToggle({ code: 200, data: {} }), null)
  assert.equal(readFavoriteToggle({ code: 500, msg: '请先登录' }), null)
  assert.equal(readFavoriteToggle(null), null)
})

function loadProfile(requests, toasts) {
  const previous = { Component: global.Component }
  let definition
  global.getApp = () => ({
    globalData: { features: {} }, getUserID: () => '42', getUserRole: () => 'player', getUserType: () => 1,
    sendRequest: (o) => requests.push(o),
  })
  global.wx = {
    getStorageSync: () => '', setStorageSync() {}, setNavigationBarColor() {}, setBackgroundColor() {},
    showToast: (o) => toasts.push(o), hideToast() {},
  }
  global.Component = (d) => { definition = d }
  delete require.cache[require.resolve(PROFILE_JS)]
  require(PROFILE_JS)
  global.Component = previous.Component
  const c = Object.assign({}, definition.methods, { data: JSON.parse(JSON.stringify(definition.data)) })
  c.data.isSelf = false
  c.setData = (patch) => {
    Object.keys(patch).forEach((k) => {
      const parts = k.split('.')
      let o = c.data
      while (parts.length > 1) o = o[parts.shift()]
      o[parts[0]] = patch[k]
    })
  }
  return c
}

test('公开商家主页读在架节点:GET 正确路径,有节点才出按钮', () => {
  const requests = []
  const c = loadProfile(requests, [])
  c.applySubjectMerchant({ id: 77, name: '店' })
  const req = requests.find((r) => /nodes\/merchant/.test(r.url))
  assert.equal(req.url, '/api/city/nodes/merchant/77')
  assert.equal(req.method, 'GET')
  req.success({ code: 200, data: { poiId: 5, name: '据点', favorited: false } })
  assert.deepEqual(c.data.merchantNode, { poiId: 5, name: '据点', favorited: false })

  c.applySubjectMerchant({ id: 77 })
  requests.filter((r) => /nodes\/merchant/.test(r.url)).pop().success({ code: 200 })
  assert.equal(c.data.merchantNode, null, '无在架节点(data 为空)不展示')
})

test('迟到回包不写到别的主页;本人视角不请求', () => {
  const requests = []
  const c = loadProfile(requests, [])
  c.applySubjectMerchant({ id: 77 })
  const stale = requests.find((r) => /nodes\/merchant/.test(r.url))
  c.clearSubjectMerchant('not-merchant')
  stale.success({ code: 200, data: { poiId: 5, favorited: true } })
  assert.equal(c.data.merchantNode, null)

  const r2 = []
  const self = loadProfile(r2, [])
  self.data.isSelf = true
  self.applySubjectMerchant({ id: 77 })
  assert.equal(r2.filter((r) => /nodes\/merchant/.test(r.url)).length, 0)
})

test('切换收藏:以返回值回读;失败如实提示且不改收藏态', () => {
  const requests = []
  const toasts = []
  const c = loadProfile(requests, toasts)
  c.applySubjectMerchant({ id: 77 })
  requests.pop().success({ code: 200, data: { poiId: 5, favorited: false } })

  c.toggleMerchantNodeFavorite()
  const req = requests.pop()
  assert.equal(req.url, '/api/city/nodes/5/favorite')
  assert.equal(req.method, 'POST')
  assert.equal(c.data.merchantNodeBusy, true)
  c.toggleMerchantNodeFavorite()
  assert.equal(requests.filter((r) => /favorite$/.test(r.url)).length, 0, '处理中不重复提交')
  req.success({ code: 200, data: { favorited: true } })
  assert.equal(c.data.merchantNode.favorited, true)
  assert.equal(c.data.merchantNodeBusy, false)
  assert.ok(!toasts.some((t) => t.icon === 'success'), 'BL-162: 按钮变「已收藏据点」即是结果,不再用成功 toast(棘轮上限 101)')

  c.toggleMerchantNodeFavorite()
  requests.pop().success({ code: 500, msg: '请先登录' })
  assert.equal(c.data.merchantNode.favorited, true, '失败不改本地态')
  assert.equal(c.data.merchantNodeBusy, false)
  assert.ok(toasts.some((t) => /请先登录/.test(t.title)))

  c.toggleMerchantNodeFavorite()
  requests.pop().fail()
  assert.equal(c.data.merchantNode.favorited, true)
  assert.ok(toasts.some((t) => /网络异常/.test(t.title)))
})

test('模板:按钮照「发消息」同款 pc-primary pc-ticket,只在有节点的他人商家主页出现', () => {
  const wxml = fs.readFileSync(PROFILE_WXML, 'utf8')
  assert.match(wxml, /<view class="pc-primary pc-ticket" wx:if="\{\{!isSelf && !viewingOwn && subjectIsMerchant && merchantNode\}\}" bindtap="toggleMerchantNodeFavorite"/)
})
