'use strict'
// BL-219:纠错半屏关掉重开或重试后,先发后到的旧类型回包不冲掉正在填的表单。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const FILE = path.join(__dirname, '..', '..', 'subpackageMember/components/cy/merchant-correction-sheet/index.js')
const TAX = { code: 200, data: { contributionTypes: ['ISSUE_REPORT'], issueTypes: ['CLOSED', 'ADDRESS'] } }

function boot() {
  const reqs = []
  const events = []
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => { reqs.push(o); return { abort() {} } } })
  global.wx = { navigateTo: () => {} }
  let def
  global.Component = (c) => { def = c }
  delete require.cache[require.resolve(FILE)]
  require(FILE)
  const comp = Object.assign({}, def.methods)
  comp.data = JSON.parse(JSON.stringify(def.data))
  comp.properties = { show: true, merchantId: '12' }
  comp.setData = function (patch) { Object.assign(this.data, patch) }
  comp.triggerEvent = (name) => events.push(name)
  return { comp, reqs, events }
}

function reopenToForm() {
  const ctx = boot()
  const { comp, reqs } = ctx
  comp.reset()
  comp.close()
  comp.reset()
  assert.equal(reqs.length, 2)
  reqs[1].success(TAX)
  comp.onIssueSelect({ detail: { index: 1 } })
  comp.onDescriptionInput({ detail: { value: '门店已搬到隔壁街' } })
  assert.equal(comp.data.stage, 'form')
  return ctx
}

test('关掉重开后第一次回包成功也不把 form 冲回 pick', () => {
  const { comp, reqs, events } = reopenToForm()
  const label = comp.data.issueLabel
  assert.ok(label)
  assert.deepEqual(events, ['close'])
  reqs[0].success(TAX)
  assert.equal(comp.data.stage, 'form')
  assert.equal(comp.data.issueLabel, label)
  assert.equal(comp.data.description, '门店已搬到隔壁街')
})

test('关掉重开后第一次回包 fail / 状态异常也不冲成 loadfail', () => {
  const { comp, reqs } = reopenToForm()
  const label = comp.data.issueLabel
  reqs[0].fail()
  reqs[0].successStatusAbnormal()
  assert.equal(comp.data.stage, 'form')
  assert.equal(comp.data.issueLabel, label)
})

test('loadfail 态重试后,旧回包不覆盖新结果', () => {
  const { comp, reqs } = boot()
  comp.reset()
  comp.setData({ stage: 'loadfail' })
  comp.loadTaxonomy()
  reqs[1].success(TAX)
  assert.equal(comp.data.stage, 'pick')
  reqs[0].fail()
  assert.equal(comp.data.stage, 'pick')
  reqs[0].success({ code: 500 })
  assert.equal(comp.data.stage, 'pick')
})

test('最新请求的回包照常生效', () => {
  const { comp, reqs } = boot()
  comp.reset()
  reqs[0].fail()
  assert.equal(comp.data.stage, 'loadfail')
  comp.loadTaxonomy()
  reqs[1].success(TAX)
  assert.equal(comp.data.stage, 'pick')
  assert.equal(comp.data.issueLabels.length, 2)
})

test('提交中不放行关闭,不丢弃在途回包', () => {
  const { comp, reqs, events } = boot()
  comp.reset()
  comp._busy = true
  comp.close()
  assert.deepEqual(events, [])
  comp._busy = false
  reqs[0].success(TAX)
  assert.equal(comp.data.stage, 'pick')
})
