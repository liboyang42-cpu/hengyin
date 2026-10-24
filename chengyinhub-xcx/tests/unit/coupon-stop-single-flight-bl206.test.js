'use strict'
// BL-206:停发优惠券在途单飞;列表 getList 过期回包丢弃。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const COUPON_PAGE = 'subpackageMember/coupon/coupon.js'

function loadCouponPage() {
  const requests = []
  const modals = []
  const toasts = []
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, COUPON_PAGE), 'utf8'), {
    // master 的券页按当前账号(currentOwnerId)隔离列表与停发回包,夹具给一个已登录账号
    getApp: () => ({ sendRequest: (options) => requests.push(options), getUserID: () => 7 }),
    Page: (value) => { definition = value },
    require: (id) => {
      if (/coupon-form\.js$/.test(id)) return require(path.join(ROOT, 'utils/coupon-form.js'))
      if (/datetime\.js$/.test(id)) return require(path.join(ROOT, 'utils/datetime.js'))
      if (/coupon-credential\.js$/.test(id)) return require(path.join(ROOT, 'subpackageMember/utils/coupon-credential.js'))
      if (/coupon-summary\.js$/.test(id)) return require(path.join(ROOT, 'subpackageMember/coupon/coupon-summary.js'))
      if (/\/modal\.js$/.test(id)) return { show: (opts) => modals.push(opts) }
      if (/\/toast\.js$/.test(id)) {
        return Object.assign((text) => toasts.push(text), {
          success: (text) => toasts.push(text),
          error: (text) => toasts.push(text),
        })
      }
      return { merchantPageShow() {}, merchantPageRestore() {} }
    },
  }, { filename: COUPON_PAGE })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, requests, modals, toasts }
}

const row = (status) => ({ id: 1, name: '进行中券', status, startTime: '2026-09-01 00:00:00',
  endTime: '2026-10-31 23:59:59', publishCount: 10, receiveCount: 1 })
const stops = (requests) => requests.filter((r) => r.url === '/api/coupon/stop')
const lists = (requests) => requests.filter((r) => r.url === '/api/coupon/mypublishlist')

function ready() {
  const h = loadCouponPage()
  h.page.getList()
  lists(h.requests)[0].success({ code: 200, data: [row(1)] })
  return h
}

test('BL-206 确认后连调两次 stopCoupon 只发 1 个停发请求;回包后可再次停发', () => {
  const { page, requests, modals, toasts } = ready()
  const active = page.data.list[0]
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  modals[0].success({ confirm: true })
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  assert.equal(modals.length, 1, '在途时不再打开确认')
  assert.equal(stops(requests).length, 1)
  stops(requests)[0].success({ code: 500, msg: '停发失败' })
  assert.equal(toasts[toasts.length - 1], '停发失败')
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  modals[1].success({ confirm: true })
  assert.equal(stops(requests).length, 2, '业务失败后可再次停发')
  stops(requests)[1].fail({})
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  assert.equal(modals.length, 3, '网络失败后也可再次停发')
})

test('BL-206 两个确认弹窗都点确认只发 1 个;取消确认不置在途', () => {
  const { page, requests, modals } = ready()
  const active = page.data.list[0]
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  modals[0].success({ confirm: false })
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  assert.equal(modals.length, 2, '取消后仍可再次停发')
  page.stopCoupon({ currentTarget: { dataset: { item: active } } })
  modals[1].success({ confirm: true })
  modals[2].success({ confirm: true })
  assert.equal(stops(requests).length, 1)
})

test('BL-206 两次 getList 后发先至时旧回包不覆盖', () => {
  const { page, requests } = loadCouponPage()
  page.getList()
  page.getList()
  const [older, newer] = lists(requests)
  newer.success({ code: 200, data: [row(4)] })
  assert.equal(page.data.list[0].statusText, '已停发')
  older.success({ code: 200, data: [row(1)] })
  assert.equal(page.data.list[0].statusText, '已停发', '旧列表不得把「已停发」覆盖回「进行中」')
  older.fail()
  assert.equal(page.data.loadState, 'ready', '旧请求的失败也丢弃')
})
