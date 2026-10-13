const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8')

test('审核工作台可筛选商家城市节点和节点模板', () => {
  assert.match(source, /\{ label: '商家城市节点', value: 'merchant_node' \}/)
  assert.match(source, /\{ label: '商家节点模板', value: 'merchant_node_template' \}/)
})

test('新增商家节点类型仍复用审核快照与通过驳回动作', () => {
  assert.match(source, /snapshotJson/)
  assert.match(source, /openReview\(detailTask, 1\)/)
  assert.match(source, /openReview\(detailTask, 2\)/)
  assert.match(source, /reviewAuditTask\(this\.reviewForm\)/)
})
