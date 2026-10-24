'use strict'

// CU-C-131(2026-09-24 走查)· 报名失败却提示「操作成功」。
//
// 俱乐部月历 → 合作主题 → 立即报名,选票种弹层同屏出现「票种没读出来」+「操作成功」,
// 只能重试且重试永远同一个矛盾提示。根因两条:
//   ① 后端只在真有票时才下发 omsTicketList(ApiActivityController.java:995 的 size()>0 分支),
//      所以成功体里没这个字段是**这一场没放票**,不是读取失败;
//   ② 组件把整个 res 交给 getRequestErrorMessage,而 safeUserMessage 的规则表不拦成功回执
//      「操作成功」⇒ 成功文案被当成错误原因渲染进 cy-error 的 sub。
// 现在:成功体缺字段 → empty 态;只有业务失败体才进 error 态并回显后端原因。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const fs = require('node:fs')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const COMPONENT = 'pages/topic/components/cy/session-picker/index.js'
const WXML = 'pages/topic/components/cy/session-picker/index.wxml'
const safeUserMessage = require(path.join(ROOT, 'utils/transport/safe-user-message.js')).safeUserMessage

/** 真实消毒口径:错误原因一律过 safeUserMessage,和 app.getRequestErrorMessage 一致。 */
function loadComponent(source) {
  const requests = []
  let definition
  const file = path.join(ROOT, COMPONENT)
  vm.runInNewContext(source == null ? fs.readFileSync(file, 'utf8') : source, {
    Component: (config) => { definition = config },
    getApp: () => ({
      sendRequest: (options) => { requests.push(options); return { abort() {} } },
      getRequestErrorMessage: (res, fallback) => safeUserMessage(res, fallback || '请求失败，请稍后重试'),
    }),
    console,
    require: (id) => require(path.resolve(path.dirname(file), id)),
  })
  const vmInstance = Object.assign({}, definition.methods, {
    data: JSON.parse(JSON.stringify(definition.data || {})),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
    triggerEvent() {},
  })
  return { vm: vmInstance, requests, observe: (show, activities) => definition.observers['show, activities'].call(vmInstance, show, activities), detach: () => definition.lifetimes.detached.call(vmInstance) }
}

const TICKETS = { omsTicketList: [{ id: 5, name: '单人票', price: 88 }] }

test('同场次入会后重开刷新资格，旧gate及失败不得覆盖新票', () => {
  const { vm, requests, observe } = loadComponent()
  const activities = [{ id: 9, name: '单场' }]
  observe(true, activities)
  requests[0].success({ code: 200, data: { gate: true, clubId: 7001 } })
  assert.equal(vm.data.ticketsState, 'gate')
  observe(false, activities)
  observe(true, activities)
  assert.equal(requests.length, 2, '同ID重开仍须重新读取加入资格')
  assert.equal(vm.data.pickedTicketId, 0)
  assert.equal(vm.data.ticketsState, 'loading')
  requests[1].success({ code: 200, data: TICKETS })
  requests[0].success({ code: 200, data: { gate: true, clubId: 7001 } })
  requests[0].fail({ msg: '旧失败' })
  assert.equal(vm.data.ticketsState, 'ready')
  assert.deepEqual(vm.data.tickets.map(t => t.id), [5])
  assert.equal(vm.data.gateClubId, 0)
})

test('多场重开保留场次并重读库存，可见期间不重置选票，移除场次清选择', () => {
  const { vm, requests, observe } = loadComponent()
  const activities = [{ id: 9 }, { id: 10 }]
  observe(true, activities)
  assert.equal(requests.length, 0, '首次多场不得代用户预选')
  vm.onPickDay({ currentTarget: { dataset: { id: 10 } } })
  requests[0].success({ code: 200, data: TICKETS })
  vm.onPickTicket({ currentTarget: { dataset: { id: 5 } } })
  observe(true, activities)
  assert.equal(requests.length, 1)
  assert.equal(vm.data.pickedTicketId, 5)
  observe(false, activities)
  observe(true, activities)
  assert.equal(requests.length, 2)
  assert.equal(vm.data.pickedActivityId, 10)
  assert.equal(vm.data.pickedTicketId, 0)
  assert.equal(vm.data.totalText, '')
  requests[1].success({ code: 200, data: { omsTicketList: [{ id: 5, name: '售罄', price: 0, remainingInventory: 0 }] } })
  vm.onPickTicket({ currentTarget: { dataset: { id: 5 } } })
  assert.equal(vm.data.pickedTicketId, 0)
  observe(true, [{ id: 11 }, { id: 12 }])
  assert.equal(vm.data.pickedActivityId, 0)
  assert.equal(vm.data.ticketsState, 'idle')
  requests[1].success({ code: 200, data: TICKETS })
  assert.equal(vm.data.ticketsState, 'idle')
})

test('关闭期间的回包失效，重试同场次只接受最新请求', () => {
  const { vm, requests, observe } = loadComponent()
  observe(true, [{ id: 9 }])
  observe(false, [{ id: 9 }])
  requests[0].success({ code: 200, data: TICKETS })
  assert.equal(vm.data.ticketsState, 'loading')
  observe(true, [{ id: 9 }])
  vm.retryTickets()
  requests[2].success({ code: 200, data: TICKETS })
  requests[1].fail({ msg: '旧失败' })
  assert.equal(vm.data.ticketsState, 'ready')
})

test('组件卸载后旧请求不再写状态', () => {
  const { vm, requests, detach } = loadComponent()
  vm.pickActivity(9)
  detach()
  requests[0].success({ code: 200, data: TICKETS })
  requests[0].fail({ msg: '旧失败' })
  assert.equal(vm.data.ticketsState, 'loading')
})

test('成功体缺 omsTicketList 是「没放票」的空态,不是读取失败(CU-C-131 复现)', () => {
  const { vm, requests } = loadComponent()
  vm.pickActivity(9)
  requests[0].success({ code: 200, msg: '操作成功', data: { id: 9, name: '夜行' } })

  assert.equal(vm.data.ticketsState, 'empty')
  assert.doesNotMatch(vm.data.ticketsError, /操作成功/, '成功回执不得当错误原因展示')
  assert.equal(vm.data.ticketsError, '')
  assert.equal(vm.data.pickedTicketId, 0, '没有票就不该有可选中的票')
})

test('票种为空数组同样是空态(已有的分支不许被改坏)', () => {
  const { vm, requests } = loadComponent()
  vm.pickActivity(9)
  requests[0].success({ code: 200, msg: '操作成功', data: { omsTicketList: [] } })
  assert.equal(vm.data.ticketsState, 'empty')
})

test('业务失败体才进 error 态,并回显消毒后的原因', () => {
  const { vm, requests } = loadComponent()
  vm.pickActivity(9)
  requests[0].success({ code: 500, msg: '活动不存在' })
  assert.equal(vm.data.ticketsState, 'error')
  assert.equal(vm.data.ticketsError, '活动不存在')
})

test('有票时正常进 ready', () => {
  const { vm, requests } = loadComponent()
  vm.pickActivity(9)
  requests[0].success({ code: 200, msg: '操作成功', data: TICKETS })
  assert.equal(vm.data.ticketsState, 'ready')
  assert.deepEqual(vm.data.tickets.map((t) => t.id), [5])
})

test('单场次空态不劝人「换一个日期」(只有一场可选)', () => {
  const wxml = fs.readFileSync(path.join(ROOT, WXML), 'utf8')
  const emptyBlock = /ticketsState === 'empty'[\s\S]*?\/>/.exec(wxml)
  assert.ok(emptyBlock, 'cy-empty 空态分支还在')
  assert.match(emptyBlock[0], /singleDay/, '单场次要有自己的下一步说法')
})

test('负控:撤掉修复(成功体也走 error+回显 msg)时,复现用例必须真红', () => {
  const current = fs.readFileSync(path.join(ROOT, COMPONENT), 'utf8')
  const regressed = current.replace(
    /          if \(!rows && d\) \{[\s\S]*?\n          \}\n/,
    ''
  )
  assert.notEqual(regressed, current, '负控锚点失效:empty 分支已改名，扫描口径需同步')
  const { vm, requests } = loadComponent(regressed)
  vm.pickActivity(9)
  requests[0].success({ code: 200, msg: '操作成功', data: { id: 9, name: '夜行' } })
  assert.throws(() => assert.equal(vm.data.ticketsState, 'empty'), assert.AssertionError)
  assert.equal(vm.data.ticketsState, 'error')
  assert.equal(vm.data.ticketsError, '操作成功', '这就是走查看到的矛盾提示')
})


test('主题选票拒绝已开始与已结束票，下一步重新检查时间', () => {
  const { vm, requests } = loadComponent()
  const confirmed = []
  vm.triggerEvent = (name, data) => confirmed.push({ name, data })
  vm.pickActivity(9)
  requests[0].success({ code: 200, data: { omsTicketList: [
    { id: 1, name: '已开始', startTime: '2020-01-01 10:00:00', endTime: '2999-01-01 10:00:00' },
    { id: 2, name: '已结束', startTime: '2020-01-01 10:00:00', endTime: '2020-01-02 10:00:00' },
    { id: 3, name: '未来场次', startTime: '2999-01-01 10:00:00' },
  ] } })
  assert.deepEqual(vm.data.tickets.map(t => t.windowText), ['场次已开始', '场次已结束', ''])
  vm.onPickTicket({ currentTarget: { dataset: { id: 1 } } })
  assert.equal(vm.data.pickedTicketId, 0)
  vm.onPickTicket({ currentTarget: { dataset: { id: 3 } } })
  vm.onNext()
  assert.equal(confirmed.length, 1)
  vm.data.tickets[2].startTime = '2020-01-01 10:00:00'
  vm.onNext()
  assert.equal(confirmed.length, 1)
  assert.equal(vm.data.pickedTicketId, 0)
})


test('same-day sessions expose distinct server names and Beijing assembly times', () => {
  const { vm, observe } = loadComponent();
  observe(true, [{ id: 21, name: '自办路线', startDate: '2026-10-14 09:00:00' },
    { id: 20, name: '俱乐部场次', startDate: '2026-10-14T11:00:00Z' }]);
  assert.equal(vm.data.days[0].detailText, '09:00 · 自办路线');
  assert.equal(vm.data.days[1].detailText, '19:00 · 俱乐部场次');
  assert.equal(vm.data.days[0].day, vm.data.days[1].day);
});
