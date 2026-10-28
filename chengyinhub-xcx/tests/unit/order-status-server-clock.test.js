// U-033(2026-10-24):订单「未开始/进行中/已完成」标签按服务端读时刻 serverNow 判,手机时钟只兜底。
// 负控:把 utils/order-status.js 里 orderClockNow(value) 换回 Date.now() → 「手机时钟被调快」用例变红。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { summarizeOrderState, orderClockNow } = require('../../utils/order-status.js')
const { buildOrderPreviewList } = require('../../subpackageMember/components/cy/profile/order-preview.js')

const ROOT = path.join(__dirname, '../..')
const SERVER_NOW = new Date('2026-10-24T12:00:00+08:00').getTime()   // 活动 13:00 开始,服务端看是「未开始」
const PHONE_FAST = new Date('2026-10-25T12:00:00+08:00').getTime()  // 手机被调快一天,看已结束

function paidRow(extra) {
  return Object.assign({
    id: 1, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
    refundInfo: { refundable: true, reason: '可免费取消' },
    cmsActivity: { name: '夜游', startDate: '2026-10-24 13:00:00', endDate: '2026-10-24 18:00:00' },
  }, extra || {})
}

function withPhoneClock(ms, fn) {
  const real = Date.now
  Date.now = () => ms
  try { return fn() } finally { Date.now = real }
}

test('手机时钟被调快时,带 serverNow 的订单仍按服务端时刻判为未开始', () => {
  withPhoneClock(PHONE_FAST, () => {
    assert.equal(summarizeOrderState(paidRow({ serverNow: SERVER_NOW })).key, 'not_started')
    assert.equal(summarizeOrderState(paidRow({ serverNow: SERVER_NOW + 2 * 3600e3 })).key, 'in_progress')
    assert.equal(summarizeOrderState(paidRow({ serverNow: SERVER_NOW + 7 * 3600e3 })).key, 'completed')
  })
})

test('后端没给 serverNow(旧版本/字段缺失/非法)时回落手机时钟', () => {
  withPhoneClock(PHONE_FAST, () => {
    assert.equal(summarizeOrderState(paidRow()).key, 'completed')
    assert.equal(summarizeOrderState(paidRow({ serverNow: null })).key, 'completed')
    assert.equal(summarizeOrderState(paidRow({ serverNow: 'abc' })).key, 'completed')
  })
  assert.equal(orderClockNow({}, 42), 42)
  assert.equal(orderClockNow({ serverNow: SERVER_NOW }, 42), SERVER_NOW)
})

test('显式传入的 now 仍优先(测试与既有调用口径不变)', () => {
  assert.equal(summarizeOrderState(paidRow({ serverNow: PHONE_FAST }), SERVER_NOW).key, 'not_started')
})

test('「我的」订单预览:页面传入的手机时钟只兜底,serverNow 优先', () => {
  const list = buildOrderPreviewList([paidRow({ serverNow: SERVER_NOW })], null, PHONE_FAST, 8)
  assert.deepEqual(list.map((o) => o.id), [1], '服务端看还没开始的单不能因手机时钟快而被当作已结束滤掉')
  assert.equal(list[0].statusText, '待开始')
})

test('订单列表/详情/参与详情调用 summarizeOrderState 时不再传手机时钟(交给 serverNow)', () => {
  for (const rel of [
    'subpackageMember/components/cy/scene-member-order-history/index.js',
    'subpackageMember/components/cy/scene-member-order-detail/index.js',
    'subpackageMember/components/scene-member-participation-detail/index.js',
    'subpackageMember/mycanyu/mycanyu.js',
  ]) {
    const src = fs.readFileSync(path.join(ROOT, rel), 'utf8')
    assert.ok(/summarizeOrderState\(/.test(src), rel)
    assert.ok(!/summarizeOrderState\([^)]*Date\.now\(\)/.test(src), rel + ' 不得把手机时钟塞给 summarizeOrderState')
  }
})
