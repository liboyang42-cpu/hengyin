'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const test = require('node:test')
const ROOT = path.resolve(__dirname, '../..')
const SCENE = 'subpackageMember/components/cy/scene-play-activity-detail/index'
const PICKER = 'components/cy/session-picker/index'
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')
const ticket = (id, extra = {}) => ({ id, name: `票种${id}`, price: 0, remainingInventory: 2,
  startTime: '2099-11-18 10:00:00', endTime: '2099-11-18 11:30:00', ...extra })
const detail = (extra = {}) => ({ id: 42, name: '公开活动', memberId: 52, isSignUp: 0,
  startDate: '2099-11-18 10:00:00', endDate: '2099-11-18 11:30:00',
  omsTicketList: [ticket(8), ticket(9)], ...extra })

function mount(source = read(SCENE + '.js')) {
  const requests = [], navigations = [], notices = []
  const app = { globalData: { authorization: 'test-session' }, user: 46, type: 1,
    getUserID() { return this.user }, getUserType() { return this.type },
    ensureLoginFor() { return true }, getRequestErrorMessage: (_, fallback) => fallback,
    sendRequest(options) { requests.push(options) } }
  const wx = { navigateTo(options) { navigations.push(options) } }
  function component(relative, code = read(relative + '.js')) {
    let definition
    const filename = path.join(ROOT, relative + '.js')
    const localRequire = createRequire(filename)
    vm.runInNewContext(code, { getApp: () => app, wx, Component(value) { definition = value },
      module: { exports: {} }, console, setTimeout, clearTimeout,
      require(request) {
        if (request.endsWith('toast.js')) return (message) => notices.push(message)
        if (request.endsWith('page-motion.js')) return { enter() {} }
        if (request.endsWith('analytics.js')) return { track() {} }
        if (request.endsWith('modal.js') || request.endsWith('media-preview.js')) return {}
        return localRequire(request)
      } }, { filename })
    const host = { data: structuredClone(definition.data), ...definition.methods,
      setData(patch) { Object.assign(this.data, patch) }, triggerEvent() {} }
    return { host, definition }
  }
  const { host, definition } = component(SCENE, source)
  host.data.activityId = '42'
  function respond(data = detail()) { requests.at(-1).success({ code: 200, data }) }
  definition.lifetimes.attached.call(host)
  respond()
  return { host, definition, app, requests, navigations, notices, component, respond }
}

test('活动介绍只展示一个集合点，不复制主题路线或玩法；底部入口接真实选票组件', () => {
  const wxml = read(SCENE + '.wxml')
  const json = JSON.parse(read(SCENE + '.json'))
  assert.equal(json.usingComponents['cy-session-picker'], '/' + PICKER)
  assert.match(wxml, /bindtap="openSignupSheet"/)
  assert.match(wxml, /<cy-session-picker\b[^>]*show="\{\{signupSheetShow\}\}"[^>]*activities="\{\{signupActivities\}\}"[^>]*bind:close="closeSignupSheet"[^>]*bind:confirm="onSignupSheetConfirm"/)
  assert.doesNotMatch(wxml, /bindtap="goTopic"|info\.memberTemplateList|关联玩法模板|主题路线/)
  assert.match(wxml, /bindtap="openLocation"/)
  assert.match(read(SCENE + '.wxss'), /\.activity-action\s*\{[^}]*position:\s*fixed/)
})

test('多票主按钮先开底部选票；真实 picker 的确认只进入原活动结算一次', () => {
  const m = mount()
  m.host.openSignupSheet()
  assert.equal(m.host.data.signupSheetShow, true)
  assert.equal(Object.hasOwn(m.host.data, 'selectedTicketId'), false, '退出页面展示的选择不再写入data')
  assert.equal(Object.hasOwn(m.host.data, 'canConfirmSignup'), false, '票的可提交状态由真实半屏负责')
  for (const method of ['goTopic', 'goTemplateDetail', 'selectTicket']) assert.equal(m.host[method], undefined)
  assert.equal(m.navigations.length, 0)
  const p = m.component(PICKER)
  p.host.data.show = true
  p.host.data.activities = m.host.data.signupActivities
  p.host.triggerEvent = (name, value) => {
    if (name === 'confirm') m.host.onSignupSheetConfirm({ detail: value })
  }
  p.definition.observers['show, activities'].call(p.host, true, p.host.data.activities)
  assert.equal(String(m.requests.at(-1).data.id), '42')
  m.respond()
  p.host.onPickTicket({ currentTarget: { dataset: { id: 9 } } })
  p.host.onNext()
  p.host.onNext()
  assert.equal(m.navigations.length, 0, '最终权限/库存重读前不能进入结算')
  m.respond()
  assert.equal(m.navigations.length, 1)
  assert.equal(m.navigations[0].url, '/pages/activity/baoming/baoming?activityId=42&ticketId=9')
  assert.equal(m.host.data.signupSheetShow, false)
  assert.ok(m.requests.every(r => r.url === '/api/activity/info'), '介绍/选票不能创建订单或报名')
})

function assertForeignConfirmationRejected(source) {
  const m = mount(source)
  m.host.openSignupSheet()
  const reads = m.requests.length
  m.host.onSignupSheetConfirm({ detail: { activityId: 43, ticketId: 8 } })
  assert.equal(m.navigations.length, 0, '别场活动的确认不能进入本场结算')
  assert.equal(m.requests.length, reads, '别场确认不能恢复为当前活动的购票意图')
  m.host.onSignupSheetConfirm({ detail: { activityId: 42, ticketId: 999 } })
  assert.equal(m.navigations.length, 0, '未读取的票不得代入结算')
}
test('确认同时绑定真实活动与所读票种', () => assertForeignConfirmationRejected())

for (const invalidation of ['close', 'reload', 'activity', 'missing', 'identity', 'hide', 'detach']) {
  test(`选票旧回调在 ${invalidation} 后不可导航`, () => {
    const m = mount()
    m.host.openSignupSheet()
    if (invalidation === 'close') m.host.closeSignupSheet()
    if (invalidation === 'reload') m.host.reload()
    if (invalidation === 'activity') { m.host.data.activityId = '43'; m.host.load('43') }
    if (invalidation === 'missing') {
      m.host.data.activityId = ''; m.host.load('')
      assert.equal(m.host.data.signupSheetShow, false)
    }
    if (invalidation === 'identity') m.app.user = 48
    if (invalidation === 'hide') m.definition.pageLifetimes.hide.call(m.host)
    if (invalidation === 'detach') m.definition.lifetimes.detached.call(m.host)
    m.host.onSignupSheetConfirm({ detail: { activityId: 42, ticketId: 8 } })
    assert.equal(m.navigations.length, 0)
  })
}

test('游客正常登录后先重读当前身份的活动，再恢复选票；换活动不恢复旧意图', () => {
  const m = mount()
  m.app.user = 0
  let resume
  m.app.ensureLoginFor = (_, callback) => { resume = callback; return false }
  m.host.openSignupSheet()
  assert.equal(m.host.data.signupSheetShow, false)
  m.app.user = 46
  m.app.ensureLoginFor = () => true
  resume()
  assert.equal(m.host.data.state, 'loading')
  assert.equal(m.host.data.signupSheetShow, false)
  m.respond()
  assert.equal(m.host.data.signupSheetShow, true)
  assert.equal(m.navigations.length, 0)
  const other = mount()
  other.app.ensureLoginFor = (_, callback) => { resume = callback; return false }
  other.host.openSignupSheet()
  other.host.data.activityId = '43'
  resume()
  assert.equal(other.host.data.signupSheetShow, false)
})

test('真实写前登录闸：取消不续接，正常登录只重读一次并在成功后开半屏', async () => {
  const { createRequireLogin, hasSession } = require('../../utils/auth/require-login.js')
  for (const confirmed of [false, true]) {
    const m = mount(), prompts = []
    m.app.user = 0; m.app.globalData.authorization = ''
    m.app.getAuthorization = () => m.app.globalData.authorization
    m.app.firstLogin = async () => { m.app.user = 46; m.app.globalData.authorization = 'signed-in-test-session'; return { ok: true } }
    const page = {}
    const guard = createRequireLogin({ getApp: () => m.app, getPages: () => [page],
      isSinglePage: () => false, show: (options) => prompts.push(options) })
    m.app.ensureLoginFor = (reason, resume, context) => {
      if (hasSession(m.app)) return true
      guard.withLogin({ ...context, reason }, resume)
      return false
    }
    const before = m.requests.length
    m.host.openSignupSheet()
    assert.equal(prompts.length, 1)
    assert.equal(m.host.data.signupSheetShow, false)
    prompts[0].success({ confirm: confirmed })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(m.requests.length, before + (confirmed ? 1 : 0))
    if (confirmed) { m.respond(); assert.equal(m.host.data.signupSheetShow, true) }
    else assert.equal(m.host.data.signupSheetShow, false)
    assert.equal(m.navigations.length, 0)
  }
})

test('原商家/自己发布/已报名/已取消与空票闸仍拒绝打开报名', () => {
  for (const kind of ['merchant', 'owner', 'enrolled', 'cancelled', 'empty', 'gate', 'error']) {
    const m = mount()
    if (kind === 'merchant') m.app.type = 2
    if (kind === 'owner') m.host.data.info.memberId = 46
    if (kind === 'enrolled') m.host.data.info.isSignUp = 1
    if (kind === 'cancelled') m.host.data.info.cancelTime = '2099-01-01'
    if (kind === 'empty') m.host.data.noTicketText = '暂未开放票种'
    if (kind === 'gate' || kind === 'error') m.host.data.state = kind
    m.host.openSignupSheet()
    assert.equal(m.host.data.signupSheetShow, false, kind)
    assert.equal(m.navigations.length, 0, kind)
  }
})

test('最终确认复核库存与时间；未知库存保留原服务端裁决路径', () => {
  for (const extra of [{ remainingInventory: 0 }, { startTime: '2000-01-01 10:00:00' }, { selfGuided: true, signupDeadline: '2000-01-01 10:00:00' }]) {
    const m = mount()
    m.host.openSignupSheet()
    m.host.onSignupSheetConfirm({ detail: { activityId: 42, ticketId: 8 } })
    m.respond(detail({ omsTicketList: [ticket(8, extra)] }))
    assert.equal(m.navigations.length, 0)
  }
  const m = mount()
  m.host.reload(); m.respond(detail({ omsTicketList: [ticket(8, { remainingInventory: null })] }))
  m.host.openSignupSheet()
  m.host.onSignupSheetConfirm({ detail: { activityId: 42, ticketId: 8 } })
  m.respond(detail({ omsTicketList: [ticket(8, { remainingInventory: null })] }))
  assert.equal(m.navigations.length, 1)
})

test('确认后的失败/门卡/已报名/取消回包不导航；重试读取也不自动重放旧报名意图', () => {
  for (const result of [{ code: 500, msg: '读取失败' }, { code: 200, data: { gate: true, clubId: 7 } },
    { code: 200, data: detail({ isSignUp: 1 }) }, { code: 200, data: detail({ isSignUp: null }) },
    { code: 200, data: detail({ cancelTime: '2099-01-01' }) }]) {
    const m = mount()
    m.host.openSignupSheet()
    m.host.onSignupSheetConfirm({ detail: { activityId: 42, ticketId: 8 } })
    m.requests.at(-1).success(result)
    assert.equal(m.navigations.length, 0)
    m.host.retry(); m.respond()
    assert.equal(m.navigations.length, 0, '读取重试不是重试创建报名')
  }
})

test('确认读取迟到：切换身份、活动或隐藏后回包不能进入结算', () => {
  for (const kind of ['identity', 'activity', 'hide', 'detach']) {
    const m = mount()
    m.host.openSignupSheet()
    m.host.onSignupSheetConfirm({ detail: { activityId: 42, ticketId: 8 } })
    const pending = m.requests.at(-1)
    if (kind === 'identity') m.app.user = 48
    if (kind === 'activity') { m.host.data.activityId = '43'; m.host.load('43') }
    if (kind === 'hide') m.definition.pageLifetimes.hide.call(m.host)
    if (kind === 'detach') m.definition.lifetimes.detached.call(m.host)
    pending.success({ code: 200, data: detail() })
    assert.equal(m.navigations.length, 0, kind)
  }
})

test('关闭半屏不报名；结算返回继续沿原 show 生命周期重读已报名状态', () => {
  const m = mount()
  m.host.openSignupSheet(); m.host.closeSignupSheet()
  assert.equal(m.navigations.length, 0)
  m.definition.pageLifetimes.hide.call(m.host)
  m.definition.pageLifetimes.show.call(m.host)
  m.respond(detail({ isSignUp: 1 }))
  assert.equal(m.host.data.actionState, 'view')
  assert.equal(m.host.data.signupSheetShow, false)
})

test('两个真实分包宿主必须引用主包同一picker，不能借另一分包预加载', () => {
  const app = JSON.parse(read('app.json'))
  const owner = (file) => (app.subPackages || []).find(pkg => file.startsWith(pkg.root + '/'))?.root || 'MAIN'
  const hosts = ['pages/topic/index/index.json', SCENE + '.json']
  assert.notEqual(owner(hosts[0]), owner(hosts[1]))
  function check(paths) {
    for (const target of paths) assert.equal(owner(target.replace(/^\//, '') + '.json'), 'MAIN', '共享picker必须属于主包')
    assert.equal(new Set(paths).size, 1)
  }
  const paths = hosts.map(file => JSON.parse(read(file)).usingComponents['cy-session-picker'])
  check(paths)
  assert.throws(() => check([paths[0], '/pages/topic/components/cy/session-picker/index']), /共享picker必须属于主包/)
})

test('活动hero首屏高度扣除实际导航和共享footer，长文只增长不裁切', () => {
  const css = read(SCENE + '.wxss')
  const copy = css.match(/\.activity-hero__copy\s*\{([^}]+)\}/)[1]
  const min = copy.match(/min-height:\s*([^;]+);/)[1]
  assert.match(read('pages/activity/detail/index.wxml'), /--activity-nav-h:\s*\{\{statusBarHeight \+ navBarHeight\}\}px/)
  assert.doesNotMatch(copy, /(?:^|;)\s*(?:height|max-height|overflow):/)
  assert.match(css, /padding-bottom:\s*var\(--cy-comp-footer-h\)/)
  function height(viewport, nav, footer) {
    const expression = min.replace('100vh', String(viewport)).replace('var(--activity-nav-h, 0px)', String(nav)).replace('var(--cy-comp-footer-h)', String(footer))
    assert.match(expression, /^calc\([\d .+\-]+\)$/)
    return vm.runInNewContext(expression.slice(5, -1))
  }
  for (const [viewport, nav, footer] of [[844, 88, 94], [568, 64, 60]]) {
    assert.ok(height(viewport, nav, footer) > 0)
    assert.ok(nav + height(viewport, nav, footer) <= viewport - footer)
  }
})

test('结算只接显式真实票id，缺失/未知票不能借用旧页面选择', () => {
  const m = mount()
  m.host.openSignup()
  m.host.openSignup(999)
  assert.equal(m.navigations.length, 0)
  m.host.openSignup(9)
  assert.equal(m.navigations.length, 1)
  assert.equal(m.navigations[0].url, '/pages/activity/baoming/baoming?activityId=42&ticketId=9')
})

test('负控：删除活动确认围栏时，真实组件回归必须判红', () => {
  const source = read(SCENE + '.js')
  const needle = "String(detail.activityId) !== context.id"
  assert.ok(source.includes(needle), '负控必须命中实际边界')
  assert.throws(() => assertForeignConfirmationRejected(source.replace(needle, 'false')), assert.AssertionError)
})
