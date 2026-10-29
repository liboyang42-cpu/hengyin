'use strict'
// 2026-10-29 走查 V-20:订单详情 —— 头图恢复成主题/场次自己的图、去掉「查看票夹」、
// 页面能滑到底(page 档宿主不再 catchtouchmove)、间距放开、票价显示真实单价(免费 ¥0)。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const ROOT = path.resolve(__dirname, '../..')
const DIR = 'subpackageMember/components/cy/scene-member-order-detail/'
const JS = path.join(ROOT, DIR + 'index.js')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')

let sandbox
beforeEach(() => {
  sandbox = { requests: [] }
  global.getApp = () => ({
    globalData: {}, tips() {}, sendRequest: o => sandbox.requests.push(o),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    getImgUrl: src => !src ? '' : (src[0] === '/' ? src : (/^https?:/.test(src) ? src.replace(/^http:/, 'https:') : 'https://www.chengyinhub.com/prod-api/profile/' + src)),
  })
  global.Component = config => { sandbox.config = flattenComponentToPage(config) }
  global.wx = { showToast() {}, hideLoading() {}, showLoading() {}, navigateTo() {}, navigateBack() {} }
})
function load(row) {
  delete require.cache[require.resolve(JS)]
  require(JS)
  const vm = Object.assign({}, sandbox.config, { setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() } })
  vm.data = Object.assign({}, sandbox.config.data)
  sandbox.requests = []; vm.data.orderId = String(row.id); vm.onLoad()
  sandbox.requests.find(r => r.url === '/api/registration/info').success({ code: 200, data: row })
  return vm
}

test('V-20 头图用主题自己的封面，经 getImgUrl 补全，逗号分隔的 imgArr 取首图', () => {
  let vm = load({ id: 1, ownerType: 1, registrationStatus: 2, paymentStatus: 2, cmsTopic: { name: '南京路', imgArr: 'upload/a.jpg,upload/b.jpg' } })
  assert.equal(vm.data.heroImage, 'https://www.chengyinhub.com/prod-api/profile/upload/a.jpg')
  vm = load({ id: 2, ownerType: 1, registrationStatus: 2, paymentStatus: 2, cmsTopic: { name: '南京路', imgUrl: 'http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/c.jpg' } })
  assert.equal(vm.data.heroImage, 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/c.jpg')
  vm = load({ id: 3, ownerType: 2, registrationStatus: 2, paymentStatus: 2, cmsActivity: { name: '夜行', imgUrl: 'upload/x.png' } })
  assert.equal(vm.data.heroImage, 'https://www.chengyinhub.com/prod-api/profile/upload/x.png')
  vm = load({ id: 4, ownerType: 1, registrationStatus: 2, paymentStatus: 2, cmsTopic: { name: '无图' } })
  assert.equal(vm.data.heroImage, '/subpackageMember/images/icon_ticket_empty.svg')
})

test('V-20 票价显示真实单价，免费单没有快照时是 ¥0 而不是「—」', () => {
  let vm = load({ id: 5, ownerType: 1, registrationStatus: 2, paymentStatus: 2, totalAmount: 0, payableAmount: 0, cmsTopic: { name: 'free' } })
  assert.equal(vm.data.info.isFreeOrder, true)
  assert.equal(vm.data.ticketPriceShown, 0)
  vm = load({ id: 6, ownerType: 1, registrationStatus: 2, paymentStatus: 2, ticketPrice: 39.9, payableAmount: 39.9, wechatPaymentAmount: 39.9, cmsTopic: { name: 'paid' } })
  assert.equal(vm.data.ticketPriceShown, 39.9)
  const wxml = read(DIR + 'index.wxml')
  assert.match(wxml, /<text class="orow-k">票价<\/text>\s*<text class="orow-v">\{\{money\.amount\(ticketPriceShown\)\}\} × \{\{orderQty\}\}<\/text>/)
})

test('V-20 订单详情不再有「查看票夹」按钮，进行中订单底部不出主 CTA', () => {
  const wxml = read(DIR + 'index.wxml').replace(/<!--[\s\S]*?-->/g, '')
  assert.doesNotMatch(wxml, /查看票夹/)
  assert.doesNotMatch(wxml, /bindtap="goSignInfo"/)
  assert.match(wxml, /class="bp-cta-bar" wx:if="\{\{primaryCta === 'pay'\}\}"/)
  const vm = load({ id: 7, ownerType: 1, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0, cmsTopic: { name: 't' } })
  assert.equal(vm.data.info.orderStateKey, 'in_progress')
  assert.equal(vm.data.primaryCta, '')
})

test('V-20 page 档订单详情的宿主不吃 touchmove，正文能滑到底；时间线与上一张卡留出段距', () => {
  const order = read('subpackageMember/order/order.wxml').replace(/<!--[\s\S]*?-->/g, '')
  const host = order.match(/<view class="order-scene-host" wx:if="\{\{sceneCurrent && sceneCurrent\.id === 'member-order-detail'\}\}"[^>]*>/)
  assert.ok(host); assert.doesNotMatch(host[0], /catchtouchmove/)
  assert.match(read('utils/scene-registry.js'), /'member-order-detail': \{[^}]*variant: 'page'/)
  assert.match(read(DIR + 'index.wxss'), /\.order-timeline \{ margin: var\(--cy-space-5\) 0 var\(--cy-space-3\);/)
})
