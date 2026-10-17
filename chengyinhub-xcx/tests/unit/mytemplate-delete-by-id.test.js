'use strict'

// BL-192：我的模板删除成功后按 id 生成新数组再 setData，不能对点击时的 index 原地 splice。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.js')

function loadPage() {
  const requests = []
  let definition
  global.getApp = () => ({
    getRequestErrorMessage: (response, fallback) => (response && (response.msg || response.errMsg)) || fallback,
    getUserRole: () => 'user',
    getUserType: () => 'user',
    sendRequest: (options) => requests.push(options),
  })
  global.Page = (config) => { definition = config }
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    navigateTo() {},
    showModal(options) { options.success({ confirm: true }) },
    showToast() {},
    stopPullDownRefresh() {},
  }
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.assign(this.data, patch)
      if (done) done.call(this)
    },
  })
  return { page, requests }
}

const tap = (page, index) => page.deleteItem({ currentTarget: { dataset: { index } } })
const deletes = (requests) => requests.filter((r) => r.url === '/api/template/delete')

test('两条删除先后返回，删掉的是正确的两条，且不原地改旧数组', () => {
  const h = loadPage()
  const original = [{ id: 1, title: 'A' }, { id: 2, title: 'B' }, { id: 3, title: 'C' }]
  h.page.data.list = original
  tap(h.page, 0)
  tap(h.page, 1)
  const [first, second] = deletes(h.requests)
  assert.deepEqual([first.data.template_id, second.data.template_id], [1, 2])
  first.success({ code: '200' })
  second.success({ code: '200' })
  assert.deepEqual(h.page.data.list.map((item) => item.id), [3])
  assert.equal(original.length, 3, '不能原地 splice this.data.list')
})

test('请求期间下拉刷新重载列表：只摘目标 id，找不到就不动', () => {
  const h = loadPage()
  h.page.data.list = [{ id: 1, title: 'A' }, { id: 2, title: 'B' }]
  tap(h.page, 0)
  h.page.data.list = [{ id: 9, title: 'X' }, { id: 1, title: 'A' }]
  deletes(h.requests)[0].success({ code: '200' })
  assert.deepEqual(h.page.data.list.map((item) => item.id), [9])

  tap(h.page, 0)
  h.page.data.list = [{ id: 7, title: 'Y' }]
  deletes(h.requests)[1].success({ code: '200' })
  assert.deepEqual(h.page.data.list.map((item) => item.id), [7])
})
