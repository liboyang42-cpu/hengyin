const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const workspace = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/workspace.vue'),
  'utf8'
)

function loadComponent() {
  const script = workspace.match(/<script>([\s\S]*?)<\/script>/)[1]
  const body = script
    .replace(/^import .*$/gm, '')
    .replace(/components:\s*\{[^}]*\},/, 'components: {},')
    .replace(/export default/, 'return ')
  return new Function(body)()
}

test('工作台状态轴遇到未知数据时不得伪装为待调配、已结束或零金额', () => {
  const component = loadComponent()
  const methods = component.methods
  const termsVm = { money: methods.money, isKnownNumeric: methods.isKnownNumeric }

  assert.equal(methods.candidateReviewLabel(null), '审核状态待确认')
  assert.equal(methods.candidateReviewLabel(9), '审核状态异常')
  assert.equal(methods.candidateReviewLabel(3), '调配驳回')
  assert.equal(methods.awardLabel(undefined), '调配状态待确认')
  assert.equal(methods.awardLabel(9), '调配状态异常')
  assert.equal(methods.leadStatusLabel(null), '状态待确认')
  assert.equal(methods.cooperationStatusLabel(0), '生效')
  assert.equal(methods.cooperationStatusLabel(1), '已结束')
  assert.equal(methods.cooperationStatusLabel(9), '状态待确认')
  assert.equal(methods.settlementStatusLabel(null), '状态待确认')
  assert.equal(methods.counterpartyLabel({ counterpartyType: 'partner', counterpartyId: 88 }), '未知合作方 #88')
  assert.equal(methods.termsLabel.call(termsVm, { shareMode: 1, shareRate: null }), '条款待确认')
  assert.equal(methods.termsLabel.call(termsVm, { shareMode: 2, fixedFee: undefined }), '条款待确认')
  assert.equal(methods.money(null), '待核验')
  assert.equal(methods.money('  '), '待核验')
  assert.equal(methods.money(true), '待核验')
  assert.equal(methods.money(0), '¥0.00')
  assert.equal(methods.money('18.5'), '¥18.50')
  assert.equal(methods.ticketInventoryLabel({ totalInventory: null, remainingInventory: null }), '不限量')
  assert.equal(methods.ticketInventoryLabel({ totalInventory: 10, remainingInventory: 7 }), '7 / 10')
  assert.equal(component.computed.clubFulfillmentStateLabel.call({
    clubFulfillment: { state: 'NOT_REQUIRED' }
  }), '不需要俱乐部承接')
  assert.equal(methods.runBlockerLabel('SELF_PLAY_PASS_CONFIGURATION_MISSING'), '自玩通行证价格待配置')
  assert.equal(methods.runBlockerLabel('LIFECYCLE_TERMINAL'), '主题已结算或关闭，不能开跑')

  assert.match(workspace, /candidateReviewLabel\(row\.status\)/)
  assert.match(workspace, /candidateReviewTone\(row\.status\)/)
  assert.match(workspace, /v-if="canOpenTopicEditor"[\s\S]*?内容勘误/)
  assert.match(workspace, /awardLabel\(row\.auditStatus\)/)
  assert.match(workspace, /leadStatusLabel\(row\.status\)/)
  assert.match(workspace, /cooperationStatusLabel\(row\.status\)/)
  assert.match(workspace, /settlementStatusLabel\(row\.status\)/)
  assert.doesNotMatch(workspace, /payeeLabel\(|row\.payeeId|row\.amount/,
    '主题查询工作台不得展示结算收款人标识或金额')
  assert.match(workspace, /operation\.editPolicy !== 'LOCKED'/,
    '普通编辑入口必须先遵守后端返回的生命周期边界')
  assert.match(workspace, /if \(!this\.canOpenTopicEditor\) return/,
    '即使直接调用前端方法，也不能绕过锁定态的导航边界')
  assert.match(workspace, /ticketInventoryLabel\(row\)/,
    '无限库存须明确显示，不能渲染成 null / null')
})

test('工作台按后端 capability 合同区分未接通、无权限与读取失败', () => {
  const component = loadComponent()
  const vm = {
    capabilities: {
      candidates: { state: 'AVAILABLE', reasonCode: 'MERCHANT_CANDIDATES_PROJECTED', asOf: '2026-07-22 14:00:00' },
      cooperation: { state: 'NOT_INTEGRATED', reasonCode: 'GATE0_COOPERATION_EVIDENCE_MISSING' },
      settlement: { state: 'NOT_GRANTED', reasonCode: 'SETTLEMENT_PERMISSION_DENIED' }
    },
    axisCapability(axis) {
      return component.methods.axisCapability.call(this, axis)
    }
  }

  assert.equal(component.methods.axisCapability.call(vm, 'missing').state, 'LOAD_FAILED')
  assert.equal(component.methods.axisCapability.call(vm, 'missing').reasonCode, 'CAPABILITY_CONTRACT_MISSING')
  assert.equal(component.methods.isAxisAvailable.call(vm, 'candidates'), true)
  assert.equal(component.methods.isAxisAvailable.call(vm, 'cooperation'), false)
  assert.equal(component.methods.axisCapabilityLabel.call(vm, 'cooperation'), '暂未接入')
  assert.equal(component.methods.axisCapabilityLabel.call(vm, 'settlement'), '无查看权限')
  assert.equal(component.methods.axisCapabilityLabel.call(vm, 'missing'), '读取失败')
  assert.match(component.methods.axisCapabilityDescription.call(vm, 'cooperation'), /数据源尚未接入/)
  assert.match(component.methods.axisCapabilityDescription.call(vm, 'settlement'), /无查看权限/)
  assert.match(workspace, /isAxisAvailable\('cooperation'\)/)
  assert.match(workspace, /axisCapabilityDescription\('settlement'\)/)
  assert.doesNotMatch(workspace, /cooperationConnected|settlementConnected/)
})
