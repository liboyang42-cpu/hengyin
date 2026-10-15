// 第 2 轮 W5 尾项(F-W5-04/07/11/13)复验:PR #5 的修复没有回归测试的补上,
// 以及复验时发现的同类遗漏(参与详情的免费单文案、管理 tab 期次行三列错位)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function rule(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matched = css.match(new RegExp('(?:^|\\n|\\})\\s*' + escaped + '\\s*\\{([^}]*)\\}'))
  assert.ok(matched, '缺少样式规则 ' + selector)
  return matched[1]
}

// ---------- F-W5-04 模板个人审核未通过:作者看得到驳回原因 ----------

test('F-W5-04 我的玩法:个人审核未通过标出状态,并展示 my-list 下发的 reason', () => {
  const { marketView } = require(path.join(ROOT, 'utils/template-market.js'))
  const rejected = marketView({ draftStatus: 1, status: 2, checkStatus: 2 })
  assert.equal(rejected._statusText, '个人审核未通过')
  assert.equal(rejected._stateClass, 'st-rejected')
  const wxml = read('subpackageMember/mytemplate/mytemplate.wxml')
  assert.match(wxml, /<view class="dd" wx:if="\{\{item\.reason\}\}">\s*<view class="label">审核反馈<\/view>\s*<view class="cont flex-ac"><text>\{\{item\.reason\}\}<\/text><\/view>/)
  // decorateItem 用 Object.assign 保留原字段,reason 不被投影丢掉。
  assert.match(read('subpackageMember/mytemplate/mytemplate.js'), /return Object\.assign\(\{\}, item, marketView\(item\)/)
})

// ---------- F-W5-07 免费单:取消报名文案,不出「申请退款 / 取消并退款」 ----------

test('F-W5-07 免费单判定:0 元且无现金/积分支付才算免费', () => {
  const { isFreeOrder } = require(path.join(ROOT, 'utils/order-actual-pay.js'))
  assert.equal(isFreeOrder({ totalAmount: 0, payableAmount: '0', pointPaymentAmount: 0, wechatPaymentAmount: 0 }), true)
  assert.equal(isFreeOrder({ totalAmount: 10, payableAmount: 10 }), false)
  assert.equal(isFreeOrder({ totalAmount: 0, payableAmount: 0, pointPaymentAmount: 100 }), false)
  assert.equal(isFreeOrder({}), false, '金额缺失不下「免费」结论')
})

test('F-W5-07 订单列表/详情/参与详情:免费单一律走 order.cancel-free「取消报名」', () => {
  const { DANGER_ACTIONS } = require(path.join(ROOT, 'utils/danger-actions.js'))
  const free = DANGER_ACTIONS['order.cancel-free']
  assert.ok(free, 'order.cancel-free 必须已登记')
  assert.equal(free.confirmText, '取消报名')
  assert.doesNotMatch(JSON.stringify(free), /退款原路|取消并退款/)

  const historyWxml = read('subpackageMember/components/cy/scene-member-order-history/index.wxml')
  assert.match(historyWxml, /item\.isFreeOrder \? '取消报名' : '申请退款'/)
  const historyJs = read('subpackageMember/components/cy/scene-member-order-history/index.js')
  assert.match(historyJs, /isFreeOrder\(that\.data\.list\[index\]\) \? 'order\.cancel-free' : 'order\.cancel-refund'/)

  const detailWxml = read('subpackageMember/components/cy/scene-member-order-detail/index.wxml')
  assert.match(detailWxml, /info\.isFreeOrder \? '取消报名' : \(info\.registrationStatus==2 \? '取消并退款' : '取消订单'\)/)
  assert.match(read('subpackageMember/components/cy/scene-member-order-detail/index.js'),
    /info\.isFreeOrder \? 'order\.cancel-free' : 'order\.cancel-refund'/)

  const participation = read('subpackageMember/components/scene-member-participation-detail/index.js')
  assert.match(participation, /isFreeOrder\(this\.data\.info\) \? 'order\.cancel-free' : 'order\.cancel-refund'/,
    '参与详情的「取消参与」也不能对免费单弹「取消并退款」')
  assert.match(participation, /require\('\.\.\/\.\.\/\.\.\/utils\/order-actual-pay\.js'\)/)
})

test('F-W5-07 可取消资格只读服务端 refundInfo.refundable,前端不再按 24h 自己猜', () => {
  const { canRequestOrderRefund } = require(path.join(ROOT, 'utils/order-status.js'))
  const base = { registrationStatus: 2, paymentStatus: 2, verificationStatus: 0 }
  assert.equal(canRequestOrderRefund(Object.assign({}, base, { refundInfo: { refundable: true } })), true)
  assert.equal(canRequestOrderRefund(Object.assign({}, base, { refundInfo: { refundable: false } })), false)
  assert.equal(canRequestOrderRefund(Object.assign({}, base, { verificationStatus: 1, refundInfo: { refundable: true } })), false)
})

// ---------- F-W5-11 管理 tab 期次行 ----------

test('F-W5-11 期次行无封面:改两列,标题不落进 112rpx 封面列', () => {
  const css = read('pages/club/detail/index.wxss')
  assert.match(rule(css, '.manage-project'), /grid-template-columns:\s*112rpx minmax\(0, 1fr\) auto/)
  assert.match(rule(css, '.manage-project--edition'), /grid-template-columns:\s*minmax\(0, 1fr\) auto/)
  const wxml = read('pages/club/detail/index.wxml')
  assert.match(wxml, /<view class="manage-project manage-project--edition" wx:for="\{\{shareEditions\}\}"/)
})

// ---------- F-W5-13 活动详情:分类行叫分类,主题另给入口 ----------

test('F-W5-13 活动详情:分类行标「分类」,缺失写「分类待确认」;有 topicId 才出主题路线', () => {
  const wxml = read('subpackageMember/components/cy/scene-play-activity-detail/index.wxml')
  assert.match(wxml, /<text class="activity-row__key">分类<\/text><text class="activity-row__value">\{\{categoryText \|\| '分类待确认'\}\}<\/text>/)
  assert.doesNotMatch(wxml, /主题待确认/)
  assert.doesNotMatch(wxml, /activity-row__key">主题<\/text>/, '不得把分类当主题名展示')
  assert.match(wxml, /<view wx:if="\{\{info\.topicId\}\}" class="activity-row" bindtap="goTopic"/)
})
