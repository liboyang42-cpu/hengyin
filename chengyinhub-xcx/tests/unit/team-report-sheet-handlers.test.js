'use strict'
// BL-54 举报处理器单测:真实调用 pages/team/report-sheet 的方法(不改 UI)。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const MODULE = path.resolve(__dirname, '../../pages/team/report-sheet/index.js')

function load(teamId) {
  const sandbox = { requests: [], events: [] }
  global.getApp = () => ({ sendRequest: (options) => sandbox.requests.push(options) })
  global.Component = (config) => { sandbox.raw = config; sandbox.def = flattenComponentToPage(config) }
  global.wx = {}
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const vm = Object.assign({}, sandbox.def, {
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name) { sandbox.events.push(name) },
  })
  vm.data = JSON.parse(JSON.stringify(sandbox.def.data))
  vm.properties = { show: true, teamId: teamId == null ? '42' : teamId }
  return { vm, sandbox }
}

const pick = (vm, index) => vm.onSelect({ detail: { index } })

test('选原因 → POST /api/team/report,body 为 {teamId:number, reason:原因文字}', () => {
  const { vm, sandbox } = load('42')
  pick(vm, 2)
  assert.equal(sandbox.requests.length, 1)
  const req = sandbox.requests[0]
  assert.equal(req.url, '/api/team/report')
  assert.equal(req.method, 'POST')
  assert.deepEqual(JSON.parse(req.data), { teamId: 42, reason: vm.data.reasons[2] })
  assert.equal(vm.data.stage, 'busy')
})

test('teamId 非法或原因越界:不发请求', () => {
  for (const bad of ['', '0', '-3', 'abc', '1.5']) {
    const { vm, sandbox } = load(bad)
    pick(vm, 0)
    assert.equal(sandbox.requests.length, 0, 'teamId=' + bad)
    assert.equal(vm.data.stage, 'pick')
  }
  const { vm, sandbox } = load('42')
  pick(vm, 99)
  assert.equal(sandbox.requests.length, 0)
})

test('在途时重复点选不再发请求,也不能关闭', () => {
  const { vm, sandbox } = load('42')
  pick(vm, 0)
  pick(vm, 1)
  assert.equal(sandbox.requests.length, 1)
  vm.onPickCancel()
  vm.onResultClose()
  assert.deepEqual(sandbox.events, [])
})

test('成功:回执显示后端 taskId;缺 taskId 时为空串,不编号', () => {
  const a = load('42')
  pick(a.vm, 0)
  a.sandbox.requests[0].success({ code: 200, data: { taskId: 9001 } })
  assert.equal(a.vm.data.stage, 'ok')
  assert.equal(a.vm.data.taskId, '9001')
  a.vm.onResultClose()
  assert.deepEqual(a.sandbox.events, ['close'])

  const b = load('42')
  pick(b.vm, 0)
  b.sandbox.requests[0].success({ code: '200', data: {} })
  assert.equal(b.vm.data.stage, 'ok')
  assert.equal(b.vm.data.taskId, '')
})

test('业务失败:显示后端 msg,无 msg 用兜底;失败后可重试回到选原因', () => {
  const a = load('42')
  pick(a.vm, 0)
  a.sandbox.requests[0].success({ code: 500, msg: '不能举报自己的队伍' })
  assert.equal(a.vm.data.stage, 'fail')
  assert.equal(a.vm.data.failedText, '不能举报自己的队伍')
  a.vm.onRetry()
  assert.equal(a.vm.data.stage, 'pick')
  assert.equal(a.vm.data.failedText, '')
  pick(a.vm, 1)
  assert.equal(a.sandbox.requests.length, 2, '失败后解锁,可再次提交')

  const b = load('42')
  pick(b.vm, 0)
  b.sandbox.requests[0].success({ code: 500 })
  assert.equal(b.vm.data.failedText, '举报没有提交成功，请稍后再试')
})

test('网络失败 / HTTP 异常:不显示成功,给可重试文案并解锁', () => {
  for (const hook of ['fail', 'successStatusAbnormal']) {
    const { vm, sandbox } = load('42')
    pick(vm, 0)
    sandbox.requests[0][hook]({})
    assert.equal(vm.data.stage, 'fail', hook)
    assert.match(vm.data.failedText, /网络不太稳定/)
    vm.onResultClose()
    assert.deepEqual(sandbox.events, ['close'])
  }
})

test('重新打开半屏:状态复位到选原因', () => {
  const { vm, sandbox } = load('42')
  pick(vm, 0)
  sandbox.requests[0].success({ code: 200, data: { taskId: 1 } })
  sandbox.raw.observers.show.call(vm, true)
  assert.equal(vm.data.stage, 'pick')
  assert.equal(vm.data.taskId, '')
})
