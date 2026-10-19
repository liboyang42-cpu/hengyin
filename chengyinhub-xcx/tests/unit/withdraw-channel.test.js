'use strict'
// F0422-F0425 提现渠道只读卡:渠道 = 平台客服人工处理;可提现额只认服务端 available;
// 授权版本与原申请(脱敏收款账户、申请时固定的授权版本)可查;不提供任何提现写入口。

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { summaryView } = require('../../components/cy/withdraw-channel/view-model.js')

const DIR = path.join(__dirname, '../../components/cy/withdraw-channel')
const componentPath = path.join(DIR, 'index.js')
const base = {
  channel: 'CUSTOMER_SERVICE', legacyFormOpen: false, balance: 100, frozen: 50.5, available: 49.5,
  frozenNote: '可提现余额不足(其中 30.00 元为拓展赏金,入账 48 小时后可提现)',
  consentDocument: 'bank_account_collection', consentVersion: 'v1.0', consentAgreed: false,
  latest: { id: 91, status: 0, withdrawalAmount: 25, bankAccountMask: '6222********1234',
    consentVersion: 'v0.9', createTime: '2026-10-12 10:20:30' },
}

test('渠道标为平台客服人工处理,旧表单不开放;可提现额是 available 不是总余额', () => {
  const view = summaryView({ code: 200, data: base })
  assert.equal(view.channelText, '平台客服人工处理')
  assert.match(view.channelSub, /暂不开放自助提现表单/)
  assert.equal(view.legacyFormOpen, false)
  assert.equal(view.availableText, '¥49.50')
  assert.equal(view.frozenText, '另有 ¥50.50 暂不可提现')
  assert.match(view.frozenNote, /拓展赏金/)
  assert.equal(view.consentText, '银行卡信息授权文本 v1.0 · 未同意')
})

test('原申请回显脱敏账户、状态与申请时固定的授权版本;旧单未绑定版本如实标出', () => {
  const view = summaryView({ code: 200, data: base })
  assert.deepEqual(view.latest, { id: 91, amountText: '¥25.00', statusText: '待审核', statusVariant: 'warn',
    accountText: '6222********1234', consentText: '申请时授权文本 v0.9', timeText: '2026-10-12 10:20' })
  const legacy = summaryView({ code: 200, data: { ...base, latest: { ...base.latest, consentVersion: null, status: 3 } } })
  assert.equal(legacy.latest.consentText, '申请时未绑定授权版本')
  assert.equal(legacy.latest.statusText, '已打款')
  const unknown = summaryView({ code: 200, data: { ...base, latest: { ...base.latest, status: 9 } } })
  assert.equal(unknown.latest.statusText, '状态待确认')
  assert.equal(summaryView({ code: 200, data: { ...base, latest: null } }).latest, null)
})

test('未知渠道、金额坏值、畸形原申请与非 200 整体失败,不摆 ¥0.00', () => {
  for (const patch of [{ channel: 'BANK_FORM' }, { channel: undefined }, { available: null }, { available: -1 },
    { available: '1.001' }, { frozen: 'x' }, { latest: { id: 0, withdrawalAmount: 1 } },
    { latest: { id: 5, withdrawalAmount: -1 } }]) {
    assert.equal(summaryView({ code: 200, data: { ...base, ...patch } }), null, JSON.stringify(patch))
  }
  for (const response of [null, { code: 500, data: base }, { code: 200, data: null }, { code: 200, data: [] }]) {
    assert.equal(summaryView(response), null)
  }
  const free = summaryView({ code: 200, data: { ...base, frozen: 0, frozenNote: 'x' } })
  assert.equal(free.frozenText, '')
  assert.equal(free.frozenNote, '')
})

function mount() {
  let config
  let memberId = 7
  const requests = []
  global.getApp = () => ({ getUserID: () => memberId, sendRequest: (r) => requests.push(r) })
  global.Component = (c) => { config = c }
  delete require.cache[require.resolve(componentPath)]
  require(componentPath)
  const vm = { ...config.methods, data: JSON.parse(JSON.stringify(config.data)),
    setData(patch) { Object.assign(this.data, patch) } }
  config.lifetimes.attached.call(vm)
  return { vm, config, requests, switchMember(id) { memberId = id } }
}

test('只读本人摘要接口,不带客户端指定会员;切号/卸载后的旧响应不落地', () => {
  const { vm, config, requests, switchMember } = mount()
  assert.equal(requests[0].url, '/api/withdrawal/summary')
  assert.equal(requests[0].data, undefined, '会员只取登录态,不由前端传 member_id')
  requests[0].success({ code: 200, data: base })
  assert.equal(vm.data.state, 'ready')
  vm.load(); switchMember(8); requests[1].success({ code: 200, data: base })
  assert.equal(vm.data.view, null)
  switchMember(7); vm.load(); config.lifetimes.detached.call(vm); requests[2].success({ code: 200, data: base })
  assert.equal(vm.data.view, null)
  vm.load(); requests[3].fail({})
  assert.equal(vm.data.state, 'loading', '卸载后不再写状态')
})

test('失败态可重试,卡片不含任何提现写入口', () => {
  const { vm, requests } = mount()
  requests[0].successStatusAbnormal({ code: 401 })
  assert.equal(vm.data.state, 'error')
  const wxml = fs.readFileSync(path.join(DIR, 'index.wxml'), 'utf8')
  assert.match(wxml, /<cy-inline-error[^>]*bind:action="load"/)
  assert.doesNotMatch(wxml, /bindtap=|open-type=|<input/)
  const js = fs.readFileSync(componentPath, 'utf8')
  assert.doesNotMatch(js, /withdrawal\/create|preflight|recordConsent/)
  const host = fs.readFileSync(path.join(__dirname, '../../components/cy/scene-asset-earnings/index.wxml'), 'utf8')
  assert.match(host, /<cy-withdraw-channel id="withdrawChannel" \/>/)
})
