'use strict'
/* r3-pass:通行证到账分步只来自服务端事实;动效只用 transition,不新增 keyframes,并接住减少动态效果。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const pu = require('../../subpackageMember/utils/pass-unlock-view.js')

const PRODUCT = {
  productId: 9201, productName: '上海城市通票', productKind: 2, saleCity: '上海',
  validityKind: 1, validDays: 30, seatCount: 1,
  items: [{ topicTitle: '外滩夜巡' }, { topicTitle: '武康路' }]
}
const PAID = { orderNo: 'MP1', statusLabel: 'PAID', fulfillmentKind: 1 }

test('只有服务端回 PAID 且自己用才出到账分步', () => {
  assert.deepEqual(pu.unlockSteps(PRODUCT, { statusLabel: 'PENDING_CASH', fulfillmentKind: 1 }), [])
  assert.deepEqual(pu.unlockSteps(PRODUCT, null), [])
  assert.deepEqual(pu.unlockSteps(PRODUCT, Object.assign({}, PAID, { fulfillmentKind: 2 })), [], '礼品待领取不能说已到账')
  assert.deepEqual(pu.unlockSteps(null, PAID), [])
})

test('步数跟着真实字段:主题数、期限、人数;没有的事实没有那一步', () => {
  const steps = pu.unlockSteps(PRODUCT, PAID)
  assert.deepEqual(steps.map((s) => s.key), ['pass', 'themes', 'dial'])
  assert.equal(steps[0].title, '上海城市通票')
  assert.equal(steps[0].line, '城市包 · 上海')
  assert.equal(steps[1].title, '2 个主题')
  assert.deepEqual(steps[1].rows.map((r) => r.t), ['外滩夜巡', '武康路'])
  assert.deepEqual(steps[2].dial.cells.map((c) => c.d), [3, 0])
  assert.equal(steps[2].dial.unit, '天')

  const bare = pu.unlockSteps({ productId: 1, productName: '通行证', productKind: 4 }, PAID)
  assert.deepEqual(bare.map((s) => s.key), ['pass'], '没有主题、没有期限时只剩到账一步,不写 0')

  const team = pu.unlockSteps(Object.assign({}, PRODUCT, { seatCount: 12 }), PAID)
  const seats = team.find((s) => s.key === 'seats')
  assert.equal(seats.title, '12 人可用')
  assert.equal(seats.links.length, pu.MAX_LINKS, '链条最多画 8 节,标题仍写真实人数')
})

test('主题行最多 4 行,标题写真实总数', () => {
  const items = Array.from({ length: 7 }, (_, i) => ({ topicTitle: '主题' + i }))
  const s = pu.unlockSteps(Object.assign({}, PRODUCT, { items }), PAID).find((x) => x.key === 'themes')
  assert.equal(s.title, '7 个主题')
  assert.equal(s.rows.length, pu.MAX_ROWS)
})

test('固定期写截止日期,一位一格;无效日期不出拨盘', () => {
  const d = pu.validityDial({ validityKind: 2, fixedValidTo: '2027-01-09T00:00:00+08:00' })
  assert.equal(d.cells.map((c) => c.c).join(''), '2027.01.09')
  assert.equal(d.cells[4].d, null, '点号不滚动')
  assert.equal(pu.validityDial({ validityKind: 2, fixedValidTo: 'x' }), null)
  assert.equal(pu.validityDial({ validityKind: 1, validDays: null }), null)
  assert.equal(pu.validityDial({ validityKind: 1, validDays: 0 }), null)
})

test('票面字:城市优先,其次类型,都没有就空着', () => {
  assert.equal(pu.faceText({ saleCity: '上海', productKind: 2 }), '上海')
  assert.equal(pu.faceText({ productKind: 4 }), '固定期年卡')
  assert.equal(pu.faceText({}), '')
})

const COMP = ['pass-object', 'pass-unlock'].map((n) => 'subpackageMember/components/cy/' + n + '/index.wxss')

test('动效只用 transition:不新增 keyframes,不过渡布局属性', () => {
  for (const f of COMP) {
    const css = read(f)
    assert.doesNotMatch(css, /@keyframes/, f)
    assert.doesNotMatch(css, /transition[^;]*\b(width|height|top|left|right|bottom|margin|padding|all)\b/, f)
  }
})

test('减少动态效果:两件组件都有终态兜底,JS 也直接给终态', () => {
  assert.match(read(COMP[0]), /\.po--reduced \.po__tilt[^{]*\{[^}]*transition:\s*none/)
  assert.match(read(COMP[1]), /\.pu--reduced \.pu__knob[^{]*\{[^}]*transition:\s*none/)
  const js = read('subpackageMember/components/cy/pass-unlock/index.js')
  assert.match(js, /if \(reduced\)/)
  const page = read('subpackageMember/market/pass/index.js')
  assert.match(page, /readReducedMotion\(\)/)
})

test('购买页:到账分步只挂在服务端 PAID 之后,下单与支付链路不变', () => {
  const page = read('subpackageMember/market/pass/index.js')
  const after = page.slice(page.indexOf('_afterOrder(order) {'), page.indexOf('_payCash(order) {'))
  assert.match(after, /order\.statusLabel === 'PAID'/)
  assert.ok(after.indexOf('unlockSteps') > after.indexOf("order.statusLabel === 'PAID'"))
  assert.ok(after.indexOf('giftSheet: true') < after.indexOf('unlockSteps'), '礼品仍先出领取码半屏')
  assert.match(page, /wx\.requestPayment\(Object\.assign\(\{\}, p, \{\n\s*complete: \(\) => this\._verify\(order\.orderNo, 0\)/)
  const wxml = read('subpackageMember/market/pass/index.wxml')
  assert.match(wxml, /<cy-pass-unlock[^>]*show="\{\{unlock\.show\}\}"/)
  assert.match(wxml, /bind:done="onUnlockDone"/)
})
