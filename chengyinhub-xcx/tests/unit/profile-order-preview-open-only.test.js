const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { buildOrderPreviewList } = require('../../subpackageMember/components/cy/profile/order-preview.js')

const ROOT = path.join(__dirname, '../..')
const NOW = new Date('2026-10-22T04:00:00Z').getTime()
const paid = { registrationStatus: 2, paymentStatus: 2 }
const act = (start, end) => ({ cmsActivity: { name: '夜游', imgUrl: 'a.jpg', startDate: start, endDate: end } })

test('只保留待核销/待开始;已完成/已取消/已退款/已过期/已核销/活动已结束一律不显示', () => {
  const rows = [
    Object.assign({ id: 1, orderNum: 2 }, paid, act('2026-10-22 10:00:00', '2026-10-22 22:00:00')),   // 进行中 → 待核销
    Object.assign({ id: 2, orderNum: 1 }, paid, act('2026-10-30 10:00:00', '2026-10-30 22:00:00')),   // 待开始
    Object.assign({ id: 3 }, paid, { verificationStatus: 1 }, act('2026-10-22 10:00:00', '2026-10-22 22:00:00')),
    { id: 4, registrationStatus: 3 },
    { id: 5, registrationStatus: 4 },
    Object.assign({ id: 6 }, paid, { refundApplication: { payoutStatus: 1 } }, act('2026-10-30 10:00:00', '2026-10-30 22:00:00')),
    Object.assign({ id: 7 }, paid, act('2026-10-01 10:00:00', '2026-10-01 22:00:00')),                  // 已结束
    Object.assign({ id: 8 }, paid, { refundInfo: { refundable: false } }, act('2026-10-01 10:00:00', '2026-10-01 22:00:00')),
    { id: 9, registrationStatus: 1 },
  ]
  const list = buildOrderPreviewList(rows, null, NOW, 8)
  assert.deepEqual(list.map((o) => o.id), [1, 2])
})

// 2026-10-27 owner 真机走查 P-48:卡片不再写票数、不再放「出示核销码/查看」按钮,改显示「城市定向/自由探索 · 类别」。
// 原断言 actionText/ticketText 已按 owner 明确删除的行为改写为「字段不存在」。
test('卡片字段:封面/状态章/标题/档期/主题信息;无票数、无下一步按钮', () => {
  const [verify, wait] = buildOrderPreviewList([
    Object.assign({ id: 1, orderNum: 2 }, paid, act('2026-10-22 10:00:00', '2026-10-22 22:00:00')),
    Object.assign({ id: 2, orderNum: 1 }, paid, act('2026-10-30 10:00:00', '2026-10-30 22:00:00')),
  ], (u) => 'img/' + u, NOW, 8)
  assert.equal(verify.cover, 'img/a.jpg')
  assert.equal(verify.title, '夜游')
  assert.equal(verify.statusText, '待核销')
  assert.equal(verify.actionText, undefined)
  assert.equal(verify.ticketText, undefined)
  assert.equal(verify.kindText, '活动', '活动统一写「活动」')
  assert.match(verify.timeText, /^10月22日 10:00 – 10月22日 22:00$/)
  assert.equal(wait.statusText, '待开始')
  assert.equal(wait.actionText, undefined)
  const [topic] = buildOrderPreviewList([
    Object.assign({ id: 3 }, paid, { cmsTopic: { name: '老城', productType: 2, startDate: '2026-10-30 10:00:00', endDate: '2026-10-30 22:00:00',
      sysCategoryList: [{ categoryName: '' }, { categoryName: '美食' }] } }),
  ], null, NOW, 8)
  assert.equal(topic.kindText, '自由探索 · 美食')
})

test('请求只要未核销未结束的单;整段在没有未完成订单时隐藏', () => {
  const js = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.js'), 'utf8')
  assert.match(js, /status: '2',\s*is_online: 1/)
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml'), 'utf8')
  // 2026-10-27 M-25:商家视角整段不出(商家没有订单),条件前加 !isMerchantView
  assert.match(wxml, /<block wx:if="\{\{!isMerchantView && \(orderPreviewList\.length \|\| orderErr\)\}\}">/)
  assert.match(wxml, /bindtap="onOrderPreviewTap"[^>]*data-id="\{\{item\.id\}\}"/)
})
