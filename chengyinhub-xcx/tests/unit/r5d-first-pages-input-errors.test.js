'use strict'

// 第五轮 D(业主 2026-11-03):用户最先看到的两页 —— 结算页 activity/baoming 与商家入驻
// merchant/apply —— 上「报告刚填/漏填内容有问题」的弹窗改为字段下方 cy-inline-error,
// 标签带红色 *,页面滚到第一条;拦截与提交载荷不变。确认、同意、服务端与网络失败等弹窗不动。

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const uiSandbox = require('../helpers/ui-sandbox-vm.js')

const ROOT = path.resolve(__dirname, '../..')

// ---------------------------------------------------------------- merchant/apply
const APPLY_JS = path.join(ROOT, 'pages/merchant/apply/index.js')
const APPLY_WXML = path.join(ROOT, 'pages/merchant/apply/index.wxml')

function setByPath(target, dataPath, value) {
  const parts = dataPath.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadApply() {
  const requests = []
  const toasts = []
  const scrolls = []
  const imageCallbacks = []
  const locationRequests = []
  let definition
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID() { return 9 },
    getAuthorization() { return 'token-a' },
    sendRequest(options) { requests.push(options) },
    chooseImage(callback) { imageCallbacks.push(callback) },
  }
  const wx = {
    showModal() {},
    showToast(options) { toasts.push(options.title) },
    navigateBack() {},
    switchTab() {},
    navigateTo() {},
    chooseLocation(options) { locationRequests.push(options) },
    openSetting() {},
    nextTick() {},
    pageScrollTo(options) { scrolls.push(options) },
  }
  const sandbox = {
    Array, Date, Math, Object, RegExp, String,
    getApp: () => app,
    Page(config) { definition = config },
    wx,
    require(request) {
      if (request.endsWith('roleGuard.js')) {
        return { load() {}, isClubLeader() { return false }, hasSnapshot() { return true }, ownedClubs() { return [] }, clear() {} }
      }
      if (request.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
      // 2026-11-03 集成:l5 M-16 入驻第 1 步加了必填「行业类型」,页面 require 行业短表
      if (request.endsWith('merchant-industry.js')) return require(path.join(ROOT, 'pages/merchant/utils/merchant-industry.js'))
      for (const rel of ['merchant-identity-policy.js', 'time-picker-options.js', 'motion-preference.js']) {
        if (request.endsWith(rel)) return require(path.join(ROOT, 'utils', rel))
      }
      if (request.endsWith('wizard-morph.js') || request.endsWith('publisher-identity.js')) {
        const rel = request.endsWith('wizard-morph.js') ? 'utils/wizard-morph.js' : 'utils/publisher-identity.js'
        const src = fs.readFileSync(path.join(ROOT, rel), 'utf8')
        const mod = { exports: {} }
        const context = Object.assign({}, sandbox, { module: mod, exports: mod.exports })
        context.require = (dep) => (dep.startsWith('.')
          ? require(path.join(ROOT, 'utils', dep.replace(/^\.\//, '')))
          : require(dep))
        vm.runInNewContext(src, context, { filename: rel })
        return mod.exports
      }
      if (uiSandbox.isUiModule(request)) return uiSandbox.loadUiModule(request, sandbox)
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      throw new Error(`unexpected require: ${request}`)
    },
  }
  vm.runInNewContext(fs.readFileSync(APPLY_JS, 'utf8'), sandbox, { filename: APPLY_JS })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch, callback) {
    Object.entries(patch).forEach(([k, v]) => setByPath(this.data, k, v))
    if (callback) callback.call(this)
  }
  page.ensureIdentityStatus = () => {}
  Object.assign(page.data, { bootstrapState: 'ready', mode: 'form', introMode: false })
  const lastScroll = () => (scrolls.length ? scrolls[scrolls.length - 1].selector : '')
  return { page, requests, toasts, imageCallbacks, locationRequests, scrolls, lastScroll }
}

function input(page, field, value) {
  page.onInput({ currentTarget: { dataset: { field } }, detail: { value } })
}

function registrationRequests(requests) {
  return requests.filter(r => r.url === '/api/merchant/merchant_registration')
}

test('商家入驻第 1 步:漏填写在各栏下方红字、滚到第一条、不弹提示也不前进;改填即清', () => {
  const { page, requests, toasts, lastScroll, scrolls } = loadApply()
  page.refreshStep(1)
  assert.equal(page.data.canNext, false)

  page.onNextDisabledTap()
  assert.deepEqual(toasts, [], '不再弹提示')
  assert.equal(page.data.fieldErrors.name, '请填写店铺名称')
  assert.equal(page.data.fieldErrors.phone, '请填写联系电话')
  assert.equal(lastScroll(), '#apply-field-name', '滚到第一条')
  assert.equal(scrolls[0].offsetTop, -(20 + 44 + 12), '让出固定导航栏')
  page.onNext()
  assert.equal(page.data.step, 1, '拦截不变:按钮仍置灰,点了不前进')
  assert.equal(registrationRequests(requests).length, 0)

  input(page, 'name', '小店')
  assert.equal(page.data.fieldErrors.name, '', '改填店名即清')
  assert.equal(page.data.fieldErrors.phone, '请填写联系电话', '另一栏不受影响')
  // 2026-11-03 集成:l5 M-16 行业类型必填,同样写在该行下方红字、按页面顺序排在店名与电话之间
  assert.equal(page.data.fieldErrors.industryIds, '请选择行业类型')
  page.onNextDisabledTap()
  assert.equal(lastScroll(), '#apply-field-industryIds', '滚到剩下的第一条')
  page.data.industrySheet = { show: true, options: [{ key: '3', label: '书店' }], value: ['3'] }
  page.confirmIndustrySheet({ detail: { value: ['3'] } })
  assert.equal(page.data.fieldErrors.industryIds, '', '选好行业即清')
  page.onNextDisabledTap()
  assert.equal(lastScroll(), '#apply-field-phone', '滚到剩下的第一条')
  input(page, 'phone', '13800138000')
  assert.equal(page.data.fieldErrors.phone, '')
  assert.equal(page.data.canNext, true)
})

test('商家入驻第 2、3 步:地址、经营时间、营业执照漏填写在各栏下方;选好即清', () => {
  const { page, toasts, imageCallbacks, locationRequests, lastScroll } = loadApply()
  page.refreshStep(2)
  page.onNextDisabledTap()
  assert.deepEqual(toasts, [])
  assert.equal(page.data.fieldErrors.address, '请填写店铺地址')
  assert.equal(page.data.fieldErrors.businessTime, '请选择营业时间')
  assert.equal(lastScroll(), '#apply-field-address')

  page.openLocationPicker()
  locationRequests[0].success({ address: '上海市某路 1 号', latitude: 31.2, longitude: 121.4 })
  assert.equal(page.data.fieldErrors.address, '')
  page.openHoursPicker()
  page.confirmHoursPicker()
  assert.equal(page.data.fieldErrors.businessTime, '')
  assert.equal(page.data.canNext, true)

  page.refreshStep(3)
  page.onNextDisabledTap()
  assert.deepEqual(toasts, [])
  assert.equal(page.data.fieldErrors.businessLicense, '请上传营业执照')
  assert.equal(lastScroll(), '#apply-field-businessLicense')
  page.uploadLicense()
  imageCallbacks[0](['https://example.invalid/license.jpg'])
  assert.equal(page.data.fieldErrors.businessLicense, '')
})

test('商家入驻第 4 步:姓名/证件号问题写在各栏下方;只差单独同意时仍按原样弹提示', () => {
  const { page, toasts, lastScroll } = loadApply()
  page.refreshStep(4)
  page.onNextDisabledTap()
  assert.deepEqual(toasts, [])
  assert.equal(page.data.fieldErrors.realName, '请填写真实姓名')
  assert.equal(page.data.fieldErrors.idCard, '身份证号格式不正确，请确认后重填')
  assert.equal(lastScroll(), '#apply-field-realName')

  input(page, 'realName', '林一2')
  page.onNextDisabledTap()
  assert.equal(page.data.fieldErrors.realName, '姓名里不应包含数字', '与原规则同一文案')
  input(page, 'realName', '林一')
  input(page, 'idCard', '11010519491231002X')
  assert.equal(page.data.fieldErrors.realName, '')
  assert.equal(page.data.fieldErrors.idCard, '')

  page.onNextDisabledTap()
  assert.deepEqual(toasts, ['请先同意提供真实姓名与身份证号'], '同意类保留原提示')
  page.onIdentityConsent({ detail: { checked: true } })
  assert.equal(page.data.canNext, true)
})

test('经营时间弹层:没选经营日、起止相同写在弹层对应一栏下方,不关弹层;改了即清', () => {
  const { page, toasts } = loadApply()
  page.validate = () => {}
  page.openHoursPicker()
  page.data.hoursDraft.days.forEach(day => { day.on = false })
  page.confirmHoursPicker()
  assert.deepEqual(toasts, [])
  assert.equal(page.data.hoursErrors.days, '至少选择一个经营日')
  assert.equal(page.data.hoursPickerShow, true)
  assert.equal(page.data.businessTime, '')
  page.toggleHoursDay({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(page.data.hoursErrors.days, '')

  page.onHoursEndChange({ detail: { value: '10:00' } })
  page.confirmHoursPicker()
  assert.deepEqual(toasts, [])
  assert.equal(page.data.hoursErrors.time, '开始与结束时间不能相同')
  assert.equal(page.data.hoursPickerShow, true)
  page.onHoursEndChange({ detail: { value: '18:00' } })
  assert.equal(page.data.hoursErrors.time, '')
  page.confirmHoursPicker()
  assert.equal(page.data.hoursPickerShow, false)
  assert.equal(page.data.businessTime, '周一 10:00-18:00')
})

test('商家入驻:报过错再改正后提交,载荷与直接填对完全一致', () => {
  function fillAndSubmit(withErrors) {
    const { page, requests, imageCallbacks, locationRequests } = loadApply()
    page.data.identityRegistered = true
    page.refreshStep(1)
    if (withErrors) page.onNextDisabledTap()
    input(page, 'name', '小店')
    input(page, 'preference', '餐饮')
    input(page, 'phone', '13800138000')
    // 2026-11-03 集成:l5 M-16 第 1 步必填行业类型
    page.data.industrySheet = { show: true, options: [{ key: '3', label: '书店' }], value: ['3'] }
    page.confirmIndustrySheet({ detail: { value: ['3'] } })
    page.onNext()
    if (withErrors) page.onNextDisabledTap()
    page.openLocationPicker()
    locationRequests[0].success({ address: '上海市某路 1 号', latitude: 31.2, longitude: 121.4 })
    page.openHoursPicker()
    if (withErrors) {
      page.data.hoursDraft.days.forEach(day => { day.on = false })
      page.confirmHoursPicker()
      page.data.hoursDraft.days.forEach(day => { day.on = true })
    }
    page.confirmHoursPicker()
    page.onNext()
    if (withErrors) page.onNextDisabledTap()
    page.uploadLicense()
    imageCallbacks[0](['https://example.invalid/license.jpg'])
    page.onNext()
    assert.equal(page.data.step, 4)
    page.onNext()
    const sent = registrationRequests(requests)
    assert.equal(sent.length, 1)
    return JSON.parse(sent[0].data)
  }
  const clean = fillAndSubmit(false)
  assert.deepEqual(fillAndSubmit(true), clean)
  // 2026-11-03 集成:l5 M-16 以行业类型 categoryIds 取代原 preference,其余不增不减
  assert.deepEqual(Object.keys(clean).sort(), ['address', 'businessLicense', 'businessTime', 'categoryIds', 'derivatives',
    'description', 'id', 'locationLat', 'locationLng', 'name', 'phone'].sort(), '载荷字段不增不减')
})

test('商家入驻页面结构:每个必填栏有红色 * 和其下方 kind="field" 的 cy-inline-error,供 pageScrollTo 定位的 id', () => {
  const wxml = fs.readFileSync(APPLY_WXML, 'utf8')
  for (const key of ['name', 'industryIds', 'phone', 'address', 'businessTime', 'businessLicense', 'realName', 'idCard']) {
    const start = wxml.indexOf(`id="apply-field-${key}"`)
    assert.ok(start > 0, `缺少 #apply-field-${key}`)
    const block = wxml.slice(start, start + 2000)
    assert.match(block, /class="req" aria-hidden="true">\*</, `${key} 标签缺红色 *`)
    assert.ok(block.includes(`<cy-inline-error class="field-err" wx:if="{{fieldErrors.${key}}}" kind="field" title="{{fieldErrors.${key}}}" />`),
      `${key} 下方缺红字`)
  }
  assert.match(wxml, /<view class="hp-label">经营日<text class="req" aria-hidden="true">\*<\/text><\/view>/)
  assert.match(wxml, /<cy-inline-error class="hp-err" wx:if="\{\{hoursErrors\.days\}\}" kind="field"/)
  assert.match(wxml, /<cy-inline-error class="hp-err" wx:if="\{\{hoursErrors\.time\}\}" kind="field"/)
  const js = fs.readFileSync(APPLY_JS, 'utf8')
  for (const text of ['请填写店铺名称', '请填写联系电话', '请上传营业执照', '至少选择一个经营日', '开始与结束时间不能相同']) {
    assert.ok(!js.includes(`toast('${text}')`) && !js.includes(`toast(!d.name ? '${text}'`), `${text} 不应再走弹窗`)
  }
  // 留下的弹窗:数量上限、审核中状态、选位置说明与定位失败(确认/权限)
  assert.ok(js.includes("toast('最多6张')"))
  assert.ok(js.includes("toast('审核中，请耐心等待')"))
})

// ---------------------------------------------------------------- activity/baoming
const BAOMING_JS = '../../pages/activity/baoming/baoming.js'
const BAOMING_WXML = path.join(ROOT, 'pages/activity/baoming/baoming.wxml')

function loadBaoming() {
  let config
  const tips = []
  const scrolls = []
  const submits = []
  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    getUserID: () => 9,
    isDevEnv: () => false,
    tips: message => tips.push(message),
    goBack() {},
    sendRequest() {},
    recordConsent: () => Promise.resolve(),
  })
  global.Page = value => { config = value }
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast(o) { tips.push(o.title) },
    showModal() {},
    showLoading() {},
    hideLoading() {},
    pageScrollTo(o) { scrolls.push(o) },
    navigateTo() {},
  }
  delete require.cache[require.resolve(BAOMING_JS)]
  require(BAOMING_JS)
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = function (patch, callback) {
    Object.entries(patch).forEach(([k, v]) => setByPath(this.data, k, v))
    if (callback) callback()
  }
  Object.assign(page.data, {
    pageState: 'ready',
    activityId: 12,
    activityInfo: { id: 12 },
    selectedTicket: { id: 34, price: 88 },
    selectedAddress: null,
    addressList: [{ id: 7, fullName: '林一', mobilePhone: '13800138000' }],
    participantState: 'ready',
    userInfo: { realName: '', phone: '', email: '', point: 0 },
    hostShareChecked: true,
    hostShareConsentReady: true,
    totalAmount: 88,
    paymentReady: true,
    canPay: true,
  })
  page._hostShareConsentReady = true
  page._quoteSign = 'quote-signed'
  page._signupSubscriptionRequested = true
  page._workflow = { submit(payload) { submits.push(JSON.parse(JSON.stringify(payload))); return true } }
  return { page, tips, scrolls, submits }
}

function pick(page, item) {
  page.selectAddress({ currentTarget: { dataset: { item } } })
}

test('结算页:没选参与人写在该栏下方红字、滚回顶部、照旧打开选择弹层,不弹提示也不建单;选好即清', () => {
  const { page, tips, scrolls, submits } = loadBaoming()
  page.handlePayment()
  assert.deepEqual(tips, [], '不再弹提示')
  assert.equal(page.data.participantError, '请选择参与人信息')
  assert.equal(scrolls.length, 1)
  assert.equal(scrolls[0].scrollTop, 0)
  assert.equal(page.data.showAddressPopup, true, '原有打开选择弹层的行为不变')
  assert.equal(submits.length, 0, '不建单')

  pick(page, { id: 7, fullName: '林一', mobilePhone: '13800138000' })
  assert.equal(page.data.participantError, '', '选好参与人即清')
})

test('结算页:所选参与人缺姓名、缺手机号或手机号格式不对,各写在该栏下方;换成完整的参与人即清', () => {
  const cases = [
    [{ id: 1, fullName: '', mobilePhone: '13800138000' }, '参与人信息需要包含姓名'],
    [{ id: 2, fullName: '林一', mobilePhone: '' }, '参与人信息需要包含手机号'],
    [{ id: 3, fullName: '林一', mobilePhone: '1380013' }, '参与人信息中的手机号码格式不正确'],
  ]
  for (const [item, message] of cases) {
    const { page, tips, scrolls, submits } = loadBaoming()
    pick(page, item)
    page.handlePayment()
    assert.deepEqual(tips, [], `${message} 不再弹提示`)
    assert.equal(page.data.participantError, message)
    assert.equal(scrolls.length, 1)
    assert.equal(submits.length, 0)
    pick(page, { id: 7, fullName: '林一', mobilePhone: '13800138000' })
    assert.equal(page.data.participantError, '')
  }
})

test('结算页:报过错再选好参与人后提交,建单载荷与直接选好完全一致', () => {
  const direct = loadBaoming()
  pick(direct.page, { id: 7, fullName: '林一', mobilePhone: '13800138000' })
  direct.page.handlePayment()
  assert.equal(direct.submits.length, 1)

  const fixed = loadBaoming()
  fixed.page.handlePayment()
  pick(fixed.page, { id: 3, fullName: '林一', mobilePhone: '1380013' })
  fixed.page.handlePayment()
  assert.equal(fixed.submits.length, 0)
  pick(fixed.page, { id: 7, fullName: '林一', mobilePhone: '13800138000' })
  fixed.page.handlePayment()
  assert.equal(fixed.submits.length, 1)
  // requestId 每次提交随机生成(防重键),只比较它存在,其余字段逐一相等
  const strip = (p) => { assert.match(String(p.requestId), /^req_/); const c = Object.assign({}, p); delete c.requestId; return c }
  assert.deepEqual(strip(fixed.submits[0]), strip(direct.submits[0]))
})

test('结算页:单独同意未勾选仍按原样弹提示(同意类不转)', () => {
  const { page, tips } = loadBaoming()
  pick(page, { id: 7, fullName: '林一', mobilePhone: '13800138000' })
  page.data.hostShareChecked = false
  page.handlePayment()
  assert.deepEqual(tips, ['请同意向主办方提供报名信息'])
  assert.equal(page.data.participantError, '')
})

test('结算页结构:参与人信息标签带红色 *,其下方是 kind="field" 的 cy-inline-error', () => {
  const wxml = fs.readFileSync(BAOMING_WXML, 'utf8')
  assert.match(wxml, /<view class="set_left">参与人信息<text class="form-required" aria-hidden="true">\*<\/text><\/view>/)
  const row = wxml.indexOf('class="address-info"')
  const err = wxml.indexOf('<cy-inline-error class="participant-err" wx:if="{{pageState === \'ready\' && participantError}}" kind="field" title="{{participantError}}" />')
  assert.ok(row > 0 && err > row, '红字在参与人信息一栏下方')
  const sheet = wxml.indexOf('<cy-sheet footer show="{{showAddressPopup}}"')
  assert.ok(err < sheet, '红字在页面上,不在选择弹层里')
})
