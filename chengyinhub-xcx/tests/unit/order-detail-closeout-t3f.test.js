// 订单详情收尾 T3f(2026-09-15 总控裁决):
//   ① 加载失败原因只说原因,不再写「请…重新进入」(面板 2s 自动回列表,指令多余)
//   ② 「和队友一起出发」卡排在支付明细之后,不打断稿 578:2298 的顺序
//   ③ 支付信息卡里的微信交易单号是纯值,动作不进信息卡(与订单号行一致)
//   ④ 退款态的状态卡副文案 / 「退款与客服」卡说退款事实,不被 refundInfo 的可退政策
//      「可全额退款,原路退回(预计1-3个工作日)」顶掉
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const ROOT = path.resolve(__dirname, '../..')
const JS = path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail/index.js')
const WXML = path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail/index.wxml')

let sandbox
beforeEach(() => {
  sandbox = { requests: [], toasts: [] }
  global.getApp = () => ({
    globalData: {},
    tips: m => sandbox.toasts.push(m),
    sendRequest: o => sandbox.requests.push(o),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  })
  global.Component = config => { sandbox.pageConfig = flattenComponentToPage(config) }
  global.wx = { showToast() {}, hideLoading() {}, showLoading() {}, navigateTo() {}, navigateBack() {} }
})

function loadVm() {
  delete require.cache[require.resolve(JS)]
  require(JS)
  const vm = Object.assign({}, sandbox.pageConfig, {
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
  })
  vm.data = Object.assign({}, sandbox.pageConfig.data)
  return vm
}
const infoReq = () => sandbox.requests.filter(r => r.url === '/api/registration/info')[0]

test('订单详情头部与订单列表使用相同现金实付款，保留零值和应付明细', () => {
  const wxml = fs.readFileSync(WXML, 'utf8')
  const binding = wxml.match(/class="hero-amt">\{\{money\.amount\((?:cnt\.f\()?info\.(\w+)(?:, odK\))?\)\}\}/) // 2026-10-23:金额外包一层数字滚动(cnt.f),落点仍是同一字段
  assert.ok(binding, '详情头部金额绑定存在')
  for (const cash of [0.01, 0]) {
    sandbox.requests = []
    const vm = loadVm()
    vm.data.orderId = '43'
    vm.onLoad()
    infoReq().success({ code: 200, data: {
      id: 43, ownerType: 1, registrationStatus: 2, paymentStatus: 2,
      totalAmount: 15, payableAmount: 9, pointPaymentAmount: 6, wechatPaymentAmount: cash,
    } })
    assert.equal(vm.data.info[binding[1]], cash)
    assert.equal(vm.data.info.payableAmount, 9)
  }
})

test('T3f ① 网络失败 / 后端无原因的业务失败:原因只说原因,不带「重新进入」指令', () => {
  let vm = loadVm()
  vm.data.orderId = '1001'
  vm.onLoad()
  infoReq().fail({ errMsg: 'request:fail timeout' })
  assert.match(vm.data.loadErrorText, /网络/)
  assert.doesNotMatch(vm.data.loadErrorText, /重新进入|请返回/)

  sandbox.requests = []
  vm = loadVm()
  vm.data.orderId = '1001'
  vm.onLoad()
  infoReq().success({ code: '500' })
  assert.ok(vm.data.loadErrorText)
  assert.doesNotMatch(vm.data.loadErrorText, /重新进入|请返回/)
})

test('T3f ② 免费与付费明细标题准确，组队卡在明细之后', () => {
  const wxml = fs.readFileSync(WXML, 'utf8')
  const team = wxml.indexOf('team-order-card')
  const pay = wxml.match(/<view class="sec-title">\{\{([^}]*info\.isFreeOrder[^}]*)\}\}<\/view>/)
  assert.ok(team > 0 && pay, '锚点失效')
  const label = new Function('info', 'return ' + pay[1])
  assert.equal(label({ isFreeOrder: true }), '费用明细')
  assert.equal(label({ isFreeOrder: false }), '支付明细')
  assert.ok(team > wxml.indexOf(pay[0]), '「和队友一起出发」必须排在费用/支付明细之后')
})

test('T3f ③ 微信交易单号行是纯值:无 bindtap、无「复制」', () => {
  const wxml = fs.readFileSync(WXML, 'utf8')
  const row = wxml.match(/<view class="orow"[^>]*>\s*<text class="orow-k">微信交易单号<\/text>[\s\S]*?<\/view>/)
  assert.ok(row, '锚点失效:没找到微信交易单号行')
  assert.doesNotMatch(row[0], /bindtap|复制/)
  assert.doesNotMatch(fs.readFileSync(JS, 'utf8'), /copyTransactionId/, '行内复制删掉后处理函数不应成孤儿')
})

test('T3f ④ 退款态文案取退款事实,不被可退政策里的「预计1-3个工作日」顶掉', () => {
  const vm = loadVm()
  vm.data.orderId = '1001'
  vm.onLoad()
  infoReq().success({ code: '200', data: {
    id: 1001, ownerType: 1, registrationStatus: 3, paymentStatus: 4,
    refundApplication: { status: 1, payoutStatus: 0, refundAmount: 69 },
    refundInfo: { refundable: true, reason: '可全额退款,原路退回(预计1-3个工作日)', deadline: '2026-09-20 10:00:00' },
  } })
  assert.equal(vm.data.loadState, 'ready')
  const fact = vm.data.info.refundDisplayText
  assert.ok(fact)
  assert.equal(vm.data.stateNotice.sub, fact, '状态卡副文案必须是退款事实')
  assert.ok(!vm.data.info.refundDeadlineDisplay || vm.data.info.refundDeadlineDisplay === fact,
    '「退款与客服」卡优先读 refundDeadlineDisplay,退款态下它不得是可退政策')
  assert.doesNotMatch(vm.data.stateNotice.sub, /1-3/)
})

test('T3f ⑤ 退款还在审核时,状态卡标题不说「已受理」', () => {
  const vm = loadVm()
  vm.data.orderId = '1001'
  vm.onLoad()
  infoReq().success({ code: '200', data: {
    id: 1001, ownerType: 1, registrationStatus: 2, paymentStatus: 2,
    refundApplication: { status: 0, payoutStatus: 5, refundAmount: 69 },
  } })
  assert.equal(vm.data.info.statusText, '退款审核中')
  assert.doesNotMatch(vm.data.stateNotice.title, /受理/)
})


test('已核销与自然结束的已完成订单都不再提示去开场', () => {
  for (const [ownerType, verificationStatus, expected] of [
    [2, 1, '本次订单已核销'], [2, 0, '本次活动已结束'],
    [1, 1, '本次订单已核销'], [1, 0, '本次路线已结束'],
  ]) {
    const vm = loadVm()
    vm.data.orderId = 76
    vm.onLoad()
    const request = sandbox.requests.filter(r => r.url === '/api/registration/info').slice(-1)[0]
    request.success({ code: 200, data: {
      id: 76, ownerType, registrationStatus: 2, verificationStatus,
      [ownerType === 1 ? 'cmsTopic' : 'cmsActivity']: {
        id: 27, startDate: '2020-01-01 10:00:00', endDate: '2020-01-01 11:00:00',
      },
    } })
    assert.equal(vm.data.info.orderStateKey, 'completed')
    assert.equal(vm.data.info.orderHint, expected)
  }
})

test('首次扫码完成订单不冒充活动结束，票夹和退款资格仍各读原事实', () => {
  const vm = loadVm()
  vm.data.orderId = 990058
  vm.onLoad()
  infoReq().success({ code: 200, data: {
    id: 990058, ownerType: 2, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
    firstSuccessfulScanAt: '2026-10-01 18:16:47', amount: 69,
    cmsActivity: { id: 930007, startDate: '2026-10-01 14:34:00', endDate: '2099-10-01 20:34:00' },
    refundInfo: { refundable: true, reason: '可申请退款' },
  } })
  assert.equal(vm.data.info.orderStateKey, 'completed')
  assert.equal(vm.data.info.orderHint, '首次扫码成功，订单已完成')
  assert.equal(vm.data.showSecondaryTicket, true)
  assert.equal(vm.data.info.canRequestRefund, true)
})

test('OT-01 只有已支付且报名成功的订单保留票夹入口', () => {
  const cases = [
    {
      label: '待支付', info: { registrationStatus: 1, paymentStatus: 0 },
      state: 'pending_payment', primary: 'pay', secondary: false,
    },
    {
      label: '已取消', info: { registrationStatus: 3, paymentStatus: 3 },
      state: 'cancelled', primary: '', secondary: false,
    },
    {
      label: '已过期', info: { registrationStatus: 4, paymentStatus: 3 },
      state: 'expired', primary: '', secondary: false,
    },
    {
      label: '退款中', info: {
        registrationStatus: 3, paymentStatus: 4,
        refundApplication: { status: 0, payoutStatus: 5, refundAmount: 69 },
      },
      state: 'refunding', primary: '', secondary: false,
    },
    {
      label: '已退款', info: {
        registrationStatus: 3, paymentStatus: 4,
        refundApplication: { status: 1, payoutStatus: 1, refundAmount: 69 },
      },
      state: 'refunded', primary: '', secondary: false,
    },
    {
      label: '进行中有效票', info: { registrationStatus: 2, paymentStatus: 2, verificationStatus: 0 },
      state: 'in_progress', primary: 'ticket', secondary: false,
    },
    {
      label: '自然结束有效票', info: {
        registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
        cmsActivity: { startDate: '2020-01-01 10:00:00', endDate: '2020-01-01 11:00:00' },
      },
      state: 'completed', primary: '', secondary: true,
    },
    {
      label: '已核销有效票', info: { registrationStatus: 2, paymentStatus: 2, verificationStatus: 1 },
      state: 'completed', primary: '', secondary: true,
    },
    {
      label: '首次扫码完成有效票', info: {
        registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
        firstSuccessfulScanAt: '2026-10-01 18:16:47',
      },
      state: 'completed', primary: '', secondary: true,
    },
  ]

  cases.forEach((entry, index) => {
    const vm = loadVm()
    vm.data.orderId = String(8000 + index)
    vm.onLoad()
    const request = sandbox.requests.filter(r => r.url === '/api/registration/info').slice(-1)[0]
    request.success({ code: 200, data: Object.assign({
      id: 8000 + index, ownerType: 2,
    }, entry.info) })
    assert.equal(vm.data.info.orderStateKey, entry.state, entry.label + ' 状态锚点')
    assert.equal(vm.data.primaryCta, entry.primary, entry.label + ' 主 CTA')
    assert.equal(vm.data.showSecondaryTicket, entry.secondary, entry.label + ' 次级票夹 CTA')
  })
})

test('付款未确认的已报名订单不显示票夹入口，直接触发也不能导航', () => {
  for (const paymentStatus of [0, 1, 3, 4, null, undefined]) {
    const vm = loadVm()
    vm.data.orderId = '77'
    vm.onLoad()
    const request = sandbox.requests.filter(r => r.url === '/api/registration/info').slice(-1)[0]
    request.success({ code: 200, data: { id: 77, ownerType: 2, registrationStatus: 2, paymentStatus } })
    assert.notEqual(vm.data.primaryCta, 'ticket')
    assert.equal(vm.data.showSecondaryTicket, false)
    assert.doesNotMatch(vm.data.info.orderHint, /票夹已生成/)
    const navigations = []
    vm._leave = url => navigations.push(url)
    vm.goSignInfo()
    assert.deepEqual(navigations, [])
  }
})
