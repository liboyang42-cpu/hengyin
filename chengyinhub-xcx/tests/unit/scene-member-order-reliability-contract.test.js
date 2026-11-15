const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const nodeVm = require('node:vm')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const ROOT = path.resolve(__dirname, '../..')
const HISTORY = path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-history/index.js')
const DETAIL = path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail/index.js')

function historyVm(appOverrides = {}) {
  const sandbox = { requests: [], modals: [], toasts: [] }
  global.getApp = () => ({
    getPageSize: () => 10,
    getUserID: () => 7, // Explicit authenticated order reader fixture.
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest: (options) => sandbox.requests.push(options),
    ...appOverrides,
  })
  global.Component = (config) => { sandbox.def = flattenComponentToPage(config) }
  global.wx = {
    showModal: (options) => sandbox.modals.push(options),
    showLoading() {}, hideLoading() {},
    showToast: (options) => sandbox.toasts.push(options.title),
    switchTab() {},
  }
  delete require.cache[require.resolve(HISTORY)]
  require(HISTORY)
  const vm = Object.assign({}, sandbox.def, {
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent() {},
  })
  vm.data = JSON.parse(JSON.stringify(sandbox.def.data))
  return { vm, sandbox }
}

function detailVm() {
  const sandbox = { requests: [], modals: [], toasts: [] }
  global.getApp = () => ({
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest: (options) => sandbox.requests.push(options),
  })
  global.Component = (config) => { sandbox.def = flattenComponentToPage(config) }
  global.wx = {
    showModal: (options) => sandbox.modals.push(options),
    showLoading() {}, hideLoading() {},
    showToast: (options) => sandbox.toasts.push(options.title),
  }
  delete require.cache[require.resolve(DETAIL)]
  require(DETAIL)
  const vm = Object.assign({}, sandbox.def, {
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent() {},
  })
  vm.data = JSON.parse(JSON.stringify(sandbox.def.data))
  return { vm, sandbox }
}

// 只投影这两处调用的属性；重试走注册的真实组件方法和 WXML 事件绑定。
function orderError(vm) {
  const wxml = fs.readFileSync(HISTORY.replace(/\.js$/, '.wxml'), 'utf8')
  const tags = wxml.match(/<cy-error\b(?:[^>"']|"[^"]*"|'[^']*')*\/>/g)
  assert.equal(tags.length, 2)
  const value = (tag, name, fallback) => {
    const attr = tag.match(new RegExp('\\s' + name + '(?:="([^"]*)")?(?=\\s|/?>)'))
    if (!attr) return fallback
    if (attr[1] === undefined) return true
    const expression = attr[1].match(/^\{\{([\s\S]*)\}\}$/)
    return expression ? nodeVm.runInNewContext(expression[1], vm.data) : attr[1]
  }
  const tag = tags.find(tag => value(tag, 'wx:(?:if|elif)', false))
  if (!tag) return null
  const registration = JSON.parse(fs.readFileSync(HISTORY.replace(/\.js$/, '.json'), 'utf8'))
  const component = path.join(ROOT, registration.usingComponents['cy-error'] + '.js')
  let def
  const previous = global.Component
  global.Component = config => { def = config }
  try { delete require.cache[require.resolve(component)]; require(component) } finally { global.Component = previous }
  const props = {}
  for (const [name, spec] of Object.entries(def.properties)) {
    props[name] = value(tag, name.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase()), spec.value)
  }
  const template = fs.readFileSync(component.replace(/\.js$/, '.wxml'), 'utf8')
  assert.match(template, /wx:if="\{\{loadFailure\}\}"[\s\S]*wx:elif="\{\{autoBack\}\}"[\s\S]*wx:else/)
  assert.equal(props.loadFailure, false, 'loadFailure 分支只有空白，不能恢复订单读取')
  assert.equal(props.autoBack, false, '留在当前订单列表重试，不自动离开')
  assert.ok(props.retry)
  const tap = template.match(/class="cy-error-retry"[^>]*bindtap="([^"]+)"/)[1]
  return { props, tap() { def.methods[tap].call({ triggerEvent(event) { vm[value(tag, 'bind:' + event)]() } }) } }
}

test('订单首屏超时显示可见重试，真实 retry 事件只重读列表并恢复成功数据', () => {
  const { vm, sandbox } = historyVm()
  vm.getList()
  sandbox.requests[0].fail({ msg: '请求超时' })
  sandbox.requests[0].complete()
  assert.equal(vm.data.loading, false)
  assert.equal(vm.data.list.length, 0)
  const error = orderError(vm)
  assert.equal(error.props.title, '订单加载失败')
  assert.equal(error.props.sub, '已支付订单不受影响')
  assert.equal(error.props.retry, '重试')
  vm.data.page_no = 3
  error.tap()
  assert.equal(sandbox.requests.length, 2)
  assert.ok(sandbox.requests.every(request => request.url === '/api/registration/list'))
  assert.equal(sandbox.requests[1].data.pageNum, 1)
  sandbox.requests[1].success({ code: 200, data: { rows: [{ id: 10, registrationStatus: 1 }] } })
  sandbox.requests[1].complete()
  assert.deepEqual(vm.data.list.map(row => row.id), [10])
  assert.equal(vm.data.errorMsg, '')
  assert.equal(vm.data.hasMore, false)
  assert.equal(orderError(vm), null)
})

test('订单列表 200 畸形 rows 进入错误态，保留已确认列表且不抛异常', () => {
  for (const rows of [null, {}, 'not-array', true]) {
    const { vm, sandbox } = historyVm()
    vm.data.list = [{ id: 9, registrationNo: 'confirmed' }]
    vm.data.hasOrders = true
    vm.getList(false, true)
    assert.doesNotThrow(() => sandbox.requests[0].success({ code: 200, data: { rows } }))
    sandbox.requests[0].complete()
    assert.equal(vm.data.list[0].id, 9)
    assert.match(vm.data.errorMsg, /订单/)
    assert.equal(vm.data.nodata, false)
  }
})

test('订单列表刷新失败使用非阻断错误，成功重试后再替换旧列表', () => {
  const { vm, sandbox } = historyVm()
  vm.data.list = [{ id: 9, registrationNo: 'old' }]
  vm.data.hasOrders = true
  vm.getList(false, true)
  sandbox.requests[0].fail({ msg: '刷新失败' })
  sandbox.requests[0].complete()
  assert.deepEqual(vm.data.list.map((item) => item.id), [9])
  assert.equal(vm.data.errorMsg, '刷新失败')

  const error = orderError(vm)
  assert.equal(error.props.title, '订单刷新失败')
  error.tap()
  sandbox.requests[1].success({ code: 200, data: { rows: [{ id: 10, registrationStatus: 1 }] } })
  sandbox.requests[1].complete()
  assert.deepEqual(vm.data.list.map((item) => item.id), [10])
  assert.equal(vm.data.errorMsg, '')
})

test('订单 401/403 保留原登录及拒权恢复，不把权限失败画成空订单', async () => {
  for (const code of [401, 403]) {
    let logins = 0
    const { vm, sandbox } = historyVm({ reLogin() { logins++; return Promise.resolve({ ok: false }) } })
    vm.getList()
    sandbox.requests[0].success({ code })
    sandbox.requests[0].complete()
    const error = orderError(vm)
    assert.equal(error.props.title, code === 401 ? '登录后查看订单' : '当前账号无法查看订单')
    assert.equal(error.props.retry, code === 401 ? '登录' : '重试')
    assert.equal(error.props.sub, vm.data.errorMsg)
    assert.equal(vm.data.orderAuthRequired, code === 401)
    assert.equal(vm.data.orderAccessDenied, code === 403)
    error.tap()
    await Promise.resolve()
    assert.equal(logins, code === 401 ? 1 : 0)
    assert.equal(sandbox.requests.length, code === 401 ? 1 : 2, '401 走登录分支，403 仍由原读取接口裁决')
  }
})

test('订单加载中和成功空列表不显示失败重试；筛选空态条件不变', () => {
  const { vm, sandbox } = historyVm()
  vm.getList()
  assert.equal(vm.data.loading, true)
  assert.equal(orderError(vm), null)
  sandbox.requests[0].success({ code: 200, data: { rows: [] } })
  sandbox.requests[0].complete()
  assert.equal(vm.data.loading, false)
  assert.equal(vm.data.hasOrders, false)
  assert.equal(vm.data.errorMsg, '')
  assert.equal(orderError(vm), null)
  const wxml = fs.readFileSync(HISTORY.replace(/\.js$/, '.wxml'), 'utf8')
  assert.match(wxml, /cy-skeleton wx:if="\{\{loading && list.length==0\}\}"/)
  assert.match(wxml, /cy-empty wx:if="\{\{!loading && !errorMsg && list.length==0 && !hasOrders\}\}"/)
  assert.match(wxml, /cy-empty wx:if="\{\{!loading && !errorMsg && list.length==0 && hasOrders\}\}"/)
})

test('列表取消/退款从确认弹窗到请求全链路 single-flight', () => {
  const { vm, sandbox } = historyVm()
  const event = { currentTarget: { dataset: { id: 8, index: 0, status: 2 } } }
  vm.cancelOrder(event)
  vm.cancelOrder(event)
  assert.equal(sandbox.modals.length, 1, '连点只允许一个确认弹窗')

  sandbox.modals[0].success({ confirm: true })
  vm.cancelOrderRequest(8, 0, 2)
  vm.cancelOrder(event)
  assert.equal(sandbox.requests.length, 1, '确认后到请求完成前只允许一个退款请求')
  assert.equal(vm.data.cancellingId, 8)

  sandbox.requests[0].complete()
  vm.cancelOrder(event)
  assert.equal(sandbox.modals.length, 2, '请求完成后才恢复动作')
})

test('订单详情取消/退款确认弹窗与写请求 single-flight', () => {
  const { vm, sandbox } = detailVm()
  vm._orderId = 8
  vm.data.info = { id: 8, paymentStatus: 2, registrationStatus: 2, canRequestRefund: true }
  vm.cancelRegistration()
  vm.cancelRegistration()
  assert.equal(sandbox.modals.length, 1)

  sandbox.modals[0].success({ confirm: true })
  vm.cancelRegistration()
  assert.equal(sandbox.requests.length, 1)
  assert.equal(vm.data.cancelling, true)

  sandbox.requests[0].complete()
  vm.cancelRegistration()
  assert.equal(sandbox.modals.length, 2)
})

test('订单部分错误与动作 busy 状态在 WXML 中可见且可访问', () => {
  const history = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-history/index.wxml'), 'utf8')
  const detail = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail/index.wxml'), 'utf8')
  assert.match(history, /errorMsg && list\.length>0/)
  assert.match(history, /bind:retry="retryList"/)
  assert.match(history, /aria-disabled="\{\{cancellingId[^}]*\}\}"/)
  assert.match(detail, /aria-disabled="\{\{cancelling\}\}"/)
  assert.doesNotMatch(detail, />\s*›\s*</, '文字箭头必须替换成项目图标资产')
})

test('negative control: rows.map 前无数组验证、取消链路无 prompt guard 都会判红', () => {
  const history = fs.readFileSync(HISTORY, 'utf8')
  const detail = fs.readFileSync(DETAIL, 'utf8')
  assert.match(history, /Array\.isArray\(rows\)/)
  assert.match(history, /_cancelPromptOpen/)
  assert.match(history, /_cancelRequestInFlight/)
  assert.match(detail, /_cancelPromptOpen/)
})


test('订单列表的活动和主题空地点显示待确认，不泄漏 null 占位', () => {
  const { vm, sandbox } = historyVm()
  vm.getList()
  sandbox.requests[0].success({ code: 200, data: { rows: [
    { id: 1, registrationStatus: 1, cmsActivity: { addressName: null } },
    { id: 2, registrationStatus: 1, cmsTopic: { addressName: 'null' } },
    { id: 3, registrationStatus: 1, cmsActivity: { addressName: ' 人民广场 ' } },
  ] } })
  assert.deepEqual(vm.data.list.map(row => row.addressText), ['—', '—', '人民广场'])
})
