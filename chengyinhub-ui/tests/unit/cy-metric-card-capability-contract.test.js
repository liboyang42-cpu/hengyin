const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const source = fs.readFileSync(
  path.resolve(__dirname, '../../src/components/CyMetricCard/index.vue'),
  'utf8'
)

test('指标卡可由页面传入数据轴能力状态，而不把所有断连都叫作尚未接通', () => {
  assert.match(source, /connectionLabel/)
  assert.match(source, /connectionTone/)
  assert.match(source, /connected \? '已接通' : '尚未接通'/)
})
