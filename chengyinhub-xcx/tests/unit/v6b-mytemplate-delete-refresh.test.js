'use strict'
// V6b 验收（CL-48 / CX-12 列表部分，F-R2S-16）：subpackageMember/mytemplate 删除走确认弹窗，
// 取消什么都不删；确认只删被点的那一条一次并扣 total；有内容时刷新失败保留旧列表并给非阻塞提示。
// 单飞、按真实移除条数减 total、失败不摘卡已由 mytemplate-list-recovery-contract.test.js 覆盖，这里补
// deleteItem → 弹窗取消/确认的真实入口。负控：把 deleteItem 里的 if (res.confirm) 去掉，第一条必红。
// 原为 todo 的缺陷复现（刷新失败静默吞掉）已随修复转为回归用例。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const MODULE = path.resolve(__dirname, '../../subpackageMember/mytemplate/mytemplate.js')

function loadPage(answer) {
  const requests = []
  const modals = []
  const toasts = []
  let definition
  global.getCurrentPages = () => []
  global.getApp = () => ({
    getRequestErrorMessage: (response, fallback) => (response && (response.msg || response.errMsg)) || fallback,
    getUserRole: () => 'user', getUserType: () => 'user', getUserID: () => 9, getAuthorization: () => 'fixture-session',
    sendRequest: (options) => requests.push(options),
  })
  global.Page = (config) => { definition = config }
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    navigateTo() {}, stopPullDownRefresh() {}, setNavigationBarColor() {}, setBackgroundColor() {},
    showModal(options) { modals.push(options); if (answer) options.success(answer) },
    showToast(options) { toasts.push(options && options.title) },
  }
  delete require.cache[MODULE]
  require(MODULE)
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done.call(this) },
  })
  return { page, requests, modals, toasts }
}
const tap = (index) => ({ currentTarget: { dataset: { index } } })
const deletes = (requests) => requests.filter(r => r.url === '/api/template/delete')

test('V6b CX-12：删除弹窗点取消，不发删除请求，列表与总数不变', () => {
  const h = loadPage({ confirm: false, cancel: true })
  h.page.data.list = [{ id: 7, title: '甲' }, { id: 8, title: '乙' }]
  h.page.data.total = 2
  h.page.deleteItem(tap(1))
  assert.equal(h.modals.length, 1, '删除必须先确认')
  assert.equal(deletes(h.requests).length, 0, '取消后仍发了删除')
  assert.deepEqual(h.page.data.list.map(r => r.id), [7, 8])
  assert.equal(h.page.data.total, 2)
})

test('V6b CX-12：确认后只删被点的那一条一次，扣 total 并回读', () => {
  const h = loadPage({ confirm: true })
  h.page.data.list = [{ id: 7, title: '甲' }, { id: 8, title: '乙' }]
  h.page.data.total = 2
  h.page.deleteItem(tap(1))
  h.page.deleteItem(tap(1)) // 在途重复点
  assert.equal(deletes(h.requests).length, 1, '在途时重复点不能再删一次')
  assert.equal(deletes(h.requests)[0].data.template_id, 8)
  deletes(h.requests)[0].success({ code: 200 })
  deletes(h.requests)[0].complete()
  assert.deepEqual(h.page.data.list.map(r => r.id), [7])
  assert.equal(h.page.data.total, 1)
  assert.ok(h.requests.some(r => r.url === '/api/template/my-list'), '删除成功要回读列表')
})

test('V6b CX-12 缺陷复现：有内容时刷新失败被静默吞掉，旧列表被当成最新展示', () => {
  const h = loadPage()
  h.page.data.list = [{ id: 7, title: '已显示的玩法' }]
  h.page.refreshList()
  const req = h.requests.at(-1)
  req.fail({ errMsg: '网络开小差了' })
  req.complete()
  assert.deepEqual(h.page.data.list.map(r => r.id), [7], '旧内容要保留')
  const flagged = Object.keys(h.page.data).some(k => /stale|refresh(Error|Failed)/i.test(k) && h.page.data[k])
  assert.ok(h.toasts.length > 0 || flagged, '刷新失败没有任何非阻塞提示')
})
