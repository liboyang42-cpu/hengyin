/**
 * 退款模块 v5 回归: 枚举只接受合同规定的标量键, 畸形形状必须失败关闭。
 *
 * v4 的 String()/Number() 宽转换会把 JSON 畸形值当正式状态: status:[1]→已通过(绿)、
 * payoutStatus:true→原路渠道成功(绿)、sourceType:['registration']→报名订单、
 * method:['offline_transfer']→线下转账、案件状态数组→正式标签。数组/布尔/对象/小数
 * 一律不得进入已知枚举; 兼容精确整数字符串时显式校验。真实模板详情头也必须一起失败关闭。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const Vue = require(path.join(uiRoot, 'node_modules/vue'))
const compiler = require(path.join(uiRoot, 'node_modules/vue-template-compiler'))

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function load(relativePath) {
  const source = read(relativePath)
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s+\{([^}]*)\}\s+from\s+['"][^'"]+['"];?\s*$/gm, (_, names) => 'const {' + names + '}=__deps')
    .replace(/^import\s+(\w+)\s+from\s+['"][^'"]+['"];?\s*$/gm, 'const $1=__deps.$1')
    .replace(/export\s+default\s*/, 'return ')
  const deps = new Proxy({}, { get: () => () => Promise.resolve({ rows: [], total: 0, data: {} }) })
  return { source, component: new Function('__deps', script)(deps) }
}

test('v5 畸形形状(数组/布尔/对象/小数)不得进入退款已知枚举', () => {
  const m = load('src/views/business/finance/refund/index.vue').component.methods

  assert.equal(m.sourceLabel(['registration']), '未知来源')
  assert.equal(m.sourceLabel(true), '未知来源')
  assert.equal(m.sourceLabel({ registration: 1 }), '未知来源')
  assert.equal(m.reviewLabel([1]), '未知状态')
  assert.equal(m.reviewLabel(true), '未知状态')
  assert.equal(m.reviewLabel(1.5), '未知状态')
  assert.equal(m.reviewLabel('1.0'), '未知状态')
  assert.equal(m.reviewTone([1]), 'neutral')
  assert.equal(m.payoutLabel(true, [1]), '待确认')
  assert.equal(m.payoutLabel([1], [1]), '待确认')
  assert.equal(m.payoutLabel(1.5, 1), '待确认')
  assert.equal(m.payoutTone(true, [1]), 'neutral')
  assert.equal(m.payoutTone([6], 1), 'neutral')
  assert.equal(m.payoutConflict({ status: [1], payoutStatus: [1] }), '')

  // 合法标量仍照常映射(数字与精确整数字符串)
  assert.equal(m.sourceLabel('registration'), '报名订单')
  assert.equal(m.reviewLabel(1), '已通过')
  assert.equal(m.reviewLabel('1'), '已通过')
  assert.equal(m.reviewTone(2), 'danger')
  assert.equal(m.payoutLabel(1, 1), '原路渠道成功')
  assert.equal(m.payoutLabel('5', '1'), '待派发微信')
  assert.equal(m.payoutTone(1, 1), 'success')
})

test('v5 人工兜底队列与活动取消案件同样失败关闭', () => {
  const manual = load('src/views/business/finance/refund/ManualPayoutQueue.vue').component.methods
  assert.equal(manual.methodLabel(['offline_transfer']), '—')
  assert.equal(manual.methodLabel(true), '—')
  assert.equal(manual.methodLabel('offline_transfer'), '线下转账')
  assert.equal(manual.payoutLabel([2]), '状态已变化')
  assert.equal(manual.payoutLabel(true), '状态已变化')
  assert.equal(manual.payoutLabel(2), '待人工处理')
  assert.equal(manual.payoutTone([3]), 'neutral')
  assert.equal(manual.payoutTone(3), 'warning')

  const activity = load('src/views/business/finance/refund/ActivityCancelRefundCases.vue').component.methods
  assert.equal(activity.statusTone(['MANUAL_REFUND_REQUIRED']), 'neutral')
  assert.equal(activity.statusTone(true), 'neutral')
  assert.equal(activity.statusTone('MANUAL_REFUND_REQUIRED'), 'danger')
})

test('v5 真实 Vue 模板详情头对畸形形状也失败关闭', () => {
  const loaded = load('src/views/business/finance/refund/index.vue')
  const template = loaded.source.slice(loaded.source.indexOf('<template>') + 10, loaded.source.lastIndexOf('</template>'))
  const compiled = compiler.compileToFunctions(template)
  const row = { id: 9, refundNo: 'R9', status: [1], payoutStatus: true, sourceType: ['registration'], sourceId: 8, refundAmount: 25 }
  const vm = new Vue({
    data: Object.assign(loaded.component.data(), { isManualQueue: false, isActivityCancelQueue: false, detailOpen: true, detailRow: row, refundList: [row], loading: false }),
    methods: loaded.component.methods,
    computed: loaded.component.computed,
    render: compiled.render,
    staticRenderFns: compiled.staticRenderFns
  })
  const nodes = []
  ;(function walk(v) {
    if (!v) return
    nodes.push(v)
    for (const c of v.children || []) walk(c)
    if (v.componentOptions) for (const c of v.componentOptions.children || []) walk(c)
  })(vm._render())
  const header = nodes.find(v => v.tag === 'cy-entity-header')
  assert.ok(header, '详情头必须渲染')
  assert.equal(header.data.attrs.facts.find(x => x.label === '审核状态').value, '未知状态')
  assert.deepEqual(header.data.attrs.status, { label: '待确认', tone: 'neutral' })
})
